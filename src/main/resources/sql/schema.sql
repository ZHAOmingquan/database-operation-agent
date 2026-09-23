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

-- 模型名称唯一（update.sql 幂等种子依赖）
CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_model_name ON ai_model(name);

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

-- parent_key 可为 NULL，IFNULL 归一化后保证 (dict_type, dict_key, parent_key) 在 NULL 场景下仍唯一（update.sql 幂等种子依赖）
CREATE UNIQUE INDEX IF NOT EXISTS ux_sys_dict_identity ON sys_dict(dict_type, dict_key, IFNULL(parent_key, ''));

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
    chart_config TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 迁移：sql_result 增加 chart_config 列（旧库升级；新库已在建表语句中包含，报 duplicate column 由 continue-on-error 容忍）
ALTER TABLE sql_result ADD COLUMN chart_config TEXT;

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

-- 系统配置（config_key 唯一；update.sql 幂等种子依赖）
CREATE TABLE IF NOT EXISTS sys_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key TEXT NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    description TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
