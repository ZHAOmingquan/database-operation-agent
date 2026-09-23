package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.datasource.dto.ConnTestResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DynamicDataSourceManager {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    /** 获取（或按最新配置重建）受管数据源连接池 */
    public HikariDataSource getPool(Datasource ds) {
        return pools.compute(ds.id(), (id, existing) -> {
            if (existing != null && !existing.isClosed()) {
                existing.close();
            }
            return createPool(ds);
        });
    }

    private HikariDataSource createPool(Datasource ds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ds.jdbcUrl());
        cfg.setUsername(ds.username());
        cfg.setPassword(ds.password());
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        cfg.setPoolName("ds-" + ds.name());
        return new HikariDataSource(cfg);
    }

    /** 用临时连接测试（不写入缓存池），支持未保存的表单参数 */
    public ConnTestResult test(Datasource ds) {
        long start = System.currentTimeMillis();
        try (Connection conn = java.sql.DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password())) {
            conn.isValid(3);
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
            return new ConnTestResult(true, System.currentTimeMillis() - start, "连接成功");
        } catch (Exception e) {
            log.warn("datasource test failed: {}", e.getMessage());
            return new ConnTestResult(false, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    /** 数据源配置变更后失效旧池 */
    public void evict(long id) {
        HikariDataSource old = pools.remove(id);
        if (old != null) old.close();
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
