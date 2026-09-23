package com.mingzy.dbagent.datasource;

import com.mingzy.dbagent.common.AesGcmUtil;
import com.mingzy.dbagent.datasource.dto.DatasourceRequest;
import com.mingzy.dbagent.datasource.dto.DatasourceView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DatasourceService {

    private final DatasourceDao dao;
    private final AesGcmUtil aes;
    private final DynamicDataSourceManager pools;

    @Autowired
    public DatasourceService(DatasourceDao dao,
                             @Value("${app.crypto.key}") String key,
                             DynamicDataSourceManager pools) {
        this.dao = dao;
        this.aes = new AesGcmUtil(key);
        this.pools = pools;
    }

    // 测试用构造器（Task 7 单测使用）
    DatasourceService(DatasourceDao dao, AesGcmUtil aes, DynamicDataSourceManager pools) {
        this.dao = dao;
        this.aes = aes;
        this.pools = pools;
    }

    /** 返回实体列表（供工具层使用） */
    public List<Datasource> rawList() { return dao.findAll(); }

    public List<DatasourceView> list() {
        return dao.findAll().stream().map(DatasourceView::of).toList();
    }

    public Datasource requireById(long id) {
        Datasource d = dao.findById(id);
        if (d == null) throw new IllegalArgumentException("数据源不存在: " + id);
        return d;
    }

    public Datasource requireByName(String name) {
        Datasource d = dao.findByName(name);
        if (d == null) throw new IllegalArgumentException("数据源不存在: " + name);
        return d;
    }

    public DatasourceView create(DatasourceRequest req) {
        if (dao.findByName(req.name()) != null) {
            throw new IllegalArgumentException("数据源名称已存在: " + req.name());
        }
        long id = dao.insert(toEntity(null, req, true));
        return DatasourceView.of(requireById(id));
    }

    public DatasourceView update(long id, DatasourceRequest req) {
        Datasource old = requireById(id);
        Datasource byName = dao.findByName(req.name());
        if (byName != null && !byName.id().equals(id)) {
            throw new IllegalArgumentException("数据源名称已存在: " + req.name());
        }
        boolean keepPassword = req.password() == null || req.password().isBlank();
        String password = keepPassword ? old.password() : aes.encrypt(req.password());
        dao.update(new Datasource(id, req.name(), req.dbType(), req.host(), req.port(),
                req.databaseName(), req.username(), password, req.extraParams(), req.readOnly(), null, null));
        pools.evict(id);
        return DatasourceView.of(requireById(id));
    }

    private Datasource toEntity(Long id, DatasourceRequest req, boolean encryptPassword) {
        String password = encryptPassword && req.password() != null && !req.password().isBlank()
                ? aes.encrypt(req.password())
                : req.password();
        return new Datasource(id, req.name(), req.dbType(), req.host(), req.port(),
                req.databaseName(), req.username(), password, req.extraParams(), req.readOnly(), null, null);
    }

    public void delete(long id) {
        requireById(id);
        dao.delete(id);
        pools.evict(id);
    }

    public String decryptPassword(Datasource d) {
        return aes.decrypt(d.password());
    }

    /** 返回可用连接的数据源（解密密码） */
    public HikariPoolRef poolOf(Datasource d) {
        if (d.password() == null || d.password().isBlank()) {
            throw new IllegalStateException("数据源 " + d.name() + " 未配置密码");
        }
        String plain = aes.decrypt(d.password());
        Datasource withPlain = new Datasource(d.id(), d.name(), d.dbType(), d.host(), d.port(),
                d.databaseName(), d.username(), plain, d.extraParams(), d.readOnly(), d.createdAt(), d.updatedAt());
        return new HikariPoolRef(withPlain, pools.getPool(withPlain));
    }

    public record HikariPoolRef(Datasource datasource, com.zaxxer.hikari.HikariDataSource pool) {
    }
}
