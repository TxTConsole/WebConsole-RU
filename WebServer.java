package txt.console.webconsole.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.plugin.bundled.CorsPluginConfig;
import io.javalin.websocket.WsContext;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class WebServer {

    private static final String[] PERM_KEYS = {
            "console", "players", "files", "plugins", "logs",
            "server_settings", "sanctions", "ops", "whitelist",
            "file_edit", "file_delete_download", "mod_folder",
            "server_control", "logs_manage"
    };

    private final JavaPlugin plugin;
    private Javalin app;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionManager sessionManager;

    private final Set<WsContext> authenticatedClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedIps = new ConcurrentHashMap<>();

    
    private final List<ObjectNode> consoleHistoryBuffer = Collections.synchronizedList(new LinkedList<>());
    
    private long consoleSeq = 0;
    
    private final long serverBoot = System.currentTimeMillis();

    
    private final Map<String, String> elySkins = new ConcurrentHashMap<>();

    
    private final Map<String, Long> opMeta = new ConcurrentHashMap<>();
    private final File opMetaFile;

    
    private static final class WlEntry {
        final long at;
        final String by;
        WlEntry(long at, String by) { this.at = at; this.by = by; }
    }
    private final Map<String, WlEntry> wlMeta = new ConcurrentHashMap<>();
    private final File wlMetaFile;

    
    private final List<ObjectNode> punishments = Collections.synchronizedList(new LinkedList<>());
    private final File punishmentsFile;

    
    private final Map<String, String> panelSettings = new ConcurrentHashMap<>();
    private final File panelSettingsFile;

    
    private volatile String validatedCfKey = null;
    private volatile Boolean cfKeyValid = null;

    
    private final List<ObjectNode> activityLog = Collections.synchronizedList(new LinkedList<>());
    private final File activityLogFile;
    private static final int ACTIVITY_LOG_MAX = 500;

    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    
    private static final String ANSI_RESET = "\u001b[0m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_LIGHT_RED = "\u001b[91m";

    public WebServer(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sessionManager = new SessionManager(plugin);
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();
        this.punishmentsFile = new File(dataFolder, "punishments.json");
        loadPunishments();
        this.opMetaFile = new File(dataFolder, "op-meta.json");
        loadOpMeta();
        this.wlMetaFile = new File(dataFolder, "wl-meta.json");
        loadWlMeta();
        this.panelSettingsFile = new File(dataFolder, "panel-settings.json");
        loadPanelSettings();
        this.activityLogFile = new File(dataFolder, "activity-log.json");
        loadActivityLog();
    }

    public void start(String host, int port) {
        System.setProperty("slf4j.suppressHandlerError", "true");

        app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        app.before(ctx -> {
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("X-Content-Type-Options", "nosniff");
            if (ctx.path().startsWith("/api/")) {
                ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
            }
        });

        
        app.get("/lang.js", ctx -> {
            ctx.contentType("application/javascript; charset=utf-8");
            ctx.result("window.__WC_DEFAULT_LANG = " + mapper.writeValueAsString(defaultLang()) + ";");
        });

        
        app.get("/api/logs", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "logs_manage")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            File logsDir = new File("logs");
            ObjectNode response = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("files");

            if (logsDir.exists() && logsDir.isDirectory()) {
                File[] files = logsDir.listFiles();
                if (files != null) {
                    Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File f : files) {
                        if (f.isFile() && (f.getName().endsWith(".log") || f.getName().endsWith(".gz"))) {
                            ObjectNode fileNode = mapper.createObjectNode();
                            fileNode.put("name", f.getName());
                            fileNode.put("size", f.length());
                            fileNode.put("date", f.lastModified());
                            array.add(fileNode);
                        }
                    }
                }
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        app.get("/api/logs/download", ctx -> {
            String token = ctx.queryParam("token");
            String fileName = ctx.queryParam("file");

            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "logs_manage")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            if (fileName == null || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                ctx.status(400).result("Bad Request");
                return;
            }

            File logFile = new File("logs", fileName);
            if (!logFile.exists() || !logFile.isFile()) {
                ctx.status(404).result("File not found");
                return;
            }

            ctx.header("Content-Disposition", "attachment; filename=\"" + logFile.getName() + "\"");
            ctx.result(Files.readAllBytes(logFile.toPath()));
        });

        app.get("/api/status", ctx -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("status", "online");
            node.put("version", plugin.getDescription().getVersion());
            node.put("server_name", Bukkit.getServer().getName());
            node.put("players", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
            node.put("id", UUID.nameUUIDFromBytes(Bukkit.getServer().toString().getBytes()).toString().substring(0, 8));

            
            String sip = Bukkit.getIp();
            node.put("server_ip", sip == null || sip.isEmpty() ? "" : sip);
            node.put("server_port", Bukkit.getPort());

            ctx.json(mapper.writeValueAsString(node));
        });

        app.get("/api/stats", ctx -> {
            String token = ctx.header("Authorization");
            if (token == null || sessionManager.validateToken(token) == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            ctx.json(mapper.writeValueAsString(buildStatsNode()));
        });

        app.get("/api/players", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "players")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }

            ObjectNode response = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("players");
            response.put("count", Bukkit.getOnlinePlayers().size());
            response.put("max", Bukkit.getMaxPlayers());

            for (Player player : Bukkit.getOnlinePlayers()) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", player.getName());
                node.put("uuid", player.getUniqueId().toString());
                node.put("ping", player.getPing());
                node.put("game_mode", player.getGameMode().name());
                node.put("health", Math.round(player.getHealth() * 10) / 10.0);
                node.put("max_health", 20.0);
                node.put("level", player.getLevel());
                node.put("exp", Math.round(player.getExp() * 100) / 100.0);
                node.put("total_xp", player.getTotalExperience());
                node.put("world", player.getWorld().getName());
                org.bukkit.Location loc = player.getLocation();
                node.put("x", Math.round(loc.getX() * 10) / 10.0);
                node.put("y", Math.round(loc.getY() * 10) / 10.0);
                node.put("z", Math.round(loc.getZ() * 10) / 10.0);
                node.put("food", Math.round(player.getFoodLevel() * 10) / 10.0);
                node.put("ip", player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "");
                node.put("is_op", player.isOp());
                node.put("group", getPlayerGroup(player));
                node.put("is_flying", player.isFlying());
                String elySkin = getElySkin(player);
                node.put("ely", elySkin != null);
                if (elySkin != null) node.put("ely_skin", elySkin);
                array.add(node);
            }

            ctx.json(mapper.writeValueAsString(response));
        });

        app.get("/api/operators", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "ops")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            ObjectNode response = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("operators");
            File opsFile = new File(serverRoot(), "ops.json");
            if (opsFile.exists() && opsFile.isFile()) {
                try {
                    String content = new String(Files.readAllBytes(opsFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    JsonNode root = mapper.readTree(content);
                    if (root != null && root.isArray()) {
                        for (JsonNode n : root) {
                            if (!n.isObject()) continue;
                            String nm = n.path("name").asText("");
                            String uid = n.path("uuid").asText("");
                            int level = n.path("level").asInt(4);
                            Long issued = opMeta.get(uid);
                            ObjectNode entry = mapper.createObjectNode();
                            entry.put("name", nm);
                            entry.put("uuid", uid);
                            entry.put("level", level);
                            if (issued != null) {
                                entry.put("date", issued.longValue());
                            } else {
                                entry.put("date", 0L);
                            }
                            array.add(entry);
                        }
                    }
                } catch (Exception ignored) {}
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        app.get("/api/whitelist", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "whitelist")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            ObjectNode response = mapper.createObjectNode();
            Properties p = loadServerProperties();
            response.put("enabled", parseBoolProp(p, "white-list", false));
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("entries");
            File wlFile = new File(serverRoot(), "whitelist.json");
            if (wlFile.exists() && wlFile.isFile()) {
                try {
                    String content = new String(Files.readAllBytes(wlFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    JsonNode root = mapper.readTree(content);
                    if (root != null && root.isArray()) {
                        for (JsonNode n : root) {
                            if (!n.isObject()) continue;
                            String nm = n.path("name").asText("");
                            String uid = n.path("uuid").asText("");
                            String addedStr = "";
                            JsonNode addedNode = n.get("added");
                            if (addedNode != null && addedNode.isValueNode()) addedStr = addedNode.asText("");
                            WlEntry e = wlMeta.get(nm);
                            ObjectNode entry = mapper.createObjectNode();
                            entry.put("name", nm);
                            entry.put("uuid", uid);
                            entry.put("added", addedStr);
                            entry.put("added_ms", e != null ? e.at : 0L);
                            entry.put("by", e != null ? (e.by == null ? "" : e.by) : "");
                            array.add(entry);
                        }
                    }
                } catch (Exception ignored) {}
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        app.get("/api/plugins/info", ctx -> {
            String token = ctx.header("Authorization");
            if (token == null || sessionManager.validateToken(token) == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("core", coreName());
            node.put("version", minecraftVersion());
            node.put("plugin_version", plugin.getDescription().getVersion());
            node.put("loader", "paper");
            node.put("curseforge_configured", hasValidCurseForgeKey());
            com.fasterxml.jackson.databind.node.ArrayNode inst = node.putArray("installed");
            File pluginsDir = new File(serverRoot(), "plugins");
            if (pluginsDir.exists() && pluginsDir.isDirectory()) {
                String[] names = pluginsDir.list((d, nm) -> nm.toLowerCase().endsWith(".jar"));
                if (names != null) {
                    for (String nm : names) inst.add(nm);
                }
            }
            ctx.json(mapper.writeValueAsString(node));
        });

        app.get("/api/plugins/key", ctx -> {
            String token = ctx.header("Authorization");
            if (token == null || sessionManager.validateToken(token) == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            ObjectNode node = mapper.createObjectNode();
            boolean has = !curseForgeKey().isEmpty();
            node.put("configured", has);
            node.put("valid", has && hasValidCurseForgeKey());
            ctx.json(mapper.writeValueAsString(node));
        });

        app.post("/api/plugins/key", ctx -> {
            String token = ctx.header("Authorization");
            if (token == null || sessionManager.validateToken(token) == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            String key = body != null && body.has("key") ? body.get("key").asText("") : "";
            String trimmed = key == null ? "" : key.trim();
            boolean has = !trimmed.isEmpty();
            if (!has) {
                panelSettings.remove("curseforge_api_key");
                validatedCfKey = null;
                cfKeyValid = false;
            } else {
                panelSettings.put("curseforge_api_key", trimmed);
            }
            savePanelSettings();
            boolean valid = false;
            if (has) {
                validateCurseForgeKey(trimmed);
                valid = Boolean.TRUE.equals(cfKeyValid);
            }
            ctx.json(mapper.writeValueAsString(mapper.createObjectNode()
                    .put("ok", true)
                    .put("configured", has)
                    .put("valid", valid)));
        });

        app.get("/api/settings/server", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "server_settings")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            try {
                Properties p = loadServerProperties();
                ObjectNode node = mapper.createObjectNode();
                node.put("motd", p.getProperty("motd", ""));
                node.put("server_ip", p.getProperty("server-ip", ""));
                node.put("server_port", parseIntProp(p, "server-port", 25565));
                node.put("max_players", parseIntProp(p, "max-players", 20));
                node.put("view_distance", parseIntProp(p, "view-distance", 10));
                node.put("simulation_distance", parseIntProp(p, "simulation-distance", 10));
                node.put("max_world_size", parseIntProp(p, "max-world-size", 29999984));
                node.put("spawn_protection", parseIntProp(p, "spawn-protection", 0));
                node.put("online_mode", parseBoolProp(p, "online-mode", true));
                node.put("allow_flight", parseBoolProp(p, "allow-flight", false));
                node.put("pvp", parseBoolProp(p, "pvp", true));
                node.put("command_blocks", parseBoolProp(p, "enable-command-block", false));
                node.put("allow_nether", parseBoolProp(p, "allow-nether", true));
                node.put("hardcore", parseBoolProp(p, "hardcore", false));
                node.put("hide_online_players", parseBoolProp(p, "hide-online-players", false));
                node.put("spawn_monsters", parseBoolProp(p, "spawn-monsters", true));
                node.put("white_list", parseBoolProp(p, "white-list", false));
                node.put("difficulty", p.getProperty("difficulty", "normal"));
                node.put("server_core", coreName());
                node.put("mc_version", minecraftVersion());
                node.put("cpu_name", cpuModel());
                node.put("cpu_cores", cpuPhysicalCores());
                node.put("cpu_threads", Runtime.getRuntime().availableProcessors());
                node.put("ram_max", Runtime.getRuntime().maxMemory());
                node.put("ram_total", totalPhysicalMemory());
                node.put("ram_freq", ramFrequencyMhz());
                node.put("disk_total", diskTotal());
                node.put("disk_free", diskFree());
                ctx.json(mapper.writeValueAsString(node));
            } catch (Exception ex) {
                ctx.status(500).result("{\"error\":\"read_failed\"}");
            }
        });

        app.post("/api/settings/server", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "server_settings")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            try {
                JsonNode body = ctx.bodyAsClass(JsonNode.class);
                Properties p = body != null ? bodyToProperties(body) : new Properties();
                saveServerProperties(p);
                List<String> requires = new ArrayList<>();
                runtimeApply(p, requires);
                ObjectNode response = mapper.createObjectNode();
                response.put("ok", true);
                com.fasterxml.jackson.databind.node.ArrayNode arr = response.putArray("requires_restart");
                for (String r : requires) arr.add(r);
                ctx.json(mapper.writeValueAsString(response));
            } catch (Exception ex) {
                ctx.status(500).result("{\"error\":\"save_failed\"}");
            }
        });

        app.get("/api/plugins/search", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "plugins")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            String q = ctx.queryParam("q") == null ? "" : ctx.queryParam("q");
            String platform = ctx.queryParam("platform") == null ? "modrinth" : ctx.queryParam("platform");
            String sort = ctx.queryParam("sort") == null ? "popularity" : ctx.queryParam("sort");
            ObjectNode response = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("plugins");
            response.put("platform", platform);
            try {
                if ("curseforge".equalsIgnoreCase(platform)) {
                    if (!hasValidCurseForgeKey()) {
                        response.put("error", "no_key");
                        response.put("needCurseForgeKey", true);
                        ctx.json(mapper.writeValueAsString(response));
                        return;
                    }
                    searchCurseForge(array, q, sort);
                } else {
                    searchModrinth(array, q, sort);
                }
            } catch (Exception e) {
                response.put("error", e.getMessage() == null ? "Unknown" : e.getMessage());
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        app.get("/api/plugins/versions", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "plugins")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            String id = ctx.queryParam("id") == null ? "" : ctx.queryParam("id");
            String platform = ctx.queryParam("platform") == null ? "" : ctx.queryParam("platform");
            ObjectNode response = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode arr = response.putArray("versions");
            if (!id.isEmpty()) {
                try {
                    if ("curseforge".equalsIgnoreCase(platform)) {
                        if (hasValidCurseForgeKey()) { searchCfVersions(arr, id, curseForgeKey()); }
                    } else {
                        searchMrVersions(arr, id);
                    }
                } catch (Exception e) {
                    response.put("error", e.getMessage() == null ? "Unknown" : e.getMessage());
                }
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        app.post("/api/plugins/download", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "plugins")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            String url = body != null && body.has("url") ? body.get("url").asText("") : "";
            String fileName = body != null && body.has("fileName") ? body.get("fileName").asText("") : "";
            String oldFileName = body != null && body.has("oldFileName") ? body.get("oldFileName").asText("") : "";
            ObjectNode response = mapper.createObjectNode();
            if (url.isEmpty()) {
                response.put("error", "Нет ссылки на скачивание.");
                ctx.status(400).json(mapper.writeValueAsString(response));
                return;
            }
            String error = installPlugin(url, fileName);
            if (error == null) {
                if (oldFileName != null && !oldFileName.isEmpty() && !oldFileName.equals(fileName)) {
                    try {
                        File old = new File(new File(serverRoot(), "plugins"), safeFileName(oldFileName));
                        if (old.exists()) old.delete();
                    } catch (Exception ignored) {}
                }
                String ptitle = body != null && body.has("title") ? body.get("title").asText("") : "";
                addActivityLog(oldFileName != null && !oldFileName.isEmpty() ? "plugin_reinstall" : "plugin_install",
                        sessionManager.validateToken(token), fileName, ptitle, "");
                response.put("ok", true);
            } else {
                response.put("error", error);
                ctx.status(500).json(mapper.writeValueAsString(response));
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        app.get("/api/modstatus", ctx -> {
            String token = ctx.header("Authorization");
            if (token == null || sessionManager.validateToken(token) == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("ban", modAvailable("ban"));
            node.put("mute", modAvailable("mute"));
            node.put("kick", modAvailable("kick"));
            node.put("ban_custom", isCustomConfigured("ban"));
            node.put("mute_custom", isCustomConfigured("mute"));
            ctx.json(mapper.writeValueAsString(node));
        });

        app.get("/api/punishments", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "sanctions")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            ObjectNode response = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("punishments");
            synchronized (punishments) {
                for (ObjectNode p : punishments) array.add(p.deepCopy());
            }
            response.put("ban_available", modAvailable("ban"));
            response.put("mute_available", modAvailable("mute"));
            ctx.json(mapper.writeValueAsString(response));
        });

        
        app.get("/api/activity-log", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "logs")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            ObjectNode response = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("logs");
            synchronized (activityLog) {
                for (ObjectNode l : activityLog) array.add(l.deepCopy());
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        

        
        app.get("/api/files", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "files")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            String path = ctx.queryParam("path") == null ? "" : ctx.queryParam("path");
            File dir = resolveServerPath(path);
            if (dir == null) { ctx.status(400).result("Bad path"); return; }
            if (!dir.exists()) { ctx.status(404).result("Not found"); return; }
            if (dir.isFile()) { ctx.status(400).result("Not a directory"); return; }
            if (!fileAllowed(displayPath(dir), user, false)) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("path", displayPath(dir));
            response.put("is_root", displayPath(dir).isEmpty());
            response.put("writable", fileAllowed(displayPath(dir), user, true));
            com.fasterxml.jackson.databind.node.ArrayNode array = response.putArray("entries");
            File[] children = dir.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File f : children) {
                    if (!fileAllowed(displayPath(f), user, false)) continue;
                    ObjectNode node = mapper.createObjectNode();
                    node.put("name", f.getName());
                    node.put("is_dir", f.isDirectory());
                    node.put("size", f.isDirectory() ? folderSize(f) : f.length());
                    node.put("modified", f.lastModified());
                    node.put("editable", f.isFile() && isTextFile(f));
                    array.add(node);
                }
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        
        app.get("/api/files/content", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "file_edit")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            String path = ctx.queryParam("path") == null ? "" : ctx.queryParam("path");
            File f = resolveServerPath(path);
            if (f == null) { ctx.status(400).result("Bad path"); return; }
            if (!f.exists() || !f.isFile()) { ctx.status(404).result("Not found"); return; }
            if (!isTextFile(f)) { ctx.status(415).result("Binary file"); return; }
            if (!fileAllowed(displayPath(f), user, false)) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("path", displayPath(f));
            response.put("name", f.getName());
            response.put("size", f.length());
            response.put("content", new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            ctx.json(mapper.writeValueAsString(response));
        });

        
        app.post("/api/files/new", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "file_edit")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            JsonNode body = mapper.readTree(ctx.body());
            String parent = body.has("path") ? body.get("path").asText() : "";
            String name = body.has("name") ? body.get("name").asText().trim() : "";
            boolean dir = body.has("type") && "folder".equals(body.get("type").asText());
            if (name.isEmpty()) { ctx.status(400).result("Empty name"); return; }
            if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                ctx.status(400).result("Invalid name"); return;
            }
            File base = resolveServerPath(parent);
            if (base == null || !base.isDirectory()) { ctx.status(400).result("Bad parent"); return; }
            if (!fileAllowed(displayPath(base), user, true)) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            File target = new File(base, name);
            if (target.exists()) { ctx.status(409).result("Already exists"); return; }
            boolean ok = dir ? target.mkdirs() : target.createNewFile();
            if (!ok) { ctx.status(500).result("Create failed"); return; }
            addActivityLog("file_new", sessionManager.validateToken(token), displayPath(target), name, dir ? "folder" : "file");
            ctx.json(mapper.writeValueAsString(mapper.createObjectNode().put("ok", true).put("name", name).put("is_dir", dir)));
        });

        
        app.post("/api/files/save", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "file_edit")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            JsonNode body = mapper.readTree(ctx.body());
            String path = body.has("path") ? body.get("path").asText() : "";
            String content = body.has("content") ? body.get("content").asText() : "";
            File f = resolveServerPath(path);
            if (f == null) { ctx.status(400).result("Bad path"); return; }
            if (!f.exists() || !f.isFile()) { ctx.status(404).result("Not found"); return; }
            if (!isTextFile(f)) { ctx.status(415).result("Binary file"); return; }
            if (!fileAllowed(displayPath(f), user, true)) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            Files.write(f.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            addActivityLog("file_save", sessionManager.validateToken(token), displayPath(f), "Файл изменён в редакторе", "");
            ctx.json(mapper.writeValueAsString(mapper.createObjectNode().put("ok", true)));
        });

        
        app.get("/api/files/download", ctx -> {
            String token = ctx.queryParam("token");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "file_delete_download")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            String path = ctx.queryParam("path") == null ? "" : ctx.queryParam("path");
            File f = resolveServerPath(path);
            if (f == null) { ctx.status(400).result("Bad path"); return; }
            if (!f.exists() || !f.isFile()) { ctx.status(404).result("Not found"); return; }
            if (!fileAllowed(displayPath(f), user, false)) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            ctx.header("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
            ctx.result(Files.readAllBytes(f.toPath()));
        });

        
        app.post("/api/files/upload", ctx -> {
            String token = ctx.header("Authorization");
            String user = token == null ? null : sessionManager.validateToken(token);
            if (user == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            if (!hasPerm(user, "file_edit")) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            String path = ctx.queryParam("path") == null ? "" : ctx.queryParam("path");
            File dir = resolveServerPath(path);
            if (dir == null) { ctx.status(400).result("Bad path"); return; }
            if (!dir.isDirectory()) { ctx.status(400).result("Not a directory"); return; }
            if (!fileAllowed(displayPath(dir), user, true)) {
                ctx.status(403).result("{\"error\":\"no_access\"}");
                return;
            }
            var uploaded = ctx.uploadedFiles("files");
            var relpaths = ctx.formParamMap() != null ? ctx.formParamMap().get("relpath") : null;
            int count = 0;
            if (uploaded != null) {
                int i = 0;
                for (var uf : uploaded) {
                    if (uf == null || uf.content() == null) continue;
                    String originName = new java.io.File(uf.filename()).getName();
                    String rel = null;
                    if (relpaths != null && i < relpaths.size()) rel = relpaths.get(i);
                    if (rel == null || rel.isEmpty()) rel = originName;
                    i++;
                    if (rel.isEmpty() || rel.equals(".") || rel.equals("..")) continue;
                    File target;
                    try {
                        target = new File(dir, rel).getCanonicalFile();
                        if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + java.io.File.separator)) continue;
                    } catch (Exception e) { continue; }
                    if (target.getParentFile() != null && !target.getParentFile().exists()) {
                        if (!target.getParentFile().mkdirs()) continue;
                    }
                    try (java.io.InputStream in = uf.content()) {
                        java.nio.file.Files.copy(in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        count++;
                    } catch (Exception ignored) { }
                }
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("ok", true);
            response.put("uploaded", count);
            if (count > 0) {
                addActivityLog("file_upload", sessionManager.validateToken(token), displayPath(dir), "Загрузка файлов на сервер", String.valueOf(count));
            }
            ctx.json(mapper.writeValueAsString(response));
        });

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                ctx.session.setIdleTimeout(Duration.ofHours(24));
                String ip = getIp(ctx);
                logInfo("Подключение к WebConsole: " + ip);
            });
            ws.onMessage(ctx -> {
                try {
                    JsonNode json = mapper.readTree(ctx.message());
                    String type = json.has("type") ? json.get("type").asText() : "";

                    if ("ping".equals(type)) {
                        ObjectNode pong = mapper.createObjectNode();
                        pong.put("type", "pong");
                        ctx.send(mapper.writeValueAsString(pong));
                        return;
                    } else if ("auth".equals(type)) {
                        handleAuth(ctx, json);
                    } else if ("command".equals(type)) {
                        handleCommand(ctx, json.has("command") ? json.get("command").asText() : "");
                    } else if ("delete_log".equals(type)) {
                        handleDeleteLog(ctx, json);
                    } else if ("delete_file".equals(type)) {
                        handleDeleteFile(ctx, json);
                    } else if ("delete_files".equals(type)) {
                        handleDeleteFiles(ctx, json);
                    } else if ("server_action".equals(type)) {
                        handleServerAction(ctx, json);
                    } else if ("sanction".equals(type)) {
                        handleSanction(ctx, json);
                    } else if ("op".equals(type)) {
                        handleOp(ctx, json);
                    } else if ("whitelist".equals(type)) {
                        handleWhitelist(ctx, json);
                    } else if ("unpunish".equals(type)) {
                        handleUnpunish(ctx, json);
                    } else if ("request_history".equals(type)) {
                        sendConsoleHistory(ctx);
                    }
                } catch (Exception ignored) {}
            });

            ws.onClose(ctx -> {
                authenticatedClients.remove(ctx);
                logInfo("Отключен клиент WebConsole: " + getIp(ctx));
            });
        });

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                logInfo(text("Запуск веб-сервера на http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + port + "...",
                        "Starting web server on http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + port + "..."));
                app.start(host, port);
            } catch (Exception e) {
                logSevere(String.format("Не удалось запустить веб-сервер! Порт %d занят.", port));
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, sessionManager::cleanExpiredSessions, 1200L, 2400L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshElySkins, 100L, 600L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::broadcastStats, 40L, 20L);
    }

    private void handleServerAction(WsContext ctx, JsonNode json) {
        Boolean isAuth = ctx.attribute("auth");
        if (isAuth == null || !isAuth) return;

        String username = ctx.attribute("user");
        String password = json.has("password") ? json.get("password").asText() : "";
        String action = json.has("action") ? json.get("action").asText() : "";

        if (!hasPerm(username, "server_control")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }

        boolean validPass = (userPassword(username) != null && password.equals(userPassword(username)));

        if (!validPass) {
            sendSystemMessage(ctx, "error", "Неверный пароль. Действие отменено.");
            return;
        }

        if ("stop".equals(action)) {
            sendSystemMessage(ctx, "success", "Сервер выключается...");
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop"));
        } else if ("restart".equals(action)) {
            sendSystemMessage(ctx, "success", "Сервер перезагружается...");
            Bukkit.getScheduler().runTask(plugin, () -> {
                try { new java.io.File("restart.sig").createNewFile(); } catch (Exception ignored) {}
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
            });
        }
    }

    private boolean isAuthCtx(WsContext ctx) {
        Boolean isAuth = ctx.attribute("auth");
        return isAuth != null && isAuth;
    }

    private boolean passwordValid(WsContext ctx, String password) {
        String username = ctx.attribute("user");
        String pwd = userPassword(username);
        return pwd != null && password.equals(pwd);
    }

    private String userPassword(String username) {
        if (username == null) return null;
        ConfigurationSection users = plugin.getConfig().getConfigurationSection("security.users");
        if (users == null || !users.contains(username)) return null;
        String pwd = users.getString(username + ".password");
        return pwd != null ? pwd : users.getString(username);
    }

    private boolean hasPerm(String username, String key) {
        if (username == null || key == null) return false;
        return plugin.getConfig().getBoolean("security.users." + username + ".permissions." + key, true);
    }

    public boolean canReload(String username) {
        if (username == null) return true;
        return plugin.getConfig().getBoolean("security.users." + username + ".permissions.wc_reload",
                plugin.getConfig().getBoolean("security.users." + username + ".permissions.mod_folder", true));
    }

    public String defaultLang() {
        String lang = plugin.getConfig().getString("lang", "ru");
        return "en".equalsIgnoreCase(lang) ? "en" : "ru";
    }

    public boolean isEnglish() {
        return "en".equalsIgnoreCase(plugin.getConfig().getString("lang", "ru"));
    }

    public String text(String ru, String en) {
        return isEnglish() ? en : ru;
    }

    private boolean fileAllowed(String relPath, String username, boolean write) {
        if (relPath == null || relPath.isEmpty()) return true;
        if (isInsideModFolder(relPath)) return hasPerm(username, "mod_folder");
        String first = relPath.split("/")[0];
        if (first.equals("logs") || first.equals("crash-reports")) return hasPerm(username, "logs_manage");
        if (write && first.equals("plugins")) return hasPerm(username, "plugins");
        return true;
    }

    private boolean isInsideModFolder(String relPath) {
        try {
            String modRel = displayPath(plugin.getDataFolder());
            if (modRel != null && !modRel.isEmpty()
                    && (relPath.equals(modRel) || relPath.startsWith(modRel + "/"))) {
                return true;
            }
            String jarRel = displayPath(webconsoleJar());
            if (jarRel != null && !jarRel.isEmpty() && relPath.equals(jarRel)) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPluginPresent(String name) {
        return Bukkit.getPluginManager().getPlugin(name) != null;
    }

    private java.io.File webconsoleJar() {
        if (plugin == null) return null;
        try {
            java.lang.reflect.Method m = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredMethod("getFile");
            m.setAccessible(true);
            Object f = m.invoke(plugin);
            return f instanceof java.io.File ? (java.io.File) f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCustomConfigured(String type) {
        return plugin.getConfig().getBoolean("moderation." + type + ".enabled", false)
                && !plugin.getConfig().getString("moderation." + type + ".command", "").trim().isEmpty();
    }

    private boolean modAvailable(String type) {
        if ("kick".equals(type)) return true;
        if (isCustomConfigured(type)) return true;
        
        return isPluginPresent("LiteBans") || isPluginPresent("AdvancedBan") || isPluginPresent("LightBans");
    }

    private String modCommand(String type) {
        String cfg = plugin.getConfig().getString("moderation." + type + ".command", "").trim();
        if (!cfg.isEmpty()) return cfg;
        if (isPluginPresent("LiteBans")) {
            if ("ban".equals(type)) return "litebans:ban %player% %arguments%";
            if ("mute".equals(type)) return "litebans:mute %player% %arguments%";
        }
        if (isPluginPresent("AdvancedBan")) {
            if ("ban".equals(type)) return "ban %player% %arguments%";
            if ("mute".equals(type)) return "mute %player% %arguments%";
        }
        if (isPluginPresent("LightBans")) {
            if ("ban".equals(type)) return "ban %player% %arguments%";
            if ("mute".equals(type)) return "mute %player% %arguments%";
        }
        if ("kick".equals(type)) return "kick %player% %reason%";
        return "";
    }

    private String sanitizeDuration(String duration) {
        if (duration == null) return "";
        String d = duration.trim();
        if (d.isEmpty()) return "";
        if (d.matches("(?i)^\\d+(s|m|h|d|w|mm|mo|y|v)$")) return d;
        return "";
    }

    private void handleSanction(WsContext ctx, JsonNode json) {
        if (!isAuthCtx(ctx)) return;
        if (!hasPerm(ctx.attribute("user"), "sanctions")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }
        String password = json.has("password") ? json.get("password").asText() : "";
        if (!passwordValid(ctx, password)) {
            sendSystemMessage(ctx, "error", "Неверный пароль. Действие отменено.");
            return;
        }

        String action = json.has("action") ? json.get("action").asText() : "";
        String player = json.has("player") ? json.get("player").asText() : "";
        String reason = json.has("reason") ? json.get("reason").asText() : "";

        if (!"ban".equals(action) && !"mute".equals(action) && !"kick".equals(action)) {
            sendSystemMessage(ctx, "error", "Неизвестное действие наказания.");
            return;
        }
        if (!modAvailable(action)) {
            sendSystemMessage(ctx, "error", "Требуемый мод для '" + action + "' не найден.");
            return;
        }
        if (player.trim().isEmpty()) {
            sendSystemMessage(ctx, "error", "Не указан игрок.");
            return;
        }

        String duration = "".equals(action) ? "" : sanitizeDuration(json.has("duration") ? json.get("duration").asText() : "");
        String reasonText = reason == null ? "" : reason.trim();
        String cmdTmpl = modCommand(action);
        if (cmdTmpl.isEmpty()) {
            sendSystemMessage(ctx, "error", "Команда для '" + action + "' не настроена.");
            return;
        }

        String finalCmd = cmdTmpl
                .replace("%player%", player)
                .replace("%reason%", reasonText.isEmpty() ? "Нарушение правил" : reasonText)
                .replace("%duration%", duration)
                .replace("%arguments%", buildArguments(duration, reasonText));

        finalCmd = finalCmd.replaceAll("\\s+", " ").trim();
        String runCmd = finalCmd;
        String admin = ctx.attribute("user") != null ? ctx.attribute("user") : "Console";
        final String finalAction = action;
        final String finalReason = reasonText;
        final String finalDuration = duration;
        final String finalPlayer = player.trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), runCmd);
                if ("ban".equals(finalAction) || "mute".equals(finalAction)) {
                    recordPunishment(finalPlayer, finalAction, admin, finalReason, finalDuration);
                }
                addActivityLog("sanction_issue", admin, finalPlayer, finalReason,
                        finalAction + (finalDuration.isEmpty() ? "" : " | " + finalDuration));
                sendSystemMessage(ctx, "success", "Игрок '" + player + "' наказан (" + action + ").");
            } catch (Exception e) {
                sendSystemMessage(ctx, "error", "Не удалось выполнить команду наказания.");
            }
        });
    }

    private String modUnpunishCommand(String type) {
        String key = "ban".equals(type) ? "unban-command" : "unmute-command";
        String cfg = plugin.getConfig().getString("moderation." + type + "." + key, "").trim();
        if (!cfg.isEmpty()) return cfg;
        if (isPluginPresent("LiteBans")) {
            if ("ban".equals(type)) return "litebans:unban %player%";
            if ("mute".equals(type)) return "litebans:unmute %player%";
        }
        if (isPluginPresent("AdvancedBan")) {
            if ("ban".equals(type)) return "unban %player%";
            if ("mute".equals(type)) return "unmute %player%";
        }
        if (isPluginPresent("LightBans")) {
            if ("ban".equals(type)) return "unban %player%";
            if ("mute".equals(type)) return "unmute %player%";
        }
        return "";
    }

    private void handleUnpunish(WsContext ctx, JsonNode json) {
        if (!isAuthCtx(ctx)) return;
        if (!hasPerm(ctx.attribute("user"), "sanctions")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }
        String password = json.has("password") ? json.get("password").asText() : "";
        if (!passwordValid(ctx, password)) {
            sendSystemMessage(ctx, "error", "Неверный пароль. Действие отменено.");
            return;
        }
        String action = json.has("action") ? json.get("action").asText() : "";
        String player = json.has("player") ? json.get("player").asText() : "";
        String reason = json.has("reason") ? json.get("reason").asText() : "";
        if (!"ban".equals(action) && !"mute".equals(action)) {
            sendSystemMessage(ctx, "error", "Неизвестный тип снятия наказания.");
            return;
        }
        if (player.trim().isEmpty()) {
            sendSystemMessage(ctx, "error", "Не указан игрок.");
            return;
        }
        String cmdTmpl = modUnpunishCommand(action);
        if (cmdTmpl.isEmpty()) {
            sendSystemMessage(ctx, "error", "Команда для снятия '" + action + "' не настроена.");
            return;
        }
        String finalCmd = cmdTmpl.replace("%player%", player.trim());
        if (reason != null && !reason.trim().isEmpty()) {
            finalCmd = finalCmd + " " + reason.trim();
        }
        finalCmd = finalCmd.replaceAll("\\s+", " ").trim();
        String runCmd = finalCmd;
        final String finalPlayer = player.trim();
        final String finalAction = action;
        final String finalReason = reason == null ? "" : reason.trim();
        final String admin = ctx.attribute("user") != null ? ctx.attribute("user") : "Console";
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), runCmd);
                removePunishment(finalPlayer, finalAction);
                addActivityLog("sanction_remove", admin, finalPlayer, finalReason, finalAction);
                sendSystemMessage(ctx, "success", "Наказание снято с '" + player + "' (" + action + ").");
            } catch (Exception e) {
                sendSystemMessage(ctx, "error", "Не удалось выполнить команду снятия наказания.");
            }
        });
    }

    private String buildArguments(String duration, String reason) {
        StringBuilder sb = new StringBuilder();
        if (duration != null && !duration.isEmpty()) sb.append(duration);
        if (reason != null && !reason.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(reason);
        }
        return sb.toString();
    }

    private void handleOp(WsContext ctx, JsonNode json) {
        if (!isAuthCtx(ctx)) return;
        if (!hasPerm(ctx.attribute("user"), "ops")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }
        String password = json.has("password") ? json.get("password").asText() : "";
        if (!passwordValid(ctx, password)) {
            sendSystemMessage(ctx, "error", "Неверный пароль. Действие отменено.");
            return;
        }
        String player = json.has("player") ? json.get("player").asText() : "";
        if (player.trim().isEmpty()) {
            sendSystemMessage(ctx, "error", "Не указан игрок.");
            return;
        }
        String action = json.has("action") ? json.get("action").asText() : "";
        final boolean isDeop = "deop".equalsIgnoreCase(action);
        final String p = player.trim();
        final String targetUuid = resolvePlayerUuid(p);
        final String admin = ctx.attribute("user") != null ? ctx.attribute("user") : "Console";
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (isDeop) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "deop " + p);
                    if (targetUuid != null) { opMeta.remove(targetUuid); saveOpMeta(); }
                    addActivityLog("op_revoke", admin, p, "", "");
                    sendSystemMessage(ctx, "success", "С игрока '" + p + "' сняты права оператора.");
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "op " + p);
                    if (targetUuid != null) { opMeta.put(targetUuid, System.currentTimeMillis()); saveOpMeta(); }
                    addActivityLog("op_grant", admin, p, "", "");
                    sendSystemMessage(ctx, "success", "Игрок '" + p + "' получил права оператора.");
                }
            } catch (Exception e) {
                sendSystemMessage(ctx, "error", isDeop ? "Не удалось снять права оператора." : "Не удалось выдать права оператора.");
            }
        });
    }

    private void handleWhitelist(WsContext ctx, JsonNode json) {
        if (!isAuthCtx(ctx)) return;
        if (!hasPerm(ctx.attribute("user"), "whitelist")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }
        String player = json.has("player") ? json.get("player").asText() : "";
        if (player.trim().isEmpty()) {
            sendSystemMessage(ctx, "error", "Не указан ник игрока.");
            return;
        }
        String action = json.has("action") ? json.get("action").asText() : "";
        final boolean isRemove = "remove".equalsIgnoreCase(action);
        final String p = player.trim();
        final String admin = ctx.attribute("user") != null ? ctx.attribute("user") : "Console";
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (isRemove) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist remove " + p);
                    wlMeta.remove(p);
                    saveWlMeta();
                    addActivityLog("wl_remove", admin, p, "", "");
                    sendSystemMessage(ctx, "success", "Игрок '" + p + "' удалён из белого списка.");
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist add " + p);
                    wlMeta.put(p, new WlEntry(System.currentTimeMillis(), admin));
                    saveWlMeta();
                    addActivityLog("wl_add", admin, p, "", "");
                    sendSystemMessage(ctx, "success", "Игрок '" + p + "' добавлен в белый список.");
                }
            } catch (Exception e) {
                sendSystemMessage(ctx, "error", isRemove ? "Не удалось удалить игрока из белого списка." : "Не удалось добавить игрока в белый список.");
            }
        });
    }

    private String resolvePlayerUuid(String name) {
        Player pp = Bukkit.getPlayerExact(name);
        if (pp != null) return pp.getUniqueId().toString();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getName().equalsIgnoreCase(name)) return o.getUniqueId().toString();
        }
        return null;
    }

    private void handleAuth(WsContext ctx, JsonNode json) {
        String ip = getIp(ctx);

        if (lockedIps.containsKey(ip)) {
            if (System.currentTimeMillis() < lockedIps.get(ip)) {
                sendSystemMessage(ctx, "error", "Ваш IP временно заблокирован.");
                ctx.session.close();
                return;
            } else {
                lockedIps.remove(ip);
                failedAttempts.remove(ip);
            }
        }

        if (json.has("token")) {
            String token = json.get("token").asText();
            String username = sessionManager.validateToken(token);
            if (username != null) {
                authorizeContext(ctx, username, null);
                return;
            }
        }

        String username = json.has("username") ? json.get("username").asText() : "";
        String password = json.has("password") ? json.get("password").asText() : "";

        boolean valid = (userPassword(username) != null && password.equals(userPassword(username)));

        if (valid) {
            failedAttempts.remove(ip);
            long timeoutHours = plugin.getConfig().getLong("security.session-timeout-hours", 24);
            String token = sessionManager.createSession(username, timeoutHours);
            authorizeContext(ctx, username, token);
        } else {
            handleFailedAuth(ctx, ip, username);
        }
    }

    private void authorizeContext(WsContext ctx, String username, String token) {
        ctx.attribute("auth", true);
        ctx.attribute("user", username);
        authenticatedClients.add(ctx);

        if (token != null) {
            addActivityLog("login", username, "", "", "");
        }

        ObjectNode node = mapper.createObjectNode();
        node.put("type", "auth_success");
        node.put("message", "Авторизация успешна! Добро пожаловать, " + username + ".");
        node.put("username", username);
        node.put("boot", serverBoot);
        if (token != null) node.put("token", token);

        ObjectNode permsNode = mapper.createObjectNode();
        for (String key : PERM_KEYS) permsNode.put(key, hasPerm(username, key));
        node.set("permissions", permsNode);

        ObjectNode cmdsNode = mapper.createObjectNode();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<String, List<String>> pluginCommands = new HashMap<>();

            try {
                for (Command cmd : Bukkit.getServer().getCommandMap().getKnownCommands().values()) {
                    String name = cmd.getName();
                    if (name.contains(":")) continue;

                    String owner = "Minecraft (Vanilla)";

                    if (cmd instanceof PluginIdentifiableCommand) {
                        owner = ((PluginIdentifiableCommand) cmd).getPlugin().getName();
                    } else if (cmd.getClass().getName().contains("worldedit") || name.startsWith("/")) {
                        owner = "WorldEdit / Custom";
                    } else if (cmd.getClass().getName().contains("spigot")) {
                        owner = "Spigot Core";
                    }

                    pluginCommands.computeIfAbsent(owner, k -> new ArrayList<>()).add(name);
                }

                for (Map.Entry<String, List<String>> entry : pluginCommands.entrySet()) {
                    com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
                    entry.getValue().stream().distinct().sorted().forEach(arr::add);
                    cmdsNode.set(entry.getKey(), arr);
                }
            } catch (Exception e) {
                logWarn("Не удалось просканировать некоторые команды.");
            }

            node.set("commands", cmdsNode);

            try {
                ctx.send(mapper.writeValueAsString(node));
                logInfo("WebConsole: Пользователь '" + username + "' авторизовался с IP " + getIp(ctx));
                sendConsoleHistory(ctx);
            } catch (Exception ignored) {}
        });
    }

    private void handleFailedAuth(WsContext ctx, String ip, String username) {
        int attempts = failedAttempts.getOrDefault(ip, 0) + 1;
        failedAttempts.put(ip, attempts);
        int maxAttempts = plugin.getConfig().getInt("security.max-failed-attempts", 5);

        if (attempts >= maxAttempts) {
            long lockoutMinutes = plugin.getConfig().getLong("security.lockout-minutes", 30);
            lockedIps.put(ip, System.currentTimeMillis() + (lockoutMinutes * 60 * 1000L));

            String cmd = plugin.getConfig().getString("security.punishment-command", "");
            if (!cmd.isEmpty()) {
                String finalCmd = cmd.replace("%ip%", ip).replace("%username%", username.isEmpty() ? "unknown" : username);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd));
            }
            sendSystemMessage(ctx, "error", "Превышено число попыток! IP заблокирован.");
        } else {
            sendSystemMessage(ctx, "error", "Неверный логин, пароль или токен устарел! Попыток: " + attempts + "/" + maxAttempts);
        }
        ctx.session.close();
    }

    private void handleCommand(WsContext ctx, String command) {
        Boolean isAuth = ctx.attribute("auth");
        if (isAuth != null && isAuth) {
            if (command.trim().isEmpty()) return;
            if (!hasPerm(ctx.attribute("user"), "console")) {
                sendSystemMessage(ctx, "error", "У вас нет доступа.");
                return;
            }
            final String cmd = command.trim();
            final String actor = ctx.attribute("user") != null ? ctx.attribute("user") : "Console";
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                addActivityLog("command", actor, "", cmd, "");
            });
        } else {
            sendSystemMessage(ctx, "error", "Отказано в доступе. Вы не авторизованы!");
        }
    }

    private void handleDeleteLog(WsContext ctx, JsonNode json) {
        Boolean isAuth = ctx.attribute("auth");
        if (isAuth == null || !isAuth) return;

        String username = ctx.attribute("user");
        String password = json.has("password") ? json.get("password").asText() : "";
        String fileName = json.has("file") ? json.get("file").asText() : "";

        boolean validPass = (userPassword(username) != null && password.equals(userPassword(username)));

        if (!validPass) {
            sendSystemMessage(ctx, "error", "Неверный пароль. В доступе отказано.");
            return;
        }

        if (!hasPerm(username, "logs_manage")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }

        if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendSystemMessage(ctx, "error", "Некорректное имя файла.");
            return;
        }

        File logFile = new File("logs", fileName);
        if (logFile.exists() && logFile.isFile()) {
            if (logFile.delete()) {
                sendSystemMessage(ctx, "success", "Файл логов '" + fileName + "' успешно удален навсегда.");
                try {
                    ObjectNode node = mapper.createObjectNode();
                    node.put("type", "log_deleted");
                    node.put("file", fileName);
                    ctx.send(mapper.writeValueAsString(node));
                } catch (Exception ignored) {}
            } else {
                sendSystemMessage(ctx, "error", "Не удалось удалить файл. Возможно он занят сервером.");
            }
        } else {
            sendSystemMessage(ctx, "error", "Файл логов не найден на сервере.");
        }
    }

    private void handleDeleteFile(WsContext ctx, JsonNode json) {
        Boolean isAuth = ctx.attribute("auth");
        if (isAuth == null || !isAuth) return;

        String username = ctx.attribute("user");
        String password = json.has("password") ? json.get("password").asText() : "";
        String path = json.has("path") ? json.get("path").asText() : "";

        boolean validPass = (userPassword(username) != null && password.equals(userPassword(username)));

        if (!validPass) {
            sendSystemMessage(ctx, "error", "Неверный пароль. В доступе отказано.");
            return;
        }

        if (!hasPerm(username, "file_delete_download")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }

        File target = resolveServerPath(path);
        if (target == null) {
            sendSystemMessage(ctx, "error", "Некорректный путь.");
            return;
        }
        if (!fileAllowed(displayPath(target), username, true)) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }
        if (target.equals(serverRoot()) || target.getParentFile() == null || target.getParentFile().equals(serverRoot())) {
            sendSystemMessage(ctx, "error", "Нельзя удалить корневую папку сервера.");
            return;
        }
        if (!target.exists()) {
            sendSystemMessage(ctx, "error", "Файл/папка не найдены.");
            return;
        }

        boolean deleted = deleteRecursively(target);
        if (deleted) {
            addActivityLog("file_delete", username == null ? "Console" : username, displayPath(target), "", "");
            sendSystemMessage(ctx, "success", "'" + displayPath(target) + "' успешно удалено.");
            try {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "file_deleted");
                node.put("path", displayPath(target));
                ctx.send(mapper.writeValueAsString(node));
            } catch (Exception ignored) {}
        } else {
            sendSystemMessage(ctx, "error", "Не удалось удалить. Возможно файл занят сервером.");
        }
    }

    private File serverRoot() {
        try {
            return new File(".").getCanonicalFile();
        } catch (Exception e) {
            return new File(".").getAbsoluteFile();
        }
    }

    private void handleDeleteFiles(WsContext ctx, JsonNode json) {
        Boolean isAuth = ctx.attribute("auth");
        if (isAuth == null || !isAuth) return;

        String username = ctx.attribute("user");
        String password = json.has("password") ? json.get("password").asText() : "";
        boolean validPass = (userPassword(username) != null && password.equals(userPassword(username)));
        if (!validPass) {
            sendSystemMessage(ctx, "error", "Неверный пароль. В доступе отказано.");
            return;
        }
        if (!hasPerm(username, "file_delete_download")) {
            sendSystemMessage(ctx, "error", "У вас нет доступа.");
            return;
        }

        JsonNode pathsNode = json.get("paths");
        if (pathsNode == null || !pathsNode.isArray() || pathsNode.size() == 0) {
            sendSystemMessage(ctx, "error", "Не выбрано ни одного файла.");
            return;
        }

        int deleted = 0;
        int failed = 0;
        for (JsonNode pn : pathsNode) {
            File target = resolveServerPath(pn.asText());
            if (target == null || !fileAllowed(displayPath(target), username, true)
                    || target.equals(serverRoot()) || target.getParentFile() == null || target.getParentFile().equals(serverRoot())) {
                failed++;
                continue;
            }
            if (!target.exists()) { failed++; continue; }
            if (deleteRecursively(target)) deleted++; else failed++;
        }

        if (failed == 0) {
            sendSystemMessage(ctx, "success", "Удалено файлов/папок: " + deleted + ".");
        } else {
            sendSystemMessage(ctx, "error", "Удалено: " + deleted + ", ошибок: " + failed + ".");
        }
        if (deleted > 0) {
            addActivityLog("file_delete", username == null ? "Console" : username, "", String.valueOf(deleted), "batch");
        }
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "file_deleted");
            node.put("ok", true);
            ctx.send(mapper.writeValueAsString(node));
        } catch (Exception ignored) {}
    }

    private File resolveServerPath(String path) {
        if (path == null) path = "";
        try {
            File root = serverRoot();
            File f = new File(root, path.replace('\\', '/')).getCanonicalFile();
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath() + java.io.File.separator) && !f.equals(root)) {
                return null;
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private String displayPath(File f) {
        try {
            String root = serverRoot().getCanonicalPath();
            String fp = f.getCanonicalPath();
            if (fp.equals(root)) return "";
            String rel = fp.substring(root.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
            return rel.replace('\\', '/');
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isTextFile(File f) {
        if (!f.isFile()) return false;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(f.toPath())) {
            byte[] head = new byte[512];
            int n = in.read(head);
            for (int i = 0; i < n && i < head.length; i++) {
                int b = head[i] & 0xFF;
                if (b == 0) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursively(c)) return false;
                }
            }
        }
        try {
            return f.delete();
        } catch (Exception e) {
            return false;
        }
    }

    private long folderSize(File dir) {
        try {
            return folderSize(dir, new java.util.concurrent.atomic.LongAdder());
        } catch (Exception e) {
            return 0;
        }
    }

    private long folderSize(File dir, java.util.concurrent.atomic.LongAdder counter) {
        File[] children = dir.listFiles();
        if (children == null) return counter.sum();
        for (File c : children) {
            if (c.isDirectory()) {
                folderSize(c, counter);
            } else {
                counter.add(c.length());
            }
        }
        return counter.sum();
    }

    private String getIp(WsContext ctx) {
        String cachedIp = ctx.attribute("ip");
        if (cachedIp != null) return cachedIp;

        String header = ctx.header("X-Forwarded-For");
        if (header != null && !header.isEmpty()) {
            cachedIp = header.split(",")[0].trim();
        } else {
            try {
                if (ctx.session != null && ctx.session.isOpen() && ctx.session.getRemoteAddress() != null) {
                    SocketAddress remoteAddress = ctx.session.getRemoteAddress();
                    if (remoteAddress instanceof InetSocketAddress) {
                        cachedIp = ((InetSocketAddress) remoteAddress).getAddress().getHostAddress();
                    } else {
                        cachedIp = remoteAddress.toString();
                    }
                } else {
                    cachedIp = "unknown";
                }
            } catch (Exception e) {
                cachedIp = "unknown";
            }
        }
        ctx.attribute("ip", cachedIp);
        return cachedIp;
    }

    private void sendSystemMessage(WsContext ctx, String type, String message) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", type);
            node.put("message", message);
            ctx.send(mapper.writeValueAsString(node));
        } catch (Exception ignored) {}
    }

    public void broadcastLog(String time, String level, String message) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "log");
            node.put("seq", ++consoleSeq);
            node.put("time", time);
            node.put("level", level);
            node.put("message", message.replaceAll("\\u001B\\[[;\\d]*m", ""));

            synchronized (consoleHistoryBuffer) {
                consoleHistoryBuffer.add(node);
                while (consoleHistoryBuffer.size() > 200) {
                    consoleHistoryBuffer.remove(0);
                }
            }

            if (authenticatedClients.isEmpty()) return;
            String json = mapper.writeValueAsString(node);
            for (WsContext ctx : authenticatedClients) {
                if (ctx.session.isOpen()) ctx.send(json);
            }
        } catch (Exception ignored) {}
    }

    private void logInfo(String msg) {
        plugin.getLogger().info(ANSI_GREEN + msg + ANSI_RESET);
    }

    private void logWarn(String msg) {
        plugin.getLogger().warning(ANSI_GREEN + msg + ANSI_RESET);
    }

    public void broadcastStats() {
        if (authenticatedClients.isEmpty()) return;
        ObjectNode node = buildStatsNode();
        node.put("type", "stats");
        String json;
        try { json = mapper.writeValueAsString(node); } catch (Exception e) { return; }
        for (WsContext ctx : authenticatedClients) {
            if (ctx.session.isOpen()) {
                try { ctx.send(json); } catch (Exception ignored) {}
            }
        }
    }

    private ObjectNode buildStatsNode() {
        ObjectNode node = mapper.createObjectNode();

        double tps = 20.0;
        try {
            double[] tpsArr = Bukkit.getTPS();
            if (tpsArr != null && tpsArr.length > 0) tps = Math.min(20.0, tpsArr[0]);
        } catch (Throwable ignored) {}
        tps = Math.max(0.0, Math.round(tps * 10.0) / 10.0);
        node.put("tps", tps);

        double cpu = 0.0;
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double load = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                if (load >= 0) cpu = load * 100.0;
                else cpu = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad() * 100.0;
            }
        } catch (Throwable ignored) {}
        cpu = Math.max(0.0, Math.min(100.0, Math.round(cpu * 10.0) / 10.0));
        node.put("cpu", cpu);

        Runtime rt = Runtime.getRuntime();
        long usedMem = rt.totalMemory() - rt.freeMemory();
        long maxMem = rt.maxMemory();
        double ramPct = maxMem > 0 ? Math.round((usedMem * 1000.0 / maxMem)) / 10.0 : 0.0;
        node.put("ram_percent", ramPct);
        node.put("ram_used", usedMem);
        node.put("ram_total", maxMem);

        try {
            java.io.File disk = new java.io.File(".");
            long total = disk.getTotalSpace();
            long usable = disk.getUsableSpace();
            long used = Math.max(0, total - usable);
            double diskPct = total > 0 ? Math.round((used * 1000.0 / total)) / 10.0 : 0.0;
            node.put("disk_percent", diskPct);
            node.put("disk_used", used);
            node.put("disk_total", total);
        } catch (Throwable ignored) {}

        try {
            node.put("players", Bukkit.getOnlinePlayers().size());
            node.put("players_max", Bukkit.getMaxPlayers());
        } catch (Throwable ignored) {
            node.put("players", 0);
            node.put("players_max", 0);
        }

        return node;
    }


    private void logSevere(String msg) {
        plugin.getLogger().severe(ANSI_LIGHT_RED + msg + ANSI_RESET);
    }

    private void sendConsoleHistory(WsContext ctx) {
        Boolean isAuth = ctx.attribute("auth");
        if (isAuth != null && isAuth) {
            synchronized (consoleHistoryBuffer) {
                for (ObjectNode logNode : consoleHistoryBuffer) {
                    try {
                        if (ctx.session.isOpen()) {
                            ctx.send(mapper.writeValueAsString(logNode));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }
    

    private String getElySkin(Player player) {
        return elySkins.get(player.getUniqueId().toString());
    }

    private void refreshElySkins() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                String key = player.getUniqueId().toString();
                if (!elySkins.containsKey(key)) {
                    loadElySkinAsync(player.getUniqueId(), player.getName());
                }
            }
        } catch (Exception ignored) {}
    }

    private void loadElySkinAsync(java.util.UUID uuid, String name) {
        if (uuid == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String texture = fetchElySkinTexture(name);
            if (texture != null && elySkins.get(uuid.toString()) == null) {
                elySkins.put(uuid.toString(), texture);
            }
        });
    }

    private String fetchElySkinTexture(String name) {
        if (name == null || name.isEmpty()) return null;
        java.io.BufferedReader reader = null;
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("https://mc-api.ely.by/skins/" + java.net.URLEncoder.encode(name, "UTF-8")).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WebConsole/1.1");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String body = sb.toString();
            
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (!m.find()) return null;
            String url = m.group(1);
            
            if (url.startsWith("http://")) url = "https://" + url.substring(7);
            return url;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    
    private String getPlayerGroup(Player player) {
        java.util.UUID uuid = player.getUniqueId();

        

        try {
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            org.bukkit.plugin.RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(luckPermsClass);
            if (rsp != null) {
                Object lp = rsp.getProvider();
                Object userManager = luckPermsClass.getMethod("getUserManager").invoke(lp);
                Object user = userManager.getClass().getMethod("getUser", java.util.UUID.class).invoke(userManager, uuid);
                if (user != null) {
                    Object group = user.getClass().getMethod("getPrimaryGroup").invoke(user);
                    if (group != null && !group.toString().isEmpty()) {
                        return group.toString();
                    }
                }
            }
        } catch (Throwable ignored) {}

        
        try {
            Class<?> pexClass = Class.forName("ru.tehkode.permissions.bukkit.PermissionsEx");
            Object user = pexClass.getMethod("getUser", java.util.UUID.class).invoke(null, uuid);
            if (user != null) {
                Object parentsObj = user.getClass().getMethod("getParentIdentifiers").invoke(user);
                if (parentsObj instanceof Collection) {
                    for (Object g : (Collection<?>) parentsObj) {
                        String name = (g == null) ? null : g.toString();
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        
        return "default";
    }

    public void stop() {
        if (app != null) app.stop();
    }

    private void loadOpMeta() {
        try {
            if (opMetaFile.exists()) {
                String content = new String(Files.readAllBytes(opMetaFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(content);
                if (root != null && root.isObject()) {
                    root.fields().forEachRemaining(e -> {
                        if (e.getValue().isNumber()) opMeta.put(e.getKey(), e.getValue().asLong());
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveOpMeta() {
        try {
            ObjectNode root = mapper.createObjectNode();
            opMeta.forEach(root::put);
            Files.write(opMetaFile.toPath(), mapper.writeValueAsBytes(root));
        } catch (Exception ignored) {}
    }

    private void loadWlMeta() {
        try {
            if (wlMetaFile.exists()) {
                String content = new String(Files.readAllBytes(wlMetaFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(content);
                if (root != null && root.isObject()) {
                    JsonNode entries = root.path("entries");
                    if (entries.isArray()) {
                        for (JsonNode e : entries) {
                            if (!e.isObject()) continue;
                            String nm = e.path("name").asText("");
                            if (nm.isEmpty()) continue;
                            wlMeta.put(nm, new WlEntry(e.path("at").asLong(0), e.path("by").asText("")));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveWlMeta() {
        try {
            ObjectNode root = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode arr = root.putArray("entries");
            wlMeta.forEach((n, e) -> {
                ObjectNode o = arr.addObject();
                o.put("name", n);
                o.put("at", e.at);
                o.put("by", e.by == null ? "" : e.by);
            });
            Files.write(wlMetaFile.toPath(), mapper.writeValueAsBytes(root));
        } catch (Exception ignored) {}
    }

    private void loadPunishments() {
        try {
            if (punishmentsFile.exists()) {
                String content = new String(Files.readAllBytes(punishmentsFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(content);
                if (root != null && root.isArray()) {
                    synchronized (punishments) {
                        punishments.clear();
                        for (JsonNode n : root) {
                            if (n.isObject()) punishments.add((ObjectNode) n);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void savePunishments() {
        try {
            synchronized (punishments) {
                String json = mapper.writeValueAsString(punishments);
                Files.write(punishmentsFile.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void loadActivityLog() {
        try {
            if (activityLogFile.exists()) {
                String content = new String(Files.readAllBytes(activityLogFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(content);
                if (root != null && root.isArray()) {
                    synchronized (activityLog) {
                        activityLog.clear();
                        for (JsonNode n : root) {
                            if (n.isObject()) activityLog.add((ObjectNode) n);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveActivityLog() {
        try {
            synchronized (activityLog) {
                String json = mapper.writeValueAsString(activityLog);
                Files.write(activityLogFile.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void addActivityLog(String type, String actor, String target, String details, String extra) {
        synchronized (activityLog) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", type);
            node.put("actor", actor == null || actor.isEmpty() ? "Console" : actor);
            node.put("target", target == null ? "" : target);
            node.put("details", details == null ? "" : details);
            node.put("extra", extra == null ? "" : extra);
            node.put("time", System.currentTimeMillis());
            activityLog.add(0, node);
            while (activityLog.size() > ACTIVITY_LOG_MAX) {
                activityLog.remove(activityLog.size() - 1);
            }
        }
        saveActivityLog();
    }

    private void recordPunishment(String player, String type, String admin, String reason, String duration) {
        synchronized (punishments) {
            
            punishments.removeIf(p -> player.equalsIgnoreCase(p.path("player").asText()) && type.equals(p.path("type").asText()));
            ObjectNode node = mapper.createObjectNode();
            node.put("player", player);
            node.put("type", type);
            node.put("admin", admin == null ? "Console" : admin);
            node.put("reason", reason == null ? "" : reason);
            node.put("duration", duration == null ? "" : duration);
            node.put("time", System.currentTimeMillis());
            punishments.add(node);
        }
        savePunishments();
    }

    private void removePunishment(String player, String type) {
        synchronized (punishments) {
            punishments.removeIf(p -> player.equalsIgnoreCase(p.path("player").asText()) && type.equals(p.path("type").asText()));
        }
        savePunishments();
    }

    

    private void loadPanelSettings() {
        try {
            if (panelSettingsFile.exists()) {
                String content = new String(Files.readAllBytes(panelSettingsFile.toPath()), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(content);
                if (root != null && root.isObject()) {
                    root.fields().forEachRemaining(e -> panelSettings.put(e.getKey(), e.getValue().asText()));
                }
            }
        } catch (Exception ignored) {}
    }

    private void savePanelSettings() {
        try {
            ObjectNode root = mapper.createObjectNode();
            panelSettings.forEach(root::put);
            Files.write(panelSettingsFile.toPath(), mapper.writeValueAsBytes(root));
        } catch (Exception ignored) {}
    }

    private boolean hasCurseForgeKey() {
        String fromPanel = panelSettings.get("curseforge_api_key");
        if (fromPanel != null && !fromPanel.trim().isEmpty()) return true;
        String fromConfig = plugin.getConfig().getString("plugins.curseforge-api-key", "");
        return fromConfig != null && !fromConfig.trim().isEmpty();
    }

    private String curseForgeKey() {
        String fromPanel = panelSettings.get("curseforge_api_key");
        if (fromPanel != null && !fromPanel.trim().isEmpty()) return fromPanel.trim();
        return plugin.getConfig().getString("plugins.curseforge-api-key", "").trim();
    }

    
    private boolean hasValidCurseForgeKey() {
        String key = curseForgeKey();
        if (key == null || key.isEmpty()) {
            validatedCfKey = null;
            cfKeyValid = false;
            return false;
        }
        if (key.equals(validatedCfKey) && cfKeyValid != null) return cfKeyValid;
        validateCurseForgeKey(key);
        return Boolean.TRUE.equals(cfKeyValid);
    }

    private void validateCurseForgeKey(String key) {
        validatedCfKey = key;
        cfKeyValid = false;
        if (key == null || key.trim().isEmpty()) return;
        try {
            
            String url = "https://api.curseforge.com/v1/mods/search?gameId=432&classId=5&searchFilter=essentials&pageSize=1&sortField=2&sortOrder=desc";
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "webconsole-server/1.1")
                    .header("Accept", "application/json");
            b.header("x-api-key", key.trim());
            java.net.http.HttpResponse<?> resp = httpClient.send(b.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                cfKeyValid = true;
            } else {
                cfKeyValid = false;
            }
        } catch (Exception e) {
            
            cfKeyValid = false;
        }
    }

    private String coreName() {
        String n = Bukkit.getServer().getName();
        if (n == null || n.isEmpty() || n.equalsIgnoreCase("CraftBukkit")) n = "Paper";
        return n;
    }

    private String minecraftVersion() {
        String bv = Bukkit.getBukkitVersion();
        if (bv == null) return "1.21";
        int dash = bv.indexOf('-');
        String v = dash > 0 ? bv.substring(0, dash) : bv;
        return v.trim();
    }

    private File serverPropertiesFile() {
        return new File(serverRoot(), "server.properties");
    }

    private Properties loadServerProperties() throws Exception {
        Properties p = new Properties();
        File f = serverPropertiesFile();
        if (f.exists()) {
            try (java.io.InputStreamReader r = new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.ISO_8859_1)) {
                p.load(r);
            }
        }
        return p;
    }

    private void saveServerProperties(Properties p) throws Exception {
        File f = serverPropertiesFile();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.ISO_8859_1)) {
            p.store(w, "Minecraft server properties");
        }
    }

    private int parseIntProp(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim()); } catch (Exception e) { return def; }
    }

    private boolean parseBoolProp(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        return v.trim().equalsIgnoreCase("true");
    }

    private Properties bodyToProperties(JsonNode body) {
        Properties p = new Properties();
        if (body == null) return p;
        if (body.has("motd")) p.setProperty("motd", body.get("motd").asText("").replace("\\n", "\n"));
        if (body.has("server_ip")) p.setProperty("server-ip", body.get("server_ip").asText(""));
        if (body.has("server_port")) p.setProperty("server-port", String.valueOf(body.get("server_port").asInt(25565)));
        if (body.has("max_players")) p.setProperty("max-players", String.valueOf(body.get("max_players").asInt(20)));
        if (body.has("view_distance")) p.setProperty("view-distance", String.valueOf(body.get("view_distance").asInt(10)));
        if (body.has("simulation_distance")) p.setProperty("simulation-distance", String.valueOf(body.get("simulation_distance").asInt(10)));
        if (body.has("max_world_size")) p.setProperty("max-world-size", String.valueOf(body.get("max_world_size").asInt(29999984)));
        if (body.has("spawn_protection")) p.setProperty("spawn-protection", String.valueOf(body.get("spawn_protection").asInt(0)));
        if (body.has("online_mode")) p.setProperty("online-mode", String.valueOf(body.get("online_mode").asBoolean(true)));
        if (body.has("allow_flight")) p.setProperty("allow-flight", String.valueOf(body.get("allow_flight").asBoolean(false)));
        if (body.has("pvp")) p.setProperty("pvp", String.valueOf(body.get("pvp").asBoolean(true)));
        if (body.has("command_blocks")) p.setProperty("enable-command-block", String.valueOf(body.get("command_blocks").asBoolean(false)));
        if (body.has("allow_nether")) p.setProperty("allow-nether", String.valueOf(body.get("allow_nether").asBoolean(true)));
        if (body.has("hardcore")) p.setProperty("hardcore", String.valueOf(body.get("hardcore").asBoolean(false)));
        if (body.has("hide_online_players")) p.setProperty("hide-online-players", String.valueOf(body.get("hide_online_players").asBoolean(false)));
        if (body.has("spawn_monsters")) p.setProperty("spawn-monsters", String.valueOf(body.get("spawn_monsters").asBoolean(true)));
        if (body.has("white_list")) p.setProperty("white-list", String.valueOf(body.get("white_list").asBoolean(false)));
        if (body.has("difficulty")) {
            String d = body.get("difficulty").asText("normal");
            String s = d.toLowerCase(Locale.ROOT).replace(" ", "_");
            p.setProperty("difficulty", s);
        }
        return p;
    }

    private void runtimeApply(Properties p, List<String> requires) {
        try { Bukkit.getServer().setMotd(p.getProperty("motd", "")); } catch (Exception ignored) {}
        try { Bukkit.getServer().setMaxPlayers(parseIntProp(p, "max-players", 20)); } catch (Exception ignored) {}
        try { Bukkit.getServer().setSpawnRadius(parseIntProp(p, "spawn-protection", 0)); } catch (Exception ignored) {}
        try { Bukkit.getServer().setWhitelist(parseBoolProp(p, "white-list", false)); } catch (Exception ignored) {}

        if (!parseBoolProp(p, "pvp", true)) requires.add("pvp");
        if (parseBoolProp(p, "enable-command-block", false)) requires.add("command_blocks");
        if (!parseBoolProp(p, "allow-nether", true)) requires.add("allow_nether");
        if (parseBoolProp(p, "hardcore", false)) requires.add("hardcore");
        if (parseBoolProp(p, "hide-online-players", false)) requires.add("hide_online_players");
        if (!parseBoolProp(p, "spawn-monsters", true)) requires.add("spawn_monsters");
        if (parseIntProp(p, "max-world-size", 29999984) != 29999984) requires.add("max_world_size");
    }

    private long totalPhysicalMemory() {
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                long mem = ((com.sun.management.OperatingSystemMXBean) os).getTotalPhysicalMemorySize();
                if (mem > 0) return mem;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String runCommand(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            sb.trimToSize();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private String cpuModel() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                String out = runCommand("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString");
                if (out != null) {
                    int i = out.indexOf("REG_SZ");
                    if (i >= 0) {
                        String nm = out.substring(i + 6).trim();
                        if (!nm.isEmpty()) return nm;
                    }
                }
                String pid = System.getenv("PROCESSOR_IDENTIFIER");
                if (pid != null && !pid.trim().isEmpty()) return pid.trim();
            } else if (osName.contains("linux")) {
                java.nio.file.Path cpuinfo = java.nio.file.Paths.get("/proc/cpuinfo");
                if (Files.exists(cpuinfo)) {
                    for (String l : Files.readAllLines(cpuinfo)) {
                        if (l.startsWith("model name")) {
                            int i = l.indexOf(':');
                            if (i >= 0) return l.substring(i + 1).trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private int cpuPhysicalCores() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                String out = runCommand("powershell.exe", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).NumberOfCores");
                if (out != null) {
                    String[] lines = out.split("\n");
                    for (String l : lines) {
                        String t = l.trim().replaceAll("\\s+", "");
                        if (t.matches("\\d+")) return Integer.parseInt(t);
                    }
                }
            } else if (osName.contains("linux")) {
                java.nio.file.Path cpuinfo = java.nio.file.Paths.get("/proc/cpuinfo");
                if (Files.exists(cpuinfo)) {
                    Set<String> cores = new HashSet<>();
                    String curCore = null;
                    for (String l : Files.readAllLines(cpuinfo)) {
                        if (l.startsWith("processor")) {
                            if (curCore != null) cores.add(curCore);
                            curCore = null;
                        } else if (l.startsWith("core id")) {
                            int i = l.indexOf(':');
                            curCore = i >= 0 ? l.substring(i + 1).trim() : null;
                        }
                    }
                    if (curCore != null) cores.add(curCore);
                    if (!cores.isEmpty()) return cores.size();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String ramFrequencyMhz() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                String out = runCommand("wmic", "MemoryChip", "get", "Speed");
                if (out != null) {
                    String speed = "";
                    String[] lines = out.split("\n");
                    for (String l : lines) {
                        String t = l.trim();
                        if (t.matches("\\d+")) speed = t;
                    }
                    if (!speed.isEmpty()) return speed + " МГц";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private long diskTotal() {
        try { return serverRoot().toPath().getRoot() == null ? 0L : java.nio.file.Files.getFileStore(serverRoot().toPath()).getTotalSpace(); } catch (Exception e) { return 0L; }
    }

    private long diskFree() {
        try { return serverRoot().toPath().getRoot() == null ? 0L : java.nio.file.Files.getFileStore(serverRoot().toPath()).getUsableSpace(); } catch (Exception e) { return 0L; }
    }


    private void searchModrinth(com.fasterxml.jackson.databind.node.ArrayNode out, String query, String sort) throws Exception {
        
        
        
        String facets = "[[\"project_type:plugin\"],[\"loaders:paper\"]]";
        String index = "downloads".equalsIgnoreCase(sort) ? "downloads" : "follows";
        String url = "https://api.modrinth.com/v2/search?query=" + URLEncoder.encode(query, "UTF-8")
                + "&limit=30&index=" + index + "&facets=" + URLEncoder.encode(facets, "UTF-8");
        String body = httpGet(url);
        JsonNode root = mapper.readTree(body);
        JsonNode hits = root.path("hits");
        if (hits.isArray()) {
            List<ObjectNode> results = new ArrayList<>();
            for (JsonNode h : hits) {
                ObjectNode item = mapper.createObjectNode();
                item.put("id", h.path("project_id").asText());
                item.put("slug", h.path("slug").asText());
                item.put("title", h.path("title").asText());
                item.put("description", h.path("description").asText());
                item.put("downloads", h.path("downloads").asLong(0));
                item.put("icon", h.path("icon_url").asText(""));
                item.put("platform", "modrinth");
                item.put("compatible", false); 
                results.add(item);
            }
            results.parallelStream().forEach(this::enrichModrinth);
            for (ObjectNode item : results) {
                if (item != null) out.add(item);
            }
        }
    }

    private void enrichModrinth(ObjectNode item) {
        try {
            String pid = item.path("id").asText();
            String version = minecraftVersion();
            String verUrl = "https://api.modrinth.com/v2/project/" + pid + "/version?game_versions="
                    + URLEncoder.encode("[\"" + version + "\"]", "UTF-8")
                    + "&loaders=" + URLEncoder.encode("[\"paper\"]", "UTF-8");
            String verBody = httpGet(verUrl);
            JsonNode versions = mapper.readTree(verBody);
            if (versions.isArray() && versions.size() > 0) {
                JsonNode v0 = versions.get(0);
                item.put("version", v0.path("version_number").asText(""));
                JsonNode files = v0.path("files");
                if (files.isArray() && files.size() > 0) {
                    item.put("download_url", files.get(0).path("url").asText(""));
                    item.put("fileName", files.get(0).path("filename").asText(""));
                    if (!item.path("fileName").asText("").isEmpty()) {
                        item.put("compatible", true);
                    }
                }
            }
            
            long dl = item.path("downloads").asLong(0);
            item.put("rating", ratingFromDownloads(dl));
        } catch (Exception e) {
            
            item.put("compatible", false);
            long dl = item.path("downloads").asLong(0);
            item.put("rating", ratingFromDownloads(dl));
        }
    }

    private void searchCurseForge(com.fasterxml.jackson.databind.node.ArrayNode out, String query, String sort) throws Exception {
        String version = minecraftVersion();
        String key = curseForgeKey();
        
        int sortField = "downloads".equalsIgnoreCase(sort) ? 6 : 2;
        String searchUrl = "https://api.curseforge.com/v1/mods/search?gameId=432&classId=5&sortField=" + sortField
                + "&sortOrder=desc&pageSize=20&searchFilter="
                + URLEncoder.encode(query, "UTF-8");
        ObjectNode sr = httpGetJson(searchUrl, key);
        JsonNode data = sr.path("data");
        List<ObjectNode> results = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode m : data) {
                ObjectNode item = mapper.createObjectNode();
                long modId = m.path("id").asLong();
                item.put("id", String.valueOf(modId));
                item.put("slug", m.path("slug").asText());
                item.put("title", m.path("name").asText());
                item.put("description", m.path("summary").asText());
                item.put("downloads", m.path("downloadCount").asLong(0));
                JsonNode logo = m.path("logo");
                item.put("icon", logo != null && logo.isObject() ? logo.path("thumbnailUrl").asText("") : "");
                item.put("platform", "curseforge");
                boolean compat = true;
                
                JsonNode files = m.path("latestFiles");
                if (files.isArray() && files.size() > 0) {
                    JsonNode gvs = files.get(0).path("gameVersions");
                    boolean hasVersion = false;
                    if (gvs.isArray()) {
                        for (JsonNode gv : gvs) {
                            if (version.equals(gv.asText())) { hasVersion = true; break; }
                        }
                    }
                    compat = hasVersion;
                }
                item.put("compatible", compat);
                results.add(item);
            }
        }
        
        results.parallelStream().forEach(item -> enrichCurseForge(item, key, version));
        for (ObjectNode item : results) {
            if (item != null) out.add(item);
        }
    }

    private void enrichCurseForge(ObjectNode item, String key, String version) {
        try {
            String id = item.path("id").asText();
            ObjectNode proj = httpGetJson("https://api.curseforge.com/v1/mods/" + id, key);
            JsonNode m = proj.path("data");
            long downloads = item.path("downloads").asLong(0);
            item.put("rating", ratingFromDownloads(downloads));
            
            String fileName = "";
            String url = "";
            String fileVersion = "";
            String fileUrl = "https://api.curseforge.com/v1/mods/" + id + "/files?pageSize=50&gameVersion=" + URLEncoder.encode(version, "UTF-8");
            ObjectNode fr = httpGetJson(fileUrl, key);
            JsonNode fdata = fr.path("data");
            if (fdata.isArray() && fdata.size() > 0) {
                JsonNode f0 = fdata.get(0);
                fileName = f0.path("fileName").asText("");
                JsonNode dl = f0.path("downloadUrl");
                url = dl == null || dl.isNull() ? "" : dl.asText("");
                fileVersion = f0.path("displayName").asText("");
            }
            item.put("fileName", fileName);
            item.put("download_url", url);
            item.put("version", fileVersion);
            item.put("compatible", true);
        } catch (Exception e) {
            item.put("compatible", false);
            long dl = item.path("downloads").asLong(0);
            item.put("rating", ratingFromDownloads(dl));
        }
    }

    private boolean mcCompatible(String server, String tagged) {
        if (server == null || tagged == null || server.isEmpty() || tagged.isEmpty()) return false;
        if (server.equals(tagged)) return true;
        if (server.startsWith(tagged + ".")) return true;
        if (tagged.startsWith(server + ".")) return true;
        return false;
    }

    private void searchMrVersions(com.fasterxml.jackson.databind.node.ArrayNode out, String pid) throws Exception {
        String version = minecraftVersion();
        String url = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(pid, "UTF-8") + "/version?limit=100";
        JsonNode versions = mapper.readTree(httpGet(url));
        if (!versions.isArray()) return;
        List<ObjectNode> list = new ArrayList<>();
        for (JsonNode v : versions) {
            boolean hasMc = false;
            JsonNode gv = v.path("game_versions");
            if (gv.isArray()) { for (JsonNode g : gv) if (mcCompatible(version, g.asText())) { hasMc = true; break; } }
            if (!hasMc) continue;
            boolean hasLoader = false;
            JsonNode lds = v.path("loaders");
            if (lds.isArray()) { for (JsonNode l : lds) if ("paper".equalsIgnoreCase(l.asText())) { hasLoader = true; break; } }
            if (!hasLoader) continue;
            JsonNode files = v.path("files");
            if (!files.isArray() || files.size() == 0) continue;
            JsonNode f0 = files.get(0);
            ObjectNode item = mapper.createObjectNode();
            item.put("version", v.path("version_number").asText(""));
            item.put("download_url", f0.path("url").asText(""));
            item.put("fileName", f0.path("filename").asText(""));
            item.put("published", v.path("date_published").asText(""));
            list.add(item);
        }
        list.sort((a, b) -> b.path("published").asText("").compareTo(a.path("published").asText("")));
        int max = Math.min(15, list.size());
        for (int i = 0; i < max; i++) out.add(list.get(i));
    }

    private void searchCfVersions(com.fasterxml.jackson.databind.node.ArrayNode out, String id, String key) throws Exception {
        String version = minecraftVersion();
        String url = "https://api.curseforge.com/v1/mods/" + URLEncoder.encode(id, "UTF-8")
                + "/files?pageSize=50&gameVersion=" + URLEncoder.encode(version, "UTF-8");
        ObjectNode fr = httpGetJson(url, key);
        JsonNode fdata = fr.path("data");
        if (!fdata.isArray()) return;
        int max = Math.min(15, fdata.size());
        for (int i = 0; i < max; i++) {
            JsonNode f = fdata.get(i);
            JsonNode dl = f.path("downloadUrl");
            ObjectNode item = mapper.createObjectNode();
            item.put("version", f.path("displayName").asText(""));
            item.put("fileName", f.path("fileName").asText(""));
            item.put("download_url", dl == null || dl.isNull() ? "" : dl.asText(""));
            out.add(item);
        }
    }

    private String installPlugin(String url, String suggestedName) {
        File pluginsDir = new File(serverRoot(), "plugins");
        if (!pluginsDir.exists()) pluginsDir.mkdirs();
        String fileName = safeFileName(suggestedName);
        if (fileName == null || fileName.isEmpty()) {
            fileName = "download-" + System.currentTimeMillis() + ".jar";
        }
        File target = new File(pluginsDir, fileName);
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                if (resp.body() != null) resp.body().close();
                return "Сервер вернул код: " + resp.statusCode();
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (target.length() < 100) {
                target.delete();
                return "Файл повреждён (слишком маленький).";
            }
            return null;
        } catch (Exception e) {
            return "Ошибка при скачивании: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private String safeFileName(String name) {
        if (name == null) return null;
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) return null;
        return n;
    }

    
    private int ratingFromDownloads(long dl) {
        if (dl >= 1000000) return 5;
        if (dl >= 200000) return 4;
        if (dl >= 50000) return 3;
        if (dl >= 10000) return 2;
        return 1;
    }

    private String httpGet(String url) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "webconsole-server/1.1")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private ObjectNode httpGetJson(String url, String apiKey) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "webconsole-server/1.1")
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) b.header("x-api-key", apiKey);
        java.net.http.HttpRequest req = b.GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return (ObjectNode) mapper.readTree(resp.body());
    }
}