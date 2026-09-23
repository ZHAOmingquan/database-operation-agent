-- update.sql: 幂等种子数据（INSERT OR IGNORE 依赖 schema.sql 中的唯一索引；可重复执行）
-- 测试数据源（密码为 app.crypto.key 对应的 AES-GCM 密文）
INSERT OR IGNORE INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
VALUES ('mysql-mytest', 'mysql', '192.168.110.88', 3306, 'mytest', 'root', 'WctOK9OPwM0V3/xuOtGpmalCk6wmFk1GKCVdeexwlUGlHJX+tg==', 0);

INSERT OR IGNORE INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
VALUES ('pg-mytest', 'postgresql', '192.168.110.88', 5432, 'mytest', 'ming', 'EQwg/Yi9ubfNUZua7ksBWQS13vnr9RHBEfZFCoqbC6fKo3iUbA==', 0);

-- 模型厂商字典
INSERT OR IGNORE INTO sys_dict (dict_type, dict_key, dict_label, parent_key, sort, enabled) VALUES
  ('model_provider', 'minimax', 'MiniMax', NULL, 1, 1),
  ('model_provider', 'deepseek', 'DeepSeek', NULL, 2, 1),
  ('model_provider', 'qwen', '通义千问', NULL, 3, 1),
  ('model_provider', 'zhipu', '智谱 GLM', NULL, 4, 1),
  ('model_provider', 'openai', 'OpenAI', NULL, 5, 1),
  ('model_provider', 'ollama', 'Ollama（本地）', NULL, 6, 1);

-- 模型ID字典（parent_key 为厂商）
INSERT OR IGNORE INTO sys_dict (dict_type, dict_key, dict_label, parent_key, sort, enabled) VALUES
  ('model_id', 'MiniMax-M3', 'MiniMax-M3', 'minimax', 1, 1),
  ('model_id', 'MiniMax-Text-01', 'MiniMax-Text-01', 'minimax', 2, 1),
  ('model_id', 'deepseek-chat', 'DeepSeek Chat', 'deepseek', 1, 1),
  ('model_id', 'deepseek-reasoner', 'DeepSeek Reasoner', 'deepseek', 2, 1),
  ('model_id', 'qwen-plus', 'Qwen Plus', 'qwen', 1, 1),
  ('model_id', 'qwen-max', 'Qwen Max', 'qwen', 2, 1),
  ('model_id', 'glm-4-plus', 'GLM-4-Plus', 'zhipu', 1, 1),
  ('model_id', 'gpt-4o-mini', 'GPT-4o mini', 'openai', 1, 1),
  ('model_id', 'qwen2.5:7b', 'Qwen2.5 7B', 'ollama', 1, 1);

-- 测试模型：MiniMax-M3
INSERT OR IGNORE INTO ai_model (name, provider, base_url, api_key, model_id, temperature, max_tokens, enabled)
VALUES ('MiniMax-M3（测试）', 'minimax', 'https://api.minimaxi.com/v1', 'q6fiYkYPQ/aK8XByPk4db7u5RXnz5CdPJuA+tU1cTTyaSCC/rspZlJJuEU+PR7c/a8osFEVRZtcvuiaQmklIT3sbnV1RoeE9B47DQDJ/YPK6FAM/wXyn7rZiPV//7JSUygsmPz+fQW6ec74w4et0CEeeSOziTbNJzdApiJoROZrcvc/MNpVxiDj3OWopQBfZzJRC03u80pC2', 'MiniMax-M3', 0.7, 4096, 1);
