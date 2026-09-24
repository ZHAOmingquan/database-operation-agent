package com.mingzy.dbagent.common;

public enum DbType {
    mysql, postgresql;

    public static DbType of(String value) {
        for (DbType t : values()) {
            if (t.name().equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("不支持的数据源类型: " + value);
    }

    public String jdbcUrl(String host, int port, String database, String extraParams) {
        boolean hasDb = database != null && !database.isBlank();
        String base = this == mysql
                ? "jdbc:mysql://%s:%d%s?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000"
                : "jdbc:postgresql://%s:%d%s?connectTimeout=5";
        String url = String.format(base, host, port, hasDb ? "/" + database.trim() : "");
        if (extraParams != null && !extraParams.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + extraParams.trim();
        }
        return url;
    }
}
