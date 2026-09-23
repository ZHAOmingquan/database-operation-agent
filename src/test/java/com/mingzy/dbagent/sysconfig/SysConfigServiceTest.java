package com.mingzy.dbagent.sysconfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SysConfigServiceTest {

    private SysConfigService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE sys_config (id INTEGER PRIMARY KEY AUTOINCREMENT, config_key TEXT NOT NULL UNIQUE,
              config_value TEXT NOT NULL, description TEXT, updated_at TEXT)
            """);
        service = new SysConfigService(new SysConfigDao(jdbc));
        jdbc.update("INSERT INTO sys_config(config_key, config_value, description) VALUES('developer_mode','false','开发者模式')");
    }

    @Test
    void getBoolReadsAndDefaults() {
        assertThat(service.getBool("developer_mode", true)).isFalse();
        assertThat(service.getBool("missing_key", true)).isTrue();
    }

    @Test
    void setUpdatesValue() {
        service.set("developer_mode", "true");
        assertThat(service.getBool("developer_mode", false)).isTrue();
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void setValidatesKeyAndValue() {
        assertThatThrownBy(() -> service.set("missing_key", "true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.set("developer_mode", "not-bool"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("true 或 false");
    }
}
