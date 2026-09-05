package txt.console.webconsole;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import txt.console.webconsole.server.WebServer;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class WebConsolePlugin extends JavaPlugin {

    private WebServer webServer;
    private AbstractAppender logAppender;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        int port = getConfig().getInt("server.port", 8080);
        String host = getConfig().getString("server.host", "0.0.0.0");

        getLogger().info(isEnglish()
                ? "\u001b[32mInitializing WebConsole...\u001b[0m"
                : "\u001b[32mИнициализация WebConsole...\u001b[0m");

        moveConsoleLogs();

        printWelcomeBanner();

        webServer = new WebServer(this);
        webServer.start(host, port);

        setupLogInterceptor();

        org.bukkit.command.PluginCommand wc = getCommand("wc");
        if (wc != null) {
            wc.setExecutor(this::onWcCommand);
        }
    }

    private boolean onWcCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player && !sender.hasPermission("webconsole.command")) {
            sender.sendMessage(isEnglish() ? "You don't have permission to use this command."
                    : "У вас нет прав на использование этой команды.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (sender instanceof Player) {
                String name = sender.getName();
                if (webServer == null || !webServer.canReload(name)) {
                    sender.sendMessage(isEnglish() ? "You don't have access to reload the panel."
                            : "У вас нет доступа к перезагрузке панели.");
                    return true;
                }
            }
            reloadPanel();
            sender.sendMessage(webServer != null && webServer.isEnglish()
                    ? "WebConsole panel has been reloaded."
                    : "Панель WebConsole перезагружена.");
            return true;
        }
        sender.sendMessage(isEnglish() ? "Usage: /wc reload"
                : "Использование: /wc reload");
        return true;
    }

    private void reloadPanel() {
        reloadConfig();
        if (webServer != null) webServer.stop();
        int port = getConfig().getInt("server.port", 8080);
        String host = getConfig().getString("server.host", "0.0.0.0");
        webServer = new WebServer(this);
        webServer.start(host, port);
        getLogger().info(isEnglish()
                ? "\u001b[32mWebConsole has been reloaded.\u001b[0m"
                : "\u001b[32mWebConsole перезагружен.\u001b[0m");
    }

    private boolean isEnglish() {
        return "en".equalsIgnoreCase(getConfig().getString("lang", "ru"));
    }

    private void printWelcomeBanner() {
        String version = getDescription().getVersion();
        String[] lines;
        if (isEnglish()) {
            lines = new String[]{
                "#--------------------------------------------#",
                "#",
                "# WebConsole // By @TxT.Console",
                "# version → v" + version + " // Date → 05.09.2026",
                "#",
                "# Thanks for using the plugin <3",
                "#",
                "#--------------------------------------------#"
            };
        } else {
            lines = new String[]{
                "#--------------------------------------------#",
                "#",
                "# WebConsole // От @TxT.Console",
                "# версия → v" + version + " // Дата → 05.09.2026",
                "#",
                "# Спасибо, за использование плагина <3",
                "#",
                "#--------------------------------------------#"
            };
        }
        for (String line : lines) {
            getLogger().info("\u001b[32m" + line + "\u001b[0m");
        }
    }

    private void moveConsoleLogs() {
        try {
            File logsDir = new File(getDataFolder(), "logs_console");
            if (!logsDir.exists()) logsDir.mkdirs();
            File[] files = new File(".").listFiles((dir, name) ->
                    (name.startsWith("console-new") && (name.endsWith(".txt") || name.endsWith(".txt.err"))));
            if (files != null) {
                for (File f : files) {
                    File target = new File(logsDir, f.getName());
                    boolean ok = f.renameTo(target);
                    if (!ok) {
                        java.nio.file.Files.move(f.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDisable() {
        removeLogInterceptor();

        if (webServer != null) {
            webServer.stop();
        }
        getLogger().info("\u001b[32m"
                + (isEnglish() ? "WebConsole has been disabled successfully." : "WebConsole успешно выключен.")
                + "\u001b[0m");
    }

    private void setupLogInterceptor() {
        Logger rootLogger = (Logger) LogManager.getRootLogger();

        logAppender = new AbstractAppender("WebConsoleAppender", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (webServer != null) {
                    String level = event.getLevel().name();
                    
                    String message = event.getMessage().getFormattedMessage();
                    String time = timeFormat.format(new Date(event.getTimeMillis()));

                    webServer.broadcastLog(time, level, message);
                }
            }
        };

        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    private void removeLogInterceptor() {
        if (logAppender != null) {
            Logger rootLogger = (Logger) LogManager.getRootLogger();
            rootLogger.removeAppender(logAppender);
            logAppender.stop();
        }
    }
}