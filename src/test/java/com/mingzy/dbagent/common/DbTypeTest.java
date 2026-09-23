package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbTypeTest {

    @Test
    void mysqlUrl() {
        assertThat(DbType.mysql.jdbcUrl("127.0.0.1", 3306, "mytest", null))
                .startsWith("jdbc:mysql://127.0.0.1:3306/mytest?");
        assertThat(DbType.mysql.jdbcUrl("127.0.0.1", 3306, "mytest", "serverTimezone=Asia/Shanghai"))
                .contains("serverTimezone=Asia/Shanghai");
    }

    @Test
    void pgUrl() {
        assertThat(DbType.postgresql.jdbcUrl("h", 5432, "db", "sslmode=disable"))
                .isEqualTo("jdbc:postgresql://h:5432/db?connectTimeout=5&sslmode=disable");
    }

    @Test
    void unknownTypeRejected() {
        assertThatThrownBy(() -> DbType.of("oracle")).hasMessageContaining("不支持");
    }
}
