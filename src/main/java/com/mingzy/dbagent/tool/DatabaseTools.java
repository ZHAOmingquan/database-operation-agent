package com.mingzy.dbagent.tool;

import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.executor.DeleteGuard;
import com.mingzy.dbagent.executor.SqlExecResult;
import com.mingzy.dbagent.executor.SqlExecutor;
import com.mingzy.dbagent.metadata.MetadataServiceRouter;
import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

@Component
public class DatabaseTools implements ToolCallbackProvider {

    private final DatasourceService datasourceService;
    private final SqlExecutor executor;
    private final MetadataServiceRouter metadata;
    private final ToolTraceRegistry traces;
    private final DeleteGuard deleteGuard;
    private final int defaultLimit;
    private final int maxResultRows;
    private final int queryTimeout;
    private final MethodToolCallbackProvider delegate;

    /** 由 ChatService/ConfirmationService 在工具执行前注入的会话上下文钩子（见 Task 21/22） */
    public interface SessionHook {
        /** 返回 null 表示 MCP 外部通道（无会话确认）；否则内部会话需确认并落库。sql=工具执行时的 SQL 原文 */
        String onQuery(long sessionId, long datasourceId, String datasourceName, String sql, SqlExecResult result, String traceIdStr);
        /** 写操作：内部会话返回 null 表示已确认可执行；返回非 null 为拒绝/超时原因；MCP 通道直接执行 */
        String confirmWrite(long sessionId, long datasourceId, String datasourceName, String sql, Long traceId);
        /** 删除类操作被开发者模式守卫拦截时的通知（仅内部会话触发；MCP 无会话不触发） */
        default void onDenied(long sessionId, String sql, String reason) {}
    }

    private SessionHook sessionHook;

    public void setSessionHook(SessionHook sessionHook) { this.sessionHook = sessionHook; }

    public DatabaseTools(DatasourceService datasourceService, SqlExecutor executor,
                         MetadataServiceRouter metadata, ToolTraceRegistry traces, DeleteGuard deleteGuard,
                         @Value("${app.sql.default-limit:10}") int defaultLimit,
                         @Value("${app.sql.max-result-rows:500}") int maxResultRows,
                         @Value("${app.sql.query-timeout-seconds:30}") int queryTimeout) {
        this.datasourceService = datasourceService;
        this.executor = executor;
        this.metadata = metadata;
        this.traces = traces;
        this.deleteGuard = deleteGuard;
        this.defaultLimit = defaultLimit;
        this.maxResultRows = maxResultRows;
        this.queryTimeout = queryTimeout;
        this.delegate = MethodToolCallbackProvider.builder().toolObjects(this).build();
    }

    @Override
    public ToolCallback[] getToolCallbacks() { return delegate.getToolCallbacks(); }

    private Long sessionIdOf(ToolContext ctx) {
        if (ctx == null || ctx.getContext() == null) return null;
        Object v = ctx.getContext().get("sessionId");
        return v == null ? null : Long.valueOf(v.toString());
    }

    private Long traceIdOf(ToolContext ctx) {
        if (ctx == null || ctx.getContext() == null) return null;
        Object v = ctx.getContext().get("traceId");
        return v == null ? null : Long.valueOf(v.toString());
    }

    @Tool(name = "list_datasources", description = "列出当前系统已配置的数据库数据源（名称/类型/是否只读）。执行 SQL 前先用此工具确认可用数据源。")
    public String listDatasources() {
        List<Datasource> all = datasourceService.rawList();
        if (all.isEmpty()) return "当前没有配置任何数据源";
        StringJoiner sj = new StringJoiner("\n");
        for (Datasource d : all) {
            sj.add("- " + d.name() + " (" + d.dbType() + ", " + (d.readOnly() ? "只读" : "可写") + ")");
        }
        return sj.toString();
    }

    @Tool(name = "list_tables", description = "列出指定数据源中的全部表与视图及其注释。")
    public String listTables(@ToolParam(description = "数据源名称") String datasourceName) {
        try {
            DatasourceService.HikariPoolRef ref = poolOf(datasourceName);
            List<TableInfo> tables = metadata.tables(ref.datasource(), ref.pool());
            if (tables.isEmpty()) return "数据源 " + datasourceName + " 中没有表";
            StringJoiner sj = new StringJoiner("\n");
            for (TableInfo t : tables) {
                sj.add("- " + t.name() + " [" + t.type() + "]" + (t.comment() == null || t.comment().isBlank() ? "" : " " + t.comment()));
            }
            return sj.toString();
        } catch (Exception e) {
            return "获取表列表失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_table_schema", description = "获取指定表的字段结构（列名/类型/是否可空/主键/默认值/注释）。")
    public String getTableSchema(@ToolParam(description = "数据源名称") String datasourceName,
                                 @ToolParam(description = "表名") String tableName) {
        try {
            DatasourceService.HikariPoolRef ref = poolOf(datasourceName);
            List<ColumnInfo> cols = metadata.columns(ref.datasource(), ref.pool(), tableName);
            if (cols.isEmpty()) return "表 " + tableName + " 不存在或无字段";
            StringJoiner sj = new StringJoiner("\n");
            sj.add("表 " + tableName + " 的字段：");
            for (ColumnInfo c : cols) {
                sj.add("- " + c.name() + " " + c.dataType()
                        + (c.primaryKey() ? " PK" : "" )
                        + (c.nullable() ? "" : " NOT NULL")
                        + (c.defaultValue() == null ? "" : " DEFAULT " + c.defaultValue())
                        + (c.comment() == null || c.comment().isBlank() ? "" : " -- " + c.comment()));
            }
            return sj.toString();
        } catch (Exception e) {
            return "获取表结构失败: " + e.getMessage();
        }
    }

    @Tool(name = "execute_query", description = "在指定数据源上执行只读查询（SELECT 等）。系统默认最多返回 10 行；未指定 limit 时按默认值截断。返回结果以表格文本形式给出。")
    public String executeQuery(@ToolParam(description = "数据源名称") String datasourceName,
                               @ToolParam(description = "SQL 查询语句") String sql,
                               @ToolParam(description = "返回行数上限（可选，默认10）", required = false) Integer limit,
                               ToolContext toolContext) {
        try {
            DatasourceService.HikariPoolRef ref = poolOf(datasourceName);
            int effLimit = limit == null || limit <= 0 ? defaultLimit : limit;
            SqlExecResult result = executor.executeQuery(ref.pool(), sql, effLimit, maxResultRows, queryTimeout);
            Long sessionId = sessionIdOf(toolContext);
            if (sessionId != null && sessionHook != null) {
                sessionHook.onQuery(sessionId, ref.datasource().id(), datasourceName, sql, result, traceIdOf(toolContext) == null ? null : String.valueOf(traceIdOf(toolContext)));
            }
            return renderResult(sql, result);
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool(name = "execute_update", description = "在指定数据源上执行写入/DDL（INSERT/UPDATE/DELETE/CREATE/ALTER/DROP 等）。删除类操作（DELETE/DROP/TRUNCATE）需先开启开发者模式；内部会话中写操作需用户在界面确认后才真正执行；MCP 外部调用请由客户端确认。")
    public String executeUpdate(@ToolParam(description = "数据源名称") String datasourceName,
                                @ToolParam(description = "SQL 语句") String sql,
                                ToolContext toolContext) {
        try {
            String denied = deleteGuard.checkAllowed(sql);
            if (denied != null) {
                Long sid = sessionIdOf(toolContext);
                if (sid != null && sessionHook != null) {
                    sessionHook.onDenied(sid, sql, denied);
                }
                return "操作被拒绝：" + denied;
            }
            DatasourceService.HikariPoolRef ref = poolOf(datasourceName);
            if (ref.datasource().readOnly()) {
                return "数据源 " + datasourceName + " 配置为只读，已拒绝写操作";
            }
            Long sessionId = sessionIdOf(toolContext);
            if (sessionId != null && sessionHook != null) {
                String refusal = sessionHook.confirmWrite(sessionId, ref.datasource().id(),
                        datasourceName, sql, traceIdOf(toolContext));
                if (refusal != null) return refusal;
            }
            SqlExecResult result = executor.executeUpdate(ref.pool(), sql, queryTimeout);
            if (sessionId != null && sessionHook != null) {
                sessionHook.onQuery(sessionId, ref.datasource().id(), datasourceName, sql, result, traceIdOf(toolContext) == null ? null : String.valueOf(traceIdOf(toolContext)));
            }
            return renderResult(sql, result);
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    private DatasourceService.HikariPoolRef poolOf(String name) {
        Datasource d = datasourceService.requireByName(name);
        return datasourceService.poolOf(d);
    }

    private String renderResult(String sql, SqlExecResult r) {
        if (!r.success()) return "SQL 执行失败: " + r.errorMessage();
        StringBuilder sb = new StringBuilder();
        if ("query".equals(r.resultType())) {
            sb.append("查询返回 ").append(r.rowCount()).append(" 行");
            if (r.truncated()) sb.append("（已截断）");
            sb.append("，耗时 ").append(r.elapsedMs()).append("ms\n");
            sb.append("列: ").append(String.join(" | ", r.columns())).append("\n");
            for (List<Object> row : r.rows()) {
                sb.append(row.stream().map(v -> v == null ? "NULL" : String.valueOf(v))
                        .collect(java.util.stream.Collectors.joining(" | "))).append("\n");
            }
        } else {
            sb.append("执行成功，影响 ").append(r.affectedRows()).append(" 行，耗时 ")
              .append(r.elapsedMs()).append("ms");
        }
        String out = sb.toString();
        return out.length() > 8000 ? out.substring(0, 8000) + "...(结果已截断)" : out;
    }
}
