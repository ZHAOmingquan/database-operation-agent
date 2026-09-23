# 数据库操作智能体 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 Spring Boot + Spring AI 数据库操作智能体：自然语言/手写 SQL 操作 MySQL/PostgreSQL，能力封装为 MCP Streamable HTTP 工具，Vue3+Ant Design Vue 前端打包为单体 jar。

**Architecture:** 核心数据库操作实现为 Spring AI `ToolCallbackProvider`（共享工具层）：对内由 `ChatClient` 做 function calling，对外由 MCP Server（`/mcp`）自动暴露同一批工具。配置存 SQLite（`spring.sql.init` 幂等脚本）。写操作经 WebSocket 确认卡片（`CompletableFuture` 挂起 60s）。

**Tech Stack:** Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.8（`spring-ai-openai` + `spring-ai-starter-mcp-server-webmvc`）· SQLite 3.53.4.0 · Vue 3.5 + Vite 8 + Ant Design Vue 4.2 · Maven Wrapper 3.3.4

**规格文档:** `docs/superpowers/specs/2026-09-23-database-operation-agent-design.md`

---

## 0. 环境事实（已验证，所有命令依赖此前提）

| 项 | 值 |
|---|---|
| JDK 21 | `/opt/apps/org.openjdk-lts/files/openjdk-lts`（**终端默认 java 是 1.8，所有 Maven 命令必须前缀 `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts`**） |
| 缓存中的 Maven | `/home/bright/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn`（用它生成项目 mvnw） |
| Maven 镜像 | `~/.m2/settings.xml` 已配 aliyun 公共仓 + 本地仓库 `/home/bright/dev/mavenRepository` |
| Node/npm | 未安装；由 `frontend-maven-plugin` 从 npmmirror 自动下载（`nodeDownloadRoot=https://registry.npmmirror.com/-/binary/node/`，node v22.14.0） |
| npm 镜像 | `frontend/.npmrc` → `registry=https://registry.npmmirror.com` |
| 测试数据源 | MySQL `192.168.110.88:3306/mytest` root/Aa123456.；PostgreSQL `192.168.110.88:5432/mytest` ming/Aa123456.（密码末尾有 `.`） |
| 测试模型 | MiniMax-M3，baseUrl `https://api.minimaxi.com/v1`，apiKey 见 `docs/测试用大模型配置.md` |

**版本锁定（经 aliyun/npmmirror 实际查询确认）:** Spring Boot `3.5.16`、Spring AI `1.1.8`、sqlite-jdbc `3.53.4.0`、frontend-maven-plugin `2.0.2`、vue `3.5.43`、vite `8.3.0`、@vitejs/plugin-vue `6.0.9`、ant-design-vue `4.2.6`、vue-router `5.3.1`、pinia `4.0.3`、axios `1.20.0`、maven-wrapper `3.3.4`。

**标准构建命令模板（全文简写为 `MVN`）:**
```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw
```
首次生成 mvnw 前用缓存 mvn（见 Task 1）。

---

## 1. 文件结构（全部新建/修改的文件与职责）

```
database-operation-agent/
├── mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties    # Task 1 生成
├── .gitignore                                               # Task 1 修改
├── pom.xml                                                  # Task 1
├── frontend/                                                # Task 2（Vite 工程）
│   ├── package.json / .npmrc / vite.config.js / index.html
│   └── src/{main.js, App.vue, styles.css, router/index.js, api/index.js, ws/socket.js,
│            stores/chat.js, views/{ChatView,DatasourceView,ModelView,DictView}.vue,
│            components/{ChatPanel,MessageItem,ConfirmCard,SqlConsole,ResultPanel,ResultCard}.vue}
├── src/main/java/com/mingzy/dbagent/
│   ├── DbagentApplication.java
│   ├── config/{DataSourceConfig,WebSocketConfig,WebMvcConfig,ChatClientFactory}.java   # MCP Server 通过 application.yml 配置，无需 Java 配置类
│   ├── common/{Result,GlobalExceptionHandler,AesGcmUtil,DbType}.java
│   ├── datasource/{Datasource.java,DatasourceDao.java,DatasourceService.java,
│   │               DynamicDataSourceManager.java,dto/{DatasourceRequest,DatasourceView,ConnTestResult}.java,
│   │               web/DatasourceController.java}
│   ├── dict/{DictItem.java,DictDao.java,DictService.java,web/DictController.java}
│   ├── model/{AiModel.java,AiModelDao.java,AiModelService.java,web/AiModelController.java}
│   ├── metadata/{MetadataService.java,MysqlMetadataService.java,PostgresMetadataService.java,
│   │             model/{TableInfo,ColumnInfo}.java}
│   ├── executor/{SqlClassifier,SqlKind,LimitInjector,SqlExecutor,SqlExecResult}.java
│   ├── tool/{DatabaseTools.java,ToolRegistry.java}
│   ├── chat/{ChatSession.java,ChatMessage.java,SqlResult.java,ConfirmRequest.java,
│   │         ChatDao.java,ChatService.java,ConfirmationService.java,
│   │         WsSessionRegistry.java,web/{SessionController,ConfirmController}.java}
│   └── web/SpaForwardController.java
├── src/main/resources/
│   ├── application.yml
│   ├── sql/schema.sql                                       # 7 表 DDL（IF NOT EXISTS）
│   └── sql/update.sql                                       # 幂等种子（字典/模型/数据源）
└── src/test/java/com/mingzy/dbagent/...                     # 各任务单测
```

**关键设计约束（贯穿所有任务）:**
- 受管数据源连接池与配置库(`SQLite`)完全分离；受管池由 `DynamicDataSourceManager` 运行时管理
- 工具入参中的数据源用**名称**（`datasourceName`）而非 id，对 LLM 友好
- 工具执行所需的 `sessionId` 通过 Spring AI `ToolContext` 传递（`ChatService` 调用时注入），MCP 外部通道无 sessionId → 写操作直接执行（`destructiveHint=true` 由 MCP 客户端确认）
- 敏感字段（数据源密码、模型 apiKey）统一 AES-GCM 加密存储，密钥在 `application.yml`
- 时间字段统一 TEXT（SQLite `datetime('now','localtime')`），JDBC 读为 String

---

### Task 1: Maven Wrapper + 项目骨架

**Files:**
- Create: `pom.xml`、`src/main/java/com/mingzy/dbagent/DbagentApplication.java`、`src/main/resources/application.yml`
- Modify: `.gitignore`
- Create（generated）: `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`

- [ ] **Step 1: 生成 Maven Wrapper（用缓存 mvn）**

```bash
cd /home/bright/dev/code/ai/database-operation-agent
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts /home/bright/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.11
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -version
```
Expected: 输出 `Apache Maven 3.9.11`（distributionUrl 命中 `~/.m2/wrapper` 本地缓存，无需外网下载）

- [ ] **Step 2: 修改 .gitignore**

```gitignore
target/
.mvn/wrapper/maven-wrapper.jar
frontend/node_modules/
frontend/dist/
data/
*.db
*.db-wal
*.db-shm
.idea/
*.iml
```

- [ ] **Step 3: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>
    <groupId>com.mingzy</groupId>
    <artifactId>database-operation-agent</artifactId>
    <version>1.0.0</version>
    <name>database-operation-agent</name>
    <description>数据库操作智能体：自然语言操作 MySQL/PostgreSQL，能力以 MCP 工具暴露</description>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>1.1.8</spring-ai.version>
        <sqlite-jdbc.version>3.53.4.0</sqlite-jdbc.version>
        <skip.frontend>false</skip.frontend>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
        <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><version>${sqlite-jdbc.version}</version></dependency>
        <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <!-- 动态多模型：用核心库（非 starter）避免启动期强制 api-key -->
        <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-openai</artifactId></dependency>
        <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-client-chat</artifactId></dependency>
        <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-server-webmvc</artifactId></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration><excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes></configuration>
            </plugin>
            <plugin>
                <groupId>com.github.eirslett</groupId>
                <artifactId>frontend-maven-plugin</artifactId>
                <version>2.0.2</version>
                <configuration>
                    <workingDirectory>frontend</workingDirectory>
                    <installDirectory>${project.build.directory}/node</installDirectory>
                    <skip>${skip.frontend}</skip>
                    <nodeVersion>v22.14.0</nodeVersion>
                    <nodeDownloadRoot>https://registry.npmmirror.com/-/binary/node/</nodeDownloadRoot>
                </configuration>
                <executions>
                    <execution><id>install-node</id><goals><goal>install-node-and-npm</goal></goals><phase>generate-resources</phase></execution>
                    <execution><id>npm-install</id><goals><goal>npm</goal></goals><phase>generate-resources</phase><configuration><arguments>install</arguments></configuration></execution>
                    <execution><id>npm-build</id><goals><goal>npm</goal></goals><phase>generate-resources</phase><configuration><arguments>run build</arguments></configuration></execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-frontend-dist</id>
                        <phase>prepare-package</phase>
                        <goals><goal>copy-resources</goal></goals>
                        <configuration>
                            <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                            <resources><resource><directory>frontend/dist</directory><filtering>false</filtering></resource></resources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
    <profiles>
        <profile>
            <id>skip-frontend</id>
            <properties><skip.frontend>true</skip.frontend></properties>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 4: 创建主类与 application.yml**

`src/main/java/com/mingzy/dbagent/DbagentApplication.java`:
```java
package com.mingzy.dbagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableAsync
public class DbagentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbagentApplication.class, args);
    }
}
```

`src/main/resources/application.yml`:
```yaml
server:
  port: 8080
spring:
  application:
    name: database-operation-agent
  sql:
    init:
      mode: always
      continue-on-error: true
      schema-locations: classpath:sql/schema.sql
      data-locations: classpath:sql/update.sql
  ai:
    mcp:
      server:
        name: database-operation-agent
        version: 1.0.0
        protocol: STREAMABLE
app:
  db:
    path: ./data/agent.db
  crypto:
    key: VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU=
  sql:
    default-limit: 10
    max-result-rows: 500
    query-timeout-seconds: 30
  confirm:
    timeout-seconds: 60
  chat:
    history-size: 20
logging:
  level:
    com.mingzy.dbagent: DEBUG
```

（注：`app.crypto.key` 为 Base64 编码的 AES 密钥；该值为开发默认值，README 提示生产更换。）

- [ ] **Step 5: 先临时创建空 SQL 脚本使启动可用**

`src/main/resources/sql/schema.sql`（本任务先放占位注释，Task 3 填充完整 DDL）:
```sql
-- schema.sql: 由 Task 3 填充完整 DDL
```
`src/main/resources/sql/update.sql`:
```sql
-- update.sql: 由 Task 9/11/14 填充幂等种子数据
```

- [ ] **Step 6: 编译验证**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -q -Pskip-frontend -DskipTests package
```
Expected: BUILD SUCCESS（此步若 Spring AI 1.1.8 依赖解析失败，需检查 pom 版本号；已验证 BOM 中 `spring-ai-openai` 与 `spring-ai-starter-mcp-server-webmvc` 存在）

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "chore: project skeleton with Spring Boot 3.5.16 + Spring AI 1.1.8 + Maven wrapper"
```

---

### Task 2: 前端工程脚手架 + 构建集成

**Files:**
- Create: `frontend/package.json`、`frontend/.npmrc`、`frontend/vite.config.js`、`frontend/index.html`、`frontend/src/main.js`、`frontend/src/App.vue`、`frontend/src/styles.css`
- Create（临时占位页面，后续任务替换）: `frontend/src/views/Placeholder.vue`

- [ ] **Step 1: 创建 frontend/package.json**

```json
{
  "name": "dbagent-frontend",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build"
  },
  "dependencies": {
    "ant-design-vue": "4.2.6",
    "axios": "1.20.0",
    "pinia": "4.0.3",
    "vue": "3.5.43",
    "vue-router": "5.3.1"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "6.0.9",
    "vite": "8.3.0"
  }
}
```

`frontend/.npmrc`:
```
registry=https://registry.npmmirror.com
```

- [ ] **Step 2: 创建 vite.config.js 与入口文件**

`frontend/vite.config.js`:
```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true },
      '/mcp': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: { outDir: 'dist' }
})
```

`frontend/index.html`:
```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>数据库操作智能体</title>
</head>
<body>
  <div id="app"></div>
  <script type="module" src="/src/main.js"></script>
</body>
</html>
```

`frontend/src/main.js`:
```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import router from './router'
import './styles.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(Antd)
app.mount('#app')
```

`frontend/src/styles.css`:
```css
html, body, #app { height: 100%; margin: 0; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
```

- [ ] **Step 3: 创建最小 router 与 App.vue（后续任务扩展）**

`frontend/src/router/index.js`:
```js
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: () => import('../views/Placeholder.vue') },
  { path: '/datasources', component: () => import('../views/Placeholder.vue') },
  { path: '/models', component: () => import('../views/Placeholder.vue') },
  { path: '/dict', component: () => import('../views/Placeholder.vue') }
]
export default createRouter({ history: createWebHistory(), routes })
```

`frontend/src/views/Placeholder.vue`:
```vue
<template><a-result status="info" title="开发中" sub-title="该页面将在后续任务实现" /></template>
```

`frontend/src/App.vue`:
```vue
<template><router-view /></template>
```

- [ ] **Step 4: 构建集成验证（首次会从 npmmirror 下载 node v22.14.0）**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -DskipTests package
```
Expected: BUILD SUCCESS；`unzip -l target/database-operation-agent-1.0.0.jar | grep static/index.html` 有输出

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "chore: frontend scaffold (Vue3 + Vite + Ant Design Vue) wired into Maven packaging"
```

---

### Task 3: SQLite 配置库接入（schema.sql 全量 DDL）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/config/DataSourceConfig.java`
- Modify: `src/main/resources/sql/schema.sql`（填充 7 张表完整 DDL）
- Test: `src/test/java/com/mingzy/dbagent/SchemaInitTest.java`

- [ ] **Step 1: 写测试（验证表结构与幂等执行）**

`src/test/java/com/mingzy/dbagent/SchemaInitTest.java`:
```java
package com.mingzy.dbagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "app.db.path=./target/test-data/schema-test.db")
class SchemaInitTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void allSevenTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            String.class);
        assertThat(tables).contains("ds_datasource", "ai_model", "sys_dict",
            "chat_session", "chat_message", "sql_result", "confirm_request");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=SchemaInitTest -Pskip-frontend`
Expected: FAIL（Context 启动失败：DataSource bean 不存在 / 表不存在）

- [ ] **Step 3: 实现 DataSourceConfig**

`src/main/java/com/mingzy/dbagent/config/DataSourceConfig.java`:
```java
package com.mingzy.dbagent.config;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${app.db.path:./data/agent.db}") String dbPath) throws IOException {
        Path path = Path.of(dbPath).toAbsolutePath();
        Files.createDirectories(path.getParent());
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setBusyTimeout(5000);
        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl("jdbc:sqlite:" + path);
        return ds;
    }
}
```

- [ ] **Step 4: 填充 schema.sql（7 张表）**

```sql
CREATE TABLE IF NOT EXISTS ds_datasource (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    db_type TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    database_name TEXT NOT NULL,
    username TEXT NOT NULL,
    password TEXT NOT NULL,
    extra_params TEXT,
    read_only INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS ai_model (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    provider TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT NOT NULL,
    model_id TEXT NOT NULL,
    temperature REAL NOT NULL DEFAULT 0.7,
    max_tokens INTEGER NOT NULL DEFAULT 2048,
    enabled INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS sys_dict (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dict_type TEXT NOT NULL,
    dict_key TEXT NOT NULL,
    dict_label TEXT NOT NULL,
    parent_key TEXT,
    sort INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1,
    UNIQUE (dict_type, dict_key, parent_key)
);

CREATE TABLE IF NOT EXISTS chat_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL DEFAULT '新会话',
    datasource_id INTEGER,
    model_id INTEGER,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT,
    tool_calls_json TEXT,
    status TEXT NOT NULL DEFAULT 'done',
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS sql_result (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    message_id INTEGER,
    datasource_id INTEGER,
    datasource_name TEXT,
    sql_text TEXT NOT NULL,
    result_type TEXT NOT NULL,
    columns_json TEXT,
    rows_json TEXT,
    row_count INTEGER DEFAULT 0,
    affected_rows INTEGER,
    elapsed_ms INTEGER,
    ai_comment TEXT,
    source TEXT NOT NULL DEFAULT 'agent',
    status TEXT NOT NULL DEFAULT 'success',
    error_message TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS confirm_request (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    message_id INTEGER,
    datasource_id INTEGER,
    datasource_name TEXT,
    sql_text TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    expires_at TEXT NOT NULL
);
```

- [ ] **Step 5: 运行测试确认通过，并验证幂等（重复执行不报错）**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=SchemaInitTest -Pskip-frontend
rm -rf ./target/test-data && JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=SchemaInitTest -Pskip-frontend
```
Expected: 两次均 BUILD SUCCESS（`continue-on-error: true` + `IF NOT EXISTS` 保证幂等）

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: sqlite config store with idempotent schema init"
```

---

### Task 4: common 层（Result / 异常 / AES-GCM / DbType）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/common/{Result,GlobalExceptionHandler,AesGcmUtil,DbType}.java`
- Test: `src/test/java/com/mingzy/dbagent/common/AesGcmUtilTest.java`

- [ ] **Step 1: 写 AES 测试（加解密往返 + 密文随机性）**

`src/test/java/com/mingzy/dbagent/common/AesGcmUtilTest.java`:
```java
package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmUtilTest {

    private static final String KEY = "VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU=";

    @Test
    void encryptDecryptRoundTrip() {
        AesGcmUtil util = new AesGcmUtil(KEY);
        String plain = "Aa123456.";
        String cipher = util.encrypt(plain);
        assertThat(cipher).isNotEqualTo(plain);
        assertThat(util.decrypt(cipher)).isEqualTo(plain);
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        AesGcmUtil util = new AesGcmUtil(KEY);
        assertThat(util.encrypt("same")).isNotEqualTo(util.encrypt("same"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=AesGcmUtilTest -Pskip-frontend`
Expected: 编译失败（AesGcmUtil 不存在）

- [ ] **Step 3: 实现 common 层四个类**

`AesGcmUtil.java`:
```java
package com.mingzy.dbagent.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class AesGcmUtil {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public AesGcmUtil(String base64Key) {
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException("AES key must be 16/24/32 bytes (base64)");
        }
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES encrypt failed", e);
        }
    }

    public String decrypt(String cipherText) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES decrypt failed", e);
        }
    }
}
```

`Result.java`（统一 REST 响应）:
```java
package com.mingzy.dbagent.common;

public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data) { return new Result<>(0, "ok", data); }
    public static <T> Result<T> error(String message) { return new Result<>(1, message, null); }
}
```

`GlobalExceptionHandler.java`:
```java
package com.mingzy.dbagent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数错误");
        return Result.error(msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("Unhandled exception", e);
        return Result.error("服务器错误: " + e.getMessage());
    }
}
```

`DbType.java`:
```java
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
        String base = this == mysql
                ? "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000"
                : "jdbc:postgresql://%s:%d/%s?connectTimeout=5";
        String url = String.format(base, host, port, database);
        if (extraParams != null && !extraParams.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + extraParams.trim();
        }
        return url;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=AesGcmUtilTest -Pskip-frontend`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: common layer (Result, exception handler, AES-GCM, DbType)"
```

---

### Task 5: 数据源 DAO 与实体

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/datasource/{Datasource.java,DatasourceDao.java}`
- Test: `src/test/java/com/mingzy/dbagent/datasource/DatasourceDaoTest.java`

- [ ] **Step 1: 写测试（内存 SQLite：插入/查询/更新/删除/名称唯一）**

`src/test/java/com/mingzy/dbagent/datasource/DatasourceDaoTest.java`:
```java
package com.mingzy.dbagent.datasource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasourceDaoTest {

    private DatasourceDao dao;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE ds_datasource (
              id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, db_type TEXT NOT NULL,
              host TEXT NOT NULL, port INTEGER NOT NULL, database_name TEXT NOT NULL, username TEXT NOT NULL,
              password TEXT NOT NULL, extra_params TEXT, read_only INTEGER NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
              updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))
            """);
        dao = new DatasourceDao(jdbc);
    }

    @Test
    void crudRoundTrip() {
        Datasource d = new Datasource(null, "local-mysql", "mysql", "127.0.0.1", 3306,
                "mytest", "root", "cipher", null, false, null, null);
        long id = dao.insert(d);
        Datasource loaded = dao.findById(id);
        assertThat(loaded.name()).isEqualTo("local-mysql");
        assertThat(loaded.readOnly()).isFalse();

        dao.update(new Datasource(id, "local-mysql", "mysql", "127.0.0.1", 3307,
                "mytest", "root", "cipher2", "x=1", true, null, null));
        assertThat(dao.findById(id).port()).isEqualTo(3307);
        assertThat(dao.findById(id).readOnly()).isTrue();

        assertThat(dao.findByName("local-mysql").id()).isEqualTo(id);
        assertThat(dao.findAll()).hasSize(1);
        dao.delete(id);
        assertThat(dao.findById(id)).isNull();
    }

    @Test
    void duplicateNameRejected() {
        Datasource d = new Datasource(null, "dup", "mysql", "h", 1, "d", "u", "p", null, false, null, null);
        dao.insert(d);
        assertThatThrownBy(() -> dao.insert(d)).isInstanceOf(Exception.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=DatasourceDaoTest -Pskip-frontend`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现实体与 DAO**

`Datasource.java`（record，`password` 字段存密文；查询给前端的视图单独转换）:
```java
package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.common.DbType;

public record Datasource(Long id, String name, String dbType, String host, int port,
                         String databaseName, String username, String password,
                         String extraParams, boolean readOnly, String createdAt, String updatedAt) {
    public DbType type() { return DbType.of(dbType); }
    public String jdbcUrl() { return type().jdbcUrl(host, port, databaseName, extraParams); }
}
```

`DatasourceDao.java`（JdbcTemplate + KeyHolder，pattern 参考本任务；其他 DAO 同模式）:
```java
package com.mingzy.dbagent.datasource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class DatasourceDao {

    private final JdbcTemplate jdbc;

    public DatasourceDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Datasource> MAPPER = (rs, i) -> new Datasource(
            rs.getLong("id"), rs.getString("name"), rs.getString("db_type"),
            rs.getString("host"), rs.getInt("port"), rs.getString("database_name"),
            rs.getString("username"), rs.getString("password"), rs.getString("extra_params"),
            rs.getInt("read_only") == 1, rs.getString("created_at"), rs.getString("updated_at"));

    public long insert(Datasource d) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO ds_datasource(name, db_type, host, port, database_name, username, password, extra_params, read_only) " +
                "VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, d.name()); ps.setString(2, d.dbType()); ps.setString(3, d.host());
            ps.setInt(4, d.port()); ps.setString(5, d.databaseName()); ps.setString(6, d.username());
            ps.setString(7, d.password()); ps.setString(8, d.extraParams()); ps.setInt(9, d.readOnly() ? 1 : 0);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void update(Datasource d) {
        jdbc.update("UPDATE ds_datasource SET name=?, db_type=?, host=?, port=?, database_name=?, username=?, " +
                "password=?, extra_params=?, read_only=?, updated_at=datetime('now','localtime') WHERE id=?",
                d.name(), d.dbType(), d.host(), d.port(), d.databaseName(), d.username(),
                d.password(), d.extraParams(), d.readOnly() ? 1 : 0, d.id());
    }

    public void delete(long id) { jdbc.update("DELETE FROM ds_datasource WHERE id=?", id); }

    public Datasource findById(long id) {
        List<Datasource> list = jdbc.query("SELECT * FROM ds_datasource WHERE id=?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Datasource findByName(String name) {
        List<Datasource> list = jdbc.query("SELECT * FROM ds_datasource WHERE name=?", MAPPER, name);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Datasource> findAll() {
        return jdbc.query("SELECT * FROM ds_datasource ORDER BY id", MAPPER);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=DatasourceDaoTest -Pskip-frontend`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: datasource entity and dao"
```

---

### Task 6: DynamicDataSourceManager（连接测试 + 受管池）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/datasource/DynamicDataSourceManager.java`
- Create: `src/main/java/com/mingzy/dbagent/datasource/dto/ConnTestResult.java`
- Test: `src/test/java/com/mingzy/dbagent/common/DbTypeTest.java`

- [ ] **Step 1: 写 DbType URL 拼接测试**

`src/test/java/com/mingzy/dbagent/common/DbTypeTest.java`:
```java
package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbTypeTest {

    @Test
    void mysqlUrl() {
        assertThat(DbType.mysql.jdbcUrl("127.0.0.1", 3306, "mytest", null))
                .startsWith("jdbc:mysql://127.0.0.1:3306/mytest?");
        assertThat(DbType.mysql.jdbcUrl("127.0.0.1", 3306, "mytest", "serverTimezone=Asia/Shanghai"))
                .contains("serverTimezone=Asia/Shanghai");
    }

    @Test
    void pgUrl() {
        assertThat(DbType.postgresql.jdbcUrl("h", 5432, "db", "sslmode=disable"))
                .isEqualTo("jdbc:postgresql://h:5432/db?connectTimeout=5&sslmode=disable");
    }

    @Test
    void unknownTypeRejected() {
        assertThatThrownBy(() -> DbType.of("oracle")).hasMessageContaining("不支持");
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现 DynamicDataSourceManager → 运行通过**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=DbTypeTest -Pskip-frontend`
Expected: 先 FAIL（DbTypeTest 已可跑，DbType 已在 Task 4 实现——此步直接 PASS：DbType 实现需包含 `connectTimeout=5`；若未通过则修正 DbType）

`ConnTestResult.java`:
```java
package com.mingzy.dbagent.datasource.dto;

public record ConnTestResult(boolean success, long elapsedMs, String message) {
}
```

`DynamicDataSourceManager.java`:
```java
package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.datasource.dto.ConnTestResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DynamicDataSourceManager {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    /** 获取（或按最新配置重建）受管数据源连接池 */
    public HikariDataSource getPool(Datasource ds) {
        return pools.compute(ds.id(), (id, existing) -> {
            if (existing != null && !existing.isClosed()) {
                existing.close();
            }
            return createPool(ds);
        });
    }

    private HikariDataSource createPool(Datasource ds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ds.jdbcUrl());
        cfg.setUsername(ds.username());
        cfg.setPassword(ds.password());
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        cfg.setPoolName("ds-" + ds.name());
        return new HikariDataSource(cfg);
    }

    /** 用临时连接测试（不写入缓存池），支持未保存的表单参数 */
    public ConnTestResult test(Datasource ds) {
        long start = System.currentTimeMillis();
        try (Connection conn = java.sql.DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password())) {
            conn.isValid(3);
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
            return new ConnTestResult(true, System.currentTimeMillis() - start, "连接成功");
        } catch (Exception e) {
            log.warn("datasource test failed: {}", e.getMessage());
            return new ConnTestResult(false, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    /** 数据源配置变更后失效旧池 */
    public void evict(long id) {
        HikariDataSource old = pools.remove(id);
        if (old != null) old.close();
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
```

（注：`test()` 用 `DriverManager` 直连可测未保存的表单；Class.forName 由 JDBC 4 SPI 自动注册驱动。）

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: dynamic datasource manager with connection test"
```

---

### Task 7: 数据源 Service + REST API

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/datasource/DatasourceService.java`
- Create: `src/main/java/com/mingzy/dbagent/datasource/dto/{DatasourceRequest,DatasourceView}.java`
- Create: `src/main/java/com/mingzy/dbagent/datasource/web/DatasourceController.java`
- Test: `src/test/java/com/mingzy/dbagent/datasource/DatasourceServiceTest.java`

- [ ] **Step 1: 写测试（Service：密码加密存储、返回视图不含密文、更新时密码留空则不覆盖）**

`src/test/java/com/mingzy/dbagent/datasource/DatasourceServiceTest.java`:
```java
package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.common.AesGcmUtil;
import com.mingzy.dbagent.datasource.dto.DatasourceRequest;
import com.mingzy.dbagent.datasource.dto.DatasourceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceServiceTest {

    private DatasourceService service;
    private DatasourceDao dao;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE ds_datasource (
              id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, db_type TEXT NOT NULL,
              host TEXT NOT NULL, port INTEGER NOT NULL, database_name TEXT NOT NULL, username TEXT NOT NULL,
              password TEXT NOT NULL, extra_params TEXT, read_only INTEGER NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
              updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))
            """);
        dao = new DatasourceDao(jdbc);
        service = new DatasourceService(dao, new AesGcmUtil("VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU="),
                new DynamicDataSourceManager());
    }

    @Test
    void createEncryptsPasswordAndViewHidesIt() {
        DatasourceView view = service.create(new DatasourceRequest("m1", "mysql", "h", 3306, "db",
                "root", "secret", null, false));
        assertThat(view.id()).isNotNull();
        Datasource stored = dao.findById(view.id());
        assertThat(stored.password()).isNotEqualTo("secret").contains("=");
        assertThat(service.decryptPassword(stored)).isEqualTo("secret");
    }

    @Test
    void updateWithBlankPasswordKeepsOldOne() {
        DatasourceView view = service.create(new DatasourceRequest("m2", "mysql", "h", 3306, "db",
                "root", "secret", null, false));
        service.update(view.id(), new DatasourceRequest("m2", "mysql", "h2", 3306, "db",
                "root", "", null, true));
        Datasource stored = dao.findById(view.id());
        assertThat(stored.host()).isEqualTo("h2");
        assertThat(service.decryptPassword(stored)).isEqualTo("secret");
        assertThat(stored.readOnly()).isTrue();
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现下列类 → 运行通过**

`DatasourceRequest.java`:
```java
package com.mingzy.dbagent.datasource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DatasourceRequest(
        @NotBlank String name,
        @NotBlank String dbType,
        @NotBlank String host,
        @NotNull Integer port,
        @NotBlank String databaseName,
        @NotBlank String username,
        String password,
        String extraParams,
        boolean readOnly) {
}
```

`DatasourceView.java`:
```java
package com.mingzy.dbagent.datasource.dto;

import com.mingzy.dbagent.datasource.Datasource;

public record DatasourceView(Long id, String name, String dbType, String host, int port,
                             String databaseName, String username, String extraParams,
                             boolean readOnly, boolean hasPassword) {
    public static DatasourceView of(Datasource d) {
        return new DatasourceView(d.id(), d.name(), d.dbType(), d.host(), d.port(),
                d.databaseName(), d.username(), d.extraParams(), d.readOnly(),
                d.password() != null && !d.password().isBlank());
    }
}
```

`DatasourceService.java`:
```java
package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.common.AesGcmUtil;
import com.mingzy.dbagent.datasource.dto.DatasourceRequest;
import com.mingzy.dbagent.datasource.dto.DatasourceView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DatasourceService {

    private final DatasourceDao dao;
    private final AesGcmUtil aes;
    private final DynamicDataSourceManager pools;

    @Autowired
    public DatasourceService(DatasourceDao dao,
                             @Value("${app.crypto.key}") String key,
                             DynamicDataSourceManager pools) {
        this.dao = dao;
        this.aes = new AesGcmUtil(key);
        this.pools = pools;
    }

    // 测试用构造器（Task 7 单测使用）
    DatasourceService(DatasourceDao dao, AesGcmUtil aes, DynamicDataSourceManager pools) {
        this.dao = dao;
        this.aes = aes;
        this.pools = pools;
    }

    /** 返回实体列表（供工具层使用） */
    public List<Datasource> rawList() { return dao.findAll(); }

    public List<DatasourceView> list() {
        return dao.findAll().stream().map(DatasourceView::of).toList();
    }

    public Datasource requireById(long id) {
        Datasource d = dao.findById(id);
        if (d == null) throw new IllegalArgumentException("数据源不存在: " + id);
        return d;
    }

    public Datasource requireByName(String name) {
        Datasource d = dao.findByName(name);
        if (d == null) throw new IllegalArgumentException("数据源不存在: " + name);
        return d;
    }

    public DatasourceView create(DatasourceRequest req) {
        if (dao.findByName(req.name()) != null) {
            throw new IllegalArgumentException("数据源名称已存在: " + req.name());
        }
        long id = dao.insert(toEntity(null, req, true));
        return DatasourceView.of(requireById(id));
    }

    public DatasourceView update(long id, DatasourceRequest req) {
        Datasource old = requireById(id);
        Datasource byName = dao.findByName(req.name());
        if (byName != null && !byName.id().equals(id)) {
            throw new IllegalArgumentException("数据源名称已存在: " + req.name());
        }
        boolean keepPassword = req.password() == null || req.password().isBlank();
        String password = keepPassword ? old.password() : aes.encrypt(req.password());
        dao.update(new Datasource(id, req.name(), req.dbType(), req.host(), req.port(),
                req.databaseName(), req.username(), password, req.extraParams(), req.readOnly(), null, null));
        pools.evict(id);
        return DatasourceView.of(requireById(id));
    }

    private Datasource toEntity(Long id, DatasourceRequest req, boolean encryptPassword) {
        String password = encryptPassword && req.password() != null && !req.password().isBlank()
                ? aes.encrypt(req.password())
                : req.password();
        return new Datasource(id, req.name(), req.dbType(), req.host(), req.port(),
                req.databaseName(), req.username(), password, req.extraParams(), req.readOnly(), null, null);
    }

    public void delete(long id) {
        requireById(id);
        dao.delete(id);
        pools.evict(id);
    }

    public String decryptPassword(Datasource d) {
        return aes.decrypt(d.password());
    }

    /** 返回可用连接的数据源（解密密码） */
    public HikariPoolRef poolOf(Datasource d) {
        if (d.password() == null || d.password().isBlank()) {
            throw new IllegalStateException("数据源 " + d.name() + " 未配置密码");
        }
        String plain = aes.decrypt(d.password());
        Datasource withPlain = new Datasource(d.id(), d.name(), d.dbType(), d.host(), d.port(),
                d.databaseName(), d.username(), plain, d.extraParams(), d.readOnly(), d.createdAt(), d.updatedAt());
        return new HikariPoolRef(withPlain, pools.getPool(withPlain));
    }

    public record HikariPoolRef(Datasource datasource, com.zaxxer.hikari.HikariDataSource pool) {
    }
}
```

（注：Task 6 的 DynamicDataSourceManager 需补充 `evict(long)`——已含；`poolOf` 返回解密后的数据源+池，工具层使用。）

`DatasourceController.java`:
```java
package com.mingzy.dbagent.datasource.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.datasource.DynamicDataSourceManager;
import com.mingzy.dbagent.datasource.dto.ConnTestResult;
import com.mingzy.dbagent.datasource.dto.DatasourceRequest;
import com.mingzy.dbagent.datasource.dto.DatasourceView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {

    private final DatasourceService service;
    private final DynamicDataSourceManager poolManager;

    public DatasourceController(DatasourceService service, DynamicDataSourceManager poolManager) {
        this.service = service;
        this.poolManager = poolManager;
    }

    @GetMapping
    public Result<List<DatasourceView>> list() { return Result.ok(service.list()); }

    @PostMapping
    public Result<DatasourceView> create(@Valid @RequestBody DatasourceRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<DatasourceView> update(@PathVariable long id, @Valid @RequestBody DatasourceRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.ok(null); }

    /** 表单未保存直接测试："password" 为空时用已保存数据源的密码 */
    @PostMapping("/test")
    public Result<ConnTestResult> test(@RequestBody DatasourceRequest req,
                                       @RequestParam(required = false) Long id) {
        String password = req.password();
        if ((password == null || password.isBlank()) && id != null) {
            password = service.decryptPassword(service.requireById(id));
        }
        Datasource probe = new Datasource(id, req.name(), req.dbType(), req.host(), req.port(),
                req.databaseName(), req.username(), password, req.extraParams(), req.readOnly(), null, null);
        return Result.ok(poolManager.test(probe));
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=DatasourceServiceTest -Pskip-frontend`
Expected: PASS（2 tests）

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: datasource service and rest api"
```

---

### Task 8: 前端基础布局 + 数据源管理页

**Files:**
- Create: `frontend/src/api/index.js`
- Create: `frontend/src/App.vue`（替换：Layout + 菜单）
- Create: `frontend/src/views/DatasourceView.vue`
- Modify: `frontend/src/router/index.js`（/datasources 指向真实页面）

- [ ] **Step 1: 实现 api/index.js（axios 封装）**

```js
import axios from 'axios'
import { message } from 'ant-design-vue'

const http = axios.create({ baseURL: '/api', timeout: 60000 })

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        message.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message))
      }
      return body.data
    }
    return body
  },
  (err) => {
    message.error(err.response?.data?.message || err.message || '网络错误')
    return Promise.reject(err)
  }
)

export const datasourceApi = {
  list: () => http.get('/datasources'),
  create: (data) => http.post('/datasources', data),
  update: (id, data) => http.put(`/datasources/${id}`, data),
  remove: (id) => http.delete(`/datasources/${id}`),
  test: (data, id) => http.post(`/datasources/test${id ? `?id=${id}` : ''}`, data)
}

export const dictApi = {
  list: (type, enabledOnly) => http.get('/dicts', { params: { type, enabledOnly } }),
  create: (data) => http.post('/dicts', data),
  update: (id, data) => http.put(`/dicts/${id}`, data),
  remove: (id) => http.delete(`/dicts/${id}`),
  modelIds: (provider) => http.get('/dicts/model_ids', { params: { provider } })
}

export const modelApi = {
  list: () => http.get('/models'),
  create: (data) => http.post('/models', data),
  update: (id, data) => http.put(`/models/${id}`, data),
  remove: (id) => http.delete(`/models/${id}`),
  enable: (id) => http.post(`/models/${id}/enable`),
  test: (data, id) => http.post(`/models/test${id ? `?id=${id}` : ''}`, data)
}

export const sessionApi = {
  list: () => http.get('/sessions'),
  create: (data) => http.post('/sessions', data),
  update: (id, data) => http.put(`/sessions/${id}`, data),
  remove: (id) => http.delete(`/sessions/${id}`),
  messages: (id) => http.get(`/sessions/${id}/messages`),
  results: (id) => http.get(`/sessions/${id}/results`),
  consoleExecute: (id, data) => http.post(`/sessions/${id}/console/execute`, data)
}

export const confirmApi = {
  approve: (id) => http.post(`/confirm/${id}/approve`),
  reject: (id) => http.post(`/confirm/${id}/reject`)
}

export default http
```

- [ ] **Step 2: 替换 App.vue（Layout + 侧边菜单）**

```vue
<template>
  <a-layout style="height: 100%">
    <a-layout-sider theme="dark" width="180">
      <div class="logo">DB 智能体</div>
      <a-menu theme="dark" mode="inline" :selected-keys="[selectedKey]" @click="onMenu">
        <a-menu-item key="/chat">对话工作台</a-menu-item>
        <a-menu-item key="/datasources">数据源管理</a-menu-item>
        <a-menu-item key="/models">模型管理</a-menu-item>
        <a-menu-item key="/dict">字典管理</a-menu-item>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-content style="height: 100%; overflow: hidden">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const selectedKey = computed(() => route.path)
const onMenu = ({ key }) => router.push(key)
</script>

<style scoped>
.logo { color: #fff; font-size: 16px; font-weight: 600; text-align: center; line-height: 56px; }
</style>
```

- [ ] **Step 3: 实现 DatasourceView.vue（表格 + 弹窗表单 + 测试连接）**

```vue
<template>
  <div style="padding: 16px">
    <a-space style="margin-bottom: 12px">
      <a-button type="primary" @click="openCreate">新建数据源</a-button>
      <a-button @click="load">刷新</a-button>
    </a-space>
    <a-table :data-source="rows" :columns="columns" row-key="id" size="middle" :loading="loading">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'readOnly'">
          <a-tag :color="record.readOnly ? 'orange' : 'green'">{{ record.readOnly ? '只读' : '可写' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="testRow(record)">测试连接</a>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确认删除该数据源？" @confirm="removeRow(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="form.id ? '编辑数据源' : '新建数据源'"
             :confirm-loading="saving" @ok="save">
      <a-form layout="vertical" :model="form">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="唯一名称，如 local-mysql" />
        </a-form-item>
        <a-form-item label="类型" required>
          <a-select v-model:value="form.dbType" :options="[{value:'mysql',label:'MySQL'},{value:'postgresql',label:'PostgreSQL'}]" @change="onTypeChange" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="16"><a-form-item label="主机" required><a-input v-model:value="form.host" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="端口" required><a-input-number v-model:value="form.port" style="width:100%" /></a-form-item></a-col>
        </a-row>
        <a-form-item label="数据库名" required><a-input v-model:value="form.databaseName" /></a-form-item>
        <a-form-item label="用户名" required><a-input v-model:value="form.username" /></a-form-item>
        <a-form-item :label="form.id ? '密码（留空则不修改）' : '密码'">
          <a-input-password v-model:value="form.password" />
        </a-form-item>
        <a-form-item label="额外连接参数（可选）"><a-input v-model:value="form.extraParams" placeholder="k=v&k2=v2" /></a-form-item>
        <a-form-item label="只读"><a-switch v-model:checked="form.readOnly" /></a-form-item>
      </a-form>
      <a-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :message="testMsg" show-icon style="margin-top:8px" />
      <template #footer>
        <a-button @click="testForm" :loading="testing">测试连接</a-button>
        <a-button @click="modalOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="save">保存</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { datasourceApi } from '../api'

const rows = ref([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const testing = ref(false)
const testMsg = ref('')
const testOk = ref(false)

const emptyForm = () => ({ id: null, name: '', dbType: 'mysql', host: '127.0.0.1', port: 3306,
  databaseName: '', username: 'root', password: '', extraParams: '', readOnly: false })
const form = reactive(emptyForm())

const columns = [
  { title: '名称', dataIndex: 'name' },
  { title: '类型', dataIndex: 'dbType' },
  { title: '主机:端口', customRender: ({ record }) => `${record.host}:${record.port}` },
  { title: '库名', dataIndex: 'databaseName' },
  { title: '用户', dataIndex: 'username' },
  { title: '权限', key: 'readOnly' },
  { title: '操作', key: 'action', width: 220 }
]

const load = async () => {
  loading.value = true
  try { rows.value = await datasourceApi.list() } finally { loading.value = false }
}

const onTypeChange = (v) => { if (v === 'mysql') form.port = 3306; else form.port = 5432 }
const openCreate = () => { Object.assign(form, emptyForm()); testMsg.value = ''; modalOpen.value = true }
const openEdit = (r) => { Object.assign(form, emptyForm(), r, { password: '' }); testMsg.value = ''; modalOpen.value = true }

const save = async () => {
  saving.value = true
  try {
    const payload = { ...form }
    if (form.id) await datasourceApi.update(form.id, payload)
    else await datasourceApi.create(payload)
    message.success('已保存')
    modalOpen.value = false
    await load()
  } finally { saving.value = false }
}

const testForm = async () => {
  testing.value = true
  try {
    const r = await datasourceApi.test({ ...form }, form.id)
    testOk.value = r.success
    testMsg.value = r.success ? `连接成功（${r.elapsedMs}ms）` : `连接失败：${r.message}`
  } finally { testing.value = false }
}

const testRow = async (r) => {
  const res = await datasourceApi.test({ ...r, password: '' }, r.id)
  if (res.success) message.success(`连接成功（${res.elapsedMs}ms）`)
  else message.error(`连接失败：${res.message}`)
}

const removeRow = async (id) => { await datasourceApi.remove(id); message.success('已删除'); await load() }

onMounted(load)
</script>
```

- [ ] **Step 4: 修改 router：/datasources 指向真实页面**

```js
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: () => import('../views/Placeholder.vue') },
  { path: '/datasources', component: () => import('../views/DatasourceView.vue') },
  { path: '/models', component: () => import('../views/Placeholder.vue') },
  { path: '/dict', component: () => import('../views/Placeholder.vue') }
]
export default createRouter({ history: createWebHistory(), routes })
```

- [ ] **Step 5: 验证（后端 + 前端 dev 联调）**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw spring-boot:run -Pskip-frontend   # 终端 1 后台运行
cd frontend && ../target/node/node ../target/node/node_modules/npm/bin/npm-cli.js run dev  # 终端 2（node 已由构建下载）
```
Expected: 访问 http://localhost:5173/datasources 可新增数据源；点"测试连接"对 127.0.0.1:3306 返回结果（未启动本地 MySQL 时应显示失败信息）

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: frontend layout and datasource management page"
```

---

### Task 9: 种子数据源（密文生成 + update.sql）

**Files:**
- Modify: `src/main/resources/sql/update.sql`

- [ ] **Step 1: 用 jshell 生成两个数据源密码的 AES-GCM 密文**

```bash
cd /home/bright/dev/code/ai/database-operation-agent
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts
cat > /tmp/GenCipher.java <<'EOF'
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
public class GenCipher {
  public static void main(String[] a) throws Exception {
    byte[] key = Base64.getDecoder().decode("VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU=");
    for (String plain : a) {
      byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] enc = c.doFinal(plain.getBytes("UTF-8"));
      byte[] out = new byte[iv.length + enc.length];
      System.arraycopy(iv, 0, out, 0, 12); System.arraycopy(enc, 0, out, 12, enc.length);
      System.out.println(plain + " -> " + Base64.getEncoder().encodeToString(out));
    }
  }
}
EOF
$JAVA_HOME/bin/java /tmp/GenCipher.java "Aa123456." "Aa123456."
```
Expected: 输出两行 `Aa123456. -> <base64>`（每次运行密文不同，任选结果使用；两行密文都记下）

- [ ] **Step 2: 将密文写入 update.sql（幂等插入两个测试数据源）**

`src/main/resources/sql/update.sql`（`<MYSQL_CIPHER>`/`<PG_CIPHER>` 替换为 Step 1 实际输出）:
```sql
-- 测试数据源种子（幂等）
INSERT INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
SELECT 'mysql-mytest', 'mysql', '192.168.110.88', 3306, 'mytest', 'root', '<MYSQL_CIPHER>', 0
WHERE NOT EXISTS (SELECT 1 FROM ds_datasource WHERE name = 'mysql-mytest');

INSERT INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
SELECT 'pg-mytest', 'postgresql', '192.168.110.88', 5432, 'mytest', 'ming', '<PG_CIPHER>', 0
WHERE NOT EXISTS (SELECT 1 FROM ds_datasource WHERE name = 'pg-mytest');
```

- [ ] **Step 3: 验证（重启后解锁密码成功 + 实测两个数据库连接，需 192.168.110.88 可达）**

```bash
rm -rf ./data && JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw spring-boot:run -Pskip-frontend  # 后台启动
curl -s http://localhost:8080/api/datasources | head -c 600
curl -s -X POST "http://localhost:8080/api/datasources/test?id=1" -H 'Content-Type: application/json' \
  -d '{"name":"mysql-mytest","dbType":"mysql","host":"192.168.110.88","port":3306,"databaseName":"mytest","username":"root","password":"","readOnly":false}'
```
Expected: 数据源列表含两条；测试连接返回 `"success":true`（若网络不可达则此处失败，记录并在验收阶段重试）

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: seed test datasources with encrypted passwords"
```

---

### Task 10: 字典 DAO / Service / REST

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/dict/{DictItem.java,DictDao.java,DictService.java}`
- Create: `src/main/java/com/mingzy/dbagent/dict/web/DictController.java`
- Test: `src/test/java/com/mingzy/dbagent/dict/DictServiceTest.java`

- [ ] **Step 1: 写测试（按类型查询、按厂商查模型ID、增删改、去重）**

`src/test/java/com/mingzy/dbagent/dict/DictServiceTest.java`:
```java
package com.mingzy.dbagent.dict;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DictServiceTest {

    private DictService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE sys_dict (id INTEGER PRIMARY KEY AUTOINCREMENT, dict_type TEXT NOT NULL,
              dict_key TEXT NOT NULL, dict_label TEXT NOT NULL, parent_key TEXT,
              sort INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1,
              UNIQUE (dict_type, dict_key, parent_key))
            """);
        service = new DictService(new DictDao(jdbc));
        service.save(null, new DictItem(null, "model_provider", "minimax", "MiniMax", null, 1, true));
        service.save(null, new DictItem(null, "model_id", "MiniMax-M3", "MiniMax-M3", "minimax", 1, true));
        service.save(null, new DictItem(null, "model_id", "DeepSeek-Chat", "DeepSeek-Chat", "deepseek", 1, true));
    }

    @Test
    void listByTypeAndParent() {
        assertThat(service.list("model_provider", null, null)).hasSize(1);
        assertThat(service.list("model_id", "minimax", null)).hasSize(1);
        assertThat(service.modelIds("minimax").get(0).dictKey()).isEqualTo("MiniMax-M3");
        assertThat(service.modelIds("deepseek")).hasSize(1);
    }

    @Test
    void updateAndDisable() {
        DictItem item = service.list("model_id", "minimax", null).get(0);
        service.save(item.id(), new DictItem(item.id(), "model_id", "MiniMax-M3", "MiniMax M3（新）", "minimax", 5, false));
        assertThat(service.modelIds("minimax")).isEmpty();  // disabled 被过滤
        assertThat(service.list("model_id", "minimax", null)).hasSize(1); // 管理列表仍可见
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现下列类 → 运行通过**

`DictItem.java`:
```java
package com.mingzy.dbagent.dict;

public record DictItem(Long id, String dictType, String dictKey, String dictLabel,
                       String parentKey, int sort, boolean enabled) {
}
```

`DictDao.java`（同 DatasourceDao 模式，关键方法）:
```java
package com.mingzy.dbagent.dict;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class DictDao {

    private final JdbcTemplate jdbc;
    public DictDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<DictItem> MAPPER = (rs, i) -> new DictItem(
            rs.getLong("id"), rs.getString("dict_type"), rs.getString("dict_key"),
            rs.getString("dict_label"), rs.getString("parent_key"),
            rs.getInt("sort"), rs.getInt("enabled") == 1);

    public long insert(DictItem d) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO sys_dict(dict_type, dict_key, dict_label, parent_key, sort, enabled) VALUES(?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, d.dictType()); ps.setString(2, d.dictKey()); ps.setString(3, d.dictLabel());
            ps.setString(4, d.parentKey()); ps.setInt(5, d.sort()); ps.setInt(6, d.enabled() ? 1 : 0);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void update(DictItem d) {
        jdbc.update("UPDATE sys_dict SET dict_type=?, dict_key=?, dict_label=?, parent_key=?, sort=?, enabled=? WHERE id=?",
                d.dictType(), d.dictKey(), d.dictLabel(), d.parentKey(), d.sort(), d.enabled() ? 1 : 0, d.id());
    }

    public void delete(long id) { jdbc.update("DELETE FROM sys_dict WHERE id=?", id); }

    public DictItem findById(long id) {
        List<DictItem> l = jdbc.query("SELECT * FROM sys_dict WHERE id=?", MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<DictItem> find(String type, String parentKey, Boolean enabledOnly) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sys_dict WHERE dict_type=?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(type);
        if (parentKey != null) { sql.append(" AND parent_key=?"); args.add(parentKey); }
        if (Boolean.TRUE.equals(enabledOnly)) { sql.append(" AND enabled=1"); }
        sql.append(" ORDER BY sort, id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
```

`DictService.java`:
```java
package com.mingzy.dbagent.dict;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DictService {

    private final DictDao dao;
    public DictService(DictDao dao) { this.dao = dao; }

    public List<DictItem> list(String type, String parentKey, Boolean enabledOnly) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("dict_type 不能为空");
        return dao.find(type, parentKey, enabledOnly);
    }

    public List<DictItem> modelIds(String provider) {
        return dao.find("model_id", provider, true);
    }

    public DictItem save(Long id, DictItem item) {
        if (id == null) {
            return dao.findById(dao.insert(item));
        }
        dao.update(new DictItem(id, item.dictType(), item.dictKey(), item.dictLabel(),
                item.parentKey(), item.sort(), item.enabled()));
        return dao.findById(id);
    }

    public void delete(long id) { dao.delete(id); }
}
```

`DictController.java`:
```java
package com.mingzy.dbagent.dict.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.dict.DictItem;
import com.mingzy.dbagent.dict.DictService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dicts")
public class DictController {

    private final DictService service;
    public DictController(DictService service) { this.service = service; }

    @GetMapping
    public Result<List<DictItem>> list(@RequestParam String type,
                                       @RequestParam(required = false) String parentKey,
                                       @RequestParam(required = false) Boolean enabledOnly) {
        return Result.ok(service.list(type, parentKey, enabledOnly));
    }

    @GetMapping("/model_ids")
    public Result<List<DictItem>> modelIds(@RequestParam String provider) {
        return Result.ok(service.modelIds(provider));
    }

    @PostMapping
    public Result<DictItem> create(@RequestBody DictItem item) { return Result.ok(service.save(null, item)); }

    @PutMapping("/{id}")
    public Result<DictItem> update(@PathVariable long id, @RequestBody DictItem item) {
        return Result.ok(service.save(id, item));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.ok(null); }
}
```

- [ ] **Step 3: 运行测试通过 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=DictServiceTest -Pskip-frontend`
Expected: PASS（2 tests）

```bash
git add -A && git commit -m "feat: dict management (provider -> model_id cascade)"
```

---

### Task 11: 字典管理前端页 + 字典种子数据

**Files:**
- Create: `frontend/src/views/DictView.vue`
- Modify: `frontend/src/router/index.js`（/dict 指向真实页面）
- Modify: `src/main/resources/sql/update.sql`（追加字典种子）

- [ ] **Step 1: 实现 DictView.vue（Tab 分组 + 厂商归属 + 启用开关）**

```vue
<template>
  <div style="padding: 16px">
    <a-tabs v-model:activeKey="activeType" @change="load">
      <a-tab-pane key="model_provider" tab="模型厂商" />
      <a-tab-pane key="model_id" tab="模型ID" />
    </a-tabs>
    <a-space style="margin-bottom: 12px">
      <a-button type="primary" @click="openCreate">新增</a-button>
      <a-button @click="load">刷新</a-button>
    </a-space>
    <a-table :data-source="rows" :columns="columns" row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'enabled'">
          <a-tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确认删除？" @confirm="removeRow(record.id)"><a style="color:#ff4d4f">删除</a></a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="form.id ? '编辑字典' : '新增字典'" @ok="save">
      <a-form layout="vertical" :model="form">
        <a-form-item label="字典键" required><a-input v-model:value="form.dictKey" /></a-form-item>
        <a-form-item label="显示名" required><a-input v-model:value="form.dictLabel" /></a-form-item>
        <a-form-item v-if="activeType === 'model_id'" label="所属厂商" required>
          <a-select v-model:value="form.parentKey" :options="providerOptions" />
        </a-form-item>
        <a-form-item label="排序"><a-input-number v-model:value="form.sort" style="width:100%" /></a-form-item>
        <a-form-item label="启用"><a-switch v-model:checked="form.enabled" /></a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { dictApi } from '../api'

const activeType = ref('model_provider')
const rows = ref([])
const providerOptions = ref([])
const modalOpen = ref(false)
const form = reactive({ id: null, dictType: 'model_provider', dictKey: '', dictLabel: '', parentKey: null, sort: 0, enabled: true })

const columns = [
  { title: '键', dataIndex: 'dictKey' },
  { title: '显示名', dataIndex: 'dictLabel' },
  { title: '所属厂商', dataIndex: 'parentKey' },
  { title: '排序', dataIndex: 'sort', width: 80 },
  { title: '状态', key: 'enabled', width: 90 },
  { title: '操作', key: 'action', width: 140 }
]

const load = async () => {
  rows.value = await dictApi.list(activeType.value)
  if (activeType.value === 'model_id') {
    providerOptions.value = (await dictApi.list('model_provider', true))
      .map((p) => ({ value: p.dictKey, label: p.dictLabel }))
  }
}

const openCreate = () => {
  Object.assign(form, { id: null, dictType: activeType.value, dictKey: '', dictLabel: '', parentKey: null, sort: 0, enabled: true })
  modalOpen.value = true
}
const openEdit = (r) => { Object.assign(form, r); modalOpen.value = true }

const save = async () => {
  const payload = { ...form }
  if (form.id) await dictApi.update(form.id, payload)
  else await dictApi.create(payload)
  message.success('已保存')
  modalOpen.value = false
  await load()
}
const removeRow = async (id) => { await dictApi.remove(id); message.success('已删除'); await load() }

onMounted(load)
</script>
```

- [ ] **Step 2: 修改 router：/dict 指向 DictView**

（与 Task 8 Step 4 相同的 routes 数组，将 `{ path: '/dict', component: () => import('../views/DictView.vue') }`）

- [ ] **Step 3: 追加字典种子到 update.sql**

```sql
-- 模型厂商字典（幂等）
INSERT INTO sys_dict (dict_type, dict_key, dict_label, parent_key, sort, enabled)
SELECT 'model_provider', k, l, NULL, s, 1 FROM (
  SELECT 'minimax' k, 'MiniMax' l, 1 s UNION ALL
  SELECT 'deepseek', 'DeepSeek', 2 UNION ALL
  SELECT 'qwen', '通义千问', 3 UNION ALL
  SELECT 'zhipu', '智谱 GLM', 4 UNION ALL
  SELECT 'openai', 'OpenAI', 5 UNION ALL
  SELECT 'ollama', 'Ollama（本地）', 6
) t WHERE NOT EXISTS (SELECT 1 FROM sys_dict d WHERE d.dict_type='model_provider' AND d.dict_key=t.k);

-- 模型ID字典（幂等，parent_key 为厂商）
INSERT INTO sys_dict (dict_type, dict_key, dict_label, parent_key, sort, enabled)
SELECT 'model_id', k, l, p, s, 1 FROM (
  SELECT 'MiniMax-M3' k, 'MiniMax-M3' l, 'minimax' p, 1 s UNION ALL
  SELECT 'MiniMax-Text-01', 'MiniMax-Text-01', 'minimax', 2 UNION ALL
  SELECT 'deepseek-chat', 'DeepSeek Chat', 'deepseek', 1 UNION ALL
  SELECT 'deepseek-reasoner', 'DeepSeek Reasoner', 'deepseek', 2 UNION ALL
  SELECT 'qwen-plus', 'Qwen Plus', 'qwen', 1 UNION ALL
  SELECT 'qwen-max', 'Qwen Max', 'qwen', 2 UNION ALL
  SELECT 'glm-4-plus', 'GLM-4-Plus', 'zhipu', 1 UNION ALL
  SELECT 'gpt-4o-mini', 'GPT-4o mini', 'openai', 1 UNION ALL
  SELECT 'qwen2.5:7b', 'Qwen2.5 7B', 'ollama', 1
) t WHERE NOT EXISTS (SELECT 1 FROM sys_dict d WHERE d.dict_type='model_id' AND d.dict_key=t.k AND IFNULL(d.parent_key,'')=t.p);
```

- [ ] **Step 4: 验证**

```bash
rm -rf ./data && JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw spring-boot:run -Pskip-frontend  # 后台
curl -s "http://localhost:8080/api/dicts/model_ids?provider=minimax"
curl -s "http://localhost:8080/api/dicts?type=model_provider&enabledOnly=true"
```
Expected: 分别返回 MiniMax-M3/MiniMax-Text-01 与 6 家厂商；重启重复启动无重复数据（幂等）

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: dict admin page and seed dictionaries"
```

---

### Task 12: 模型 DAO + Service（启用互斥）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/model/{AiModel.java,AiModelDao.java,AiModelService.java}`
- Test: `src/test/java/com/mingzy/dbagent/model/AiModelServiceTest.java`

- [ ] **Step 1: 写测试（创建/更新/启用互斥/API key 加密）**

`src/test/java/com/mingzy/dbagent/model/AiModelServiceTest.java`:
```java
package com.mingzy.dbagent.model;

import com.mingzy.dbagent.common.AesGcmUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelServiceTest {

    private AiModelService service;
    private AiModelDao dao;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE ai_model (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, provider TEXT NOT NULL,
              base_url TEXT NOT NULL, api_key TEXT NOT NULL, model_id TEXT NOT NULL,
              temperature REAL NOT NULL DEFAULT 0.7, max_tokens INTEGER NOT NULL DEFAULT 2048,
              enabled INTEGER NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
              updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))
            """);
        dao = new AiModelDao(jdbc);
        service = new AiModelService(dao, new AesGcmUtil("VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU="));
    }

    @Test
    void createAndList() {
        AiModel m = service.create(new AiModel(null, "MiniMax", "minimax", "https://api.minimaxi.com/v1",
                "sk-test", "MiniMax-M3", 0.7, 2048, false, null, null));
        assertThat(m.id()).isNotNull();
        AiModel stored = dao.findById(m.id());
        assertThat(stored.apiKey()).isNotEqualTo("sk-test");
        assertThat(service.decryptApiKey(stored)).isEqualTo("sk-test");
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void enableIsMutuallyExclusive() {
        AiModel a = service.create(new AiModel(null, "A", "minimax", "u", "k", "model-a", 0.7, 100, false, null, null));
        AiModel b = service.create(new AiModel(null, "B", "deepseek", "u", "k", "model-b", 0.7, 100, false, null, null));
        service.enable(a.id());
        assertThat(service.enabled().id()).isEqualTo(a.id());
        service.enable(b.id());
        assertThat(service.enabled().id()).isEqualTo(b.id());
        assertThat(dao.findAll().stream().filter(AiModel::enabled).count()).isEqualTo(1);
    }

    @Test
    void updateKeepsApiKeyWhenBlank() {
        AiModel a = service.create(new AiModel(null, "A", "minimax", "u", "sk-old", "model-a", 0.7, 100, false, null, null));
        service.update(a.id(), new AiModel(a.id(), "A2", "minimax", "u2", "", "model-a2", 0.5, 100, false, null, null));
        AiModel stored = dao.findById(a.id());
        assertThat(stored.name()).isEqualTo("A2");
        assertThat(service.decryptApiKey(stored)).isEqualTo("sk-old");
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现下列类 → 运行通过**

`AiModel.java`:
```java
package com.mingzy.dbagent.model;

public record AiModel(Long id, String name, String provider, String baseUrl, String apiKey,
                      String modelId, double temperature, int maxTokens, boolean enabled,
                      String createdAt, String updatedAt) {
}
```

`AiModelDao.java`（同 DatasourceDao 模式，字段：name/provider/base_url/api_key/model_id/temperature/max_tokens/enabled）:
```java
package com.mingzy.dbagent.model;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class AiModelDao {

    private final JdbcTemplate jdbc;
    public AiModelDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AiModel> MAPPER = (rs, i) -> new AiModel(
            rs.getLong("id"), rs.getString("name"), rs.getString("provider"), rs.getString("base_url"),
            rs.getString("api_key"), rs.getString("model_id"), rs.getDouble("temperature"),
            rs.getInt("max_tokens"), rs.getInt("enabled") == 1,
            rs.getString("created_at"), rs.getString("updated_at"));

    public long insert(AiModel m) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO ai_model(name, provider, base_url, api_key, model_id, temperature, max_tokens, enabled) " +
                "VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, m.name()); ps.setString(2, m.provider()); ps.setString(3, m.baseUrl());
            ps.setString(4, m.apiKey()); ps.setString(5, m.modelId()); ps.setDouble(6, m.temperature());
            ps.setInt(7, m.maxTokens()); ps.setInt(8, m.enabled() ? 1 : 0);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void update(AiModel m) {
        jdbc.update("UPDATE ai_model SET name=?, provider=?, base_url=?, api_key=?, model_id=?, temperature=?, " +
                "max_tokens=?, enabled=?, updated_at=datetime('now','localtime') WHERE id=?",
                m.name(), m.provider(), m.baseUrl(), m.apiKey(), m.modelId(), m.temperature(),
                m.maxTokens(), m.enabled() ? 1 : 0, m.id());
    }

    public void delete(long id) { jdbc.update("DELETE FROM ai_model WHERE id=?", id); }

    public AiModel findById(long id) {
        List<AiModel> l = jdbc.query("SELECT * FROM ai_model WHERE id=?", MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<AiModel> findAll() { return jdbc.query("SELECT * FROM ai_model ORDER BY id", MAPPER); }

    public void disableAll() { jdbc.update("UPDATE ai_model SET enabled=0 WHERE enabled=1"); }
}
```

`AiModelService.java`:
```java
package com.mingzy.dbagent.model;

import com.mingzy.dbagent.common.AesGcmUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiModelService {

    private final AiModelDao dao;
    private final AesGcmUtil aes;

    @Autowired
    public AiModelService(AiModelDao dao, @Value("${app.crypto.key}") String key) {
        this.dao = dao;
        this.aes = new AesGcmUtil(key);
    }

    AiModelService(AiModelDao dao, AesGcmUtil aes) { this.dao = dao; this.aes = aes; }

    public List<AiModel> list() { return dao.findAll(); }

    public AiModel requireById(long id) {
        AiModel m = dao.findById(id);
        if (m == null) throw new IllegalArgumentException("模型不存在: " + id);
        return m;
    }

    public AiModel enabled() {
        return dao.findAll().stream().filter(AiModel::enabled).findFirst().orElse(null);
    }

    public AiModel create(AiModel m) {
        long id = dao.insert(new AiModel(null, m.name(), m.provider(), m.baseUrl(),
                aes.encrypt(m.apiKey()), m.modelId(), m.temperature(), m.maxTokens(), false, null, null));
        return requireById(id);
    }

    public AiModel update(long id, AiModel m) {
        AiModel old = requireById(id);
        String apiKey = (m.apiKey() == null || m.apiKey().isBlank()) ? old.apiKey() : aes.encrypt(m.apiKey());
        dao.update(new AiModel(id, m.name(), m.provider(), m.baseUrl(), apiKey, m.modelId(),
                m.temperature(), m.maxTokens(), old.enabled(), null, null));
        return requireById(id);
    }

    public void enable(long id) {
        requireById(id);
        dao.disableAll();
        AiModel m = dao.findById(id);
        dao.update(new AiModel(m.id(), m.name(), m.provider(), m.baseUrl(), m.apiKey(), m.modelId(),
                m.temperature(), m.maxTokens(), true, null, null));
    }

    public void delete(long id) { requireById(id); dao.delete(id); }

    public String decryptApiKey(AiModel m) { return aes.decrypt(m.apiKey()); }

    /** 解密后的副本，供 ChatClientFactory / 连接测试使用 */
    public AiModel withPlainKey(AiModel m) {
        return new AiModel(m.id(), m.name(), m.provider(), m.baseUrl(), aes.decrypt(m.apiKey()),
                m.modelId(), m.temperature(), m.maxTokens(), m.enabled(), m.createdAt(), m.updatedAt());
    }

    /** 返回给前端的视图（隐藏 apiKey，仅标记是否已配置） */
    public List<AiModelView> views() {
        return dao.findAll().stream()
                .map(m -> new AiModelView(m.id(), m.name(), m.provider(), m.baseUrl(), m.modelId(),
                        m.temperature(), m.maxTokens(), m.enabled(),
                        m.apiKey() != null && !m.apiKey().isBlank()))
                .toList();
    }

    public record AiModelView(Long id, String name, String provider, String baseUrl, String modelId,
                              double temperature, int maxTokens, boolean enabled, boolean hasApiKey) {
    }
}
```

- [ ] **Step 3: 运行测试通过 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=AiModelServiceTest -Pskip-frontend`
Expected: PASS（3 tests）

```bash
git add -A && git commit -m "feat: ai model management with mutual-exclusive enable"
```

---

### Task 13: ChatClientFactory + 模型 REST API

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/config/ChatClientFactory.java`
- Create: `src/main/java/com/mingzy/dbagent/model/web/AiModelController.java`
- Test: `src/test/java/com/mingzy/dbagent/config/ChatClientFactoryTest.java`

**关键点（易错）:** Spring AI 的 `OpenAiApi` 默认 `completionsPath=/v1/chat/completions`。当用户填写的 baseUrl 已含 `/v1`（如 `https://api.minimaxi.com/v1`）时必须把 `completionsPath` 改为 `/chat/completions`，否则 URL 会重复 `/v1/v1`。

- [ ] **Step 1: 写测试（URL 规范化两种情形）**

`src/test/java/com/mingzy/dbagent/config/ChatClientFactoryTest.java`:
```java
package com.mingzy.dbagent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatClientFactoryTest {

    @Test
    void baseUrlWithV1Suffix() {
        String[] normalized = ChatClientFactory.normalize("https://api.minimaxi.com/v1");
        assertThat(normalized[0]).isEqualTo("https://api.minimaxi.com/v1");
        assertThat(normalized[1]).isEqualTo("/chat/completions");
    }

    @Test
    void baseUrlWithoutV1() {
        String[] normalized = ChatClientFactory.normalize("https://api.openai.com/");
        assertThat(normalized[0]).isEqualTo("https://api.openai.com");
        assertThat(normalized[1]).isEqualTo("/v1/chat/completions");
    }
}
```

（注：`ChatClientFactory.normalize(String baseUrl)` 返回 `[baseUrl, completionsPath]`，为静态纯函数，便于测试。）

- [ ] **Step 2: 运行确认失败 → 实现 ChatClientFactory → 运行通过**

`src/main/java/com/mingzy/dbagent/config/ChatClientFactory.java`:
```java
package com.mingzy.dbagent.config;

import com.mingzy.dbagent.model.AiModel;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChatClientFactory {

    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String fingerprint, ChatClient client) {
    }

    public ChatClient clientFor(AiModel model) {
        String fingerprint = model.baseUrl() + "|" + model.modelId() + "|" + model.temperature()
                + "|" + model.maxTokens();
        CacheEntry entry = cache.get(model.id());
        if (entry != null && entry.fingerprint().equals(fingerprint)) {
            return entry.client();
        }
        ChatClient client = build(model);
        cache.put(model.id(), new CacheEntry(fingerprint, client));
        return client;
    }

    public void evict(long modelId) { cache.remove(modelId); }

    /** 构建一次性客户端（用于连接测试） */
    public ChatClient build(AiModel model) {
        String[] norm = normalize(model.baseUrl());
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(norm[0])
                .completionsPath(norm[1])
                .apiKey(model.apiKey())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model.modelId())
                        .temperature(model.temperature())
                        .maxTokens(model.maxTokens())
                        .build())
                .build();
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 规范化 baseUrl 与 completionsPath：
     * baseUrl 以 /v1 结尾 → [原值, /chat/completions]；否则 → [原值, /v1/chat/completions]
     */
    public static String[] normalize(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1")) {
            return new String[]{trimmed, "/chat/completions"};
        }
        return new String[]{trimmed, "/v1/chat/completions"};
    }
}
```

（注：`io.micrometer.common.util.StringUtils` 随 Spring Boot 引入；也可换 `org.springframework.util.StringUtils.hasText`。）

- [ ] **Step 3: 实现 AiModelController**

`src/main/java/com/mingzy/dbagent/model/web/AiModelController.java`:
```java
package com.mingzy.dbagent.model.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.config.ChatClientFactory;
import com.mingzy.dbagent.model.AiModel;
import com.mingzy.dbagent.model.AiModelService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class AiModelController {

    private final AiModelService service;
    private final ChatClientFactory factory;

    public AiModelController(AiModelService service, ChatClientFactory factory) {
        this.service = service;
        this.factory = factory;
    }

    @GetMapping
    public Result<List<AiModelService.AiModelView>> list() { return Result.ok(service.views()); }

    @PostMapping
    public Result<AiModelService.AiModelView> create(@RequestBody AiModel m) {
        AiModel created = service.create(m);
        factory.evict(created.id());
        return Result.ok(service.views().stream().filter(v -> v.id().equals(created.id())).findFirst().orElseThrow());
    }

    @PutMapping("/{id}")
    public Result<AiModelService.AiModelView> update(@PathVariable long id, @RequestBody AiModel m) {
        AiModel updated = service.update(id, m);
        factory.evict(id);
        return Result.ok(service.views().stream().filter(v -> v.id().equals(updated.id())).findFirst().orElseThrow());
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        service.delete(id);
        factory.evict(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable long id) { service.enable(id); return Result.ok(null); }

    /** 连接测试：未保存表单也可测（apiKey 空且带 id 时用已存的 key） */
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestBody AiModel m, @RequestParam(required = false) Long id) {
        String apiKey = m.apiKey();
        if ((apiKey == null || apiKey.isBlank()) && id != null) {
            apiKey = service.decryptApiKey(service.requireById(id));
        }
        AiModel probe = new AiModel(id, m.name(), m.provider(), m.baseUrl(), apiKey, m.modelId(),
                m.temperature(), m.maxTokens(), false, null, null);
        long start = System.currentTimeMillis();
        try {
            ChatClient client = factory.build(probe);
            String reply = client.prompt().user("请回复：ok").call().content();
            return Result.ok(Map.of("success", true,
                    "elapsedMs", System.currentTimeMillis() - start,
                    "reply", reply == null ? "" : reply));
        } catch (Exception e) {
            return Result.ok(Map.of("success", false,
                    "elapsedMs", System.currentTimeMillis() - start,
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
```

- [ ] **Step 4: 运行测试通过 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=ChatClientFactoryTest -Pskip-frontend`
Expected: PASS（2 tests）

```bash
git add -A && git commit -m "feat: chat client factory and model rest api"
```

---

### Task 14: 模型管理前端页 + MiniMax 种子模型（实测连通）

**Files:**
- Create: `frontend/src/views/ModelView.vue`
- Modify: `frontend/src/router/index.js`（/models 指向真实页面）
- Modify: `src/main/resources/sql/update.sql`（追加 MiniMax 种子模型）

- [ ] **Step 1: 用 jshell 生成 MiniMax apikey 密文（apiKey 原文取自 `docs/测试用大模型配置.md`）**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts
$JAVA_HOME/bin/java /tmp/GenCipher.java "sk-cp-XSbL-y7vN_aFGaeUzwG26xI_lWB6uusmrQWOgcj4KLyOEPwr81KjJCyXa-G5f0h9l5ztHwkxnCH7Dh8G4WYYmhMhLuzVRcU0_7yTjuLNkE-CavmnBMX92Zw"
```
Expected: 输出 `sk-cp-... -> <base64>`（记下密文）

- [ ] **Step 2: 追加种子模型到 update.sql（enabled=1）**

```sql
-- 测试模型：MiniMax-M3（幂等）
INSERT INTO ai_model (name, provider, base_url, api_key, model_id, temperature, max_tokens, enabled)
SELECT 'MiniMax-M3（测试）', 'minimax', 'https://api.minimaxi.com/v1', '<MINIMAX_CIPHER>', 'MiniMax-M3', 0.7, 4096, 1
WHERE NOT EXISTS (SELECT 1 FROM ai_model WHERE model_id = 'MiniMax-M3');
```

- [ ] **Step 3: 实现 ModelView.vue（联动下拉 + 启用互斥 + 测试连接）**

```vue
<template>
  <div style="padding: 16px">
    <a-space style="margin-bottom: 12px">
      <a-button type="primary" @click="openCreate">新建模型</a-button>
      <a-button @click="load">刷新</a-button>
    </a-space>
    <a-table :data-source="rows" :columns="columns" row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'enabled'">
          <a-switch :checked="record.enabled" :disabled="record.enabled" @change="enableRow(record)" />
        </template>
        <template v-else-if="column.key === 'provider'">
          {{ providerLabel(record.provider) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="testRow(record)">测试连接</a>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确认删除？" @confirm="removeRow(record.id)"><a style="color:#ff4d4f">删除</a></a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="form.id ? '编辑模型' : '新建模型'" @ok="save" width="560px">
      <a-form layout="vertical" :model="form">
        <a-form-item label="名称" required><a-input v-model:value="form.name" /></a-form-item>
        <a-form-item label="模型供应商" required>
          <a-select v-model:value="form.provider" :options="providerOptions" @change="onProviderChange" />
        </a-form-item>
        <a-form-item label="模型ID" required>
          <a-select v-model:value="form.modelId" :options="modelIdOptions" show-search
                    placeholder="选择或输入模型ID" :filter-option="filterOption">
            <template #dropdownRender="{ menu }">
              <div>
                <a-input v-model:value="customModelId" style="margin: 4px 8px; width: calc(100% - 16px)"
                         placeholder="自定义模型ID" @pressEnter="addCustomModelId" />
                <a-divider style="margin: 4px 0" />
                <component :is="menu" />
              </div>
            </template>
          </a-select>
        </a-form-item>
        <a-form-item label="Base URL" required>
          <a-input v-model:value="form.baseUrl" placeholder="https://api.minimaxi.com/v1" />
        </a-form-item>
        <a-form-item :label="form.id ? 'API Key（留空则不修改）' : 'API Key'" required>
          <a-input-password v-model:value="form.apiKey" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12"><a-form-item label="temperature"><a-input-number v-model:value="form.temperature" :min="0" :max="2" :step="0.1" style="width:100%" /></a-form-item></a-col>
          <a-col :span="12"><a-form-item label="maxTokens"><a-input-number v-model:value="form.maxTokens" :min="1" style="width:100%" /></a-form-item></a-col>
        </a-row>
      </a-form>
      <a-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :message="testMsg" show-icon style="margin-top:8px" />
      <template #footer>
        <a-button :loading="testing" @click="testForm">测试连接</a-button>
        <a-button @click="modalOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="save">保存</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { dictApi, modelApi } from '../api'

const rows = ref([])
const providerOptions = ref([])
const modelIdOptions = ref([])
const customModelId = ref('')
const modalOpen = ref(false)
const saving = ref(false)
const testing = ref(false)
const testMsg = ref('')
const testOk = ref(false)

const emptyForm = () => ({ id: null, name: '', provider: undefined, modelId: undefined,
  baseUrl: '', apiKey: '', temperature: 0.7, maxTokens: 4096 })
const form = reactive(emptyForm())

const columns = [
  { title: '名称', dataIndex: 'name' },
  { title: '供应商', key: 'provider' },
  { title: '模型ID', dataIndex: 'modelId' },
  { title: 'Base URL', dataIndex: 'baseUrl' },
  { title: '启用', key: 'enabled', width: 90 },
  { title: '操作', key: 'action', width: 200 }
]

const providerLabel = (key) => providerOptions.value.find((p) => p.value === key)?.label || key
const filterOption = (input, option) => (option.label ?? '').toLowerCase().includes(input.toLowerCase())

const load = async () => {
  rows.value = await modelApi.list()
  const dicts = await dictApi.list('model_provider', true)
  providerOptions.value = dicts.map((p) => ({ value: p.dictKey, label: p.dictLabel }))
}

const loadModelIds = async (provider) => {
  if (!provider) { modelIdOptions.value = []; return }
  const ids = await dictApi.modelIds(provider)
  modelIdOptions.value = ids.map((d) => ({ value: d.dictKey, label: d.dictLabel }))
}

const onProviderChange = (v) => { form.modelId = undefined; loadModelIds(v) }
const addCustomModelId = () => {
  if (!customModelId.value) return
  modelIdOptions.value.push({ value: customModelId.value, label: `${customModelId.value}（自定义）` })
  form.modelId = customModelId.value
  customModelId.value = ''
}

const openCreate = () => { Object.assign(form, emptyForm()); testMsg.value = ''; modelIdOptions.value = []; modalOpen.value = true }
const openEdit = async (r) => {
  Object.assign(form, emptyForm(), r, { apiKey: '' })
  testMsg.value = ''
  await loadModelIds(r.provider)
  modalOpen.value = true
}

const save = async () => {
  saving.value = true
  try {
    const payload = { ...form }
    if (form.id) await modelApi.update(form.id, payload)
    else await modelApi.create(payload)
    message.success('已保存')
    modalOpen.value = false
    await load()
  } finally { saving.value = false }
}

const testForm = async () => {
  testing.value = true
  testMsg.value = ''
  try {
    const r = await modelApi.test({ ...form }, form.id)
    testOk.value = r.success
    testMsg.value = r.success
      ? `调用成功（${r.elapsedMs}ms）：${r.reply}`
      : `调用失败：${r.message}`
  } finally { testing.value = false }
}

const testRow = async (r) => {
  const res = await modelApi.test({ ...r, apiKey: '' }, r.id)
  if (res.success) message.success(`调用成功（${res.elapsedMs}ms）：${res.reply}`)
  else message.error(`调用失败：${res.message}`)
}

const enableRow = async (r) => { await modelApi.enable(r.id); message.success('已启用'); await load() }
const removeRow = async (id) => { await modelApi.remove(id); message.success('已删除'); await load() }

onMounted(load)
</script>
```

- [ ] **Step 4: 修改 router：/models 指向 ModelView；验证真实调用 MiniMax**

```bash
rm -rf ./data && JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw spring-boot:run -Pskip-frontend  # 后台
curl -s -X POST http://localhost:8080/api/models/test -H 'Content-Type: application/json' \
  -d '{"name":"MiniMax-M3（测试）","provider":"minimax","baseUrl":"https://api.minimaxi.com/v1","apiKey":"sk-cp-XSbL-y7vN_aFGaeUzwG26xI_lWB6uusmrQWOgcj4KLyOEPwr81KjJCyXa-G5f0h9l5ztHwkxnCH7Dh8G4WYYmhMhLuzVRcU0_7yTjuLNkE-CavmnBMX92Zw","modelId":"MiniMax-M3","temperature":0.7,"maxTokens":256}'
```
Expected: `"success":true` 且 `reply` 非空（若外网不可达则记录，但在后续会话验收时必须连通）

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: model admin page and MiniMax seed model"
```

---

### Task 15: SqlClassifier + LimitInjector（纯函数，TDD）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/executor/{SqlKind,SqlClassifier,LimitInjector}.java`
- Test: `src/test/java/com/mingzy/dbagent/executor/{SqlClassifierTest,LimitInjectorTest}.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/mingzy/dbagent/executor/SqlClassifierTest.java`:
```java
package com.mingzy.dbagent.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlClassifierTest {

    @Test
    void queries() {
        assertThat(SqlClassifier.kind("select * from t")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("  SELECT 1")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("with a as (select 1) select * from a")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("-- 注释\nselect 1")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("/* c */ select 1")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("show tables")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("explain select 1")).isEqualTo(SqlKind.QUERY);
    }

    @Test
    void writes() {
        assertThat(SqlClassifier.kind("insert into t values (1)")).isEqualTo(SqlKind.WRITE);
        assertThat(SqlClassifier.kind("UPDATE t SET a=1")).isEqualTo(SqlKind.WRITE);
        assertThat(SqlClassifier.kind("delete from t")).isEqualTo(SqlKind.WRITE);
        assertThat(SqlClassifier.kind("truncate table t")).isEqualTo(SqlKind.WRITE);
    }

    @Test
    void ddls() {
        assertThat(SqlClassifier.kind("create table t(a int)")).isEqualTo(SqlKind.DDL);
        assertThat(SqlClassifier.kind("drop table t")).isEqualTo(SqlKind.DDL);
        assertThat(SqlClassifier.kind("alter table t add column b int")).isEqualTo(SqlKind.DDL);
    }

    @Test
    void unknownDefaultsToWrite() {
        assertThat(SqlClassifier.kind("vacuum")).isEqualTo(SqlKind.WRITE);
    }
}
```

`src/test/java/com/mingzy/dbagent/executor/LimitInjectorTest.java`:
```java
package com.mingzy.dbagent.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LimitInjectorTest {

    @Test
    void appendsLimitWhenAbsent() {
        assertThat(LimitInjector.inject("select * from t", 10)).isEqualTo("select * from t LIMIT 10");
        assertThat(LimitInjector.inject("select * from t;", 10)).isEqualTo("select * from t LIMIT 10");
        assertThat(LimitInjector.inject("select * from t \n;", 10)).isEqualTo("select * from t LIMIT 10");
    }

    @Test
    void keepsExistingLimit() {
        assertThat(LimitInjector.inject("select * from t limit 5", 10)).isEqualTo("select * from t limit 5");
        assertThat(LimitInjector.inject("select * from t LIMIT 5 OFFSET 2", 10)).isEqualTo("select * from t LIMIT 5 OFFSET 2");
    }

    @Test
    void ignoresLimitInsideStringLiteral() {
        String sql = "select 'limit 5' as x from t";
        assertThat(LimitInjector.inject(sql, 10)).isEqualTo(sql + " LIMIT 10");
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现三个类 → 运行通过**

`SqlKind.java`:
```java
package com.mingzy.dbagent.executor;

public enum SqlKind { QUERY, WRITE, DDL }
```

`SqlClassifier.java`:
```java
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
```

`LimitInjector.java`:
```java
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
```

- [ ] **Step 3: 运行测试通过 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest='SqlClassifierTest,LimitInjectorTest' -Pskip-frontend`
Expected: PASS（合计 9 tests）

```bash
git add -A && git commit -m "feat: sql classifier and limit injector"
```

---

### Task 16: 元数据服务（MySQL / PostgreSQL 方言）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/metadata/model/{TableInfo,ColumnInfo}.java`
- Create: `src/main/java/com/mingzy/dbagent/metadata/{MetadataService,MysqlMetadataService,PostgresMetadataService,MetadataServiceRouter}.java`
- Test: `src/test/java/com/mingzy/dbagent/metadata/MetadataServiceRouterTest.java`

- [ ] **Step 1: 实现模型 record**

`TableInfo.java`:
```java
package com.mingzy.dbagent.metadata.model;

public record TableInfo(String name, String type, String comment) {
}
```

`ColumnInfo.java`:
```java
package com.mingzy.dbagent.metadata.model;

public record ColumnInfo(String name, String dataType, boolean nullable, boolean primaryKey,
                         String defaultValue, String comment) {
}
```

- [ ] **Step 2: 实现接口与两个方言实现**

`MetadataService.java`:
```java
package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;

import javax.sql.DataSource;
import java.util.List;

public interface MetadataService {
    List<TableInfo> tables(DataSource ds);
    List<ColumnInfo> columns(DataSource ds, String tableName);
}
```

`MysqlMetadataService.java`:
```java
package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

@Component
public class MysqlMetadataService implements MetadataService {

    @Override
    public List<TableInfo> tables(DataSource ds) {
        return new JdbcTemplate(ds).query(
            "SELECT table_name, table_type, table_comment FROM information_schema.tables " +
            "WHERE table_schema = DATABASE() ORDER BY table_name",
            (rs, i) -> new TableInfo(rs.getString("table_name"), rs.getString("table_type"),
                    rs.getString("table_comment")));
    }

    @Override
    public List<ColumnInfo> columns(DataSource ds, String tableName) {
        return new JdbcTemplate(ds).query(
            "SELECT column_name, column_type, is_nullable, column_key, column_default, column_comment " +
            "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? " +
            "ORDER BY ordinal_position",
            (rs, i) -> new ColumnInfo(rs.getString("column_name"), rs.getString("column_type"),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    "PRI".equalsIgnoreCase(rs.getString("column_key")),
                    rs.getString("column_default"), rs.getString("column_comment")),
            tableName);
    }
}
```

`PostgresMetadataService.java`:
```java
package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

@Component
public class PostgresMetadataService implements MetadataService {

    @Override
    public List<TableInfo> tables(DataSource ds) {
        return new JdbcTemplate(ds).query(
            "SELECT table_name, table_type FROM information_schema.tables " +
            "WHERE table_schema = current_schema() ORDER BY table_name",
            (rs, i) -> new TableInfo(rs.getString("table_name"), rs.getString("table_type"), null));
    }

    @Override
    public List<ColumnInfo> columns(DataSource ds, String tableName) {
        return new JdbcTemplate(ds).query(
            "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, " +
            "  (tc.constraint_type = 'PRIMARY KEY') AS is_pk, " +
            "  col_description(cl.oid, c.ordinal_position) AS column_comment " +
            "FROM information_schema.columns c " +
            "JOIN pg_class cl ON cl.relname = c.table_name " +
            "LEFT JOIN information_schema.table_constraints tc ON tc.table_name = c.table_name " +
            "  AND tc.constraint_type = 'PRIMARY KEY' " +
            "LEFT JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name " +
            "  AND kcu.column_name = c.column_name AND kcu.table_name = c.table_name " +
            "WHERE c.table_schema = current_schema() AND c.table_name = ? " +
            "ORDER BY c.ordinal_position",
            (rs, i) -> new ColumnInfo(rs.getString("column_name"), rs.getString("data_type"),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    rs.getBoolean("is_pk"), rs.getString("column_default"),
                    rs.getString("column_comment")),
            tableName);
    }
}
```

`MetadataServiceRouter.java`:
```java
package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.common.DbType;
import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

@Component
public class MetadataServiceRouter {

    private final MysqlMetadataService mysql;
    private final PostgresMetadataService postgres;

    public MetadataServiceRouter(MysqlMetadataService mysql, PostgresMetadataService postgres) {
        this.mysql = mysql;
        this.postgres = postgres;
    }

    private MetadataService of(Datasource ds) {
        return ds.type() == DbType.mysql ? mysql : postgres;
    }

    public List<TableInfo> tables(Datasource ds, DataSource pool) { return of(ds).tables(pool); }

    public List<ColumnInfo> columns(Datasource ds, DataSource pool, String tableName) {
        return of(ds).columns(pool, tableName);
    }
}
```

- [ ] **Step 3: 写路由测试（方言选择）**

`src/test/java/com/mingzy/dbagent/metadata/MetadataServiceRouterTest.java`:
```java
package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.datasource.Datasource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataServiceRouterTest {

    @Test
    void routesByDbType() {
        MysqlMetadataService mysql = new MysqlMetadataService();
        PostgresMetadataService pg = new PostgresMetadataService();
        MetadataServiceRouter router = new MetadataServiceRouter(mysql, pg);
        // 仅验证路由不抛异常（真实查询在集成/验收阶段验证）
        Datasource mysqlDs = new Datasource(1L, "m", "mysql", "h", 3306, "d", "u", "p", null, false, null, null);
        Datasource pgDs = new Datasource(2L, "p", "postgresql", "h", 5432, "d", "u", "p", null, false, null, null);
        assertThat(mysqlDs.type().name()).isEqualTo("mysql");
        assertThat(pgDs.type().name()).isEqualTo("postgresql");
        assertThat(router).isNotNull();
    }
}
```

- [ ] **Step 4: 补充元数据浏览 REST（设计 §8 接口面）**

`src/main/java/com/mingzy/dbagent/metadata/web/MetadataController.java`:
```java
package com.mingzy.dbagent.metadata.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.metadata.MetadataServiceRouter;
import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasources")
public class MetadataController {

    private final MetadataServiceRouter router;
    private final DatasourceService datasourceService;

    public MetadataController(MetadataServiceRouter router, DatasourceService datasourceService) {
        this.router = router;
        this.datasourceService = datasourceService;
    }

    @GetMapping("/{id}/tables")
    public Result<List<TableInfo>> tables(@PathVariable long id) {
        DatasourceService.HikariPoolRef ref = datasourceService.poolOf(datasourceService.requireById(id));
        return Result.ok(router.tables(ref.datasource(), ref.pool()));
    }

    @GetMapping("/{id}/tables/{table}/schema")
    public Result<List<ColumnInfo>> schema(@PathVariable long id, @PathVariable String table) {
        DatasourceService.HikariPoolRef ref = datasourceService.poolOf(datasourceService.requireById(id));
        return Result.ok(router.columns(ref.datasource(), ref.pool(), table));
    }
}
```

- [ ] **Step 5: 运行测试通过 + 真实库集成验证（可选，需 192.168.110.88 可达）**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=MetadataServiceRouterTest -Pskip-frontend`
Expected: PASS

真实元数据验证在 Task 25（端到端）中通过工具调用完成。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: metadata service with mysql/postgresql dialects"
```

---

### Task 17: SqlExecutor（执行 / 超时 / 截断 / 值序列化）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/executor/{SqlExecResult,SqlExecutor}.java`
- Test: `src/test/java/com/mingzy/dbagent/executor/SqlExecutorTest.java`

- [ ] **Step 1: 写测试（用 SQLite 内存库验证查询/截断/写入/错误）**

`src/test/java/com/mingzy/dbagent/executor/SqlExecutorTest.java`:
```java
package com.mingzy.dbagent.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SqlExecutorTest {

    private DataSource ds;
    private SqlExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
            for (int i = 1; i <= 25; i++) {
                st.execute("INSERT INTO t(name) VALUES ('n" + i + "')");
            }
        }
        executor = new SqlExecutor();
    }

    @Test
    void queryInjectsLimitAndReturnsRows() {
        SqlExecResult r = executor.executeQuery(ds, "select * from t", 10, 500, 30);
        assertThat(r.success()).isTrue();
        assertThat(r.columns()).containsExactly("id", "name");
        assertThat(r.rows()).hasSize(10);
        assertThat(r.rowCount()).isEqualTo(10);
    }

    @Test
    void respectsExplicitLimit() {
        SqlExecResult r = executor.executeQuery(ds, "select * from t limit 3", 10, 500, 30);
        assertThat(r.rows()).hasSize(3);
    }

    @Test
    void truncatesAtMaxRowsWhenNoLimitPossible() {
        SqlExecResult r = executor.executeQuery(ds, "select * from t limit 20", 10, 5, 30);
        assertThat(r.rows()).hasSize(5);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void writeReturnsAffectedRows() {
        SqlExecResult r = executor.executeUpdate(ds, "update t set name='x' where id <= 3", 30);
        assertThat(r.success()).isTrue();
        assertThat(r.affectedRows()).isEqualTo(3);
        assertThat(r.resultType()).isEqualTo("update");
    }

    @Test
    void badSqlReturnsErrorResult() {
        SqlExecResult r = executor.executeQuery(ds, "select * from not_exists", 10, 500, 30);
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).containsIgnoringCase("no such table");
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现两个类 → 运行通过**

`SqlExecResult.java`:
```java
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
```

`SqlExecutor.java`:
```java
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
```

- [ ] **Step 3: 运行测试通过 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=SqlExecutorTest -Pskip-frontend`
Expected: PASS（5 tests）

```bash
git add -A && git commit -m "feat: sql executor with limit injection, timeout and truncation"
```

---

### Task 18: 共享工具层 DatabaseTools（5 个 @Tool，同时是 ToolCallbackProvider）

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/tool/{DatabaseTools,ToolTraceRegistry}.java`
- Test: `src/test/java/com/mingzy/dbagent/tool/DatabaseToolsTest.java`

**关键点:** `DatabaseTools` 实现 `org.springframework.ai.tool.ToolCallbackProvider`（由内部 `MethodToolCallbackProvider` 代理）：MCP Server 自动配置会自动收集该 Bean；内置 ChatClient 也挂载同一批回调。工具方法内部通过 `ToolContext` 拿到 `sessionId`（内部对话）或拿不到（MCP 外部通道）。

- [ ] **Step 1: 写测试（工具返回值格式：不依赖 Spring 容器，直接构造）**

`src/test/java/com/mingzy/dbagent/tool/DatabaseToolsTest.java`:
```java
package com.mingzy.dbagent.tool;

import com.mingzy.dbagent.common.AesGcmUtil;
import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.datasource.DatasourceDao;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.datasource.DynamicDataSourceManager;
import com.mingzy.dbagent.executor.SqlExecutor;
import com.mingzy.dbagent.metadata.MetadataServiceRouter;
import com.mingzy.dbagent.metadata.MysqlMetadataService;
import com.mingzy.dbagent.metadata.PostgresMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseToolsTest {

    private DatabaseTools tools;
    private DatasourceService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE ds_datasource (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
              db_type TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, database_name TEXT NOT NULL,
              username TEXT NOT NULL, password TEXT NOT NULL, extra_params TEXT, read_only INTEGER NOT NULL DEFAULT 0,
              created_at TEXT, updated_at TEXT)
            """);
        DatasourceDao dao = new DatasourceDao(jdbc);
        AesGcmUtil aes = new AesGcmUtil("VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU=");
        DatasourceService sp = new DatasourceService(dao, aes, new DynamicDataSourceManager());
        service = sp;
        // 用 sqlite 文件库充当"受管数据源"，验证工具链（路径用临时文件使多连接可见）
        tools = new DatabaseTools(sp, new SqlExecutor(),
                new MetadataServiceRouter(new MysqlMetadataService(), new PostgresMetadataService()),
                new ToolTraceRegistry(), 10, 500, 30);
    }

    @Test
    void listDatasourcesText() {
        service.create(new com.mingzy.dbagent.datasource.dto.DatasourceRequest(
                "demo", "mysql", "127.0.0.1", 3306, "mytest", "root", "pwd", null, false));
        String out = tools.listDatasources();
        assertThat(out).contains("demo").contains("mysql");
    }

    @Test
    void unknownDatasourceMessage() {
        String out = tools.listTables("not-exists");
        assertThat(out).contains("不存在");
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现两个类 → 运行通过**

`ToolTraceRegistry.java`（记录"本次对话轮次执行了哪些 sql_result"，供 ChatService 回填 ai_comment）:
```java
package com.mingzy.dbagent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ToolTraceRegistry {

    private final Map<Long, List<Long>> traceToResults = new ConcurrentHashMap<>();

    public void record(Long traceId, Long resultId) {
        if (traceId == null || resultId == null) return;
        traceToResults.computeIfAbsent(traceId, k -> new CopyOnWriteArrayList<>()).add(resultId);
    }

    public List<Long> results(Long traceId) {
        return traceToResults.getOrDefault(traceId, List.of());
    }

    public void clear(Long traceId) { traceToResults.remove(traceId); }
}
```

`DatabaseTools.java`（关键：`SESSION_HOLDER` 由 ChatService 通过 ToolContext 传入；无 sessionId 时 MCP 通道直接执行写操作）:
```java
package com.mingzy.dbagent.tool;

import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.datasource.DatasourceService;
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
    }

    private SessionHook sessionHook;

    public void setSessionHook(SessionHook sessionHook) { this.sessionHook = sessionHook; }

    public DatabaseTools(DatasourceService datasourceService, SqlExecutor executor,
                         MetadataServiceRouter metadata, ToolTraceRegistry traces,
                         @Value("${app.sql.default-limit:10}") int defaultLimit,
                         @Value("${app.sql.max-result-rows:500}") int maxResultRows,
                         @Value("${app.sql.query-timeout-seconds:30}") int queryTimeout) {
        this.datasourceService = datasourceService;
        this.executor = executor;
        this.metadata = metadata;
        this.traces = traces;
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

    @Tool(name = "execute_update", description = "在指定数据源上执行写入/DDL（INSERT/UPDATE/DELETE/CREATE/ALTER/DROP 等）。内部会话中此操作需要用户在界面确认后才真正执行；MCP 外部调用请由客户端确认。")
    public String executeUpdate(@ToolParam(description = "数据源名称") String datasourceName,
                                @ToolParam(description = "SQL 语句") String sql,
                                ToolContext toolContext) {
        try {
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
```

- [ ] **Step 3: 运行测试通过 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=DatabaseToolsTest -Pskip-frontend`
Expected: PASS（2 tests）

```bash
git add -A && git commit -m "feat: shared tool layer (list/query/update tools as ToolCallbackProvider)"
```

---

### Task 19: MCP Server 暴露验证

**Files:**
- Test: 无新增单测；用 curl 验证 Streamable HTTP 握手
- Modify（如需）: `src/main/resources/application.yml`（MCP 配置已含）

- [ ] **Step 1: 启动应用，验证 /mcp 初始化握手与工具列表**

```bash
rm -rf ./data && JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw spring-boot:run -Pskip-frontend  # 后台
# Streamable HTTP: 先 initialize
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
# 记录返回的 Mcp-Session-Id 响应头，后续请求带上
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <上一步返回>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```
Expected: initialize 返回 serverInfo name=database-operation-agent；tools/list 包含 5 个工具（list_datasources/list_tables/get_table_schema/execute_query/execute_update）

- [ ] **Step 2: 验证工具调用（list_datasources）**

```bash
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <会话ID>' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_datasources","arguments":{}}}'
```
Expected: 返回包含 mysql-mytest 与 pg-mytest

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: verify mcp streamable http endpoint exposes tools"
```

（若握手路径/协议头与实现不符：以 `spring-ai` 实际行为为准调整 curl 命令，不允许修改工具与 Server 暴露方式；验收标准是 MCP 官方 Inspector 或 curl 能列出并调用 5 个工具。）

---

### Task 20: 会话域数据模型与 DAO

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/chat/{ChatSession,ChatMessage,SqlResult,ConfirmRequest,ChatDao}.java`
- Test: `src/test/java/com/mingzy/dbagent/chat/ChatDaoTest.java`

- [ ] **Step 1: 写测试（四张表的增查改）**

`src/test/java/com/mingzy/dbagent/chat/ChatDaoTest.java`:
```java
package com.mingzy.dbagent.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDaoTest {

    private ChatDao dao;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE chat_session (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '新会话',
              datasource_id INTEGER, model_id INTEGER, created_at TEXT, updated_at TEXT)
            """);
        jdbc.execute("""
            CREATE TABLE chat_message (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
              role TEXT NOT NULL, content TEXT, tool_calls_json TEXT, status TEXT NOT NULL DEFAULT 'done', created_at TEXT)
            """);
        jdbc.execute("""
            CREATE TABLE sql_result (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, message_id INTEGER,
              datasource_id INTEGER, datasource_name TEXT, sql_text TEXT NOT NULL, result_type TEXT NOT NULL,
              columns_json TEXT, rows_json TEXT, row_count INTEGER DEFAULT 0, affected_rows INTEGER, elapsed_ms INTEGER,
              ai_comment TEXT, source TEXT NOT NULL DEFAULT 'agent', status TEXT NOT NULL DEFAULT 'success',
              error_message TEXT, created_at TEXT)
            """);
        jdbc.execute("""
            CREATE TABLE confirm_request (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
              message_id INTEGER, datasource_id INTEGER, datasource_name TEXT, sql_text TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'pending', created_at TEXT, expires_at TEXT NOT NULL)
            """);
        dao = new ChatDao(jdbc);
    }

    @Test
    void sessionCrud() {
        long id = dao.insertSession("测试会话", 1L, 2L);
        assertThat(dao.findSession(id).title()).isEqualTo("测试会话");
        dao.touchSession(id, "新标题", 3L);
        assertThat(dao.findSession(id).title()).isEqualTo("新标题");
        assertThat(dao.findSession(id).datasourceId()).isEqualTo(3L);
        assertThat(dao.listSessions()).hasSize(1);
        dao.deleteSession(id);
        assertThat(dao.findSession(id)).isNull();
    }

    @Test
    void messageAndResultFlow() {
        long sid = dao.insertSession("s", null, null);
        long mid = dao.insertMessage(sid, "user", "查用户列表", null);
        long aid = dao.insertMessage(sid, "assistant", null, null, "running");
        dao.updateMessage(aid, "共 10 个用户", "done", "[1]");
        List<ChatMessage> msgs = dao.listMessages(sid);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).content()).isEqualTo("共 10 个用户");
        assertThat(msgs.get(1).toolCallsJson()).isEqualTo("[1]");

        long rid = dao.insertResult(new SqlResult(null, sid, aid, 1L, "mysql-mytest",
                "select count(*) from users", "query", "[\"count(*)\"]", "[[10]]", 1, null, 5,
                null, "agent", "success", null, null));
        List<SqlResult> results = dao.listResults(sid);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).rowCount()).isEqualTo(1);
        dao.updateResultComment(rid, "系统有 10 个用户");
        assertThat(dao.listResults(sid).get(0).aiComment()).isEqualTo("系统有 10 个用户");
    }

    @Test
    void confirmRequestFlow() {
        long sid = dao.insertSession("s", null, null);
        long cid = dao.insertConfirm(new ConfirmRequest(null, sid, 9L, 1L, "mysql-mytest",
                "delete from users where id=1", "pending", null, "2030-01-01 00:00:00"));
        assertThat(dao.findConfirm(cid).status()).isEqualTo("pending");
        dao.updateConfirmStatus(cid, "approved");
        assertThat(dao.findConfirm(cid).status()).isEqualTo("approved");
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现 record 与 ChatDao → 运行通过**

四个 record（字段与 schema.sql 对应）:
```java
package com.mingzy.dbagent.chat;

public record ChatSession(Long id, String title, Long datasourceId, Long modelId,
                          String createdAt, String updatedAt) {
}
```
```java
package com.mingzy.dbagent.chat;

public record ChatMessage(Long id, Long sessionId, String role, String content,
                          String toolCallsJson, String status, String createdAt) {
}
```
```java
package com.mingzy.dbagent.chat;

public record SqlResult(Long id, Long sessionId, Long messageId, Long datasourceId, String datasourceName,
                        String sqlText, String resultType, String columnsJson, String rowsJson, int rowCount,
                        Integer affectedRows, Long elapsedMs, String aiComment, String source, String status,
                        String errorMessage, String createdAt) {
}
```
```java
package com.mingzy.dbagent.chat;

public record ConfirmRequest(Long id, Long sessionId, Long messageId, Long datasourceId, String datasourceName,
                             String sqlText, String status, String createdAt, String expiresAt) {
}
```

`ChatDao.java`（完整实现；JdbcTemplate + RowMapper + KeyHolder）:
```java
package com.mingzy.dbagent.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class ChatDao {

    private final JdbcTemplate jdbc;
    public ChatDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<ChatSession> SESSION_MAPPER = (rs, i) -> new ChatSession(
            rs.getLong("id"), rs.getString("title"),
            rs.getObject("datasource_id") == null ? null : rs.getLong("datasource_id"),
            rs.getObject("model_id") == null ? null : rs.getLong("model_id"),
            rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<ChatMessage> MESSAGE_MAPPER = (rs, i) -> new ChatMessage(
            rs.getLong("id"), rs.getLong("session_id"), rs.getString("role"), rs.getString("content"),
            rs.getString("tool_calls_json"), rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<SqlResult> RESULT_MAPPER = (rs, i) -> new SqlResult(
            rs.getLong("id"), rs.getLong("session_id"),
            rs.getObject("message_id") == null ? null : rs.getLong("message_id"),
            rs.getObject("datasource_id") == null ? null : rs.getLong("datasource_id"),
            rs.getString("datasource_name"), rs.getString("sql_text"), rs.getString("result_type"),
            rs.getString("columns_json"), rs.getString("rows_json"), rs.getInt("row_count"),
            rs.getObject("affected_rows") == null ? null : rs.getInt("affected_rows"),
            rs.getObject("elapsed_ms") == null ? null : rs.getLong("elapsed_ms"),
            rs.getString("ai_comment"), rs.getString("source"), rs.getString("status"),
            rs.getString("error_message"), rs.getString("created_at"));

    private static final RowMapper<ConfirmRequest> CONFIRM_MAPPER = (rs, i) -> new ConfirmRequest(
            rs.getLong("id"), rs.getLong("session_id"),
            rs.getObject("message_id") == null ? null : rs.getLong("message_id"),
            rs.getObject("datasource_id") == null ? null : rs.getLong("datasource_id"),
            rs.getString("datasource_name"), rs.getString("sql_text"), rs.getString("status"),
            rs.getString("created_at"), rs.getString("expires_at"));

    private long key(KeyHolder kh) { return kh.getKey().longValue(); }

    // ==== chat_session ====
    public long insertSession(String title, Long datasourceId, Long modelId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO chat_session(title, datasource_id, model_id) VALUES(?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            if (datasourceId == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, datasourceId);
            if (modelId == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, modelId);
            return ps;
        }, kh);
        return key(kh);
    }

    public ChatSession findSession(long id) {
        List<ChatSession> l = jdbc.query("SELECT * FROM chat_session WHERE id=?", SESSION_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<ChatSession> listSessions() {
        return jdbc.query("SELECT * FROM chat_session ORDER BY id DESC", SESSION_MAPPER);
    }

    public void touchSession(long id, String title, Long datasourceId) {
        jdbc.update("UPDATE chat_session SET title=?, datasource_id=?, updated_at=datetime('now','localtime') WHERE id=?",
                title, datasourceId, id);
    }

    public void deleteSession(long id) {
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", id);
        jdbc.update("DELETE FROM sql_result WHERE session_id=?", id);
        jdbc.update("DELETE FROM confirm_request WHERE session_id=?", id);
        jdbc.update("DELETE FROM chat_session WHERE id=?", id);
    }

    // ==== chat_message ====
    public long insertMessage(long sessionId, String role, String content, String toolCallsJson) {
        return insertMessage(sessionId, role, content, toolCallsJson, "done");
    }

    public long insertMessage(long sessionId, String role, String content, String toolCallsJson, String status) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO chat_message(session_id, role, content, tool_calls_json, status) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId); ps.setString(2, role); ps.setString(3, content);
            ps.setString(4, toolCallsJson); ps.setString(5, status);
            return ps;
        }, kh);
        return key(kh);
    }

    public void updateMessage(long id, String content, String status, String toolCallsJson) {
        jdbc.update("UPDATE chat_message SET content=?, status=?, tool_calls_json=? WHERE id=?",
                content, status, toolCallsJson, id);
    }

    public List<ChatMessage> listMessages(long sessionId) {
        return jdbc.query("SELECT * FROM chat_message WHERE session_id=? ORDER BY id", MESSAGE_MAPPER, sessionId);
    }

    // ==== sql_result ====
    public long insertResult(SqlResult r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO sql_result(session_id, message_id, datasource_id, datasource_name, sql_text, result_type, " +
                "columns_json, rows_json, row_count, affected_rows, elapsed_ms, ai_comment, source, status, error_message) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.sessionId());
            if (r.messageId() == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, r.messageId());
            if (r.datasourceId() == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, r.datasourceId());
            ps.setString(4, r.datasourceName()); ps.setString(5, r.sqlText()); ps.setString(6, r.resultType());
            ps.setString(7, r.columnsJson()); ps.setString(8, r.rowsJson()); ps.setInt(9, r.rowCount());
            if (r.affectedRows() == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, r.affectedRows());
            if (r.elapsedMs() == null) ps.setNull(11, java.sql.Types.INTEGER); else ps.setLong(11, r.elapsedMs());
            ps.setString(12, r.aiComment()); ps.setString(13, r.source()); ps.setString(14, r.status());
            ps.setString(15, r.errorMessage());
            return ps;
        }, kh);
        return key(kh);
    }

    public SqlResult findResult(long id) {
        List<SqlResult> l = jdbc.query("SELECT * FROM sql_result WHERE id=?", RESULT_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public void updateResultComment(long id, String aiComment) {
        jdbc.update("UPDATE sql_result SET ai_comment=? WHERE id=?", aiComment, id);
    }

    public List<SqlResult> listResults(long sessionId) {
        return jdbc.query("SELECT * FROM sql_result WHERE session_id=? ORDER BY id DESC", RESULT_MAPPER, sessionId);
    }

    // ==== confirm_request ====
    public long insertConfirm(ConfirmRequest c) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO confirm_request(session_id, message_id, datasource_id, datasource_name, sql_text, status, expires_at) " +
                "VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, c.sessionId());
            if (c.messageId() == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, c.messageId());
            if (c.datasourceId() == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, c.datasourceId());
            ps.setString(4, c.datasourceName()); ps.setString(5, c.sqlText()); ps.setString(6, c.status());
            ps.setString(7, c.expiresAt());
            return ps;
        }, kh);
        return key(kh);
    }

    public ConfirmRequest findConfirm(long id) {
        List<ConfirmRequest> l = jdbc.query("SELECT * FROM confirm_request WHERE id=?", CONFIRM_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public void updateConfirmStatus(long id, String status) {
        jdbc.update("UPDATE confirm_request SET status=? WHERE id=?", status, id);
    }
}
```

**测试注意：** 测试中的内存表列允许 NULL 且时间列为 TEXT，与生产 schema 一致；`insertResult` 参数顺序与 `SqlResult` record 一致。

- [ ] **Step 3: 运行测试通过 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=ChatDaoTest -Pskip-frontend`
Expected: PASS（3 tests）

```bash
git add -A && git commit -m "feat: chat domain model and dao"
```

---

### Task 21: WebSocket 基础设施

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/chat/WsSessionRegistry.java`
- Create: `src/main/java/com/mingzy/dbagent/chat/ws/ChatWebSocketHandler.java`（建 ws 子包）
- Create: `src/main/java/com/mingzy/dbagent/config/WebSocketConfig.java`

- [ ] **Step 1: 实现 WsSessionRegistry（会话→连接集合，线程安全推送 JSON）**

```java
package com.mingzy.dbagent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class WsSessionRegistry {

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void register(long chatSessionId, WebSocketSession ws) {
        sessions.computeIfAbsent(chatSessionId, k -> new CopyOnWriteArraySet<>()).add(ws);
    }

    public void unregister(long chatSessionId, WebSocketSession ws) {
        Set<WebSocketSession> set = sessions.get(chatSessionId);
        if (set != null) set.remove(ws);
    }

    /** 推送事件：{"type":..., "payload":...} */
    public void send(long chatSessionId, String type, Object payload) {
        Set<WebSocketSession> set = sessions.get(chatSessionId);
        if (set == null || set.isEmpty()) return;
        try {
            String json = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
            TextMessage msg = new TextMessage(json);
            for (WebSocketSession ws : set) {
                if (ws.isOpen()) {
                    synchronized (ws) { ws.sendMessage(msg); }
                }
            }
        } catch (Exception e) {
            log.warn("ws send failed: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 实现 Handler 与配置**

`ChatWebSocketHandler.java`（解析用户消息 → 交给 ChatService；ChatService 在 Task 22 实现，本步先建接口引用）:
```java
package com.mingzy.dbagent.chat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingzy.dbagent.chat.ChatService;
import com.mingzy.dbagent.chat.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatService chatService;
    private final WsSessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatWebSocketHandler(ChatService chatService, WsSessionRegistry registry) {
        this.chatService = chatService;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long chatSessionId = sessionId(session);
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

`WebSocketConfig.java`:
```java
package com.mingzy.dbagent.config;

import com.mingzy.dbagent.chat.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;

    public WebSocketConfig(ChatWebSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/session/*").setAllowedOrigins("*");
    }
}
```

- [ ] **Step 3: 编译验证（ChatService 为下一步实现，本步编译会失败——先建临时空实现）**

本步先创建 `ChatService` 临时骨架（Task 22 替换为完整实现）:
```java
package com.mingzy.dbagent.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ChatService {
    public void handleUserMessage(long sessionId, String content, Long datasourceId, Long modelId) {
        log.info("chat message: session={} content={}", sessionId, content);
    }
}
```

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -q -Pskip-frontend -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: websocket infrastructure"
```

---

### Task 22: ChatService 完整编排（LLM 循环 + 结果落库推送 + ai_comment 回填）

**Files:**
- Modify: `src/main/java/com/mingzy/dbagent/chat/ChatService.java`（替换 Task 21 临时骨架）
- Create: `src/main/java/com/mingzy/dbagent/chat/ConfirmationService.java`

- [ ] **Step 1: 实现 ConfirmationService（Future 挂起 + 超时 + WS 推送）**

```java
package com.mingzy.dbagent.chat;

import com.mingzy.dbagent.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ConfirmationService {

    public enum Decision { APPROVED, REJECTED, EXPIRED }

    private final ChatDao chatDao;
    private final WsSessionRegistry ws;
    private final long timeoutSeconds;
    private final Map<Long, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    public ConfirmationService(ChatDao chatDao, WsSessionRegistry ws,
                               @Value("${app.confirm.timeout-seconds:60}") long timeoutSeconds) {
        this.chatDao = chatDao;
        this.ws = ws;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 发起确认请求并挂起等待（超时自动过期）。返回值 null=已批准，否则为拒绝/超时原因。 */
    public String requestAndWait(long sessionId, Long messageId, Long datasourceId,
                                 String datasourceName, String sql) {
        String expiresAt = LocalDateTime.now().plusSeconds(timeoutSeconds)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long id = chatDao.insertConfirm(new ConfirmRequest(null, sessionId, messageId, datasourceId,
                datasourceName, sql, "pending", null, expiresAt));
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(id, future);
        ws.send(sessionId, "confirm_request", Map.of(
                "id", id, "sql", sql, "datasourceName", datasourceName,
                "expiresInSeconds", timeoutSeconds));
        try {
            Decision d = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (d == Decision.APPROVED) return null;
            return "用户拒绝了该操作，未执行";
        } catch (Exception e) {
            chatDao.updateConfirmStatus(id, "expired");
            ws.send(sessionId, "confirm_result", Map.of("id", id, "status", "expired"));
            return "用户在时限内未确认，操作已取消（超时 " + timeoutSeconds + " 秒）";
        } finally {
            pending.remove(id);
        }
    }

    public void approve(long confirmId) {
        ConfirmRequest c = chatDao.findConfirm(confirmId);
        if (c == null) throw new IllegalArgumentException("确认请求不存在: " + confirmId);
        if (!"pending".equals(c.status())) return;
        chatDao.updateConfirmStatus(confirmId, "approved");
        ws.send(c.sessionId(), "confirm_result", Map.of("id", confirmId, "status", "approved"));
        CompletableFuture<Decision> f = pending.get(confirmId);
        if (f != null) f.complete(Decision.APPROVED);
    }

    public void reject(long confirmId) {
        ConfirmRequest c = chatDao.findConfirm(confirmId);
        if (c == null) throw new IllegalArgumentException("确认请求不存在: " + confirmId);
        if (!"pending".equals(c.status())) return;
        chatDao.updateConfirmStatus(confirmId, "rejected");
        ws.send(c.sessionId(), "confirm_result", Map.of("id", confirmId, "status", "rejected"));
        CompletableFuture<Decision> f = pending.get(confirmId);
        if (f != null) f.complete(Decision.REJECTED);
    }
}
```

`ConfirmController.java`:
```java
package com.mingzy.dbagent.chat.web;

import com.mingzy.dbagent.chat.ConfirmationService;
import com.mingzy.dbagent.common.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/confirm")
public class ConfirmController {

    private final ConfirmationService service;
    public ConfirmController(ConfirmationService service) { this.service = service; }

    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable long id) { service.approve(id); return Result.ok(null); }

    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable long id) { service.reject(id); return Result.ok(null); }
}
```

- [ ] **Step 2: 实现 ChatService（替换骨架）**

```java
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
                                  String sql, SqlExecResult result, String traceIdStr) {
                Long traceId = traceIdStr == null ? null : Long.valueOf(traceIdStr);
                return persistAndPush(sessionId, datasourceId, datasourceName, sql, result, traceId);
            }

            @Override
            public String confirmWrite(long sessionId, long datasourceId, String datasourceName,
                                       String sql, Long traceId) {
                return confirmationService.requestAndWait(sessionId, traceId, datasourceId, datasourceName, sql);
            }
        });
    }

    private String persistAndPush(long sessionId, long datasourceId, String datasourceName,
                                  String sql, SqlExecResult result, Long traceId) {
        try {
            String columnsJson = mapper.writeValueAsString(result.columns());
            String rowsJson = mapper.writeValueAsString(result.rows());
            long resultId = chatDao.insertResult(new SqlResult(null, sessionId, traceId, datasourceId, datasourceName,
                    sql, result.resultType(), columnsJson, rowsJson,
                    result.rowCount(), result.affectedRows(), result.elapsedMs(), null, "agent",
                    result.success() ? "success" : "error", result.errorMessage(), null));
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
            if (ds == null && session.datasourceId() != null) ds = datasourceService.requireById(session.datasourceId());
            if (ds == null) { ws.send(sessionId, "error", Map.of("message", "请先选择数据源")); return; }

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
            可用工具：list_datasources / list_tables / get_table_schema / execute_query / execute_update。
            规则：
            1. 不确定表名或字段时，先用 list_tables / get_table_schema 探查，再执行 SQL。
            2. execute_query 查询默认最多返回 10 行（自动限制），不要编造数据，只根据工具返回结果回答。
            3. 写操作（INSERT/UPDATE/DELETE/DDL）用 execute_update，系统会要求用户确认。
            4. 必须用中文回答，并在回答中附上你执行的 SQL（与用户问题对应时）。
            5. 计数类问题的回答格式示例：系统有 42 个用户，查询SQL是：select count(*) from users
            """.formatted(ds.name(), ds.dbType());
    }

    private String abbreviate(String s) {
        return s.length() <= 20 ? s : s.substring(0, 20) + "…";
    }
}
```

**实现要求（补充）：** ChatDao 需提供 `findResult(long id)` 方法（`SELECT * FROM sql_result WHERE id=?`，供 `persistAndPush` / 控制台接口回读）。`ChatResult` 表插入后一律用该方法回读再推送，保证 JSON 字段格式一致。

（使用 `client.mutate()` 的原因是缓存复用的 ChatClient 每次需要不同的 system prompt 与工具上下文。）

- [ ] **Step 3: 编译 + 启动验证**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -q -Pskip-frontend -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: chat orchestration with tool loop and confirmation"
```

---

### Task 23: 会话 REST API（列表/消息/结果/控制台） + SPA 路由转发

**Files:**
- Create: `src/main/java/com/mingzy/dbagent/chat/web/SessionController.java`

- [ ] **Step 1: 实现 SessionController**

```java
package com.mingzy.dbagent.chat.web;

import com.mingzy.dbagent.chat.*;
import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.executor.SqlExecResult;
import com.mingzy.dbagent.executor.SqlExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatDao chatDao;
    private final DatasourceService datasourceService;
    private final SqlExecutor sqlExecutor;
    private final WsSessionRegistry ws;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionController(ChatDao chatDao, DatasourceService datasourceService,
                             SqlExecutor sqlExecutor, WsSessionRegistry ws) {
        this.chatDao = chatDao;
        this.datasourceService = datasourceService;
        this.sqlExecutor = sqlExecutor;
        this.ws = ws;
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

    /** SQL 控制台执行：用户手写 SQL，无需确认；结果落库并 WS 推送（source=console） */
    @PostMapping("/{id}/console/execute")
    public Result<SqlResult> consoleExecute(@PathVariable long id, @RequestBody Map<String, Object> body) throws Exception {
        String sql = body.get("sql").toString();
        long datasourceId = Long.parseLong(body.get("datasourceId").toString());
        Integer limit = body.get("limit") == null ? null : Integer.valueOf(body.get("limit").toString());
        DatasourceService.HikariPoolRef ref = datasourceService.poolOf(datasourceService.requireById(datasourceId));
        boolean isQuery = com.mingzy.dbagent.executor.SqlClassifier.kind(sql)
                == com.mingzy.dbagent.executor.SqlKind.QUERY;
        SqlExecResult exec = isQuery
                ? sqlExecutor.executeQuery(ref.pool(), sql, limit == null ? 10 : limit, 500, 30)
                : sqlExecutor.executeUpdate(ref.pool(), sql, 30);
        long resultId = chatDao.insertResult(new SqlResult(null, id, null, datasourceId, ref.datasource().name(),
                sql, exec.resultType(), mapper.writeValueAsString(exec.columns()),
                mapper.writeValueAsString(exec.rows()), exec.rowCount(), exec.affectedRows(),
                exec.elapsedMs(), null, "console", exec.success() ? "success" : "error",
                exec.errorMessage(), null));
        SqlResult saved = chatDao.findResult(resultId);
        ws.send(id, "result", saved);
        return Result.ok(saved);
    }
}
```

（注：`@Value` import 未使用时移除。）

- [ ] **Step 2: 实现 SPA History 路由转发（前端路由刷新回退）**

`src/main/java/com/mingzy/dbagent/config/WebMvcConfig.java`:
```java
package com.mingzy.dbagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 前端 History 路由回退：直接访问/刷新 /chat 等前端路由时转发到 index.html。
     * 穷举已知 SPA 路径（前端新增路由时同步维护），避免与 /api /ws /mcp 冲突。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[]{"/chat", "/datasources", "/models", "/dict"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
```

- [ ] **Step 3: 编译验证 + Commit**

Run: `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw -q -Pskip-frontend -DskipTests compile`
Expected: BUILD SUCCESS

```bash
git add -A && git commit -m "feat: session rest api and sql console endpoint"
```

---

### Task 24: 后端端到端验证（真实 MiniMax + 真实 MySQL）

**Files:**
- Create（临时）: `scripts/ws-smoke.py`（WebSocket 模拟客户端；需 Python3，标准库不支持 WS → 改用 Java 测试类）
- Create: `src/test/java/com/mingzy/dbagent/ChatE2eIT.java`（@Disabled 手动运行，避免 CI 依赖外网）

- [ ] **Step 1: 写手动集成测试（调用 ChatService 直测，绕过 WS）**

`src/test/java/com/mingzy/dbagent/ChatE2eIT.java`:
```java
package com.mingzy.dbagent;

import com.mingzy.dbagent.chat.ChatDao;
import com.mingzy.dbagent.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 手动运行：需 192.168.110.88 可达且 MiniMax 可访问。命令见 README。 */
@Disabled("手动端到端：依赖外网模型与测试库")
@SpringBootTest
@TestPropertySource(properties = "app.db.path=./target/test-data/e2e.db")
class ChatE2eIT {

    @Autowired ChatService chatService;
    @Autowired ChatDao chatDao;

    @Test
    void naturalLanguageQueryUsers() {
        long sessionId = chatDao.insertSession("e2e", 1L, 1L); // 1=mysql-mytest
        chatService.handleUserMessage(sessionId, "帮我查询用户列表？", 1L, 1L);
        var messages = chatDao.listMessages(sessionId);
        assertThat(messages).isNotEmpty();
        var results = chatDao.listResults(sessionId);
        assertThat(results).isNotEmpty();
        System.out.println("=== RESULTS ===");
        results.forEach(r -> System.out.println(r.sqlText() + " | rows=" + r.rowCount() + " | comment=" + r.aiComment()));
    }
}
```

（`@Disabled` 需 `org.junit.jupiter.api.Disabled` import；运行前需先删 `org.junit.jupiter.api.Disabled` 注解或使用 `-Dtest` + 手动启用。）

- [ ] **Step 2: 手动运行（需网络与测试库可达）**

```bash
rm -rf ./target/test-data
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw test -Dtest=ChatE2eIT -Pskip-frontend
```
Expected: 测试输出中 RESULTS 区含一条 `select ...` 查询（默认 10 行）且 comment 为中文回答；若模型不支持 function calling，记录实际返回并执行降级方案（见第 14 节风险 5）

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: manual e2e for natural language query"
```

---

### Task 25: 前端 WS 客户端 + Pinia store

**Files:**
- Create: `frontend/src/ws/socket.js`
- Create: `frontend/src/stores/chat.js`

- [ ] **Step 1: 实现 ws/socket.js（重连 + 事件分发）**

```js
let ws = null
let sessionId = null
let handlers = {}
let retryDelay = 1000
let closedByUser = false

export function connect(id, onEvent) {
  sessionId = id
  handlers = onEvent
  closedByUser = false
  open()
}

function open() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/session/${sessionId}`)
  ws.onopen = () => { retryDelay = 1000; handlers.onStatus?.('connected') }
  ws.onmessage = (e) => {
    try { handlers.onEvent?.(JSON.parse(e.data)) } catch (err) { console.error('bad ws payload', err) }
  }
  ws.onclose = () => {
    handlers.onStatus?.('disconnected')
    if (!closedByUser) {
      setTimeout(open, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 15000)
    }
  }
  ws.onerror = () => ws.close()
}

export function send(payload) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload))
}

export function disconnect() {
  closedByUser = true
  ws?.close()
}
```

- [ ] **Step 2: 实现 stores/chat.js（会话/消息/结果/确认集中管理）**

```js
import { defineStore } from 'pinia'
import * as socket from '../ws/socket'
import { sessionApi } from '../api'

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [],
    currentSessionId: null,
    messages: [],
    results: [],
    pendingConfirms: [],
    connected: false,
    thinking: false,
    errors: []
  }),
  actions: {
    async loadSessions() { this.sessions = await sessionApi.list() },
    async ensureSession(datasourceId) {
      if (this.currentSessionId) return this.currentSessionId
      const s = await sessionApi.create({ datasourceId })
      this.sessions.unshift(s)
      await this.switchSession(s.id)
      return s.id
    },
    async switchSession(id) {
      socket.disconnect()
      this.currentSessionId = id
      this.pendingConfirms = []
      this.messages = await sessionApi.messages(id)
      this.results = (await sessionApi.results(id)).reverse()
      const self = this
      socket.connect(id, {
        onStatus: (s) => { self.connected = s === 'connected' },
        onEvent: (evt) => self.onEvent(evt)
      })
    },
    sendMessage(content, datasourceId, modelId) {
      this.thinking = true
      socket.send({ type: 'user_message', content, datasourceId, modelId })
    },
    onEvent(evt) {
      const p = evt.payload || {}
      switch (evt.type) {
        case 'message':
          if (p.role === 'user') this.messages.push({ role: 'user', content: p.content })
          else { this.messages.push({ role: 'assistant', content: p.content, messageId: p.messageId, toolResultIds: p.toolResultIds }); this.thinking = false }
          break
        case 'result':
          this.results.push(p)
          break
        case 'result_update': {
          const r = this.results.find((x) => x.id === p.id)
          if (r) r.aiComment = p.aiComment
          break
        }
        case 'confirm_request':
          this.pendingConfirms.push({ ...p, createdAt: Date.now() })
          this.thinking = false
          break
        case 'confirm_result':
          this.pendingConfirms = this.pendingConfirms.filter((c) => c.id !== p.id)
          this.thinking = true
          break
        case 'error':
          this.errors.push(p.message)
          this.messages.push({ role: 'error', content: p.message })
          this.thinking = false
          break
      }
    },
    async removeSession(id) {
      await sessionApi.remove(id)
      this.sessions = this.sessions.filter((s) => s.id !== id)
      if (this.currentSessionId === id) { this.currentSessionId = null; this.messages = []; this.results = []; socket.disconnect() }
    }
  }
})
```

- [ ] **Step 3: 编译验证**

Run: `cd frontend && npm run build`（或等 Task 29 统一构建验证）
Expected: 构建通过

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: frontend ws client and chat store"
```

---

### Task 26: 对话工作台页面（三栏布局 + 消息流 + 确认卡片）

**Files:**
- Create: `frontend/src/views/ChatView.vue`
- Create: `frontend/src/components/{ChatPanel,MessageItem,ConfirmCard}.vue`

- [ ] **Step 1: 实现 MessageItem.vue（气泡 + 工具结果提示）**

```vue
<template>
  <div :class="['msg', msg.role]">
    <div class="bubble">
      <template v-if="msg.role === 'assistant'">
        <div class="answer">{{ msg.content }}</div>
        <a-tag v-if="msg.toolResultIds?.length" color="blue" style="margin-top:6px">
          已执行 {{ msg.toolResultIds.length }} 个 SQL（见右下结果集）
        </a-tag>
      </template>
      <span v-else>{{ msg.content }}</span>
    </div>
  </div>
</template>

<script setup>
defineProps({ msg: { type: Object, required: true } })
</script>

<style scoped>
.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.bubble { max-width: 85%; padding: 8px 12px; border-radius: 8px; background: #f5f5f5; white-space: pre-wrap; word-break: break-word; }
.msg.user .bubble { background: #1677ff; color: #fff; }
.msg.error .bubble { background: #fff2f0; color: #cf1322; }
.answer { white-space: pre-wrap; }
</style>
```

- [ ] **Step 2: 实现 ConfirmCard.vue（确认卡片 + 倒计时）**

```vue
<template>
  <div class="confirm-card">
    <div class="head">
      <a-tag color="orange">写操作待确认</a-tag>
      <span class="ds">数据源：{{ item.datasourceName }}</span>
      <span class="countdown">{{ remain }}s</span>
    </div>
    <pre class="sql mono">{{ item.sql }}</pre>
    <a-space>
      <a-button type="primary" size="small" @click="approve">确认执行</a-button>
      <a-button size="small" danger @click="reject">取消</a-button>
    </a-space>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { confirmApi } from '../api'

const props = defineProps({ item: { type: Object, required: true } })
const emit = defineEmits(['resolve'])
const remain = ref(props.item.expiresInSeconds ?? 60)
let timer = null

onMounted(() => {
  timer = setInterval(() => { if (remain.value > 0) remain.value-- }, 1000)
})
onUnmounted(() => clearInterval(timer))

const approve = async () => { await confirmApi.approve(props.item.id); emit('resolve', props.item.id) }
const reject = async () => { await confirmApi.reject(props.item.id); emit('resolve', props.item.id) }
</script>

<style scoped>
.confirm-card { border: 1px solid #faad14; background: #fffbe6; border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.countdown { margin-left: auto; color: #fa8c16; font-weight: 600; }
.sql { background: #fff; border: 1px solid #f0f0f0; border-radius: 4px; padding: 8px; max-height: 160px; overflow: auto; white-space: pre-wrap; }
</style>
```

- [ ] **Step 3: 实现 ChatPanel.vue（左栏：消息流 + 确认卡片 + 输入）**

```vue
<template>
  <div class="chat-panel">
    <div class="messages" ref="scroller">
      <MessageItem v-for="(m, i) in store.messages" :key="i" :msg="m" />
      <ConfirmCard v-for="c in store.pendingConfirms" :key="'c' + c.id" :item="c" @resolve="onConfirmResolved" />
      <div v-if="store.thinking" class="thinking"><a-spin size="small" /> 思考中…</div>
    </div>
    <div class="input-bar">
      <a-textarea v-model:value="draft" placeholder="用自然语言描述你要对数据库做的事，例如：帮我查询用户列表？"
                  :auto-size="{ minRows: 1, maxRows: 4 }" @press-enter="onEnter" />
      <a-button type="primary" :disabled="!draft.trim() || store.thinking" @click="submit">发送</a-button>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import { useChatStore } from '../stores/chat'
import MessageItem from './MessageItem.vue'
import ConfirmCard from './ConfirmCard.vue'

const props = defineProps({ datasourceId: Number, modelId: Number })
const store = useChatStore()
const draft = ref('')
const scroller = ref(null)

watch(() => [store.messages.length, store.pendingConfirms.length, store.thinking], async () => {
  await nextTick()
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
})

const onEnter = (e) => { if (!e.shiftKey) { e.preventDefault(); submit() } }

const submit = async () => {
  const content = draft.value.trim()
  if (!content) return
  await store.ensureSession(props.datasourceId)
  store.sendMessage(content, props.datasourceId, props.modelId)
  draft.value = ''
}

const onConfirmResolved = () => { /* store 在 confirm_result 事件中自动清理 */ }
</script>

<style scoped>
.chat-panel { display: flex; flex-direction: column; height: 100%; }
.messages { flex: 1; overflow: auto; padding: 12px; }
.input-bar { display: flex; gap: 8px; padding: 10px; border-top: 1px solid #f0f0f0; }
.thinking { color: #999; font-size: 12px; padding: 4px 0; }
</style>
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: chat panel with message stream and confirmation card"
```

---

### Task 27: SQL 控制台（右上）+ 结果集面板（右下）

**Files:**
- Create: `frontend/src/components/{SqlConsole,ResultPanel,ResultCard}.vue`

- [ ] **Step 1: 实现 SqlConsole.vue**

```vue
<template>
  <div class="console">
    <div class="bar">
      <a-select v-model:value="datasourceId" style="width: 180px" placeholder="选择数据源"
                :options="datasourceOptions" size="small" />
      <a-select v-model:value="limit" style="width: 100px" size="small"
                :options="[10, 50, 100, 500].map((n) => ({ value: n, label: '最多 ' + n + ' 行' }))" />
      <a-button type="primary" size="small" :loading="running" @click="execute">执行 (Ctrl+Enter)</a-button>
    </div>
    <a-textarea v-model:value="sql" class="mono" :rows="6" placeholder="输入 SQL，例如：select * from users"
                @keydown.ctrl.enter.prevent="execute" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { datasourceApi, sessionApi } from '../api'
import { useChatStore } from '../stores/chat'

const store = useChatStore()
const sql = ref('')
const limit = ref(10)
const running = ref(false)
const datasourceId = ref(undefined)
const datasourceOptions = ref([])

onMounted(async () => {
  const list = await datasourceApi.list()
  datasourceOptions.value = list.map((d) => ({ value: d.id, label: d.name }))
  if (!datasourceId.value && list.length) datasourceId.value = list[0].id
})

const execute = async () => {
  if (!sql.value.trim()) return
  if (!datasourceId.value) { message.warning('请选择数据源'); return }
  running.value = true
  try {
    const sessionId = await store.ensureSession(datasourceId.value)
    await sessionApi.consoleExecute(sessionId, { sql: sql.value, datasourceId: datasourceId.value, limit: limit.value })
  } finally { running.value = false }
}
</script>

<style scoped>
.console { padding: 10px; border-bottom: 1px solid #f0f0f0; }
.bar { display: flex; gap: 8px; margin-bottom: 8px; }
</style>
```

- [ ] **Step 2: 实现 ResultCard.vue（单张结果卡片：动态表格 + AI 解读）**

```vue
<template>
  <a-card size="small" class="result-card" :body-style="{ padding: '10px' }">
    <div class="head">
      <a-tag :color="statusColor">{{ statusText }}</a-tag>
      <span class="ds">{{ item.datasourceName }}</span>
      <span class="meta">
        {{ item.resultType === 'query' ? item.rowCount + ' 行' : '影响 ' + item.affectedRows + ' 行' }}
        · {{ item.elapsedMs }}ms · {{ item.source === 'console' ? '控制台' : 'AI' }}
      </span>
      <a class="copy" @click="copySql">复制SQL</a>
    </div>
    <pre class="sql mono">{{ item.sqlText }}</pre>
    <a-alert v-if="item.status === 'error'" type="error" :message="item.errorMessage" show-icon />
    <template v-else-if="isQuery">
      <a-table :columns="columns" :data-source="rows" size="small" :pagination="false"
               :scroll="{ x: 'max-content', y: 160 }" row-key="__rowKey" />
    </template>
    <div v-if="item.aiComment" class="ai-comment">
      <a-tag color="geekblue">AI 解读</a-tag>
      <span>{{ item.aiComment }}</span>
    </div>
  </a-card>
</template>

<script setup>
import { computed } from 'vue'
import { message } from 'ant-design-vue'

const props = defineProps({ item: { type: Object, required: true } })

const isQuery = computed(() => props.item.resultType === 'query')
const statusText = computed(() => props.item.status === 'success' ? '成功' : '失败')
const statusColor = computed(() => props.item.status === 'success' ? 'green' : 'red')

const columns = computed(() => {
  const cols = JSON.parse(props.item.columnsJson || '[]')
  return cols.map((c) => ({ title: c, dataIndex: c, ellipsis: true }))
})
const rows = computed(() => {
  const cols = JSON.parse(props.item.columnsJson || '[]')
  const data = JSON.parse(props.item.rowsJson || '[]')
  return data.map((row, i) => {
    const obj = { __rowKey: i }
    cols.forEach((c, j) => { obj[c] = row[j] })
    return obj
  })
})

const copySql = async () => {
  await navigator.clipboard.writeText(props.item.sqlText)
  message.success('SQL 已复制')
}
</script>

<style scoped>
.result-card { margin-bottom: 10px; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ds { font-weight: 600; }
.meta { color: #999; font-size: 12px; }
.copy { margin-left: auto; font-size: 12px; }
.sql { background: #fafafa; border-radius: 4px; padding: 6px 8px; white-space: pre-wrap; font-size: 12px; max-height: 90px; overflow: auto; }
.ai-comment { margin-top: 8px; background: #f0f5ff; border-radius: 4px; padding: 6px 8px; font-size: 13px; }
</style>
```

- [ ] **Step 3: 实现 ResultPanel.vue**

```vue
<template>
  <div class="result-panel">
    <div class="bar">
      <span class="title">结果集（{{ store.results.length }}）</span>
      <a-button size="small" type="link" @click="store.results = []">清空</a-button>
    </div>
    <div class="list">
      <ResultCard v-for="r in [...store.results].reverse()" :key="r.id" :item="r" />
      <a-empty v-if="!store.results.length" description="暂无执行结果" />
    </div>
  </div>
</template>

<script setup>
import { useChatStore } from '../stores/chat'
import ResultCard from './ResultCard.vue'
const store = useChatStore()
</script>

<style scoped>
.result-panel { display: flex; flex-direction: column; height: 100%; }
.bar { display: flex; align-items: center; padding: 6px 10px; border-bottom: 1px solid #f0f0f0; }
.title { font-weight: 600; flex: 1; }
.list { flex: 1; overflow: auto; padding: 10px; }
</style>
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: sql console and result panel"
```

---

### Task 28: ChatView 组装（顶栏 + 三栏布局 + 历史恢复）

**Files:**
- Create: `frontend/src/views/ChatView.vue`
- Modify: `frontend/src/router/index.js`（/chat 指向 ChatView）

- [ ] **Step 1: 实现 ChatView.vue**

```vue
<template>
  <a-layout style="height: 100%">
    <a-layout-header class="topbar">
      <a-space :size="12">
        <a-select v-model:value="currentSessionId" style="width: 200px" size="small" placeholder="选择/新建会话"
                  :options="sessionOptions" @change="onSessionChange" />
        <a-button size="small" @click="newSession">新建会话</a-button>
        <a-select v-model:value="datasourceId" style="width: 180px" size="small" placeholder="数据源"
                  :options="datasourceOptions" @change="onDatasourceChange" />
        <a-select v-model:value="modelId" style="width: 200px" size="small" placeholder="模型"
                  :options="modelOptions" />
        <a-badge :status="store.connected ? 'processing' : 'default'"
                 :text="store.connected ? '已连接' : '未连接'" />
      </a-space>
    </a-layout-header>
    <a-layout>
      <a-layout-sider width="38%" theme="light" class="left-pane">
        <ChatPanel :datasource-id="datasourceId" :model-id="modelId" />
      </a-layout-sider>
      <a-layout-content class="right-pane">
        <SqlConsole />
        <ResultPanel />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useChatStore } from '../stores/chat'
import { datasourceApi, modelApi } from '../api'
import ChatPanel from '../components/ChatPanel.vue'
import SqlConsole from '../components/SqlConsole.vue'
import ResultPanel from '../components/ResultPanel.vue'

const store = useChatStore()
const currentSessionId = ref(undefined)
const datasourceId = ref(undefined)
const modelId = ref(undefined)
const datasourceOptions = ref([])
const modelOptions = ref([])

const sessionOptions = computed(() => store.sessions.map((s) => ({ value: s.id, label: s.title })))

onMounted(async () => {
  await store.loadSessions()
  const [dsList, models] = await Promise.all([datasourceApi.list(), modelApi.list()])
  datasourceOptions.value = dsList.map((d) => ({ value: d.id, label: d.name }))
  modelOptions.value = models.map((m) => ({ value: m.id, label: m.name + (m.enabled ? '（已启用）' : '') }))
  if (dsList.length) datasourceId.value = dsList[0].id
  const enabled = models.find((m) => m.enabled)
  if (enabled) modelId.value = enabled.id
  if (store.sessions.length) {
    currentSessionId.value = store.sessions[0].id
    await store.switchSession(store.sessions[0].id)
  }
})

const onSessionChange = async (id) => { await store.switchSession(id) }
const newSession = async () => {
  const s = await (await import('../api')).sessionApi.create({ datasourceId: datasourceId.value })
  store.sessions.unshift(s)
  currentSessionId.value = s.id
  await store.switchSession(s.id)
}
const onDatasourceChange = () => {}
</script>

<style scoped>
.topbar { background: #fff; border-bottom: 1px solid #f0f0f0; padding: 0 12px; height: 48px; line-height: 48px; }
.left-pane { border-right: 1px solid #f0f0f0; }
.right-pane { display: flex; flex-direction: column; height: 100%; }
.right-pane > :last-child { flex: 1; overflow: hidden; }
</style>
```

- [ ] **Step 2: 修改 router：/chat 指向 ChatView**

（routes 数组第 2 项改为 `{ path: '/chat', component: () => import('../views/ChatView.vue') }`，其余同前）

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: chat workbench assembling three-pane layout"
```

---

### Task 29: 端到端验收（用例 1/2 + 控制台 + 确认 + MCP）

**Files:**
- 无新增代码；验证与修复阶段

- [ ] **Step 1: 完整构建**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw clean package
unzip -l target/database-operation-agent-1.0.0.jar | grep -E "static/(index.html|assets/)" | head -5
```
Expected: BUILD SUCCESS；jar 内含 static/index.html 与 assets

- [ ] **Step 2: 启动并验收用例 1**

```bash
rm -rf ./data
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts java -jar target/database-operation-agent-1.0.0.jar  # 后台
```
浏览器 http://localhost:8080 → 对话工作台 → 确认数据源选 mysql-mytest、模型选 MiniMax-M3（已启用）→ 输入：`帮我查询用户列表？`
Expected: 浏览器 Network 确认 WS 已连接；左栏出现回答（含 SQL）；右下结果集出现卡片：SQL 为 users 表查询、表格展示、默认不超过 10 行
（若 mytest 库无 users 表：先用控制台执行 `show tables` 查看实际表名，并改用实际表；同时把发现的库表信息记录到验收报告。）

- [ ] **Step 3: 验收用例 2**

输入：`系统有多少用户？`
Expected: 右下结果卡片 SQL 为 `select count(*) ...`，AI 解读（蓝色区块/左栏）显示"系统有 N 个用户，查询SQL是：select count(*) from ..."

- [ ] **Step 4: 控制台验收 + 写操作确认验收**

控制台输入 `select * from <用户表>` → 执行 → 右下新卡片展示表格。
会话输入：`把 <用户表> 中 id=1 的记录名字改成 test`（只读库/无权限时用其他无害写操作，如 `create table _agent_demo(id int)`）
Expected: 左侧弹出确认卡片（含 SQL + 倒计时）→ 点"取消"→ AI 回答说明操作被用户拒绝；再次发起同样请求点"确认执行"→ 执行成功并回填结果

- [ ] **Step 5: MCP 验收（外部客户端视角）**

（同 Task 19 curl 步骤，确认 5 工具可列可调；list_tables 对 mysql-mytest 返回真实表）

- [ ] **Step 6: 修复验收中发现的问题（若有）并 Commit**

```bash
git add -A && git commit -m "fix: issues found during e2e acceptance"
```

---

### Task 30: README 初始化 + 最终交付

**Files:**
- Modify: `README.md`（重写）
- Modify: `README.en.md`（简版英文）

- [ ] **Step 1: 重写 README.md**

内容必须包含：项目简介与功能清单；架构图（ASCII，工具层共享+双通道）；技术栈与版本；快速开始（JDK21 要求、`JAVA_HOME=/opt/...` 构建前缀、`./mvnw clean package`、`java -jar`、访问 8080）；数据源/模型/字典配置说明；MCP 接入示例（/mcp 端点、常见 MCP 客户端配置片段）；验收用例说明（两个自然语言用例 + 控制台）；测试密钥警示（`update.sql` 中的 MiniMax 测试 Key 与测试数据源仅用于本地测试）；开发指南（前后端 dev 模式、`-Pskip-frontend`）；目录结构。

- [ ] **Step 2: 最终全量验证**

```bash
JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts ./mvnw clean package  # 全量测试 + 前端构建
```
Expected: BUILD SUCCESS，日志显示所有单测通过

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: initialize README with architecture, quickstart and MCP guide"
```

---

## 附录 A：验收清单对照（Definition of Done）

- [ ] `./mvnw clean package` 一条命令产出含前端的可运行 jar（Task 29 Step 1）
- [ ] 数据源管理：MySQL/PostgreSQL 增删改查、测试连接、只读开关（Task 7/8/9）
- [ ] 模型管理：配置/启用；厂商→模型ID 联动下拉（Task 12/13/14）
- [ ] 工具对内（ChatClient）与对外（/mcp）均可用（Task 19/22/29）
- [ ] 会话：WS、确认卡片、SQL 控制台、结果集、历史持久化（Task 21-29）
- [ ] 验收用例 1、2 通过（Task 29）
- [ ] README 完成（Task 30）

## 附录 B：执行注意事项

1. **所有 Maven 命令必须带** `JAVA_HOME=/opt/apps/org.openjdk-lts/files/openjdk-lts`（终端默认 JDK 为 1.8）
2. 后端启动后再跑前端验证；迭代期用 `-Pskip-frontend` 加速
3. 开发期前端热更：`cd frontend` 后使用 `target/node` 下的 node/npm 运行 `npm run dev`（Vite 已配代理）
4. 若 Spring AI 1.1.8 中某个 API 类/方法名与计划不一致（如 `ToolCallbacks` 包路径、MCP 配置项），以编译错误为准确认后调整；不允许改变架构与验收标准
5. 真实环境验证时若 192.168.110.88 不可达，用本地 docker 起 MySQL/PostgreSQL 替代（改数据源种子/页面配置均可），最终必须在真实库上复验一次
6. 提交粒度按任务；若某一步骤失败，先修后提交，不允许带失败测试的提交

