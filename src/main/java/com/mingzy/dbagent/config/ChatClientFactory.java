package com.mingzy.dbagent.config;

import com.mingzy.dbagent.model.AiModel;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChatClientFactory {

    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String fingerprint, ChatClient client) {
    }

    public ChatClient clientFor(AiModel model) {
        String fingerprint = model.baseUrl() + "|" + model.modelId() + "|" + model.temperature()
                + "|" + model.maxTokens();
        CacheEntry entry = cache.get(model.id());
        if (entry != null && entry.fingerprint().equals(fingerprint)) {
            return entry.client();
        }
        ChatClient client = build(model);
        cache.put(model.id(), new CacheEntry(fingerprint, client));
        return client;
    }

    public void evict(long modelId) { cache.remove(modelId); }

    /** 构建一次性客户端（用于连接测试） */
    public ChatClient build(AiModel model) {
        String[] norm = normalize(model.baseUrl());
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(norm[0])
                .completionsPath(norm[1])
                .apiKey(model.apiKey())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model.modelId())
                        .temperature(model.temperature())
                        .maxTokens(model.maxTokens())
                        .build())
                .build();
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 规范化 baseUrl 与 completionsPath：
     * baseUrl 以 /v1 结尾 → [原值, /chat/completions]；否则 → [原值, /v1/chat/completions]
     */
    public static String[] normalize(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1")) {
            return new String[]{trimmed, "/chat/completions"};
        }
        return new String[]{trimmed, "/v1/chat/completions"};
    }
}
