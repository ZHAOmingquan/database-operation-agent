package com.mingzy.dbagent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingzy.dbagent.config.ChatClientFactory;
import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.executor.SqlExecResult;
import com.mingzy.dbagent.model.AiModel;
import com.mingzy.dbagent.model.AiModelService;
import com.mingzy.dbagent.tool.DatabaseTools;
import com.mingzy.dbagent.tool.ToolTraceRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatService {

    private final ChatDao chatDao;
    private final AiModelService modelService;
    private final ChatClientFactory clientFactory;
    private final DatabaseTools tools;
    private final ToolTraceRegistry traces;
    private final DatasourceService datasourceService;
    private final ConfirmationService confirmationService;
    private final WsSessionRegistry ws;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatService(ChatDao chatDao, AiModelService modelService, ChatClientFactory clientFactory,
                       DatabaseTools tools, ToolTraceRegistry traces, DatasourceService datasourceService,
                       ConfirmationService confirmationService, WsSessionRegistry ws) {
        this.chatDao = chatDao;
        this.modelService = modelService;
        this.clientFactory = clientFactory;
        this.tools = tools;
        this.traces = traces;
        this.datasourceService = datasourceService;
        this.confirmationService = confirmationService;
        this.ws = ws;
    }

    /** 注入工具层的会话钩子：工具执行时落库/推送/确认 */
    @PostConstruct
    void wireTools() {
        tools.setSessionHook(new DatabaseTools.SessionHook() {
            @Override
            public String onQuery(long sessionId, long datasourceId, String datasourceName,
                                  String sql, SqlExecResult result, String traceIdStr, String chartJson) {
                Long traceId = traceIdStr == null ? null : Long.valueOf(traceIdStr);
                return persistAndPush(sessionId, datasourceId, datasourceName, sql, result, traceId, chartJson);
            }

            @Override
            public String confirmWrite(long sessionId, long datasourceId, String datasourceName,
                                       String sql, Long traceId) {
                return confirmationService.requestAndWait(sessionId, traceId, datasourceId, datasourceName, sql);
            }

            @Override
            public void onDenied(long sessionId, String sql, String reason) {
                ws.send(sessionId, "delete_denied", Map.of("sql", sql, "reason", reason));
            }
        });
    }

    private String persistAndPush(long sessionId, long datasourceId, String datasourceName,
                                  String sql, SqlExecResult result, Long traceId, String chartJson) {
        try {
            String columnsJson = mapper.writeValueAsString(result.columns());
            String rowsJson = mapper.writeValueAsString(result.rows());
            long resultId = chatDao.insertResult(new SqlResult(null, sessionId, traceId, datasourceId, datasourceName,
                    sql, result.resultType(), columnsJson, rowsJson,
                    result.rowCount(), result.affectedRows(), result.elapsedMs(), null, "agent",
                    result.success() ? "success" : "error", result.errorMessage(), chartJson, null));
            traces.record(traceId, resultId);
            ws.send(sessionId, "result", chatDao.findResult(resultId));
            return resultId + "";
        } catch (Exception e) {
            log.error("persist result failed", e);
            return null;
        }
    }

    public void handleUserMessage(long sessionId, String content, Long datasourceId, Long modelId) {
        try {
            if (content == null || content.isBlank()) return;
            ChatSession session = chatDao.findSession(sessionId);
            if (session == null) { ws.send(sessionId, "error", Map.of("message", "会话不存在")); return; }
            AiModel model = modelId != null ? modelService.requireById(modelId) : modelService.enabled();
            if (model == null) { ws.send(sessionId, "error", Map.of("message", "请先在模型管理中启用一个模型")); return; }
            Datasource ds = datasourceId != null ? datasourceService.requireById(datasourceId) : null;
            if (ds == null && session.datasourceId() != null) {
                try { ds = datasourceService.requireById(session.datasourceId()); }
                catch (IllegalArgumentException ignored) { /* 会话引用的数据源已被删除，按无可用数据源引导 */ }
            }
            if (ds == null) {
                boolean hasAny = !datasourceService.rawList().isEmpty();
                ws.send(sessionId, "no_datasource", Map.of(
                        "message", hasAny ? "请先选择数据源" : "尚未配置数据源，请先新建数据源",
                        "hasAnyDatasource", hasAny));
                return;
            }

            // 1) 落库用户消息 + 更新会话标题/数据源
            chatDao.insertMessage(sessionId, "user", content, null);
            String title = session.title().equals("新会话") ? abbreviate(content) : session.title();
            chatDao.touchSession(sessionId, title, ds.id());
            ws.send(sessionId, "message", Map.of("role", "user", "content", content));

            // 2) 占位 assistant 消息（其 id 作为本轮 traceId）
            long traceId = chatDao.insertMessage(sessionId, "assistant", null, null, "running");

            // 3) 组装并调用模型
            AiModel plain = modelService.withPlainKey(model);
            ChatClient client = clientFactory.clientFor(plain)
                    .mutate()
                    .defaultSystem(systemPrompt(ds))
                    .defaultToolCallbacks(tools.getToolCallbacks())
                    .build();
            String answer = client.prompt()
                    .user(content)
                    .toolContext(Map.of("sessionId", sessionId, "traceId", traceId))
                    .call()
                    .content();

            // 4) 落库回答 + 回填 ai_comment（最后一个 result）
            List<Long> resultIds = traces.results(traceId);
            chatDao.updateMessage(traceId, answer, "done", resultIds.isEmpty() ? null : resultIds.toString());
            if (!resultIds.isEmpty()) {
                long lastResultId = resultIds.get(resultIds.size() - 1);
                chatDao.updateResultComment(lastResultId, answer);
                ws.send(sessionId, "result_update", Map.of("id", lastResultId, "aiComment", answer));
            }
            traces.clear(traceId);
            ws.send(sessionId, "message", Map.of("role", "assistant", "content", answer,
                    "messageId", traceId, "toolResultIds", resultIds));
        } catch (Exception e) {
            log.error("chat failed", e);
            ws.send(sessionId, "error", Map.of("message", "对话失败: " + e.getMessage()));
        }
    }

    private String systemPrompt(Datasource ds) {
        return """
            你是数据库操作智能体。当前会话使用的数据源：%s（%s）。
            可用工具：list_datasources / list_tables / get_table_schema / execute_query / execute_update / render_chart。
            规则：
            1. 不确定表名或字段时，先用 list_tables / get_table_schema 探查，再执行 SQL。
            2. execute_query 查询默认最多返回 10 行（自动限制），不要编造数据，只根据工具返回结果回答。
            3. 写操作（INSERT/UPDATE/DELETE/DDL）用 execute_update，系统会要求用户确认；删除类操作（DELETE/DROP/TRUNCATE）需先开启开发者模式，若被拒绝请提示用户在“系统配置”中开启。
            4. 必须用中文回答，并在回答中附上你执行的 SQL（与用户问题对应时）。
            5. 计数类问题的回答格式示例：系统有 42 个用户，查询SQL是：select count(*) from users
            6. 当用户要求统计分析、趋势/分布/占比、分组对比或明确要求图表时，改用 render_chart 工具：写聚合 SQL（含 GROUP BY 等），指定 chartType（bar/line/pie）并尽量指定 xField/yField；工具会把数据推送到右侧结果区以 ECharts 图表展示。
            """.formatted(ds.name(), ds.dbType());
    }

    private String abbreviate(String s) {
        return s.length() <= 20 ? s : s.substring(0, 20) + "…";
    }
}
