package com.mingzy.dbagent.datasource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class DatasourceDao {

    private final JdbcTemplate jdbc;

    public DatasourceDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Datasource> MAPPER = (rs, i) -> new Datasource(
            rs.getLong("id"), rs.getString("name"), rs.getString("db_type"),
            rs.getString("host"), rs.getInt("port"), rs.getString("database_name"),
            rs.getString("username"), rs.getString("password"), rs.getString("extra_params"),
            rs.getInt("read_only") == 1, rs.getString("created_at"), rs.getString("updated_at"));

    public long insert(Datasource d) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO ds_datasource(name, db_type, host, port, database_name, username, password, extra_params, read_only) " +
                "VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, d.name()); ps.setString(2, d.dbType()); ps.setString(3, d.host());
            ps.setInt(4, d.port()); ps.setString(5, d.databaseName()); ps.setString(6, d.username());
            ps.setString(7, d.password()); ps.setString(8, d.extraParams()); ps.setInt(9, d.readOnly() ? 1 : 0);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void update(Datasource d) {
        jdbc.update("UPDATE ds_datasource SET name=?, db_type=?, host=?, port=?, database_name=?, username=?, " +
                "password=?, extra_params=?, read_only=?, updated_at=datetime('now','localtime') WHERE id=?",
                d.name(), d.dbType(), d.host(), d.port(), d.databaseName(), d.username(),
                d.password(), d.extraParams(), d.readOnly() ? 1 : 0, d.id());
    }

    public void delete(long id) { jdbc.update("DELETE FROM ds_datasource WHERE id=?", id); }

    public Datasource findById(long id) {
        List<Datasource> list = jdbc.query("SELECT * FROM ds_datasource WHERE id=?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Datasource findByName(String name) {
        List<Datasource> list = jdbc.query("SELECT * FROM ds_datasource WHERE name=?", MAPPER, name);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Datasource> findAll() {
        return jdbc.query("SELECT * FROM ds_datasource ORDER BY id", MAPPER);
    }
}
