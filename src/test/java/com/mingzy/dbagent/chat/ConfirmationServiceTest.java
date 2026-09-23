package com.mingzy.dbagent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConfirmationServiceTest {

    private final ChatDao chatDao = mock(ChatDao.class);
    private final WsSessionRegistry ws = mock(WsSessionRegistry.class);
    private final ConfirmationService service = new ConfirmationService(chatDao, ws, 60);

    private ConfirmRequest pendingConfirm() {
        return new ConfirmRequest(7L, 9L, null, null, "mysql-mytest",
                "delete from users where id=1", "pending", null, "2030-01-01 00:00:00");
    }

    @Test
    void approveRejectsForeignFingerprint() {
        when(chatDao.findConfirm(7L)).thenReturn(pendingConfirm());
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> service.approve(7L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话不存在或无访问权限: 9");
        verify(chatDao, never()).updateConfirmStatus(anyLong(), anyString());
    }

    @Test
    void approveOwnedSessionProceeds() {
        when(chatDao.findConfirm(7L)).thenReturn(pendingConfirm());
        when(chatDao.findOwnedSession(9L, "fp-a"))
                .thenReturn(new ChatSession(9L, "t", null, null, null, null));

        service.approve(7L, "fp-a");

        verify(chatDao).updateConfirmStatus(7L, "approved");
    }

    @Test
    void rejectRejectsForeignFingerprint() {
        when(chatDao.findConfirm(7L)).thenReturn(pendingConfirm());
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> service.reject(7L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话不存在或无访问权限: 9");
        verify(chatDao, never()).updateConfirmStatus(anyLong(), anyString());
    }
}
