package com.mingzy.dbagent.chat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingzy.dbagent.chat.ChatService;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatService chatService;
    private final WsSessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatWebSocketHandler(ChatService chatService, WsSessionRegistry registry) {
        this.chatService = chatService;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long chatSessionId = sessionId(session);
        registry.register(chatSessionId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText();
        if ("user_message".equals(type)) {
            String content = node.path("content").asText();
            Long datasourceId = node.hasNonNull("datasourceId") ? node.get("datasourceId").asLong() : null;
            Long modelId = node.hasNonNull("modelId") ? node.get("modelId").asLong() : null;
            // 异步执行，避免阻塞 WS 线程
            new Thread(() -> chatService.handleUserMessage(sessionId(session), content, datasourceId, modelId),
                    "chat-" + sessionId(session)).start();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(sessionId(session), session);
    }

    private long sessionId(WebSocketSession session) {
        String path = session.getUri().getPath(); // /ws/session/{id}
        String id = path.substring(path.lastIndexOf('/') + 1);
        return Long.parseLong(id);
    }
}
