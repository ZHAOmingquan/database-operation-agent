package com.mingzy.dbagent.chat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatQueue;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import com.mingzy.dbagent.common.BrowserFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatDao chatDao;
    private final WsSessionRegistry registry;
    private final ChatQueue chatQueue;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatWebSocketHandler(ChatDao chatDao, WsSessionRegistry registry, ChatQueue chatQueue) {
        this.chatDao = chatDao;
        this.registry = registry;
        this.chatQueue = chatQueue;
    }

    /** 握手校验：会话必须属于连接携带的浏览器指纹（?fp=），否则拒绝（Policy Violation） */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        long chatSessionId;
        try {
            chatSessionId = sessionId(session);
        } catch (RuntimeException e) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String fingerprint = BrowserFingerprint.fromQuery(
                session.getUri() == null ? null : session.getUri().getQuery());
        if (fingerprint == null || fingerprint.isBlank()
                || chatDao.findOwnedSession(chatSessionId, fingerprint.trim()) == null) {
            log.warn("WS 握手拒绝：sessionId={} 指纹缺失或不匹配", chatSessionId);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
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
            // 进入全局排队队列，由唯一的 worker 线程按 FIFO 顺序处理，避免并发打爆模型
            chatQueue.enqueue(sessionId(session), content, datasourceId, modelId);
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
