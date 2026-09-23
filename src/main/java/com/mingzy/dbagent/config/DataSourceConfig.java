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
