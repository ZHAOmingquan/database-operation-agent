package com.mingzy.dbagent.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
public class SqlExecutor {

    public SqlExecResult executeQuery(DataSource ds, String sql, int limit, int maxRows, int timeoutSeconds) {
        String effective = LimitInjector.inject(sql, limit);
        long start = System.currentTimeMillis();
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            st.setMaxRows(maxRows + 1);  // 多取 1 行用于判断截断
            try (ResultSet rs = st.executeQuery(effective)) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                List<String> columns = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) columns.add(meta.getColumnLabel(i));
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= maxRows) { truncated = true; break; }
                    List<Object> row = new ArrayList<>(cols);
                    for (int i = 1; i <= cols; i++) row.add(toJsonSafe(rs.getObject(i)));
                    rows.add(row);
                }
                return SqlExecResult.ok("query", columns, rows, null,
                        System.currentTimeMillis() - start, truncated);
            }
        } catch (Exception e) {
            log.warn("query failed: {} sql={}", e.getMessage(), effective);
            return SqlExecResult.error("query", System.currentTimeMillis() - start, rootMessage(e));
        }
    }

    public SqlExecResult executeUpdate(DataSource ds, String sql, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            int affected = st.executeUpdate(sql);
            String type = SqlClassifier.kind(sql) == SqlKind.DDL ? "ddl" : "update";
            return SqlExecResult.ok(type, List.of(), List.of(), affected,
                    System.currentTimeMillis() - start, false);
        } catch (Exception e) {
            log.warn("update failed: {} sql={}", e.getMessage(), sql);
            return SqlExecResult.error("update", System.currentTimeMillis() - start, rootMessage(e));
        }
    }

    static Object toJsonSafe(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (v instanceof java.sql.Timestamp || v instanceof java.sql.Date || v instanceof java.sql.Time
                || v instanceof java.time.temporal.Temporal) {
            return v.toString();
        }
        return v.toString();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
