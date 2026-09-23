package com.mingzy.dbagent.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatMessage;
import com.mingzy.dbagent.chat.ChatSession;
import com.mingzy.dbagent.chat.SqlResult;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.executor.DeleteGuard;
import com.mingzy.dbagent.executor.SqlClassifier;
import com.mingzy.dbagent.executor.SqlExecResult;
import com.mingzy.dbagent.executor.SqlExecutor;
import com.mingzy.dbagent.executor.SqlKind;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatDao chatDao;
    private final DatasourceService datasourceService;
    private final SqlExecutor sqlExecutor;
    private final WsSessionRegistry ws;
    private final DeleteGuard deleteGuard;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionController(ChatDao chatDao, DatasourceService datasourceService,
                             SqlExecutor sqlExecutor, WsSessionRegistry ws, DeleteGuard deleteGuard) {
        this.chatDao = chatDao;
        this.datasourceService = datasourceService;
        this.sqlExecutor = sqlExecutor;
        this.ws = ws;
        this.deleteGuard = deleteGuard;
    }

    @GetMapping
    public Result<List<ChatSession>> list() { return Result.ok(chatDao.listSessions()); }

    @PostMapping
    public Result<ChatSession> create(@RequestBody(required = false) Map<String, Object> body) {
        String title = body != null && body.get("title") != null ? body.get("title").toString() : "新会话";
        Long datasourceId = body != null && body.get("datasourceId") != null
                ? Long.valueOf(body.get("datasourceId").toString()) : null;
        long id = chatDao.insertSession(title, datasourceId, null);
        return Result.ok(chatDao.findSession(id));
    }

    @PutMapping("/{id}")
    public Result<ChatSession> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ChatSession s = chatDao.findSession(id);
        if (s == null) throw new IllegalArgumentException("会话不存在: " + id);
        String title = body.get("title") != null ? body.get("title").toString() : s.title();
        Long datasourceId = body.get("datasourceId") != null
                ? Long.valueOf(body.get("datasourceId").toString()) : s.datasourceId();
        chatDao.touchSession(id, title, datasourceId);
        return Result.ok(chatDao.findSession(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) { chatDao.deleteSession(id); return Result.ok(null); }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> messages(@PathVariable long id) { return Result.ok(chatDao.listMessages(id)); }

    @GetMapping("/{id}/results")
    public Result<List<SqlResult>> results(@PathVariable long id) { return Result.ok(chatDao.listResults(id)); }

    /** SQL 控制台执行：用户手写 SQL，无需确认；删除类操作仍受开发者模式守卫；结果落库并 WS 推送（source=console） */
    @PostMapping("/{id}/console/execute")
    public Result<SqlResult> consoleExecute(@PathVariable long id, @RequestBody Map<String, Object> body) throws Exception {
        String sql = body.get("sql").toString();
        long datasourceId = Long.parseLong(body.get("datasourceId").toString());
        Integer limit = body.get("limit") == null ? null : Integer.valueOf(body.get("limit").toString());
        String denied = deleteGuard.checkAllowed(sql);
        if (denied != null) {
            ws.send(id, "delete_denied", Map.of("sql", sql, "reason", denied));
            throw new IllegalArgumentException(denied);
        }
        DatasourceService.HikariPoolRef ref = datasourceService.poolOf(datasourceService.requireById(datasourceId));
        boolean isQuery = SqlClassifier.kind(sql) == SqlKind.QUERY;
        SqlExecResult exec = isQuery
                ? sqlExecutor.executeQuery(ref.pool(), sql, limit == null ? 10 : limit, 500, 30)
                : sqlExecutor.executeUpdate(ref.pool(), sql, 30);
        long resultId = chatDao.insertResult(new SqlResult(null, id, null, datasourceId, ref.datasource().name(),
                sql, exec.resultType(), mapper.writeValueAsString(exec.columns()),
                mapper.writeValueAsString(exec.rows()), exec.rowCount(), exec.affectedRows(),
                exec.elapsedMs(), null, "console", exec.success() ? "success" : "error",
                exec.errorMessage(), null, null));
        SqlResult saved = chatDao.findResult(resultId);
        ws.send(id, "result", saved);
        return Result.ok(saved);
    }
}
