package com.mingzy.dbagent.sysconfig;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysConfigDao {

    private final JdbcTemplate jdbc;
    public SysConfigDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<SysConfig> MAPPER = (rs, i) -> new SysConfig(
            rs.getLong("id"), rs.getString("config_key"), rs.getString("config_value"),
            rs.getString("description"), rs.getString("updated_at"));

    public List<SysConfig> findAll() {
        return jdbc.query("SELECT * FROM sys_config ORDER BY id", MAPPER);
    }

    public SysConfig findByKey(String key) {
        List<SysConfig> l = jdbc.query("SELECT * FROM sys_config WHERE config_key=?", MAPPER, key);
        return l.isEmpty() ? null : l.get(0);
    }

    public void updateValue(String key, String value) {
        jdbc.update("UPDATE sys_config SET config_value=?, updated_at=datetime('now','localtime') WHERE config_key=?", value, key);
    }
}
