package com.mingzy.dbagent.sysconfig.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.sysconfig.SysConfig;
import com.mingzy.dbagent.sysconfig.SysConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/configs")
public class ConfigController {

    private final SysConfigService service;
    public ConfigController(SysConfigService service) { this.service = service; }

    @GetMapping
    public Result<List<SysConfig>> list() { return Result.ok(service.list()); }

    @PutMapping("/{key}")
    public Result<SysConfig> update(@PathVariable String key, @RequestBody Map<String, String> body) {
        return Result.ok(service.set(key, body.get("value")));
    }
}
