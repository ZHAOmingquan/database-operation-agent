package com.mingzy.dbagent.model;

public record AiModel(Long id, String name, String provider, String baseUrl, String apiKey,
                      String modelId, double temperature, int maxTokens, boolean enabled,
                      String createdAt, String updatedAt) {
}
