package com.mingzy.dbagent.model;

import com.mingzy.dbagent.common.AesGcmUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiModelService {

    private final AiModelDao dao;
    private final AesGcmUtil aes;

    @Autowired
    public AiModelService(AiModelDao dao, @Value("${app.crypto.key}") String key) {
        this.dao = dao;
        this.aes = new AesGcmUtil(key);
    }

    AiModelService(AiModelDao dao, AesGcmUtil aes) { this.dao = dao; this.aes = aes; }

    public List<AiModel> list() { return dao.findAll(); }

    public AiModel requireById(long id) {
        AiModel m = dao.findById(id);
        if (m == null) throw new IllegalArgumentException("模型不存在: " + id);
        return m;
    }

    public AiModel enabled() {
        return dao.findAll().stream().filter(AiModel::enabled).findFirst().orElse(null);
    }

    public AiModel create(AiModel m) {
        long id = dao.insert(new AiModel(null, m.name(), m.provider(), m.baseUrl(),
                aes.encrypt(m.apiKey()), m.modelId(), m.temperature(), m.maxTokens(), false, null, null));
        return requireById(id);
    }

    public AiModel update(long id, AiModel m) {
        AiModel old = requireById(id);
        String apiKey = (m.apiKey() == null || m.apiKey().isBlank()) ? old.apiKey() : aes.encrypt(m.apiKey());
        dao.update(new AiModel(id, m.name(), m.provider(), m.baseUrl(), apiKey, m.modelId(),
                m.temperature(), m.maxTokens(), old.enabled(), null, null));
        return requireById(id);
    }

    public void enable(long id) {
        requireById(id);
        dao.disableAll();
        AiModel m = dao.findById(id);
        dao.update(new AiModel(m.id(), m.name(), m.provider(), m.baseUrl(), m.apiKey(), m.modelId(),
                m.temperature(), m.maxTokens(), true, null, null));
    }

    public void delete(long id) { requireById(id); dao.delete(id); }

    public String decryptApiKey(AiModel m) { return aes.decrypt(m.apiKey()); }

    /** 解密后的副本，供 ChatClientFactory / 连接测试使用 */
    public AiModel withPlainKey(AiModel m) {
        return new AiModel(m.id(), m.name(), m.provider(), m.baseUrl(), aes.decrypt(m.apiKey()),
                m.modelId(), m.temperature(), m.maxTokens(), m.enabled(), m.createdAt(), m.updatedAt());
    }

    /** 返回给前端的视图（隐藏 apiKey，仅标记是否已配置） */
    public List<AiModelView> views() {
        return dao.findAll().stream()
                .map(m -> new AiModelView(m.id(), m.name(), m.provider(), m.baseUrl(), m.modelId(),
                        m.temperature(), m.maxTokens(), m.enabled(),
                        m.apiKey() != null && !m.apiKey().isBlank()))
                .toList();
    }

    public record AiModelView(Long id, String name, String provider, String baseUrl, String modelId,
                              double temperature, int maxTokens, boolean enabled, boolean hasApiKey) {
    }
}
