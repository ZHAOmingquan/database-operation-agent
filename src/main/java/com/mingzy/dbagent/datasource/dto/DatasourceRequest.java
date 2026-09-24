package com.mingzy.dbagent.datasource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DatasourceRequest(
        @NotBlank String name,
        @NotBlank String dbType,
        @NotBlank String host,
        @NotNull Integer port,
        String databaseName,
        @NotBlank String username,
        String password,
        String extraParams,
        boolean readOnly) {
}
