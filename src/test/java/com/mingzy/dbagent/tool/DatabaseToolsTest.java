package com.mingzy.dbagent.tool;

import com.mingzy.dbagent.datasource.DatasourceDao;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.datasource.DynamicDataSourceManager;
import com.mingzy.dbagent.executor.DeleteGuard;
import com.mingzy.dbagent.executor.SqlExecutor;
import com.mingzy.dbagent.metadata.MetadataServiceRouter;
import com.mingzy.dbagent.metadata.MysqlMetadataService;
import com.mingzy.dbagent.metadata.PostgresMetadataService;
import com.mingzy.dbagent.sysconfig.SysConfigDao;
import com.mingzy.dbagent.sysconfig.SysConfigService;
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
        DatasourceService sp = new DatasourceService(dao, "VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU=", new DynamicDataSourceManager());
        service = sp;
        jdbc.execute("""
            CREATE TABLE sys_config (id INTEGER PRIMARY KEY AUTOINCREMENT, config_key TEXT NOT NULL UNIQUE,
              config_value TEXT NOT NULL, description TEXT, updated_at TEXT)
            """);
        jdbc.update("INSERT INTO sys_config(config_key, config_value) VALUES('developer_mode','false')");
        DeleteGuard guard = new DeleteGuard(new SysConfigService(new SysConfigDao(jdbc)));
        // 用 sqlite 文件库充当"受管数据源"，验证工具链（路径用临时文件使多连接可见）
        tools = new DatabaseTools(sp, new SqlExecutor(),
                new MetadataServiceRouter(new MysqlMetadataService(), new PostgresMetadataService()),
                new ToolTraceRegistry(), guard, 10, 500, 30);
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

    @Test
    void deleteBlockedWithoutDeveloperMode() {
        service.create(new com.mingzy.dbagent.datasource.dto.DatasourceRequest(
                "demo", "mysql", "127.0.0.1", 3306, "mytest", "root", "pwd", null, false));
        String out = tools.executeUpdate("demo", "delete from users where id=1", null);
        assertThat(out).contains("请开启开发者模式");
    }

    @Test
    void insertNotAffectedByDeveloperMode() {
        service.create(new com.mingzy.dbagent.datasource.dto.DatasourceRequest(
                "demo2", "mysql", "127.0.0.1", 1, "mytest", "root", "pwd", null, false));
        String out = tools.executeUpdate("demo2", "insert into users(id) values(1)", null);
        assertThat(out).doesNotContain("开发者模式");
    }
}
