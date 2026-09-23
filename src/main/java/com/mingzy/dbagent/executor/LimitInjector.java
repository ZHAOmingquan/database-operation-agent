package com.mingzy.dbagent.executor;

import java.util.regex.Pattern;

public final class LimitInjector {

    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)\\blimit\\s+\\d+");

    private LimitInjector() {
    }

    /** SELECT 查询：若已有 LIMIT（字符串字面量内的除外）则原样返回，否则去尾分号后追加 LIMIT */
    public static String inject(String sql, int limit) {
        if (hasLimit(sql)) return sql;
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed + " LIMIT " + limit;
    }

    static boolean hasLimit(String sql) {
        // 移除单引号字符串字面量后再检测，避免 'limit 5' 误判
        String noStrings = sql.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "''");
        return LIMIT_PATTERN.matcher(noStrings).find();
    }
}
