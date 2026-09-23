package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.common.AesGcmUtil;
import com.mingzy.dbagent.datasource.dto.DatasourceRequest;
import com.mingzy.dbagent.datasource.dto.DatasourceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceServiceTest {

    private DatasourceService service;
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
        service = new DatasourceService(dao, new AesGcmUtil("VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU="),
                new DynamicDataSourceManager());
    }

    @Test
    void createEncryptsPasswordAndViewHidesIt() {
        DatasourceView view = service.create(new DatasourceRequest("m1", "mysql", "h", 3306, "db",
                "root", "secret", null, false));
        assertThat(view.id()).isNotNull();
        Datasource stored = dao.findById(view.id());
        assertThat(stored.password()).isNotEqualTo("secret").contains("=");
        assertThat(service.decryptPassword(stored)).isEqualTo("secret");
    }

    @Test
    void updateWithBlankPasswordKeepsOldOne() {
        DatasourceView view = service.create(new DatasourceRequest("m2", "mysql", "h", 3306, "db",
                "root", "secret", null, false));
        service.update(view.id(), new DatasourceRequest("m2", "mysql", "h2", 3306, "db",
                "root", "", null, true));
        Datasource stored = dao.findById(view.id());
        assertThat(stored.host()).isEqualTo("h2");
        assertThat(service.decryptPassword(stored)).isEqualTo("secret");
        assertThat(stored.readOnly()).isTrue();
    }
}
