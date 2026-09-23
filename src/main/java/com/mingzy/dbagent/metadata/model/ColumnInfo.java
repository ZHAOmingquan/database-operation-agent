package com.mingzy.dbagent.metadata.model;

public record ColumnInfo(String name, String dataType, boolean nullable, boolean primaryKey,
                         String defaultValue, String comment) {
}
