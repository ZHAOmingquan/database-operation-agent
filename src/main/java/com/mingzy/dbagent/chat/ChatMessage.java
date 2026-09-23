package com.mingzy.dbagent.chat;

public record ChatMessage(Long id, Long sessionId, String role, String content,
                          String toolCallsJson, String status, String createdAt) {
}
