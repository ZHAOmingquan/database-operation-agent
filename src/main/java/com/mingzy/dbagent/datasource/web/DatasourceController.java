package com.mingzy.dbagent.datasource.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.datasource.DynamicDataSourceManager;
import com.mingzy.dbagent.datasource.dto.ConnTestResult;
import com.mingzy.dbagent.datasource.dto.DatasourceRequest;
import com.mingzy.dbagent.datasource.dto.DatasourceView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {

    private final DatasourceService service;
    private final DynamicDataSourceManager poolManager;

    public DatasourceController(DatasourceService service, DynamicDataSourceManager poolManager) {
        this.service = service;
        this.poolManager = poolManager;
    }

    @GetMapping
    public Result<List<DatasourceView>> list() { return Result.ok(service.list()); }

    @PostMapping
    public Result<DatasourceView> create(@Valid @RequestBody DatasourceRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<DatasourceView> update(@PathVariable long id, @Valid @RequestBody DatasourceRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.ok(null); }

    /** 表单未保存直接测试："password" 为空时用已保存数据源的密码 */
    @PostMapping("/test")
    public Result<ConnTestResult> test(@RequestBody DatasourceRequest req,
                                       @RequestParam(required = false) Long id) {
        String password = req.password();
        if ((password == null || password.isBlank()) && id != null) {
            password = service.decryptPassword(service.requireById(id));
        }
        Datasource probe = new Datasource(id, req.name(), req.dbType(), req.host(), req.port(),
                req.databaseName(), req.username(), password, req.extraParams(), req.readOnly(), null, null);
        return Result.ok(poolManager.test(probe));
    }
}
