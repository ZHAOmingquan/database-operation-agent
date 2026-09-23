package com.mingzy.dbagent.model;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class AiModelDao {

    private final JdbcTemplate jdbc;
    public AiModelDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AiModel> MAPPER = (rs, i) -> new AiModel(
            rs.getLong("id"), rs.getString("name"), rs.getString("provider"), rs.getString("base_url"),
            rs.getString("api_key"), rs.getString("model_id"), rs.getDouble("temperature"),
            rs.getInt("max_tokens"), rs.getInt("enabled") == 1,
            rs.getString("created_at"), rs.getString("updated_at"));

    public long insert(AiModel m) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO ai_model(name, provider, base_url, api_key, model_id, temperature, max_tokens, enabled) " +
                "VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, m.name()); ps.setString(2, m.provider()); ps.setString(3, m.baseUrl());
            ps.setString(4, m.apiKey()); ps.setString(5, m.modelId()); ps.setDouble(6, m.temperature());
            ps.setInt(7, m.maxTokens()); ps.setInt(8, m.enabled() ? 1 : 0);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void update(AiModel m) {
        jdbc.update("UPDATE ai_model SET name=?, provider=?, base_url=?, api_key=?, model_id=?, temperature=?, " +
                "max_tokens=?, enabled=?, updated_at=datetime('now','localtime') WHERE id=?",
                m.name(), m.provider(), m.baseUrl(), m.apiKey(), m.modelId(), m.temperature(),
                m.maxTokens(), m.enabled() ? 1 : 0, m.id());
    }

    public void delete(long id) { jdbc.update("DELETE FROM ai_model WHERE id=?", id); }

    public AiModel findById(long id) {
        List<AiModel> l = jdbc.query("SELECT * FROM ai_model WHERE id=?", MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<AiModel> findAll() { return jdbc.query("SELECT * FROM ai_model ORDER BY id", MAPPER); }

    public void disableAll() { jdbc.update("UPDATE ai_model SET enabled=0 WHERE enabled=1"); }
}
