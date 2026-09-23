package com.mingzy.dbagent.datasource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasourceDaoTest {

    private DatasourceDao dao;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE ds_datasource (
              id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, db_type TEXT NOT NULL,
              host TEXT NOT NULL, port INTEGER NOT NULL, database_name TEXT NOT NULL, username TEXT NOT NULL,
              password TEXT NOT NULL, extra_params TEXT, read_only INTEGER NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
              updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))
            """);
        dao = new DatasourceDao(jdbc);
    }

    @Test
    void crudRoundTrip() {
        Datasource d = new Datasource(null, "local-mysql", "mysql", "127.0.0.1", 3306,
                "mytest", "root", "cipher", null, false, null, null);
        long id = dao.insert(d);
        Datasource loaded = dao.findById(id);
        assertThat(loaded.name()).isEqualTo("local-mysql");
        assertThat(loaded.readOnly()).isFalse();

        dao.update(new Datasource(id, "local-mysql", "mysql", "127.0.0.1", 3307,
                "mytest", "root", "cipher2", "x=1", true, null, null));
        assertThat(dao.findById(id).port()).isEqualTo(3307);
        assertThat(dao.findById(id).readOnly()).isTrue();

        assertThat(dao.findByName("local-mysql").id()).isEqualTo(id);
        assertThat(dao.findAll()).hasSize(1);
        dao.delete(id);
        assertThat(dao.findById(id)).isNull();
    }

    @Test
    void duplicateNameRejected() {
        Datasource d = new Datasource(null, "dup", "mysql", "h", 1, "d", "u", "p", null, false, null, null);
        dao.insert(d);
        assertThatThrownBy(() -> dao.insert(d)).isInstanceOf(Exception.class);
    }
}
