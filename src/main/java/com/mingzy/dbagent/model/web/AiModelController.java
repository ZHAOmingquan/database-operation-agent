package com.mingzy.dbagent.model.web;

import com.mingzy.dbagent.common.Result;
import com.mingzy.dbagent.config.ChatClientFactory;
import com.mingzy.dbagent.model.AiModel;
import com.mingzy.dbagent.model.AiModelService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class AiModelController {

    private final AiModelService service;
    private final ChatClientFactory factory;

    public AiModelController(AiModelService service, ChatClientFactory factory) {
        this.service = service;
        this.factory = factory;
    }

    @GetMapping
    public Result<List<AiModelService.AiModelView>> list() { return Result.ok(service.views()); }

    @PostMapping
    public Result<AiModelService.AiModelView> create(@RequestBody AiModel m) {
        AiModel created = service.create(m);
        factory.evict(created.id());
        return Result.ok(service.views().stream().filter(v -> v.id().equals(created.id())).findFirst().orElseThrow());
    }

    @PutMapping("/{id}")
    public Result<AiModelService.AiModelView> update(@PathVariable long id, @RequestBody AiModel m) {
        AiModel updated = service.update(id, m);
        factory.evict(id);
        return Result.ok(service.views().stream().filter(v -> v.id().equals(updated.id())).findFirst().orElseThrow());
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        service.delete(id);
        factory.evict(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable long id) { service.enable(id); return Result.ok(null); }

    /** 连接测试：未保存表单也可测（apiKey 空且带 id 时用已存的 key） */
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestBody AiModel m, @RequestParam(required = false) Long id) {
        String apiKey = m.apiKey();
        if ((apiKey == null || apiKey.isBlank()) && id != null) {
            apiKey = service.decryptApiKey(service.requireById(id));
        }
        AiModel probe = new AiModel(id, m.name(), m.provider(), m.baseUrl(), apiKey, m.modelId(),
                m.temperature(), m.maxTokens(), false, null, null);
        long start = System.currentTimeMillis();
        try {
            ChatClient client = factory.build(probe);
            String reply = client.prompt().user("请回复：ok").call().content();
            return Result.ok(Map.of("success", true,
                    "elapsedMs", System.currentTimeMillis() - start,
                    "reply", reply == null ? "" : reply));
        } catch (Exception e) {
            return Result.ok(Map.of("success", false,
                    "elapsedMs", System.currentTimeMillis() - start,
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
