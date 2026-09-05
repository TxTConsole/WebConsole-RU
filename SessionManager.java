package txt.console.webconsole.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.file.Files;
import java.security.Key;
import java.util.Base64;
import java.util.UUID;

public class SessionManager {

    private final JavaPlugin plugin;
    private final File sessionDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Key secretKey;

    public SessionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sessionDir = new File(plugin.getDataFolder(), "sessions");

        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }

        
        this.secretKey = new SecretKeySpec("WebConsoleSecret".getBytes(), "AES");
    }

    public String createSession(String username, long timeoutHours) {
        String token = UUID.randomUUID().toString();
        long expires = System.currentTimeMillis() + (timeoutHours * 3600 * 1000L);

        ObjectNode node = mapper.createObjectNode();
        node.put("username", username);
        node.put("expires", expires);
        node.put("token", token);

        try {
            String json = mapper.writeValueAsString(node);
            String encryptedData = encrypt(json);

            File userFile = new File(sessionDir, username + ".session");
            Files.write(userFile.toPath(), encryptedData.getBytes());
        } catch (Exception e) {
            plugin.getLogger().warning("\u001b[32mНе удалось сохранить зашифрованную сессию для: " + username + "\u001b[0m");
        }
        return token;
    }

    public String validateToken(String token) {
        if (token == null || token.isEmpty()) return null;

        long now = System.currentTimeMillis();
        File[] files = sessionDir.listFiles();

        if (files == null) return null;

        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".session")) continue;

            try {
                String encrypted = new String(Files.readAllBytes(file.toPath()));
                String json = decrypt(encrypted);
                JsonNode node = mapper.readTree(json);

                String savedToken = node.get("token").asText();
                long expires = node.get("expires").asLong();
                String username = node.get("username").asText();

                if (savedToken.equals(token)) {
                    if (now > expires) {
                        file.delete(); 
                        return null;
                    }
                    return username; 
                }
            } catch (Exception e) {
                file.delete(); 
            }
        }
        return null;
    }

    public void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        File[] files = sessionDir.listFiles();

        if (files == null) return;

        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".session")) continue;
            try {
                String encrypted = new String(Files.readAllBytes(file.toPath()));
                String json = decrypt(encrypted);
                JsonNode node = mapper.readTree(json);
                if (now > node.get("expires").asLong()) {
                    file.delete();
                }
            } catch (Exception e) {
                file.delete();
            }
        }
    }

    private String encrypt(String data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes()));
    }

    private String decrypt(String data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return new String(cipher.doFinal(Base64.getDecoder().decode(data)));
    }
}