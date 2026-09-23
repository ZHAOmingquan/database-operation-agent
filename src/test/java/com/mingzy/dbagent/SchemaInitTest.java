package com.mingzy.dbagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "app.db.path=./target/test-data/schema-test.db")
class SchemaInitTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void allSevenTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            String.class);
        assertThat(tables).contains("ds_datasource", "ai_model", "sys_dict", "sys_config",
            "chat_session", "chat_message", "sql_result", "confirm_request");
    }

    @Test
    void chatSessionHasClientFingerprintColumn() {
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT name FROM pragma_table_info('chat_session')", String.class);
        assertThat(columns).contains("client_fingerprint");
    }
}
