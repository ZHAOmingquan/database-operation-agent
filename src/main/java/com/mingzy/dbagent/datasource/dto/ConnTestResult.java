package com.mingzy.dbagent.datasource.dto;

public record ConnTestResult(boolean success, long elapsedMs, String message) {
}
