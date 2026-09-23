-- update.sql: 幂等种子数据（INSERT OR IGNORE 依赖 schema.sql 中的唯一索引；可重复执行）
-- 测试数据源（密码为 app.crypto.key 对应的 AES-GCM 密文）
INSERT OR IGNORE INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
VALUES ('mysql-mytest', 'mysql', '192.168.110.88', 3306, 'mytest', 'root', 'WctOK9OPwM0V3/xuOtGpmalCk6wmFk1GKCVdeexwlUGlHJX+tg==', 0);

INSERT OR IGNORE INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
VALUES ('pg-mytest', 'postgresql', '192.168.110.88', 5432, 'mytest', 'ming', 'EQwg/Yi9ubfNUZua7ksBWQS13vnr9RHBEfZFCoqbC6fKo3iUbA==', 0);

-- 升级清理：移除上一版种子中的旧厂商/模型ID（幂等；不影响运行期手动添加的其他字典项）
DELETE FROM sys_dict WHERE dict_type = 'model_provider' AND dict_key IN ('zhipu', 'openai', 'ollama');
DELETE FROM sys_dict WHERE dict_type = 'model_id' AND dict_key IN
  ('MiniMax-Text-01', 'deepseek-chat', 'deepseek-reasoner', 'qwen-plus', 'qwen-max', 'glm-4-plus', 'gpt-4o-mini', 'qwen2.5:7b');

-- 模型厂商字典
INSERT OR IGNORE INTO sys_dict (dict_type, dict_key, dict_label, parent_key, sort, enabled) VALUES
  ('model_provider', 'deepseek', 'DeepSeek', NULL, 1, 1),
  ('model_provider', 'qwen', '通义千问', NULL, 2, 1),
  ('model_provider', 'glm', '智谱 GLM', NULL, 3, 1),
  ('model_provider', 'kimi', 'Kimi', NULL, 4, 1),
  ('model_provider', 'minimax', 'MiniMax', NULL, 5, 1);

-- 模型ID字典（parent_key 为厂商）
INSERT OR IGNORE INTO sys_dict (dict_type, dict_key, dict_label, parent_key, sort, enabled) VALUES
  ('model_id', 'deepseek-v4-flash', 'DeepSeek V4 Flash', 'deepseek', 1, 1),
  ('model_id', 'deepseek-v4-pro', 'DeepSeek V4 Pro', 'deepseek', 2, 1),
  ('model_id', 'qwen3.8-max', 'Qwen3.8 Max', 'qwen', 1, 1),
  ('model_id', 'qwen3.8-flash', 'Qwen3.8 Flash', 'qwen', 2, 1),
  ('model_id', 'glm-5.3', 'GLM-5.3', 'glm', 1, 1),
  ('model_id', 'glm-5.3-flash', 'GLM-5.3 Flash', 'glm', 2, 1),
  ('model_id', 'k3', 'K3', 'kimi', 1, 1),
  ('model_id', 'kimi-for-coding', 'Kimi for Coding', 'kimi', 2, 1),
  ('model_id', 'MiniMax-M3', 'MiniMax-M3', 'minimax', 1, 1),
  ('model_id', 'MiniMax-M2.7', 'MiniMax-M2.7', 'minimax', 2, 1);

-- 测试模型：MiniMax-M3
INSERT OR IGNORE INTO ai_model (name, provider, base_url, api_key, model_id, temperature, max_tokens, enabled)
VALUES ('MiniMax-M3（测试）', 'minimax', 'https://api.minimaxi.com/v1', 'q6fiYkYPQ/aK8XByPk4db7u5RXnz5CdPJuA+tU1cTTyaSCC/rspZlJJuEU+PR7c/a8osFEVRZtcvuiaQmklIT3sbnV1RoeE9B47DQDJ/YPK6FAM/wXyn7rZiPV//7JSUygsmPz+fQW6ec74w4et0CEeeSOziTbNJzdApiJoROZrcvc/MNpVxiDj3OWopQBfZzJRC03u80pC2', 'MiniMax-M3', 0.7, 4096, 1);
