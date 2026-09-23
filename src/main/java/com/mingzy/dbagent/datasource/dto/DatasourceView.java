package com.mingzy.dbagent.datasource.dto;

import com.mingzy.dbagent.datasource.Datasource;

public record DatasourceView(Long id, String name, String dbType, String host, int port,
                             String databaseName, String username, String extraParams,
                             boolean readOnly, boolean hasPassword) {
    public static DatasourceView of(Datasource d) {
        return new DatasourceView(d.id(), d.name(), d.dbType(), d.host(), d.port(),
                d.databaseName(), d.username(), d.extraParams(), d.readOnly(),
                d.password() != null && !d.password().isBlank());
    }
}
