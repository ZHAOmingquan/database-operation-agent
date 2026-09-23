package com.mingzy.dbagent.chat;

public record SqlResult(Long id, Long sessionId, Long messageId, Long datasourceId, String datasourceName,
                        String sqlText, String resultType, String columnsJson, String rowsJson, int rowCount,
                        Integer affectedRows, Long elapsedMs, String aiComment, String source, String status,
                        String errorMessage, String createdAt) {
}
