package com.mingzy.dbagent.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDaoTest {

    private ChatDao dao;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE chat_session (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '新会话',
              datasource_id INTEGER, model_id INTEGER, created_at TEXT, updated_at TEXT)
            """);
        jdbc.execute("""
            CREATE TABLE chat_message (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
              role TEXT NOT NULL, content TEXT, tool_calls_json TEXT, status TEXT NOT NULL DEFAULT 'done', created_at TEXT)
            """);
        jdbc.execute("""
            CREATE TABLE sql_result (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, message_id INTEGER,
              datasource_id INTEGER, datasource_name TEXT, sql_text TEXT NOT NULL, result_type TEXT NOT NULL,
              columns_json TEXT, rows_json TEXT, row_count INTEGER DEFAULT 0, affected_rows INTEGER, elapsed_ms INTEGER,
              ai_comment TEXT, source TEXT NOT NULL DEFAULT 'agent', status TEXT NOT NULL DEFAULT 'success',
              error_message TEXT, chart_config TEXT, created_at TEXT)
            """);
        jdbc.execute("""
            CREATE TABLE confirm_request (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
              message_id INTEGER, datasource_id INTEGER, datasource_name TEXT, sql_text TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'pending', created_at TEXT, expires_at TEXT NOT NULL)
            """);
        dao = new ChatDao(jdbc);
    }

    @Test
    void sessionCrud() {
        long id = dao.insertSession("测试会话", 1L, 2L);
        assertThat(dao.findSession(id).title()).isEqualTo("测试会话");
        dao.touchSession(id, "新标题", 3L);
        assertThat(dao.findSession(id).title()).isEqualTo("新标题");
        assertThat(dao.findSession(id).datasourceId()).isEqualTo(3L);
        assertThat(dao.listSessions()).hasSize(1);
        dao.deleteSession(id);
        assertThat(dao.findSession(id)).isNull();
    }

    @Test
    void messageAndResultFlow() {
        long sid = dao.insertSession("s", null, null);
        long mid = dao.insertMessage(sid, "user", "查用户列表", null);
        long aid = dao.insertMessage(sid, "assistant", null, null, "running");
        dao.updateMessage(aid, "共 10 个用户", "done", "[1]");
        List<ChatMessage> msgs = dao.listMessages(sid);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).content()).isEqualTo("共 10 个用户");
        assertThat(msgs.get(1).toolCallsJson()).isEqualTo("[1]");

        long rid = dao.insertResult(new SqlResult(null, sid, aid, 1L, "mysql-mytest",
                "select count(*) from users", "query", "[\"count(*)\"]", "[[10]]", 1, null, 5L,
                null, "agent", "success", null, null, null));
        List<SqlResult> results = dao.listResults(sid);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).rowCount()).isEqualTo(1);
        dao.updateResultComment(rid, "系统有 10 个用户");
        assertThat(dao.listResults(sid).get(0).aiComment()).isEqualTo("系统有 10 个用户");
    }

    @Test
    void chartConfigRoundTrip() {
        long sid = dao.insertSession("s", null, null);
        String cfg = "{\"chartType\":\"bar\",\"title\":\"用户状态分布\",\"xField\":\"status\",\"yField\":\"cnt\"}";
        long rid = dao.insertResult(new SqlResult(null, sid, null, 1L, "mysql-mytest",
                "select status, count(*) as cnt from users group by status", "query",
                "[\"status\",\"cnt\"]", "[[\"1\",1036]]", 1, null, 7L,
                null, "agent", "success", null, cfg, null));
        assertThat(dao.findResult(rid).chartConfig()).isEqualTo(cfg);
    }

    @Test
    void confirmRequestFlow() {
        long sid = dao.insertSession("s", null, null);
        long cid = dao.insertConfirm(new ConfirmRequest(null, sid, 9L, 1L, "mysql-mytest",
                "delete from users where id=1", "pending", null, "2030-01-01 00:00:00"));
        assertThat(dao.findConfirm(cid).status()).isEqualTo("pending");
        dao.updateConfirmStatus(cid, "approved");
        assertThat(dao.findConfirm(cid).status()).isEqualTo("approved");
    }
}
