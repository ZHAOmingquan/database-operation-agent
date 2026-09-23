-- update.sql: 幂等种子数据（可重复执行；spring.sql.init data-locations）
-- 测试数据源（密码为 app.crypto.key 对应的 AES-GCM 密文）
INSERT INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
SELECT 'mysql-mytest', 'mysql', '192.168.110.88', 3306, 'mytest', 'root', 'WctOK9OPwM0V3/xuOtGpmalCk6wmFk1GKCVdeexwlUGlHJX+tg==', 0
WHERE NOT EXISTS (SELECT 1 FROM ds_datasource WHERE name = 'mysql-mytest');

INSERT INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
SELECT 'pg-mytest', 'postgresql', '192.168.110.88', 5432, 'mytest', 'ming', 'EQwg/Yi9ubfNUZua7ksBWQS13vnr9RHBEfZFCoqbC6fKo3iUbA==', 0
WHERE NOT EXISTS (SELECT 1 FROM ds_datasource WHERE name = 'pg-mytest');

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
