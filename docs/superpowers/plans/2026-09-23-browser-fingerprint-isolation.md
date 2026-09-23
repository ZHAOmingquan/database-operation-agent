# 浏览器指纹会话隔离 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过浏览器指纹隔离会话数据：不同浏览器互不可见（会话列表 / 消息 / 结果 / 控制台 / 确认 / WS 连接），同一浏览器历史稳定；旧会话隐藏并支持认领。

**Architecture:** 前端生成指纹（浏览器特征 + 16 字节随机盐 → SHA-256，缓存于 `localStorage['dbagent_fp']`），REST 统一携带 `X-Browser-Fingerprint` 头、WS 以 `?fp=` 参数传递；后端 `chat_session.client_fingerprint` 列 + DAO 过滤/归属校验 + 接口与握手双重拦截（`common/BrowserFingerprint` 统一提取）。

**Tech Stack:** Java 21 · Spring Boot 3.5.16 · SQLite（sqlite-jdbc）· Vue 3 + Vite · agent-browser（端到端浏览器验证）

**规格文档:** `docs/superpowers/specs/2026-09-23-database-operation-agent-design.md` §17

---

## 0. 环境事实与执行约定（已验证）

| 项 | 值 |
|---|---|
| JDK 21 | `/opt/apps/org.openjdk-lts/files/openjdk-lts`（终端默认 java 可能是 1.8，**所有 mvn 命令前缀 `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts`**） |
| 端口 | 应用默认端口已由用户调整为 **18080**（application.yml）；E2E 后端统一用 `--server.port=18080`（8080/8081 均被本机 `code` 进程占用） |
| E2E 数据库 | 用真实库副本 `data/agent.db → target/e2e-fp/agent.db`（含升级前旧会话，用于验证“旧会话隐藏/认领”），**不写真实库** |
| 前端测试框架 | 无（package.json 无 test script）→ 前端改动以 Task 7 浏览器 E2E 验证；后端全程 TDD |
| 提交策略 | 各任务末尾 Commit 步骤**仅在用户明确授权后执行**；授权时只 `git add` 本任务涉及文件；工作区已有其他未提交修改（README 标题、frontend 组件、pom.xml 格式化），**不得混入提交**，README/设计文档是否提交需与用户确认 |

**MVN 命令模板（下称 `MVN`）:**
```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw
```

## 1. 文件结构（全部新建/修改的文件与职责）

```
database-operation-agent/
├── src/main/resources/sql/schema.sql                       # Task 1：chat_session 加列 + 迁移 + 索引
├── src/test/java/com/mingzy/dbagent/
│   └── SchemaInitTest.java                                 # Task 1：列存在性断言
├── src/main/java/com/mingzy/dbagent/common/BrowserFingerprint.java        # Task 2（新建）
├── src/test/java/com/mingzy/dbagent/common/BrowserFingerprintTest.java    # Task 2（新建）
├── src/main/java/com/mingzy/dbagent/chat/ChatDao.java      # Task 3：insert/list/findOwned
├── src/test/java/com/mingzy/dbagent/chat/ChatDaoTest.java  # Task 3：隔离断言
├── src/main/java/com/mingzy/dbagent/chat/web/SessionController.java       # Task 3：头 + 归属校验
├── src/test/java/com/mingzy/dbagent/chat/web/SessionControllerTest.java   # Task 3（新建）
├── src/test/java/com/mingzy/dbagent/ChatE2eIT.java         # Task 3：签名同步
├── src/main/java/com/mingzy/dbagent/chat/ConfirmationService.java         # Task 4：确认归属校验
├── src/main/java/com/mingzy/dbagent/chat/web/ConfirmController.java       # Task 4：头透传
├── src/test/java/com/mingzy/dbagent/chat/ConfirmationServiceTest.java     # Task 4（新建）
├── src/main/java/com/mingzy/dbagent/chat/ws/ChatWebSocketHandler.java     # Task 5：握手校验
├── src/test/java/com/mingzy/dbagent/chat/ws/ChatWebSocketHandlerTest.java # Task 5（新建）
├── frontend/src/utils/fingerprint.js                       # Task 6（新建）
├── frontend/src/api/index.js                               # Task 6：请求拦截器
├── frontend/src/ws/socket.js                               # Task 6：?fp= 参数
└── frontend/src/main.js                                    # Task 6：启动初始化
```

**关键约束（贯穿所有任务）:**
- 数据源 / 模型 / 字典 / 系统配置保持**全局**，不参与隔离；MCP 不涉及会话，不改动
- 旧会话（`client_fingerprint IS NULL`）对任何指纹**不可见**（SQL `=` 对 NULL 天然不匹配，无需额外条件）
- 归属不匹配与不存在统一报 `会话不存在或无访问权限: {id}`（不暴露存在性）；缺失指纹报 `缺少浏览器指纹`
- `deleteSession` 级联删除消息/结果/确认，**必须先校验归属再删除**，避免跨指纹误删

---

### Task 1: 数据库迁移（chat_session.client_fingerprint）

**Files:**
- Modify: `src/test/java/com/mingzy/dbagent/SchemaInitTest.java`
- Modify: `src/main/resources/sql/schema.sql`

- [ ] **Step 1: 写失败测试**

在 `SchemaInitTest.java` 的 `allSevenTablesExist()` 方法后追加：

```java
    @Test
    void chatSessionHasClientFingerprintColumn() {
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT name FROM pragma_table_info('chat_session')", String.class);
        assertThat(columns).contains("client_fingerprint");
    }
```

- [ ] **Step 2: 运行确认失败**

```bash
cd /home/bright/dev/code/ai/database-operation-agent
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=SchemaInitTest
```

Expected: FAIL，`Expecting ArrayList ... to contain "client_fingerprint"`

- [ ] **Step 3: 修改 schema.sql**

`chat_session` 建表语句（第 47-54 行）替换为：

```sql
CREATE TABLE IF NOT EXISTS chat_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL DEFAULT '新会话',
    datasource_id INTEGER,
    model_id INTEGER,
    client_fingerprint TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 迁移：chat_session 增加 client_fingerprint 列（旧库升级；新库已在建表语句中包含，报 duplicate column 由 continue-on-error 容忍）
ALTER TABLE chat_session ADD COLUMN client_fingerprint TEXT;

-- 指纹过滤索引（会话列表按指纹查询）
CREATE INDEX IF NOT EXISTS ix_chat_session_client_fingerprint ON chat_session(client_fingerprint);
```

- [ ] **Step 4: 运行确认通过**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=SchemaInitTest
```

Expected: PASS（Tests run: 2, Failures: 0）

- [ ] **Step 5: 提交（用户授权后执行）**

```bash
git add src/main/resources/sql/schema.sql src/test/java/com/mingzy/dbagent/SchemaInitTest.java
git commit -m "feat: chat_session 增加 client_fingerprint 列与索引（浏览器指纹隔离）"
```

---

### Task 2: BrowserFingerprint 工具类（TDD）

**Files:**
- Create: `src/test/java/com/mingzy/dbagent/common/BrowserFingerprintTest.java`
- Create: `src/main/java/com/mingzy/dbagent/common/BrowserFingerprint.java`

- [ ] **Step 1: 写失败测试**

创建 `BrowserFingerprintTest.java`：

```java
package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserFingerprintTest {

    @Test
    void requireRejectsMissingOrBlank() {
        assertThatThrownBy(() -> BrowserFingerprint.require(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("缺少浏览器指纹");
        assertThatThrownBy(() -> BrowserFingerprint.require("   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("缺少浏览器指纹");
    }

    @Test
    void requireTrimsValidValue() {
        assertThat(BrowserFingerprint.require(" abc123 ")).isEqualTo("abc123");
    }

    @Test
    void fromQueryParsesFpParam() {
        assertThat(BrowserFingerprint.fromQuery("fp=abc")).isEqualTo("abc");
        assertThat(BrowserFingerprint.fromQuery("a=1&fp=xyz&b=2")).isEqualTo("xyz");
        assertThat(BrowserFingerprint.fromQuery(null)).isNull();
        assertThat(BrowserFingerprint.fromQuery("")).isNull();
        assertThat(BrowserFingerprint.fromQuery("a=1&b=2")).isNull();
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=BrowserFingerprintTest
```

Expected: 编译失败（`BrowserFingerprint` 不存在）

- [ ] **Step 3: 实现工具类**

创建 `BrowserFingerprint.java`：

```java
package com.mingzy.dbagent.common;

/** 浏览器指纹提取与校验：REST 用请求头，WebSocket 用查询参数（浏览器无法为 WS 设置自定义头）。 */
public final class BrowserFingerprint {

    /** REST 请求头名 */
    public static final String HEADER = "X-Browser-Fingerprint";

    /** WebSocket 查询参数名 */
    public static final String QUERY_PARAM = "fp";

    private BrowserFingerprint() {}

    /** 校验并归一化指纹；缺失或空白时抛业务异常（全局异常处理器统一返回） */
    public static String require(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("缺少浏览器指纹");
        return raw.trim();
    }

    /** 从 URL query 中解析 fp 参数；缺失返回 null（不抛异常，供 WS 握手自行决定关闭策略） */
    public static String fromQuery(String query) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && QUERY_PARAM.equals(pair.substring(0, idx))) return pair.substring(idx + 1);
        }
        return null;
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=BrowserFingerprintTest
```

Expected: PASS（Tests run: 3, Failures: 0）

- [ ] **Step 5: 提交（用户授权后执行）**

```bash
git add src/main/java/com/mingzy/dbagent/common/BrowserFingerprint.java src/test/java/com/mingzy/dbagent/common/BrowserFingerprintTest.java
git commit -m "feat: 浏览器指纹提取与校验工具 BrowserFingerprint"
```

---

### Task 3: ChatDao 指纹隔离 + 会话接口接入

**Files:**
- Modify: `src/test/java/com/mingzy/dbagent/chat/ChatDaoTest.java`
- Create: `src/test/java/com/mingzy/dbagent/chat/web/SessionControllerTest.java`
- Modify: `src/main/java/com/mingzy/dbagent/chat/ChatDao.java`
- Modify: `src/main/java/com/mingzy/dbagent/chat/web/SessionController.java`
- Modify: `src/test/java/com/mingzy/dbagent/ChatE2eIT.java`

- [ ] **Step 1: 更新 ChatDaoTest（含新增隔离用例）**

(a) `setUp()` 中 chat_session 建表语句替换为（增加 `client_fingerprint TEXT`）：

```java
        jdbc.execute("""
            CREATE TABLE chat_session (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '新会话',
              datasource_id INTEGER, model_id INTEGER, client_fingerprint TEXT, created_at TEXT, updated_at TEXT)
            """);
```

(b) 4 处 `insertSession` 调用补第 4 参（并让断言按指纹查询）：

```java
        long id = dao.insertSession("测试会话", 1L, 2L, "fp-test");   // sessionCrud
        assertThat(dao.listSessions("fp-test")).hasSize(1);          // sessionCrud 原 listSessions()
        long sid = dao.insertSession("s", null, null, "fp-test");    // messageAndResultFlow
        long sid = dao.insertSession("s", null, null, "fp-test");    // chartConfigRoundTrip
        long sid = dao.insertSession("s", null, null, "fp-test");    // confirmRequestFlow
```

(c) 文件末尾追加两个隔离测试：

```java
    @Test
    void sessionsAreIsolatedByFingerprint() {
        long a = dao.insertSession("A 的会话", null, null, "fp-a");
        long b = dao.insertSession("B 的会话", null, null, "fp-b");
        assertThat(dao.listSessions("fp-a")).extracting(ChatSession::id).containsExactly(a);
        assertThat(dao.listSessions("fp-b")).extracting(ChatSession::id).containsExactly(b);
        assertThat(dao.findOwnedSession(a, "fp-a")).isNotNull();
        assertThat(dao.findOwnedSession(a, "fp-b")).isNull();
        assertThat(dao.findOwnedSession(b, "fp-b")).isNotNull();
    }

    @Test
    void legacySessionsWithoutFingerprintAreInvisible() {
        dao.insertSession("旧会话", null, null, null);
        assertThat(dao.listSessions("fp-a")).isEmpty();
        assertThat(dao.listSessions("fp-b")).isEmpty();
    }
```

- [ ] **Step 2: 新建 SessionControllerTest**

创建 `src/test/java/com/mingzy/dbagent/chat/web/SessionControllerTest.java`：

```java
package com.mingzy.dbagent.chat.web;

import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatSession;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.executor.DeleteGuard;
import com.mingzy.dbagent.executor.SqlExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SessionControllerTest {

    private final ChatDao chatDao = mock(ChatDao.class);
    private final DatasourceService datasourceService = mock(DatasourceService.class);
    private final SqlExecutor sqlExecutor = mock(SqlExecutor.class);
    private final WsSessionRegistry ws = mock(WsSessionRegistry.class);
    private final DeleteGuard deleteGuard = mock(DeleteGuard.class);
    private final SessionController controller =
            new SessionController(chatDao, datasourceService, sqlExecutor, ws, deleteGuard);

    @Test
    void listRequiresFingerprint() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("缺少浏览器指纹");
    }

    @Test
    void listFiltersByFingerprint() {
        ChatSession s = new ChatSession(1L, "新会话", null, null, null, null);
        when(chatDao.listSessions("fp-a")).thenReturn(List.of(s));

        Result<List<ChatSession>> r = controller.list("fp-a");

        assertThat(r.code()).isZero();
        assertThat(r.data()).containsExactly(s);
        verify(chatDao).listSessions("fp-a");
    }

    @Test
    void messagesRejectsForeignSession() {
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> controller.messages(9L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("会话不存在或无访问权限: 9");
    }

    @Test
    void deleteRejectsForeignSessionWithoutClearingData() {
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> controller.delete(9L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("会话不存在或无访问权限: 9");
        verify(chatDao, never()).deleteSession(anyLong());
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest='ChatDaoTest,SessionControllerTest'
```

Expected: 编译失败（新签名不存在）或断言失败

- [ ] **Step 4: 修改 ChatDao**

`insertSession`（原 3 参版）替换为：

```java
    public long insertSession(String title, Long datasourceId, Long modelId, String clientFingerprint) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO chat_session(title, datasource_id, model_id, client_fingerprint) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            if (datasourceId == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, datasourceId);
            if (modelId == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, modelId);
            if (clientFingerprint == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, clientFingerprint);
            return ps;
        }, kh);
        return key(kh);
    }
```

`listSessions`（原无参版）替换为，并新增 `findOwnedSession`（放在 `findSession` 之后）：

```java
    public List<ChatSession> listSessions(String clientFingerprint) {
        return jdbc.query("SELECT * FROM chat_session WHERE client_fingerprint=? ORDER BY id DESC",
                SESSION_MAPPER, clientFingerprint);
    }

    /** 归属校验：仅当会话属于该指纹时返回，否则 null（旧的无指纹会话对任何指纹都不可见） */
    public ChatSession findOwnedSession(long id, String clientFingerprint) {
        List<ChatSession> l = jdbc.query("SELECT * FROM chat_session WHERE id=? AND client_fingerprint=?",
                SESSION_MAPPER, id, clientFingerprint);
        return l.isEmpty() ? null : l.get(0);
    }
```

`findSession(long)`、`touchSession`、`deleteSession` 保持不变（内部编排仍用 `findSession`）。

- [ ] **Step 5: 修改 SessionController（全量替换为以下最终版）**

```java
package com.mingzy.dbagent.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatMessage;
import com.mingzy.dbagent.chat.ChatSession;
import com.mingzy.dbagent.chat.SqlResult;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import com.mingzy.dbagent.common.BrowserFingerprint;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    public Result<List<ChatSession>> list(
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        return Result.ok(chatDao.listSessions(BrowserFingerprint.require(clientFingerprint)));
    }

    @PostMapping
    public Result<ChatSession> create(
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint,
            @RequestBody(required = false) Map<String, Object> body) {
        String fp = BrowserFingerprint.require(clientFingerprint);
        String title = body != null && body.get("title") != null ? body.get("title").toString() : "新会话";
        Long datasourceId = body != null && body.get("datasourceId") != null
                ? Long.valueOf(body.get("datasourceId").toString()) : null;
        long id = chatDao.insertSession(title, datasourceId, null, fp);
        return Result.ok(chatDao.findSession(id));
    }

    @PutMapping("/{id}")
    public Result<ChatSession> update(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint,
            @RequestBody Map<String, Object> body) {
        ChatSession s = requireOwned(id, clientFingerprint);
        String title = body.get("title") != null ? body.get("title").toString() : s.title();
        Long datasourceId = body.get("datasourceId") != null
                ? Long.valueOf(body.get("datasourceId").toString()) : s.datasourceId();
        chatDao.touchSession(id, title, datasourceId);
        return Result.ok(chatDao.findSession(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        requireOwned(id, clientFingerprint);
        chatDao.deleteSession(id);
        return Result.ok(null);
    }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> messages(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        requireOwned(id, clientFingerprint);
        return Result.ok(chatDao.listMessages(id));
    }

    @GetMapping("/{id}/results")
    public Result<List<SqlResult>> results(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        requireOwned(id, clientFingerprint);
        return Result.ok(chatDao.listResults(id));
    }

    /** SQL 控制台执行：用户手写 SQL，无需确认；删除类操作仍受开发者模式守卫；结果落库并 WS 推送（source=console） */
    @PostMapping("/{id}/console/execute")
    public Result<SqlResult> consoleExecute(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint,
            @RequestBody Map<String, Object> body) throws Exception {
        requireOwned(id, clientFingerprint);
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

    /** 归属校验：不匹配与不存在统一报错，避免暴露会话存在性 */
    private ChatSession requireOwned(long id, String clientFingerprint) {
        ChatSession s = chatDao.findOwnedSession(id, BrowserFingerprint.require(clientFingerprint));
        if (s == null) throw new IllegalArgumentException("会话不存在或无访问权限: " + id);
        return s;
    }
}
```

- [ ] **Step 6: 同步 ChatE2eIT 签名**

`ChatE2eIT.java` 第 24 行：

```java
        long sessionId = chatDao.insertSession("e2e", 1L, 1L, "fp-e2e-manual"); // 1=mysql-mytest
```

- [ ] **Step 7: 运行确认通过**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest='ChatDaoTest,SessionControllerTest'
```

Expected: PASS（ChatDaoTest 6 个用例、SessionControllerTest 4 个用例全绿）

- [ ] **Step 8: 提交（用户授权后执行）**

```bash
git add src/main/java/com/mingzy/dbagent/chat/ChatDao.java \
  src/main/java/com/mingzy/dbagent/chat/web/SessionController.java \
  src/test/java/com/mingzy/dbagent/chat/ChatDaoTest.java \
  src/test/java/com/mingzy/dbagent/chat/web/SessionControllerTest.java \
  src/test/java/com/mingzy/dbagent/ChatE2eIT.java
git commit -m "feat: 会话按浏览器指纹隔离（DAO 过滤 + 会话接口归属校验）"
```

---

### Task 4: 写操作确认归属校验

**Files:**
- Create: `src/test/java/com/mingzy/dbagent/chat/ConfirmationServiceTest.java`
- Modify: `src/main/java/com/mingzy/dbagent/chat/ConfirmationService.java`
- Modify: `src/main/java/com/mingzy/dbagent/chat/web/ConfirmController.java`

- [ ] **Step 1: 写失败测试**

创建 `ConfirmationServiceTest.java`：

```java
package com.mingzy.dbagent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConfirmationServiceTest {

    private final ChatDao chatDao = mock(ChatDao.class);
    private final WsSessionRegistry ws = mock(WsSessionRegistry.class);
    private final ConfirmationService service = new ConfirmationService(chatDao, ws, 60);

    private ConfirmRequest pendingConfirm() {
        return new ConfirmRequest(7L, 9L, null, null, "mysql-mytest",
                "delete from users where id=1", "pending", null, "2030-01-01 00:00:00");
    }

    @Test
    void approveRejectsForeignFingerprint() {
        when(chatDao.findConfirm(7L)).thenReturn(pendingConfirm());
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> service.approve(7L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话不存在或无访问权限: 9");
        verify(chatDao, never()).updateConfirmStatus(anyLong(), anyString());
    }

    @Test
    void approveOwnedSessionProceeds() {
        when(chatDao.findConfirm(7L)).thenReturn(pendingConfirm());
        when(chatDao.findOwnedSession(9L, "fp-a"))
                .thenReturn(new ChatSession(9L, "t", null, null, null, null));

        service.approve(7L, "fp-a");

        verify(chatDao).updateConfirmStatus(7L, "approved");
    }

    @Test
    void rejectRejectsForeignFingerprint() {
        when(chatDao.findConfirm(7L)).thenReturn(pendingConfirm());
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        assertThatThrownBy(() -> service.reject(7L, "fp-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话不存在或无访问权限: 9");
        verify(chatDao, never()).updateConfirmStatus(anyLong(), anyString());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=ConfirmationServiceTest
```

Expected: 编译失败（`approve(long,String)` 不存在）

- [ ] **Step 3: 修改 ConfirmationService**

`approve` / `reject` 两个方法替换为：

```java
    public void approve(long confirmId, String clientFingerprint) {
        ConfirmRequest c = requireOwnedConfirm(confirmId, clientFingerprint);
        if (!"pending".equals(c.status())) return;
        chatDao.updateConfirmStatus(confirmId, "approved");
        ws.send(c.sessionId(), "confirm_result", Map.of("id", confirmId, "status", "approved"));
        CompletableFuture<Decision> f = pending.get(confirmId);
        if (f != null) f.complete(Decision.APPROVED);
    }

    public void reject(long confirmId, String clientFingerprint) {
        ConfirmRequest c = requireOwnedConfirm(confirmId, clientFingerprint);
        if (!"pending".equals(c.status())) return;
        chatDao.updateConfirmStatus(confirmId, "rejected");
        ws.send(c.sessionId(), "confirm_result", Map.of("id", confirmId, "status", "rejected"));
        CompletableFuture<Decision> f = pending.get(confirmId);
        if (f != null) f.complete(Decision.REJECTED);
    }

    /** 确认请求必须存在且所属会话属于当前浏览器指纹，否则视为不存在（不暴露存在性） */
    private ConfirmRequest requireOwnedConfirm(long confirmId, String clientFingerprint) {
        ConfirmRequest c = chatDao.findConfirm(confirmId);
        if (c == null) throw new IllegalArgumentException("确认请求不存在: " + confirmId);
        if (chatDao.findOwnedSession(c.sessionId(), clientFingerprint) == null)
            throw new IllegalArgumentException("会话不存在或无访问权限: " + c.sessionId());
        return c;
    }
```

- [ ] **Step 4: 修改 ConfirmController（全量替换为以下最终版）**

```java
package com.mingzy.dbagent.chat.web;

import com.mingzy.dbagent.chat.ConfirmationService;
import com.mingzy.dbagent.common.BrowserFingerprint;
import com.mingzy.dbagent.common.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/confirm")
public class ConfirmController {

    private final ConfirmationService service;
    public ConfirmController(ConfirmationService service) { this.service = service; }

    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        service.approve(id, BrowserFingerprint.require(clientFingerprint));
        return Result.ok(null);
    }

    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        service.reject(id, BrowserFingerprint.require(clientFingerprint));
        return Result.ok(null);
    }
}
```

- [ ] **Step 5: 运行确认通过**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=ConfirmationServiceTest
```

Expected: PASS（Tests run: 3, Failures: 0）

- [ ] **Step 6: 提交（用户授权后执行）**

```bash
git add src/main/java/com/mingzy/dbagent/chat/ConfirmationService.java \
  src/main/java/com/mingzy/dbagent/chat/web/ConfirmController.java \
  src/test/java/com/mingzy/dbagent/chat/ConfirmationServiceTest.java
git commit -m "feat: 写操作确认按会话指纹校验归属"
```

---

### Task 5: WebSocket 握手指纹校验

**Files:**
- Create: `src/test/java/com/mingzy/dbagent/chat/ws/ChatWebSocketHandlerTest.java`
- Modify: `src/main/java/com/mingzy/dbagent/chat/ws/ChatWebSocketHandler.java`

- [ ] **Step 1: 写失败测试**

创建 `ChatWebSocketHandlerTest.java`：

```java
package com.mingzy.dbagent.chat.ws;

import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatService;
import com.mingzy.dbagent.chat.ChatSession;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {

    private final ChatService chatService = mock(ChatService.class);
    private final ChatDao chatDao = mock(ChatDao.class);
    private final WsSessionRegistry registry = mock(WsSessionRegistry.class);
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(chatService, chatDao, registry);

    private WebSocketSession ws(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create(uri));
        return session;
    }

    @Test
    void registersWhenFingerprintMatches() throws Exception {
        WebSocketSession session = ws("/ws/session/9?fp=fp-a");
        when(chatDao.findOwnedSession(9L, "fp-a"))
                .thenReturn(new ChatSession(9L, "t", null, null, null, null));

        handler.afterConnectionEstablished(session);

        verify(registry).register(9L, session);
        verify(session, never()).close(any());
    }

    @Test
    void closesWhenFingerprintMissing() throws Exception {
        WebSocketSession session = ws("/ws/session/9");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(registry, never()).register(anyLong(), any());
    }

    @Test
    void closesWhenFingerprintMismatches() throws Exception {
        WebSocketSession session = ws("/ws/session/9?fp=fp-b");
        when(chatDao.findOwnedSession(9L, "fp-b")).thenReturn(null);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(registry, never()).register(anyLong(), any());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=ChatWebSocketHandlerTest
```

Expected: 编译失败（构造器为 2 参，测试传 3 参）

- [ ] **Step 3: 修改 ChatWebSocketHandler（全量替换为以下最终版）**

```java
package com.mingzy.dbagent.chat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatService;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import com.mingzy.dbagent.common.BrowserFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatService chatService;
    private final ChatDao chatDao;
    private final WsSessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatWebSocketHandler(ChatService chatService, ChatDao chatDao, WsSessionRegistry registry) {
        this.chatService = chatService;
        this.chatDao = chatDao;
        this.registry = registry;
    }

    /** 握手校验：会话必须属于连接携带的浏览器指纹（?fp=），否则拒绝（Policy Violation） */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        long chatSessionId;
        try {
            chatSessionId = sessionId(session);
        } catch (RuntimeException e) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String fingerprint = BrowserFingerprint.fromQuery(
                session.getUri() == null ? null : session.getUri().getQuery());
        if (fingerprint == null || fingerprint.isBlank()
                || chatDao.findOwnedSession(chatSessionId, fingerprint.trim()) == null) {
            log.warn("WS 握手拒绝：sessionId={} 指纹缺失或不匹配", chatSessionId);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        registry.register(chatSessionId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText();
        if ("user_message".equals(type)) {
            String content = node.path("content").asText();
            Long datasourceId = node.hasNonNull("datasourceId") ? node.get("datasourceId").asLong() : null;
            Long modelId = node.hasNonNull("modelId") ? node.get("modelId").asLong() : null;
            // 异步执行，避免阻塞 WS 线程
            new Thread(() -> chatService.handleUserMessage(sessionId(session), content, datasourceId, modelId),
                    "chat-" + sessionId(session)).start();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(sessionId(session), session);
    }

    private long sessionId(WebSocketSession session) {
        String path = session.getUri().getPath(); // /ws/session/{id}
        String id = path.substring(path.lastIndexOf('/') + 1);
        return Long.parseLong(id);
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test -Dtest=ChatWebSocketHandlerTest
```

Expected: PASS（Tests run: 3, Failures: 0）

- [ ] **Step 5: 提交（用户授权后执行）**

```bash
git add src/main/java/com/mingzy/dbagent/chat/ws/ChatWebSocketHandler.java \
  src/test/java/com/mingzy/dbagent/chat/ws/ChatWebSocketHandlerTest.java
git commit -m "feat: WS 握手按浏览器指纹校验会话归属"
```

---

### Task 6: 前端指纹生成与传递

**Files:**
- Create: `frontend/src/utils/fingerprint.js`
- Modify: `frontend/src/api/index.js`、`frontend/src/ws/socket.js`、`frontend/src/main.js`

- [ ] **Step 1: 新建 fingerprint.js**

```js
// 浏览器指纹：浏览器特征串 + 16 字节随机盐 → SHA-256，缓存于 localStorage['dbagent_fp']。
// 同一浏览器（刷新/重启）指纹稳定；不同浏览器/无头实例因随机盐必然互异；清空站点数据视为新身份。
const STORAGE_KEY = 'dbagent_fp'
let cached = null

function randomSalt() {
  const bytes = new Uint8Array(16)
  if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(bytes)
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

function fnv1a64Hex(input) {
  let hash = 0xcbf29ce484222325n
  const prime = 0x100000001b3n
  const mask = 0xffffffffffffffffn
  for (let i = 0; i < input.length; i++) {
    hash ^= BigInt(input.charCodeAt(i))
    hash = (hash * prime) & mask
  }
  return hash.toString(16).padStart(16, '0')
}

async function hashHex(input) {
  if (window.crypto && window.crypto.subtle) {
    const digest = await window.crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
  }
  return fnv1a64Hex(input) // 非安全上下文（如局域网 http 访问）回退
}

function features() {
  const s = window.screen || {}
  return [
    navigator.userAgent,
    navigator.language,
    navigator.platform || '',
    `${s.width || 0}x${s.height || 0}x${s.colorDepth || 0}`,
    window.devicePixelRatio || 1,
    String(new Date().getTimezoneOffset()),
    navigator.hardwareConcurrency || 0
  ].join('|')
}

export async function initFingerprint() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) { cached = saved; return cached }
  } catch (e) { /* 存储不可用时退化为内存态指纹 */ }
  cached = await hashHex(`${randomSalt()}|${features()}`)
  try { localStorage.setItem(STORAGE_KEY, cached) } catch (e) { /* 忽略 */ }
  return cached
}

export function getFingerprint() { return cached }
```

- [ ] **Step 2: 修改 api/index.js（文件头部插入请求拦截器）**

将文件前 5 行（import 与 axios 实例创建）替换为：

```js
import axios from 'axios'
import { message } from 'ant-design-vue'
import { getFingerprint } from '../utils/fingerprint'

const http = axios.create({ baseURL: '/api', timeout: 60000 })

// 所有请求统一携带浏览器指纹（后端按指纹隔离会话数据）
http.interceptors.request.use((cfg) => {
  const fp = getFingerprint()
  if (fp) cfg.headers['X-Browser-Fingerprint'] = fp
  return cfg
})
```

（其余内容不变）

- [ ] **Step 3: 修改 ws/socket.js**

(a) 文件第 1 行前追加 import：

```js
import { getFingerprint } from '../utils/fingerprint'
```

(b) `open()` 中构造 URL 的一行（原 `ws = new WebSocket(...)`）替换为：

```js
  // 浏览器无法为 WS 设置自定义头，指纹以查询参数传递（握手校验见后端 ChatWebSocketHandler）
  const fp = getFingerprint()
  ws = new WebSocket(`${proto}://${location.host}/ws/session/${sessionId}${fp ? `?fp=${encodeURIComponent(fp)}` : ''}`)
```

- [ ] **Step 4: 修改 main.js（全量替换为以下最终版）**

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import router from './router'
import { initFingerprint } from './utils/fingerprint'
import './styles.css'

// 先初始化浏览器指纹再挂载：axios 请求拦截器与 WS 连接都依赖它
initFingerprint().then(() => {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.use(Antd)
  app.mount('#app')
})
```

- [ ] **Step 5: 构建验证（前端无测试框架，构建 + Task 7 E2E 为准）**

```bash
cd frontend && npm run build
```

Expected: `✓ built in ...` 无报错，`dist/` 更新。若 npm 不在 PATH，改用 `target/node/node`（frontend-maven-plugin 下载的 node）或 `../mvnw -DskipTests package`。

- [ ] **Step 6: 提交（用户授权后执行）**

```bash
git add frontend/src/utils/fingerprint.js frontend/src/api/index.js frontend/src/ws/socket.js frontend/src/main.js
git commit -m "feat: 前端生成浏览器指纹并随请求/WS 传递"
```

---

### Task 7: 端到端浏览器验证（agent-browser 双实例）

**前置事实：** 8080 被本机进程占用 → 后端跑 8081；E2E 操作用真实库副本，不写真实库。使用已加载的 agent-browser 技能（`--session` + `--profile` 双实例隔离；若该组合不可用，改用两个独立 `--profile` 目录启动，必要时参阅技能文档 fallback）。

- [ ] **Step 1: 准备环境（副本库 + 构建产物）**

```bash
export JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts
export PATH=$JAVA_HOME/bin:$PATH
cd /home/bright/dev/code/ai/database-operation-agent
mkdir -p target/e2e-fp
cp -f data/agent.db target/e2e-fp/agent.db
cp -f data/agent.db-wal target/e2e-fp/agent.db-wal 2>/dev/null || true
cp -f data/agent.db-shm target/e2e-fp/agent.db-shm 2>/dev/null || true
cd frontend && npm run build && cd ..
./mvnw -q -DskipTests package -Pskip-frontend
ls -la target/*.jar
```

Expected: 构建成功，jar 存在（如 `target/database-operation-agent-1.0.0.jar`）

- [ ] **Step 2: 启动后端（8081 + 副本库）并等待就绪**

```bash
nohup java -jar target/database-operation-agent-1.0.0.jar \
  --server.port=8081 --app.db.path=./target/e2e-fp/agent.db \
  > target/e2e-fp/app.log 2>&1 &
echo $! > target/e2e-fp/app.pid
for i in $(seq 1 30); do code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/datasources); [ "$code" = "200" ] && break; sleep 1; done
echo "ready=$code"
```

Expected: `ready=200`

- [ ] **Step 3: 启动浏览器实例 A，记录指纹**

```bash
agent-browser --session fp-a --profile /tmp/dbagent-fp-a open http://localhost:8081/chat
agent-browser --session fp-a wait --load networkidle
agent-browser --session fp-a eval 'localStorage.getItem("dbagent_fp")'
agent-browser --session fp-a snapshot -i
```

Expected: eval 返回 64 位 hex 指纹（记为 `FP_A`）；快照含「新建会话」按钮与「暂无会话，点击新建」

- [ ] **Step 4: A 新建会话并取会话 id**

```bash
agent-browser --session fp-a find text "新建会话" click
agent-browser --session fp-a wait --load networkidle
agent-browser --session fp-a eval --stdin <<'EOF'
(async () => {
  const fp = localStorage.getItem('dbagent_fp')
  const j = await (await fetch('/api/sessions', { headers: { 'X-Browser-Fingerprint': fp } })).json()
  return { fp, ids: j.data.map(s => s.id) }
})()
EOF
```

Expected: `{ fp: '<FP_A>', ids: [<SID_A>] }`（人工记录 SID_A）

- [ ] **Step 5: 启动浏览器实例 B，断言列表隔离**

```bash
agent-browser --session fp-b --profile /tmp/dbagent-fp-b open http://localhost:8081/chat
agent-browser --session fp-b wait --load networkidle
agent-browser --session fp-b eval 'localStorage.getItem("dbagent_fp")'
agent-browser --session fp-b snapshot -i
```

Expected: 指纹 `FP_B` 与 `FP_A` 不同；快照为「会话（0）」「暂无会话，点击新建」——**看不到 A 新建的会话**

- [ ] **Step 6: 越权与缺参校验（curl）**

```bash
FP_A='<步骤3的指纹>'; FP_B='<步骤5的指纹>'; SID_A=<步骤4的会话id>

curl -s -H "X-Browser-Fingerprint: $FP_B" http://localhost:8081/api/sessions/$SID_A/messages
# 预期 {"code":1,"message":"会话不存在或无访问权限: SID_A","data":null}
curl -s -H "X-Browser-Fingerprint: $FP_B" http://localhost:8081/api/sessions/$SID_A/results
# 同上
curl -s -X DELETE -H "X-Browser-Fingerprint: $FP_B" http://localhost:8081/api/sessions/$SID_A
# 同上（且 A 的数据未被删除，Step 12 复核）
curl -s -H "X-Browser-Fingerprint: $FP_A" http://localhost:8081/api/sessions/$SID_A/messages
# 预期 {"code":0,"message":"ok","data":[]}
curl -s http://localhost:8081/api/sessions
# 预期 {"code":1,"message":"缺少浏览器指纹","data":null}
```

- [ ] **Step 7: WS 握手校验（浏览器内建连）**

将下方 `<SID_A>` 替换为实际 id 后执行（B 实例，预期被拒）：

```bash
agent-browser --session fp-b eval --stdin <<'EOF'
new Promise(res => {
  const w = new WebSocket(`ws://${location.host}/ws/session/<SID_A>?fp=${localStorage.getItem('dbagent_fp')}`)
  w.onopen = () => { w.close(); res({ opened: true }) }
  w.onclose = e => res({ closed: true, code: e.code, reason: e.reason })
  setTimeout(() => res({ timeout: true }), 3000)
})
EOF
```

Expected: `{"closed":true,"code":1008,...}`（Policy Violation）

A 实例连自己的会话（同样替换 `<SID_A>`）：

```bash
agent-browser --session fp-a eval --stdin <<'EOF'
new Promise(res => {
  const w = new WebSocket(`ws://${location.host}/ws/session/<SID_A>?fp=${localStorage.getItem('dbagent_fp')}`)
  w.onopen = () => { w.close(); res({ opened: true }) }
  w.onclose = e => res({ closed: true, code: e.code })
  setTimeout(() => res({ timeout: true }), 3000)
})
EOF
```

Expected: `{"opened":true}`

- [ ] **Step 8: 旧会话隐藏验证（副本库）**

```bash
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('target/e2e-fp/agent.db')
print('legacy ids =', [r[0] for r in c.execute("select id from chat_session where client_fingerprint is null")])
PY
```

Expected: 列出升级前旧会话 id（≥1）；对照 Step 4-5 的列表结果，这些 id 均不在 A/B 的列表中（若副本库无旧会话，先手工插入一条 `insert into chat_session(title) values('旧会话')` 再重试）

- [ ] **Step 9: 认领 SQL 验证（副本库，用 FP_A 认领）**

```bash
FP_A='<步骤3的指纹>'
python3 - <<PY
import sqlite3
c = sqlite3.connect('target/e2e-fp/agent.db')
c.execute("update chat_session set client_fingerprint=? where client_fingerprint is null", ("$FP_A",))
c.commit()
print('remaining legacy =', c.execute("select count(*) from chat_session where client_fingerprint is null").fetchone()[0])
PY
```

Expected: `remaining legacy = 0`

- [ ] **Step 10: A 刷新看到认领后的历史；B 仍看不到**

```bash
agent-browser --session fp-a open http://localhost:8081/chat
agent-browser --session fp-a wait --load networkidle
agent-browser --session fp-a snapshot -i
```

Expected: 「会话（N）」，N = 1（A 自建）+ 旧会话数

```bash
agent-browser --session fp-b open http://localhost:8081/chat
agent-browser --session fp-b snapshot -i
```

Expected: B 仍为「会话（0）」

- [ ] **Step 11: 同一浏览器稳定性（关闭后重开）**

```bash
agent-browser --session fp-a close
agent-browser --session fp-a --profile /tmp/dbagent-fp-a open http://localhost:8081/chat
agent-browser --session fp-a eval 'localStorage.getItem("dbagent_fp")'
agent-browser --session fp-a snapshot -i
```

Expected: 指纹仍等于 `FP_A`；历史会话仍在

- [ ] **Step 12: 复核 A 数据完好 + 清理**

```bash
curl -s -H "X-Browser-Fingerprint: $FP_A" http://localhost:8081/api/sessions/$SID_A/messages
# 预期 code=0（Step 6 的越权 DELETE 未造成影响）
agent-browser --session fp-a close
agent-browser --session fp-b close
kill $(cat target/e2e-fp/app.pid) && rm -f target/e2e-fp/app.pid
```

---

### Task 8: 全量回归 + 文档核对 + 汇总

**Files:**
- Modify（如实现有偏差）: `README.md`、`docs/superpowers/specs/2026-09-23-database-operation-agent-design.md`

- [ ] **Step 1: 全量单测**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -Pskip-frontend test
```

Expected: 全部 PASS（含既有测试与新测试；若有失败必须先修复再继续）

- [ ] **Step 2: 核对文档与实现一致性**

逐项核对（不一致则以实现为准修正 README / 设计文档 §17）：字段名 `client_fingerprint`、头名 `X-Browser-Fingerprint`、WS 参数 `fp`、localStorage 键 `dbagent_fp`、错误文案「缺少浏览器指纹」「会话不存在或无访问权限」、storage key、端口/命令。

- [ ] **Step 3: 设计文档 §17 补充计划引用**

将 §17 标题行改为：

```markdown
## 17. 迭代需求 3（浏览器指纹会话隔离，2026-09-23 交付，实施见计划 docs/superpowers/plans/2026-09-23-browser-fingerprint-isolation.md）
```

- [ ] **Step 4: 汇总汇报（不自动提交）**

向用户汇报：变更文件清单、Task 7 实测输出摘要（双实例指纹、隔离断言、越权/WS 拒绝、旧会话认领）、全量测试结果；就 git 提交（含工作区既有未提交改动的处理方式）征询用户意见。
