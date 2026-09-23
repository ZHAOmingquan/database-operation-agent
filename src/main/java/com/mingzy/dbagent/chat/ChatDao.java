package com.mingzy.dbagent.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class ChatDao {

    private final JdbcTemplate jdbc;
    public ChatDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<ChatSession> SESSION_MAPPER = (rs, i) -> new ChatSession(
            rs.getLong("id"), rs.getString("title"),
            rs.getObject("datasource_id") == null ? null : rs.getLong("datasource_id"),
            rs.getObject("model_id") == null ? null : rs.getLong("model_id"),
            rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<ChatMessage> MESSAGE_MAPPER = (rs, i) -> new ChatMessage(
            rs.getLong("id"), rs.getLong("session_id"), rs.getString("role"), rs.getString("content"),
            rs.getString("tool_calls_json"), rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<SqlResult> RESULT_MAPPER = (rs, i) -> new SqlResult(
            rs.getLong("id"), rs.getLong("session_id"),
            rs.getObject("message_id") == null ? null : rs.getLong("message_id"),
            rs.getObject("datasource_id") == null ? null : rs.getLong("datasource_id"),
            rs.getString("datasource_name"), rs.getString("sql_text"), rs.getString("result_type"),
            rs.getString("columns_json"), rs.getString("rows_json"), rs.getInt("row_count"),
            rs.getObject("affected_rows") == null ? null : rs.getInt("affected_rows"),
            rs.getObject("elapsed_ms") == null ? null : rs.getLong("elapsed_ms"),
            rs.getString("ai_comment"), rs.getString("source"), rs.getString("status"),
            rs.getString("error_message"), rs.getString("chart_config"), rs.getString("created_at"));

    private static final RowMapper<ConfirmRequest> CONFIRM_MAPPER = (rs, i) -> new ConfirmRequest(
            rs.getLong("id"), rs.getLong("session_id"),
            rs.getObject("message_id") == null ? null : rs.getLong("message_id"),
            rs.getObject("datasource_id") == null ? null : rs.getLong("datasource_id"),
            rs.getString("datasource_name"), rs.getString("sql_text"), rs.getString("status"),
            rs.getString("created_at"), rs.getString("expires_at"));

    private long key(KeyHolder kh) { return kh.getKey().longValue(); }

    // ==== chat_session ====
    public long insertSession(String title, Long datasourceId, Long modelId, String clientFingerprint) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO chat_session(title, datasource_id, model_id, client_fingerprint) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            if (datasourceId == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, datasourceId);
            if (modelId == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, modelId);
            if (clientFingerprint == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, clientFingerprint);
            return ps;
        }, kh);
        return key(kh);
    }

    public ChatSession findSession(long id) {
        List<ChatSession> l = jdbc.query("SELECT * FROM chat_session WHERE id=?", SESSION_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 归属校验：仅当会话属于该指纹时返回，否则 null（旧的无指纹会话对任何指纹都不可见） */
    public ChatSession findOwnedSession(long id, String clientFingerprint) {
        List<ChatSession> l = jdbc.query("SELECT * FROM chat_session WHERE id=? AND client_fingerprint=?",
                SESSION_MAPPER, id, clientFingerprint);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<ChatSession> listSessions(String clientFingerprint) {
        return jdbc.query("SELECT * FROM chat_session WHERE client_fingerprint=? ORDER BY id DESC",
                SESSION_MAPPER, clientFingerprint);
    }

    public void touchSession(long id, String title, Long datasourceId) {
        jdbc.update("UPDATE chat_session SET title=?, datasource_id=?, updated_at=datetime('now','localtime') WHERE id=?",
                title, datasourceId, id);
    }

    public void deleteSession(long id) {
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", id);
        jdbc.update("DELETE FROM sql_result WHERE session_id=?", id);
        jdbc.update("DELETE FROM confirm_request WHERE session_id=?", id);
        jdbc.update("DELETE FROM chat_session WHERE id=?", id);
    }

    // ==== chat_message ====
    public long insertMessage(long sessionId, String role, String content, String toolCallsJson) {
        return insertMessage(sessionId, role, content, toolCallsJson, "done");
    }

    public long insertMessage(long sessionId, String role, String content, String toolCallsJson, String status) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO chat_message(session_id, role, content, tool_calls_json, status) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId); ps.setString(2, role); ps.setString(3, content);
            ps.setString(4, toolCallsJson); ps.setString(5, status);
            return ps;
        }, kh);
        return key(kh);
    }

    public void updateMessage(long id, String content, String status, String toolCallsJson) {
        jdbc.update("UPDATE chat_message SET content=?, status=?, tool_calls_json=? WHERE id=?",
                content, status, toolCallsJson, id);
    }

    public List<ChatMessage> listMessages(long sessionId) {
        return jdbc.query("SELECT * FROM chat_message WHERE session_id=? ORDER BY id", MESSAGE_MAPPER, sessionId);
    }

    // ==== sql_result ====
    public long insertResult(SqlResult r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO sql_result(session_id, message_id, datasource_id, datasource_name, sql_text, result_type, " +
                "columns_json, rows_json, row_count, affected_rows, elapsed_ms, ai_comment, source, status, error_message, chart_config) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.sessionId());
            if (r.messageId() == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, r.messageId());
            if (r.datasourceId() == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, r.datasourceId());
            ps.setString(4, r.datasourceName()); ps.setString(5, r.sqlText()); ps.setString(6, r.resultType());
            ps.setString(7, r.columnsJson()); ps.setString(8, r.rowsJson()); ps.setInt(9, r.rowCount());
            if (r.affectedRows() == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, r.affectedRows());
            if (r.elapsedMs() == null) ps.setNull(11, java.sql.Types.INTEGER); else ps.setLong(11, r.elapsedMs());
            ps.setString(12, r.aiComment()); ps.setString(13, r.source()); ps.setString(14, r.status());
            ps.setString(15, r.errorMessage()); ps.setString(16, r.chartConfig());
            return ps;
        }, kh);
        return key(kh);
    }

    public SqlResult findResult(long id) {
        List<SqlResult> l = jdbc.query("SELECT * FROM sql_result WHERE id=?", RESULT_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public void updateResultComment(long id, String aiComment) {
        jdbc.update("UPDATE sql_result SET ai_comment=? WHERE id=?", aiComment, id);
    }

    public List<SqlResult> listResults(long sessionId) {
        return jdbc.query("SELECT * FROM sql_result WHERE session_id=? ORDER BY id DESC", RESULT_MAPPER, sessionId);
    }

    // ==== confirm_request ====
    public long insertConfirm(ConfirmRequest c) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO confirm_request(session_id, message_id, datasource_id, datasource_name, sql_text, status, expires_at) " +
                "VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, c.sessionId());
            if (c.messageId() == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, c.messageId());
            if (c.datasourceId() == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, c.datasourceId());
            ps.setString(4, c.datasourceName()); ps.setString(5, c.sqlText()); ps.setString(6, c.status());
            ps.setString(7, c.expiresAt());
            return ps;
        }, kh);
        return key(kh);
    }

    public ConfirmRequest findConfirm(long id) {
        List<ConfirmRequest> l = jdbc.query("SELECT * FROM confirm_request WHERE id=?", CONFIRM_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public void updateConfirmStatus(long id, String status) {
        jdbc.update("UPDATE confirm_request SET status=? WHERE id=?", status, id);
    }
}
