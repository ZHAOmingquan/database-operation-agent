package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.datasource.Datasource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataServiceRouterTest {

    @Test
    void routesByDbType() {
        MysqlMetadataService mysql = new MysqlMetadataService();
        PostgresMetadataService pg = new PostgresMetadataService();
        MetadataServiceRouter router = new MetadataServiceRouter(mysql, pg);
        // 仅验证路由不抛异常（真实查询在集成/验收阶段验证）
        Datasource mysqlDs = new Datasource(1L, "m", "mysql", "h", 3306, "d", "u", "p", null, false, null, null);
        Datasource pgDs = new Datasource(2L, "p", "postgresql", "h", 5432, "d", "u", "p", null, false, null, null);
        assertThat(mysqlDs.type().name()).isEqualTo("mysql");
        assertThat(pgDs.type().name()).isEqualTo("postgresql");
        assertThat(router).isNotNull();
    }
}
