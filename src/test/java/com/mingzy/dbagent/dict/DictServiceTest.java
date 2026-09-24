package com.mingzy.dbagent.dict;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictServiceTest {

    private DictService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE sys_dict (id INTEGER PRIMARY KEY AUTOINCREMENT, dict_type TEXT NOT NULL,
              dict_key TEXT NOT NULL, dict_label TEXT NOT NULL, parent_key TEXT,
              sort INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1,
              ext_value TEXT,
              UNIQUE (dict_type, dict_key, parent_key))
            """);
        service = new DictService(new DictDao(jdbc));
        service.save(null, new DictItem(null, "model_provider", "minimax", "MiniMax", null, 1, true,
                "https://api.minimaxi.com/v1"));
        service.save(null, new DictItem(null, "model_id", "MiniMax-M3", "MiniMax-M3", "minimax", 1, true));
        service.save(null, new DictItem(null, "model_id", "DeepSeek-Chat", "DeepSeek-Chat", "deepseek", 1, true));
    }

    @Test
    void extValueRoundTrip() {
        DictItem p = service.list("model_provider", null, null).get(0);
        assertThat(p.extValue()).isEqualTo("https://api.minimaxi.com/v1");
        service.save(p.id(), new DictItem(p.id(), "model_provider", "minimax", "MiniMax", null, 1, true,
                "https://api.minimaxi.com/v2"));
        assertThat(service.list("model_provider", null, null).get(0).extValue())
                .isEqualTo("https://api.minimaxi.com/v2");
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

    @Test
    void duplicateRejected() {
        assertThatThrownBy(() -> service.save(null,
                new DictItem(null, "model_id", "MiniMax-M3", "MiniMax M3 重复", "minimax", 9, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }
}
