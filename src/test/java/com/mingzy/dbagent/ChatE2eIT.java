package com.mingzy.dbagent;

import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 手动运行：需 192.168.110.88 可达且 MiniMax 可访问。命令见 README。 */
@Disabled("手动端到端：依赖外网模型与测试库")
@SpringBootTest
@TestPropertySource(properties = "app.db.path=./target/test-data/e2e.db")
class ChatE2eIT {

    @Autowired ChatService chatService;
    @Autowired ChatDao chatDao;

    @Test
    void naturalLanguageQueryUsers() {
        long sessionId = chatDao.insertSession("e2e", 1L, 1L, "fp-e2e-manual"); // 1=mysql-mytest
        chatService.handleUserMessage(sessionId, "帮我查询用户列表？", 1L, 1L);
        var messages = chatDao.listMessages(sessionId);
        assertThat(messages).isNotEmpty();
        var results = chatDao.listResults(sessionId);
        assertThat(results).isNotEmpty();
        System.out.println("=== RESULTS ===");
        results.forEach(r -> System.out.println(r.sqlText() + " | rows=" + r.rowCount() + " | comment=" + r.aiComment()));
    }
}
