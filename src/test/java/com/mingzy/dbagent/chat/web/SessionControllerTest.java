package com.mingzy.dbagent.chat.web;

import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatSession;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.executor.DeleteGuard;
import com.mingzy.dbagent.executor.SqlExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SessionControllerTest {

    private final ChatDao chatDao = mock(ChatDao.class);
    private final DatasourceService datasourceService = mock(DatasourceService.class);
    private final SqlExecutor sqlExecutor = mock(SqlExecutor.class);
    private final WsSessionRegistry ws = mock(WsSessionRegistry.class);
    private final DeleteGuard deleteGuard = mock(DeleteGuard.class);
    private final SessionController controller =
            new SessionController(chatDao, datasourceService, sqlExecutor, ws, deleteGuard);

    @Test
    void listRequiresFingerprint() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("缺少浏览器指纹");
    }

    @Test
    void listFiltersByFingerprint() {
        ChatSession s = new ChatSession(1L, "新会话", null, null, null, null);
        when(chatDao.listSessions("fp-a")).thenReturn(List.of(s));

        Result<List<ChatSession>> r = controller.list("fp-a");

        assertThat(r.code()).isZero();
        assertThat(r.data()).containsExactly(s);
        verify(chatDao).listSessions("fp-a");
    }

    @Test
    void messagesRejectsForeignSession() {
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> controller.messages(9L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("会话不存在或无访问权限: 9");
    }

    @Test
    void deleteRejectsForeignSessionWithoutClearingData() {
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> controller.delete(9L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("会话不存在或无访问权限: 9");
        verify(chatDao, never()).deleteSession(anyLong());
    }
}
