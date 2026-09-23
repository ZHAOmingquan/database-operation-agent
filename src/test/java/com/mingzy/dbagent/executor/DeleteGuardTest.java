package com.mingzy.dbagent.executor;

import com.mingzy.dbagent.sysconfig.SysConfigDao;
import com.mingzy.dbagent.sysconfig.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeleteGuardTest {

    private JdbcTemplate jdbc;
    private DeleteGuard guard;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE sys_config (id INTEGER PRIMARY KEY AUTOINCREMENT, config_key TEXT NOT NULL UNIQUE,
              config_value TEXT NOT NULL, description TEXT, updated_at TEXT)
            """);
        jdbc.update("INSERT INTO sys_config(config_key, config_value) VALUES('developer_mode','false')");
        guard = new DeleteGuard(new SysConfigService(new SysConfigDao(jdbc)));
    }

    @Test
    void deleteLikeBlockedWhenDeveloperModeOff() {
        assertThat(guard.checkAllowed("delete from users")).contains("请开启开发者模式");
        assertThat(guard.checkAllowed("DROP TABLE users")).contains("请开启开发者模式");
        assertThat(guard.checkAllowed("truncate table users")).contains("请开启开发者模式");
    }

    @Test
    void nonDeleteOperationsAllowed() {
        assertThat(guard.checkAllowed("insert into users(id) values(1)")).isNull();
        assertThat(guard.checkAllowed("update users set name='a'")).isNull();
        assertThat(guard.checkAllowed("create table t(a int)")).isNull();
        assertThat(guard.checkAllowed("select * from users")).isNull();
    }

    @Test
    void deleteAllowedWhenDeveloperModeOn() {
        jdbc.update("UPDATE sys_config SET config_value='true' WHERE config_key='developer_mode'");
        assertThat(guard.checkAllowed("delete from users")).isNull();
    }
}
