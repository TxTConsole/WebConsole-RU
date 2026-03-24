package com.webconsole;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.WebSocket;
import org.bukkit.Bukkit;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;

public class MyWebSocketServer extends WebSocketServer {
    private final WebConsolePlugin plugin;
    private final Set<WebSocket> authenticatedClients = new HashSet<>();

    public MyWebSocketServer(InetSocketAddress address, WebConsolePlugin plugin) {
        super(address);
        this.plugin = plugin;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        conn.send("SYSTEM: Соединение установлено. Введите пароль (auth:пароль).");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        authenticatedClients.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.equals("auth:admin123")) {
            authenticatedClients.add(conn);
            conn.send("SYSTEM: Авторизация успешна!");
            return;
        }

        if (authenticatedClients.contains(conn)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message);
            });
        } else {
            conn.send("SYSTEM: Ошибка! Введите пароль (auth:admin123)");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) { ex.printStackTrace(); }

    @Override
    public void onStart() { }

    public void broadcastLog(String message) {
        for (WebSocket conn : authenticatedClients) {
            conn.send(message);
        }
    }
}