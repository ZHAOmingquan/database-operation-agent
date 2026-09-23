package com.mingzy.dbagent.chat;

public record ConfirmRequest(Long id, Long sessionId, Long messageId, Long datasourceId, String datasourceName,
                             String sqlText, String status, String createdAt, String expiresAt) {
}
