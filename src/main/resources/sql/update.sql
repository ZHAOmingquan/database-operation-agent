-- update.sql: 幂等种子数据（可重复执行；spring.sql.init data-locations）
-- 测试数据源（密码为 app.crypto.key 对应的 AES-GCM 密文）
INSERT INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
SELECT 'mysql-mytest', 'mysql', '192.168.110.88', 3306, 'mytest', 'root', 'WctOK9OPwM0V3/xuOtGpmalCk6wmFk1GKCVdeexwlUGlHJX+tg==', 0
WHERE NOT EXISTS (SELECT 1 FROM ds_datasource WHERE name = 'mysql-mytest');

INSERT INTO ds_datasource (name, db_type, host, port, database_name, username, password, read_only)
SELECT 'pg-mytest', 'postgresql', '192.168.110.88', 5432, 'mytest', 'ming', 'EQwg/Yi9ubfNUZua7ksBWQS13vnr9RHBEfZFCoqbC6fKo3iUbA==', 0
WHERE NOT EXISTS (SELECT 1 FROM ds_datasource WHERE name = 'pg-mytest');
