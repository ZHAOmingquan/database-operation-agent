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
