package com.mingzy.dbagent.dict.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.dict.DictItem;
import com.mingzy.dbagent.dict.DictService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dicts")
public class DictController {

    private final DictService service;
    public DictController(DictService service) { this.service = service; }

    @GetMapping
    public Result<List<DictItem>> list(@RequestParam String type,
                                       @RequestParam(required = false) String parentKey,
                                       @RequestParam(required = false) Boolean enabledOnly) {
        return Result.ok(service.list(type, parentKey, enabledOnly));
    }

    @GetMapping("/model_ids")
    public Result<List<DictItem>> modelIds(@RequestParam String provider) {
        return Result.ok(service.modelIds(provider));
    }

    @PostMapping
    public Result<DictItem> create(@RequestBody DictItem item) { return Result.ok(service.save(null, item)); }

    @PutMapping("/{id}")
    public Result<DictItem> update(@PathVariable long id, @RequestBody DictItem item) {
        return Result.ok(service.save(id, item));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.ok(null); }
}
