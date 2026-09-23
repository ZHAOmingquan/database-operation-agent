package com.mingzy.dbagent.executor;

import java.util.List;

public record SqlExecResult(boolean success, String resultType, List<String> columns,
                            List<List<Object>> rows, int rowCount, Integer affectedRows,
                            long elapsedMs, boolean truncated, String errorMessage) {

    public static SqlExecResult ok(String resultType, List<String> columns, List<List<Object>> rows,
                                   Integer affectedRows, long elapsedMs, boolean truncated) {
        return new SqlExecResult(true, resultType, columns, rows,
                rows == null ? 0 : rows.size(), affectedRows, elapsedMs, truncated, null);
    }

    public static SqlExecResult error(String resultType, long elapsedMs, String message) {
        return new SqlExecResult(false, resultType, List.of(), List.of(), 0, null, elapsedMs, false, message);
    }
}
