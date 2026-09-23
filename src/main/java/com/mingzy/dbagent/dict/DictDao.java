package com.mingzy.dbagent.dict;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class DictDao {

    private final JdbcTemplate jdbc;
    public DictDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<DictItem> MAPPER = (rs, i) -> new DictItem(
            rs.getLong("id"), rs.getString("dict_type"), rs.getString("dict_key"),
            rs.getString("dict_label"), rs.getString("parent_key"),
            rs.getInt("sort"), rs.getInt("enabled") == 1);

    public long insert(DictItem d) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO sys_dict(dict_type, dict_key, dict_label, parent_key, sort, enabled) VALUES(?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, d.dictType()); ps.setString(2, d.dictKey()); ps.setString(3, d.dictLabel());
            ps.setString(4, d.parentKey()); ps.setInt(5, d.sort()); ps.setInt(6, d.enabled() ? 1 : 0);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void update(DictItem d) {
        jdbc.update("UPDATE sys_dict SET dict_type=?, dict_key=?, dict_label=?, parent_key=?, sort=?, enabled=? WHERE id=?",
                d.dictType(), d.dictKey(), d.dictLabel(), d.parentKey(), d.sort(), d.enabled() ? 1 : 0, d.id());
    }

    public void delete(long id) { jdbc.update("DELETE FROM sys_dict WHERE id=?", id); }

    public DictItem findById(long id) {
        List<DictItem> l = jdbc.query("SELECT * FROM sys_dict WHERE id=?", MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<DictItem> find(String type, String parentKey, Boolean enabledOnly) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sys_dict WHERE dict_type=?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(type);
        if (parentKey != null) { sql.append(" AND parent_key=?"); args.add(parentKey); }
        if (Boolean.TRUE.equals(enabledOnly)) { sql.append(" AND enabled=1"); }
        sql.append(" ORDER BY sort, id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
