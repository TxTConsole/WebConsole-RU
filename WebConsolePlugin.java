package com.webconsole;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class WebConsolePlugin extends JavaPlugin {
    private HttpServer server;
    private CustomLogAppender appender;
    private FileConfiguration messagesConfig;
    private final Gson gson = new Gson();
    private long startTime;
    private final Map<String, Long> activeSessions = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        startTime = System.currentTimeMillis();
        saveDefaultConfig();
        reloadMessagesConfig();

        appender = new CustomLogAppender();
        appender.setup();

        int port = getConfig().getInt("server.port", 8080);

        try {
            // Bind to 0.0.0.0 to allow mobile/external connections
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

            // Static resources
            server.createContext("/", exchange -> {
                if (!exchange.getRequestURI().getPath().equals("/")) {
                    sendResponse(exchange, "Not Found", 404);
                    return;
                }
                serveResource(exchange, "index.html", "text/html");
            });

            server.createContext("/style.css", exchange -> {
                serveResource(exchange, "style.css", "text/css");
            });

            server.createContext("/plugins.js", exchange -> {
                serveResource(exchange, "plugins.js", "application/javascript");
            });

            // ==================== AUTHENTICATION ENDPOINTS ====================
            server.createContext("/api/auth", exchange -> {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                        Map<String, Object> data = gson.fromJson(isr, Map.class);
                        String login = (String) data.get("login");
                        String pass = (String) data.get("password");

                        boolean valid = false;
                        // Check if security is enabled
                        if (getConfig().getBoolean("security.enabled", true)) {
                            // Try new user list format
                            if (getConfig().contains("security.users")) {
                                String storedPass = getConfig().getString("security.users." + login);
                                if (storedPass != null && storedPass.equals(pass)) {
                                    valid = true;
                                }
                            }
                            // Fallback to old single password format
                            else if (getConfig().contains("security.password")) {
                                String adminPass = getConfig().getString("security.password");
                                if ("admin".equals(login) && adminPass != null && adminPass.equals(pass)) {
                                    valid = true;
                                }
                            }
                        } else {
                            // Security disabled - allow any login
                            valid = true;
                        }

                        if (valid) {
                            String token = UUID.randomUUID().toString();
                            long timeoutHours = getConfig().getLong("security.session-timeout-hours", 24);
                            long expiry = System.currentTimeMillis() + (timeoutHours * 60 * 60 * 1000);
                            activeSessions.put(token, expiry);
                            Map<String, Object> response = new HashMap<>();
                            response.put("success", true);
                            response.put("token", token);
                            sendJsonResponse(exchange, response);
                        } else {
                            Map<String, Object> response = new HashMap<>();
                            response.put("success", false);
                            response.put("message", "Неверный логин или пароль");
                            sendJsonResponse(exchange, response);
                        }
                    } catch (Exception e) {
                        sendResponse(exchange, "Bad Request", 400);
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            });

            server.createContext("/api/ping", exchange -> {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "online");
                response.put("version", getDescription().getVersion());
                sendJsonResponse(exchange, response);
            });

            // ==================== PROTECTED ENDPOINTS ====================

            // API: Get logs
            server.createContext("/api/logs", exchange -> {
                if (!checkAuth(exchange)) return;
                String query = exchange.getRequestURI().getQuery();
                int lastId = 0;
                if (query != null && query.contains("lastId=")) {
                    try { lastId = Integer.parseInt(query.split("lastId=")[1].split("&")[0]); } catch (Exception ignored) {}
                }
                List<CustomLogAppender.LogEntry> logs = appender.getLogsAfter(lastId);
                sendJsonResponse(exchange, logs);
            });

            // API: Log history
            server.createContext("/api/logs/history", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    File logsDir = new File("logs");
                    List<Map<String, Object>> files = new ArrayList<>();
                    if (logsDir.exists() && logsDir.isDirectory()) {
                        for (File f : logsDir.listFiles()) {
                            if (f.getName().endsWith(".log") || f.getName().endsWith(".log.gz")) {
                                Map<String, Object> map = new HashMap<>();
                                map.put("name", f.getName());
                                map.put("date", f.lastModified());
                                map.put("size", f.length());
                                files.add(map);
                            }
                        }
                    }
                    files.sort((a, b) -> Long.compare((Long)b.get("date"), (Long)a.get("date")));
                    sendJsonResponse(exchange, files);
                }
            });

            // API: Log actions (delete)
            server.createContext("/api/logs/action", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                        Map<String, String> data = gson.fromJson(isr, Map.class);
                        String action = data.get("action");
                        String fileName = data.get("file");
                        File logFile = new File("logs", fileName);

                        if (!logFile.getParentFile().getName().equals("logs")) {
                            sendResponse(exchange, "Invalid file", 400);
                            return;
                        }

                        if ("delete".equals(action)) {
                            if (logFile.exists() && logFile.delete()) {
                                sendJsonResponse(exchange, Map.of("status", "ok"));
                            } else {
                                sendResponse(exchange, "Failed to delete", 500);
                            }
                        }
                    }
                }
            });

            // API: Download log file
            server.createContext("/api/logs/download", exchange -> {
                if (!checkAuth(exchange)) return;
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("file=")) {
                    String fileName = query.split("file=")[1].split("&")[0];
                    File logFile = new File("logs", fileName);
                    if (logFile.exists() && logFile.getParentFile().getName().equals("logs")) {
                        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                        exchange.sendResponseHeaders(200, logFile.length());
                        try (OutputStream os = exchange.getResponseBody(); InputStream fis = new FileInputStream(logFile)) {
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = fis.read(buffer)) != -1) os.write(buffer, 0, len);
                        }
                        return;
                    }
                }
                sendResponse(exchange, "Not found", 404);
            });

            // API: Execute command
            server.createContext("/api/command", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }
                if (!getConfig().getBoolean("security.allow-commands", true)) {
                    sendResponse(exchange, "Commands disabled", 403);
                    return;
                }

                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    Map<String, String> data = gson.fromJson(isr, Map.class);
                    String cmd = data.get("command");
                    if (cmd != null && !cmd.isEmpty()) {
                        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                        sendJsonResponse(exchange, Map.of("status", "ok"));
                    } else {
                        sendResponse(exchange, "Empty command", 400);
                    }
                } catch (Exception e) {
                    sendResponse(exchange, "Bad Request", 400);
                }
            });

            // API: Server status
            server.createContext("/api/status", exchange -> {
                if (!checkAuth(exchange)) return;
                sendJsonResponse(exchange, getServerStatus());
            });

            // API: Server control (restart/stop)
            server.createContext("/api/control", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    Map<String, String> data = gson.fromJson(isr, Map.class);
                    String action = data.get("action");

                    if ("restart".equals(action) || "stop".equals(action)) {
                        sendJsonResponse(exchange, Map.of("status", "processing"));
                        Bukkit.getScheduler().runTask(this, () -> {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                            Bukkit.getScheduler().runTaskLater(this, () -> {
                                if ("restart".equals(action)) {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
                                } else {
                                    Bukkit.shutdown();
                                }
                            }, 40L);
                        });
                    } else {
                        sendResponse(exchange, "Unknown action", 400);
                    }
                }
            });

            // API: Players list
            server.createContext("/api/players", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    List<Map<String, Object>> players = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        Map<String, Object> pData = new HashMap<>();
                        pData.put("name", p.getName());
                        pData.put("ping", p.getPing() + " ms");
                        int totalSeconds = p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20;
                        pData.put("playTime", String.format("%02d ч. %02d мин.", totalSeconds / 3600, (totalSeconds % 3600) / 60));
                        players.add(pData);
                    }
                    sendJsonResponse(exchange, players);
                }
            });

            // API: Punishments list
            server.createContext("/api/punishments", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    List<Map<String, String>> punished = new ArrayList<>();
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm");

                    for (org.bukkit.BanEntry entry : Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntries()) {
                        Map<String, String> data = new HashMap<>();
                        data.put("name", entry.getTarget());
                        data.put("type", "Ban");
                        data.put("reason", entry.getReason() != null ? entry.getReason() : "Нет причины");
                        data.put("source", entry.getSource() != null ? entry.getSource() : "Console");
                        data.put("created", entry.getCreated() != null ? sdf.format(entry.getCreated()) : "-");
                        data.put("expiration", entry.getExpiration() != null ? sdf.format(entry.getExpiration()) : "Навсегда");
                        punished.add(data);
                    }
                    for (org.bukkit.BanEntry entry : Bukkit.getBanList(org.bukkit.BanList.Type.IP).getBanEntries()) {
                        Map<String, String> data = new HashMap<>();
                        data.put("name", entry.getTarget());
                        data.put("type", "IP Ban");
                        data.put("reason", entry.getReason() != null ? entry.getReason() : "Нет причины");
                        data.put("source", entry.getSource() != null ? entry.getSource() : "Console");
                        data.put("created", entry.getCreated() != null ? sdf.format(entry.getCreated()) : "-");
                        data.put("expiration", entry.getExpiration() != null ? sdf.format(entry.getExpiration()) : "Навсегда");
                        punished.add(data);
                    }
                    sendJsonResponse(exchange, punished);
                }
            });

            // API: Available commands list
            server.createContext("/api/commands", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    List<String> commands = new ArrayList<>();
                    for (org.bukkit.help.HelpTopic topic : Bukkit.getHelpMap().getHelpTopics()) {
                        if (topic.getName().startsWith("/")) {
                            commands.add(topic.getName().substring(1));
                        }
                    }
                    sendJsonResponse(exchange, commands);
                }
            });

            // API: Server info (for plugins)
            server.createContext("/api/server-info", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    JsonObject response = new JsonObject();
                    String fullVersion = Bukkit.getBukkitVersion();
                    String majorVersion = fullVersion.split("-")[0];
                    String[] parts = majorVersion.split("\\.");
                    String shortVersion = parts.length >= 2 ? parts[0] + "." + parts[1] : majorVersion;

                    response.addProperty("version", shortVersion);
                    response.addProperty("fullVersion", majorVersion);
                    response.addProperty("software", Bukkit.getName().toLowerCase());
                    response.addProperty("serverName", Bukkit.getName());

                    String jsonResponse = gson.toJson(response);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(jsonResponse.getBytes());
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            });

            // API: Install plugin
            server.createContext("/api/install-plugin", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }

                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    Map<String, String> request = gson.fromJson(isr, Map.class);
                    String url = request.get("url");
                    String fileName = request.get("fileName");

                    if (url == null || fileName == null) {
                        sendJsonResponse(exchange, Map.of("success", false, "message", "Missing parameters"));
                        return;
                    }

                    // Security check
                    if (!url.startsWith("https://cdn.modrinth.com/")) {
                        sendJsonResponse(exchange, Map.of("success", false, "message", "Invalid URL source"));
                        return;
                    }

                    if (fileName.matches(".*[\\\\/]|.*\\.\\.+.*") || !fileName.endsWith(".jar")) {
                        sendJsonResponse(exchange, Map.of("success", false, "message", "Invalid file name"));
                        return;
                    }

                    boolean success = PluginInstaller.downloadPlugin(url, fileName);
                    sendJsonResponse(exchange, Map.of("success", success, "message", success ? "Plugin installed" : "Download failed"));

                } catch (Exception e) {
                    getLogger().warning("Error installing plugin: " + e.getMessage());
                    sendJsonResponse(exchange, Map.of("success", false, "message", e.getMessage()));
                }
            });

            // API: File list
            server.createContext("/api/files", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String query = exchange.getRequestURI().getQuery();
                String path = "";
                if (query != null && query.startsWith("path=")) {
                    path = query.substring(5);
                }
                File baseDir = new File(".").getCanonicalFile();
                File target = new File(baseDir, path).getCanonicalFile();
                if (!target.getPath().startsWith(baseDir.getPath())) {
                    sendResponse(exchange, "Access denied", 403);
                    return;
                }
                List<Map<String, Object>> files = new ArrayList<>();
                File[] listFiles = target.listFiles();
                if (listFiles != null) {
                    for (File f : listFiles) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", f.getName());
                        map.put("path", target.equals(baseDir) ? f.getName() : path + "/" + f.getName());
                        map.put("type", f.isDirectory() ? "dir" : "file");
                        map.put("size", f.length());
                        map.put("modified", f.lastModified());
                        files.add(map);
                    }
                }
                files.sort((a,b) -> {
                    boolean aDir = "dir".equals(a.get("type"));
                    boolean bDir = "dir".equals(b.get("type"));
                    if (aDir && !bDir) return -1;
                    if (!aDir && bDir) return 1;
                    return ((String)a.get("name")).compareTo((String)b.get("name"));
                });
                sendJsonResponse(exchange, files);
            });

            // API: Read file
            server.createContext("/api/files/read", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }
                String query = exchange.getRequestURI().getQuery();
                if (query == null || !query.startsWith("path=")) {
                    sendResponse(exchange, "Missing path", 400);
                    return;
                }
                String path = query.substring(5);
                File baseDir = new File(".").getCanonicalFile();
                File file = new File(baseDir, path).getCanonicalFile();
                if (!file.getPath().startsWith(baseDir.getPath())) {
                    sendResponse(exchange, "Access denied", 403);
                    return;
                }
                if (!file.exists() || file.isDirectory()) {
                    sendResponse(exchange, "File not found", 404);
                    return;
                }
                if (file.length() > 5 * 1024 * 1024) {
                    sendResponse(exchange, "File too large", 413);
                    return;
                }
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                sendResponse(exchange, content, 200);
            });

            // API: Save file
            server.createContext("/api/files/save", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    Map<String, String> data = gson.fromJson(isr, Map.class);
                    String path = data.get("path");
                    String content = data.get("content");
                    File baseDir = new File(".").getCanonicalFile();
                    File file = new File(baseDir, path).getCanonicalFile();
                    if (!file.getPath().startsWith(baseDir.getPath())) {
                        sendResponse(exchange, "Access denied", 403);
                        return;
                    }
                    if (file.getPath().contains(File.separator + "plugins" + File.separator + "WebConsole") &&
                            (file.getName().endsWith(".jar") || file.getName().equals("config.yml") || file.getName().equals("messages.yml"))) {
                        sendResponse(exchange, "Cannot modify plugin files", 403);
                        return;
                    }
                    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    sendJsonResponse(exchange, Map.of("status", "ok"));
                } catch (Exception e) {
                    sendResponse(exchange, "Error saving file", 500);
                }
            });

            // API: Delete file
            server.createContext("/api/files/delete", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    Map<String, String> data = gson.fromJson(isr, Map.class);
                    String path = data.get("path");
                    File baseDir = new File(".").getCanonicalFile();
                    File file = new File(baseDir, path).getCanonicalFile();
                    if (!file.getPath().startsWith(baseDir.getPath())) {
                        sendResponse(exchange, "Access denied", 403);
                        return;
                    }
                    if (file.getPath().contains(File.separator + "plugins" + File.separator + "WebConsole") ||
                            (file.getParentFile() != null && file.getParentFile().getPath().contains(File.separator + "plugins" + File.separator + "WebConsole"))) {
                        sendResponse(exchange, "Cannot delete plugin files", 403);
                        return;
                    }
                    if (file.delete()) {
                        sendJsonResponse(exchange, Map.of("status", "ok"));
                    } else {
                        sendResponse(exchange, "Delete failed", 500);
                    }
                }
            });

            // API: Move file
            server.createContext("/api/files/move", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    Map<String, String> data = gson.fromJson(isr, Map.class);
                    String src = data.get("src");
                    String dest = data.get("dest");
                    File baseDir = new File(".").getCanonicalFile();
                    File srcFile = new File(baseDir, src).getCanonicalFile();
                    File destDir = new File(baseDir, dest).getCanonicalFile();
                    if (!srcFile.getPath().startsWith(baseDir.getPath()) || !destDir.getPath().startsWith(baseDir.getPath())) {
                        sendResponse(exchange, "Access denied", 403);
                        return;
                    }
                    if (!destDir.isDirectory()) {
                        sendResponse(exchange, "Destination is not a directory", 400);
                        return;
                    }
                    File newFile = new File(destDir, srcFile.getName());
                    if (srcFile.renameTo(newFile)) {
                        sendJsonResponse(exchange, Map.of("status", "ok"));
                    } else {
                        sendResponse(exchange, "Move failed", 500);
                    }
                }
            });

            // API: Upload files
            server.createContext("/api/files/upload", exchange -> {
                if (!checkAuth(exchange)) return;
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, "Method not allowed", 405);
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                String path = "";
                if (query != null && query.startsWith("path=")) {
                    path = query.substring(5);
                }

                File baseDir = new File(".").getCanonicalFile();
                File targetDir = new File(baseDir, path).getCanonicalFile();

                if (!targetDir.getPath().startsWith(baseDir.getPath())) {
                    sendResponse(exchange, "Access denied", 403);
                    return;
                }

                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                if (!targetDir.isDirectory()) {
                    sendResponse(exchange, "Target is not a directory", 400);
                    return;
                }

                try {
                    DiskFileItemFactory factory = new DiskFileItemFactory();
                    factory.setSizeThreshold(10 * 1024 * 1024);
                    factory.setRepository(new File(System.getProperty("java.io.tmpdir")));

                    ServletFileUpload upload = new ServletFileUpload(factory);
                    upload.setFileSizeMax(50 * 1024 * 1024);
                    upload.setSizeMax(100 * 1024 * 1024);

                    List<FileItem> items = upload.parseRequest(new HttpExchangeRequestContext(exchange));

                    List<String> uploadedFiles = new ArrayList<>();
                    List<String> errors = new ArrayList<>();

                    for (FileItem item : items) {
                        if (!item.isFormField()) {
                            String fileName = FilenameUtils.getName(item.getName());

                            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                                errors.add("Invalid filename: " + fileName);
                                continue;
                            }

                            File destFile = new File(targetDir, fileName);
                            if (destFile.getPath().contains(File.separator + "plugins" + File.separator + "WebConsole") &&
                                    (destFile.getName().endsWith(".jar") || destFile.getName().equals("config.yml") || destFile.getName().equals("messages.yml"))) {
                                errors.add("Cannot overwrite plugin file: " + fileName);
                                continue;
                            }

                            try {
                                item.write(destFile);
                                uploadedFiles.add(fileName);
                            } catch (Exception e) {
                                errors.add("Failed to write " + fileName + ": " + e.getMessage());
                            }
                        }
                    }

                    Map<String, Object> response = new HashMap<>();
                    response.put("uploaded", uploadedFiles);
                    if (!errors.isEmpty()) {
                        response.put("errors", errors);
                    }
                    response.put("status", errors.isEmpty() ? "success" : "partial");

                    sendJsonResponse(exchange, response);

                } catch (FileUploadException e) {
                    sendResponse(exchange, "Upload failed: " + e.getMessage(), 400);
                } catch (Exception e) {
                    sendResponse(exchange, "Internal error: " + e.getMessage(), 500);
                }
            });

            // API: Download file
            server.createContext("/api/files/download", exchange -> {
                if (!checkAuth(exchange)) return;
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.startsWith("path=")) {
                    String filePath = query.substring(5);
                    File baseDir = new File(".").getCanonicalFile();
                    File file = new File(baseDir, filePath).getCanonicalFile();

                    if (!file.getPath().startsWith(baseDir.getPath())) {
                        sendResponse(exchange, "Access denied", 403);
                        return;
                    }

                    if (file.exists() && !file.isDirectory()) {
                        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                        exchange.sendResponseHeaders(200, file.length());

                        try (OutputStream os = exchange.getResponseBody();
                             InputStream fis = new FileInputStream(file)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = fis.read(buffer)) != -1) {
                                os.write(buffer, 0, len);
                            }
                        }
                        return;
                    }
                }
                sendResponse(exchange, "File not found", 404);
            });

            // ==================== SERVER SETTINGS API ====================

            // API: Get/Set server.properties
            server.createContext("/api/server/properties", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    File serverProps = new File("server.properties");
                    Map<String, String> props = new HashMap<>();
                    if (serverProps.exists()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(serverProps))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.contains("=") && !line.startsWith("#")) {
                                    String[] parts = line.split("=", 2);
                                    props.put(parts[0], parts[1]);
                                }
                            }
                        }
                    }
                    sendJsonResponse(exchange, props);
                } else if ("POST".equals(exchange.getRequestMethod())) {
                    try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                        Map<String, Object> data = gson.fromJson(isr, Map.class);
                        File serverProps = new File("server.properties");
                        List<String> lines = new ArrayList<>();
                        if (serverProps.exists()) {
                            lines = Files.readAllLines(serverProps.toPath(), StandardCharsets.UTF_8);
                        }
                        for (Map.Entry<String, Object> entry : data.entrySet()) {
                            String key = entry.getKey();
                            String value = String.valueOf(entry.getValue());
                            boolean found = false;
                            for (int i = 0; i < lines.size(); i++) {
                                if (lines.get(i).startsWith(key + "=")) {
                                    lines.set(i, key + "=" + value);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                lines.add(key + "=" + value);
                            }
                        }
                        Files.write(serverProps.toPath(), lines, StandardCharsets.UTF_8);
                        sendJsonResponse(exchange, Map.of("status", "ok"));
                    } catch (Exception e) {
                        sendResponse(exchange, "Error saving properties", 500);
                    }
                }
            });

            // API: Get/Set whitelist
            server.createContext("/api/whitelist", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    File whitelistFile = new File("whitelist.json");
                    List<String> whitelist = new ArrayList<>();
                    if (whitelistFile.exists()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(whitelistFile))) {
                            String content = reader.lines().collect(Collectors.joining());
                            if (!content.isEmpty()) {
                                JsonArray jsonArray = gson.fromJson(content, JsonArray.class);
                                for (JsonElement e : jsonArray) {
                                    JsonObject obj = e.getAsJsonObject();
                                    if (obj.has("name")) {
                                        whitelist.add(obj.get("name").getAsString());
                                    }
                                }
                            }
                        }
                    }
                    sendJsonResponse(exchange, whitelist);
                } else if ("POST".equals(exchange.getRequestMethod())) {
                    try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                        Map<String, Object> data = gson.fromJson(isr, Map.class);
                        List<String> players = (List<String>) data.get("players");
                        boolean enabled = (boolean) data.get("enabled");

                        // Live server update
                        Bukkit.getScheduler().runTask(this, () -> {
                            Bukkit.setWhitelist(enabled);
                            for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
                                p.setWhitelisted(false);
                            }
                            for (String name : players) {
                                Bukkit.getOfflinePlayer(name).setWhitelisted(true);
                            }
                            Bukkit.reloadWhitelist();

                            if (enabled) {
                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    if (!p.isWhitelisted()) {
                                        p.kickPlayer("You are not whitelisted on this server!");
                                    }
                                }
                            }
                        });

                        // Save to server.properties
                        File serverProps = new File("server.properties");
                        if (serverProps.exists()) {
                            List<String> lines = Files.readAllLines(serverProps.toPath(), StandardCharsets.UTF_8);
                            boolean found = false;
                            for (int i = 0; i < lines.size(); i++) {
                                if (lines.get(i).startsWith("white-list=")) {
                                    lines.set(i, "white-list=" + enabled);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) lines.add("white-list=" + enabled);
                            Files.write(serverProps.toPath(), lines, StandardCharsets.UTF_8);
                        }

                        sendJsonResponse(exchange, Map.of("status", "ok"));
                    } catch (Exception e) {
                        sendResponse(exchange, "Error saving whitelist", 500);
                    }
                }
            });

            // API: Whitelist enabled status
            server.createContext("/api/whitelist/enabled", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    File serverProps = new File("server.properties");
                    boolean enabled = false;
                    if (serverProps.exists()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(serverProps))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("white-list=")) {
                                    enabled = Boolean.parseBoolean(line.split("=")[1]);
                                    break;
                                }
                            }
                        }
                    }
                    sendJsonResponse(exchange, enabled);
                }
            });

            // API: Get/Set operators
            server.createContext("/api/ops", exchange -> {
                if (!checkAuth(exchange)) return;
                if ("GET".equals(exchange.getRequestMethod())) {
                    File opsFile = new File("ops.json");
                    List<String> ops = new ArrayList<>();
                    if (opsFile.exists()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(opsFile))) {
                            String content = reader.lines().collect(Collectors.joining());
                            if (!content.isEmpty()) {
                                JsonArray jsonArray = gson.fromJson(content, JsonArray.class);
                                for (JsonElement e : jsonArray) {
                                    JsonObject obj = e.getAsJsonObject();
                                    if (obj.has("name")) {
                                        ops.add(obj.get("name").getAsString());
                                    }
                                }
                            }
                        }
                    }
                    sendJsonResponse(exchange, ops);
                } else if ("POST".equals(exchange.getRequestMethod())) {
                    try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                        Map<String, Object> data = gson.fromJson(isr, Map.class);
                        List<String> operators = (List<String>) data.get("operators");

                        // Live server update
                        Bukkit.getScheduler().runTask(this, () -> {
                            for (OfflinePlayer p : Bukkit.getOperators()) {
                                p.setOp(false);
                            }
                            for (String name : operators) {
                                Bukkit.getOfflinePlayer(name).setOp(true);
                            }
                        });

                        sendJsonResponse(exchange, Map.of("status", "ok"));
                    } catch (Exception e) {
                        sendResponse(exchange, "Error saving ops", 500);
                    }
                }
            });

            server.start();

            String url = "http://localhost:" + port;
            Bukkit.getConsoleSender().sendMessage(colorize("&a[WebConsole] Плагин успешно запущен!"));
            Bukkit.getConsoleSender().sendMessage(colorize("&e[WebConsole] Панель управления: &n" + url));
            Bukkit.getConsoleSender().sendMessage(colorize("&7[WebConsole] Сервер слушает на 0.0.0.0:" + port + " (доступно из сети)"));

        } catch (IOException e) {
            getLogger().severe(colorize("&4[WebConsole] Ошибка запуска веб-сервера: " + e.getMessage()));
        }
    }

    private boolean checkAuth(HttpExchange exchange) throws IOException {
        // If security is disabled, allow all requests
        if (!getConfig().getBoolean("security.enabled", true)) {
            return true;
        }

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String query = exchange.getRequestURI().getQuery();
        String token = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (query != null) {
            // For file downloads with token in URL
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }

        if (token != null) {
            Long expiry = activeSessions.get(token);
            if (expiry != null && System.currentTimeMillis() < expiry) {
                return true;
            } else if (expiry != null) {
                activeSessions.remove(token);
            }
        }

        sendResponse(exchange, "{\"success\": false, \"message\": \"Unauthorized\"}", 401);
        return false;
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
        getLogger().info(colorize("&c[WebConsole] Плагин выключен."));
    }

    private Map<String, Object> getServerStatus() {
        Map<String, Object> status = new HashMap<>();

        double[] tps = Bukkit.getTPS();
        status.put("tps", String.format("%.2f", tps[0]));

        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        status.put("playersOnline", online);
        status.put("playersMax", max);
        status.put("playersPerc", max > 0 ? (int) ((online * 100.0f) / max) : 0);

        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory() / 1024 / 1024;
        long allocatedMem = runtime.totalMemory() / 1024 / 1024;
        long freeMem = runtime.freeMemory() / 1024 / 1024;
        long usedMem = allocatedMem - freeMem;

        status.put("ramUsed", usedMem);
        status.put("ramMax", maxMem);
        status.put("ramPerc", maxMem > 0 ? (int) ((usedMem * 100) / maxMem) : 0);

        try {
            com.sun.management.OperatingSystemMXBean osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double cpuLoad = osBean.getSystemCpuLoad();
            int cpuPerc = cpuLoad < 0 ? 0 : (int)(cpuLoad * 100);
            status.put("cpuUsed", cpuPerc);
            status.put("cpuMax", 100);
            status.put("cpuPerc", cpuPerc);
        } catch (Exception e) {
            status.put("cpuUsed", 0);
            status.put("cpuMax", 100);
            status.put("cpuPerc", 0);
        }

        try {
            FileStore store = Files.getFileStore(Paths.get("."));
            long totalSpace = store.getTotalSpace() / 1024 / 1024 / 1024;
            long usedSpace = (store.getTotalSpace() - store.getUnallocatedSpace()) / 1024 / 1024 / 1024;
            status.put("diskUsed", usedSpace);
            status.put("diskMax", totalSpace);
            status.put("diskPerc", totalSpace > 0 ? (int) ((usedSpace * 100) / totalSpace) : 0);
        } catch (IOException e) {
            status.put("diskUsed", 0);
            status.put("diskMax", 0);
            status.put("diskPerc", 0);
        }

        long diff = System.currentTimeMillis() - startTime;
        long seconds = diff / 1000 % 60;
        long minutes = diff / (1000 * 60) % 60;
        long hours = diff / (1000 * 60 * 60);
        status.put("uptime", String.format("%02d:%02d:%02d", hours, minutes, seconds));

        status.put("pluginVersion", getDescription().getVersion());
        status.put("serverVersion", Bukkit.getVersion());
        status.put("serverName", Bukkit.getName());

        return status;
    }

    private void serveResource(HttpExchange exchange, String resourceName, String contentType) throws IOException {
        InputStream is = getResource(resourceName);
        if (is == null) {
            sendResponse(exchange, "Resource not found: " + resourceName, 404);
            return;
        }
        byte[] response = is.readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private void reloadMessagesConfig() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(file);
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void sendResponse(HttpExchange exchange, String text, int code) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static class HttpExchangeRequestContext implements org.apache.commons.fileupload.RequestContext {
        private final HttpExchange exchange;
        private final InputStream inputStream;

        public HttpExchangeRequestContext(HttpExchange exchange) throws IOException {
            this.exchange = exchange;
            this.inputStream = exchange.getRequestBody();
        }

        @Override
        public String getCharacterEncoding() {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null) {
                String[] parts = contentType.split(";");
                for (String part : parts) {
                    part = part.trim();
                    if (part.startsWith("charset=")) {
                        return part.substring(8);
                    }
                }
            }
            return null;
        }

        @Override
        public String getContentType() {
            return exchange.getRequestHeaders().getFirst("Content-Type");
        }

        @Override
        public int getContentLength() {
            String length = exchange.getRequestHeaders().getFirst("Content-Length");
            return length != null ? Integer.parseInt(length) : -1;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return inputStream;
        }
    }
}