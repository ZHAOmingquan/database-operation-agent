package com.mingzy.dbagent.executor;

import java.util.Set;
import java.util.regex.Pattern;

public final class SqlClassifier {

    private static final Set<String> QUERY_KEYWORDS = Set.of(
            "select", "with", "show", "desc", "describe", "explain", "pragma", "values");
    private static final Set<String> DDL_KEYWORDS = Set.of(
            "create", "drop", "alter", "rename");

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\n]*|#[^\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private SqlClassifier() {
    }

    public static SqlKind kind(String sql) {
        String cleaned = stripComments(sql).trim();
        if (cleaned.isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        String first = firstWord(cleaned);
        if (QUERY_KEYWORDS.contains(first)) return SqlKind.QUERY;
        if (DDL_KEYWORDS.contains(first)) return SqlKind.DDL;
        return SqlKind.WRITE; // insert/update/delete/truncate/merge/未知默认按写处理
    }

    static String stripComments(String sql) {
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        // 保留字符串字面量内的 --/#（简化：LLM 生成的 SQL 场景可接受）
        return LINE_COMMENT.matcher(s).replaceAll(" ");
    }

    private static String firstWord(String cleaned) {
        int i = 0;
        while (i < cleaned.length() && Character.isLetter(cleaned.charAt(i))) i++;
        return cleaned.substring(0, i).toLowerCase();
    }
}
