package com.mingzy.dbagent.chat;

public record ChatSession(Long id, String title, Long datasourceId, Long modelId,
                          String createdAt, String updatedAt) {
}
