package com.mingzy.dbagent.common;

public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data) { return new Result<>(0, "ok", data); }
    public static <T> Result<T> error(String message) { return new Result<>(1, message, null); }
}
