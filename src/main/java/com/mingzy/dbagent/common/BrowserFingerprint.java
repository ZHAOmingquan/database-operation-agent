package com.mingzy.dbagent.common;

/** 浏览器指纹提取与校验：REST 用请求头，WebSocket 用查询参数（浏览器无法为 WS 设置自定义头）。 */
public final class BrowserFingerprint {

    /** REST 请求头名 */
    public static final String HEADER = "X-Browser-Fingerprint";

    /** WebSocket 查询参数名 */
    public static final String QUERY_PARAM = "fp";

    private BrowserFingerprint() {}

    /** 校验并归一化指纹；缺失或空白时抛业务异常（全局异常处理器统一返回） */
    public static String require(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("缺少浏览器指纹");
        return raw.trim();
    }

    /** 从 URL query 中解析 fp 参数；缺失返回 null（不抛异常，供 WS 握手自行决定关闭策略） */
    public static String fromQuery(String query) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && QUERY_PARAM.equals(pair.substring(0, idx))) return pair.substring(idx + 1);
        }
        return null;
    }
}
