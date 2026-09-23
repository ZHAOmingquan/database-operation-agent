package com.mingzy.dbagent.metadata.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.datasource.DatasourceService;
import com.mingzy.dbagent.metadata.MetadataServiceRouter;
import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasources")
public class MetadataController {

    private final MetadataServiceRouter router;
    private final DatasourceService datasourceService;

    public MetadataController(MetadataServiceRouter router, DatasourceService datasourceService) {
        this.router = router;
        this.datasourceService = datasourceService;
    }

    @GetMapping("/{id}/tables")
    public Result<List<TableInfo>> tables(@PathVariable long id) {
        DatasourceService.HikariPoolRef ref = datasourceService.poolOf(datasourceService.requireById(id));
        return Result.ok(router.tables(ref.datasource(), ref.pool()));
    }

    @GetMapping("/{id}/tables/{table}/schema")
    public Result<List<ColumnInfo>> schema(@PathVariable long id, @PathVariable String table) {
        DatasourceService.HikariPoolRef ref = datasourceService.poolOf(datasourceService.requireById(id));
        return Result.ok(router.columns(ref.datasource(), ref.pool(), table));
    }
}
