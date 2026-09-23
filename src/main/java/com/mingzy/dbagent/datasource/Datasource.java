package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.common.DbType;

public record Datasource(Long id, String name, String dbType, String host, int port,
                         String databaseName, String username, String password,
                         String extraParams, boolean readOnly, String createdAt, String updatedAt) {
    public DbType type() { return DbType.of(dbType); }
    public String jdbcUrl() { return type().jdbcUrl(host, port, databaseName, extraParams); }
}
