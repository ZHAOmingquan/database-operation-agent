package com.mingzy.dbagent.chat.ws;

import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatQueue;
import com.mingzy.dbagent.chat.ChatSession;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {

    private final ChatDao chatDao = mock(ChatDao.class);
    private final WsSessionRegistry registry = mock(WsSessionRegistry.class);
    private final ChatQueue chatQueue = mock(ChatQueue.class);
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(chatDao, registry, chatQueue);

    private WebSocketSession ws(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create(uri));
        return session;
    }

    @Test
    void registersWhenFingerprintMatches() throws Exception {
        WebSocketSession session = ws("/ws/session/9?fp=fp-a");
        when(chatDao.findOwnedSession(9L, "fp-a"))
                .thenReturn(new ChatSession(9L, "t", null, null, null, null));

        handler.afterConnectionEstablished(session);

        verify(registry).register(9L, session);
        verify(session, never()).close(any());
    }

    @Test
    void closesWhenFingerprintMissing() throws Exception {
        WebSocketSession session = ws("/ws/session/9");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(registry, never()).register(anyLong(), any());
    }

    @Test
    void closesWhenFingerprintMismatches() throws Exception {
        WebSocketSession session = ws("/ws/session/9?fp=fp-b");
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(registry, never()).register(anyLong(), any());
    }
}
