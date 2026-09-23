package com.mingzy.dbagent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class WsSessionRegistry {

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void register(long chatSessionId, WebSocketSession ws) {
        sessions.computeIfAbsent(chatSessionId, k -> new CopyOnWriteArraySet<>()).add(ws);
    }

    public void unregister(long chatSessionId, WebSocketSession ws) {
        Set<WebSocketSession> set = sessions.get(chatSessionId);
        if (set != null) set.remove(ws);
    }

    /** 推送事件：{"type":..., "payload":...} */
    public void send(long chatSessionId, String type, Object payload) {
        Set<WebSocketSession> set = sessions.get(chatSessionId);
        if (set == null || set.isEmpty()) return;
        try {
            String json = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
            TextMessage msg = new TextMessage(json);
            for (WebSocketSession ws : set) {
                if (ws.isOpen()) {
                    synchronized (ws) { ws.sendMessage(msg); }
                }
            }
        } catch (Exception e) {
            log.warn("ws send failed: {}", e.getMessage());
        }
    }
}
