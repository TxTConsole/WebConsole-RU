package com.webconsole;

import org.bukkit.Bukkit;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

public class PluginInstaller {

    private static final String ALLOWED_DOMAIN = "https://cdn.modrinth.com/";

    public static boolean downloadPlugin(String url, String fileName) {
        // Проверка безопасности: разрешён только cdn.modrinth.com
        if (!url.startsWith(ALLOWED_DOMAIN)) {
            Bukkit.getLogger().severe("[WebConsole] Запрещённый URL: " + url);
            return false;
        }

        // Проверка имени файла на path traversal
        if (fileName.matches(".*[\\\\/]|.*\\.\\.+.*") || fileName.isEmpty()) {
            Bukkit.getLogger().severe("[WebConsole] Некорректное имя файла: " + fileName);
            return false;
        }

        // Проверка расширения
        if (!fileName.endsWith(".jar")) {
            Bukkit.getLogger().severe("[WebConsole] Файл не является JAR: " + fileName);
            return false;
        }

        HttpURLConnection connection = null;
        try {
            URL urlObj = new URL(url);
            connection = (HttpURLConnection) urlObj.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "WebConsole-Plugin-Installer/1.0");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Bukkit.getLogger().severe("[WebConsole] HTTP ошибка: " + responseCode);
                return false;
            }

            try (InputStream in = connection.getInputStream();
                 ReadableByteChannel rbc = Channels.newChannel(in);
                 FileOutputStream fos = new FileOutputStream("plugins/" + fileName)) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }

            Bukkit.getLogger().info("[WebConsole] Плагин успешно загружен: " + fileName);
            return true;

        } catch (IOException e) {
            Bukkit.getLogger().severe("[WebConsole] Ошибка при скачивании плагина: " + e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}