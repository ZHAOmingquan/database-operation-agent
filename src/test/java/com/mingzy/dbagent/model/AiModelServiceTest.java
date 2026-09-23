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
