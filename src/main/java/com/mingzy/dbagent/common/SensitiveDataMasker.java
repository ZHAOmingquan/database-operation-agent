package com.mingzy.dbagent.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏：防止数据库连接凭据（账号/密码/内网地址）通过工具异常消息回流给大模型。
 * 仅用于"将回传给大模型的文本"（工具返回值）这一出口，不影响本地日志与前端展示。
 */
public final class SensitiveDataMasker {

    private SensitiveDataMasker() {}

    /** JDBC URL：隐藏主机/端口/库名及 URL 上的 user/password 参数 */
    private static final Pattern JDBC_URL =
            Pattern.compile("jdbc:(mysql|postgresql)://[^\\s\"'）)]+");

    /** MySQL 认证失败消息：Access denied for user 'xxx'@'yyy' */
    private static final Pattern ACCESS_DENIED =
            Pattern.compile("(?i)(access denied for user)\\s+'[^']*'@'[^']*'");

    /** PostgreSQL 认证失败消息：password authentication failed for user "xxx" */
    private static final Pattern PG_AUTH_FAILED =
            Pattern.compile("(?i)(password authentication failed for user)\\s+\"[^\"]*\"");

    /** 键值形态的凭据：password=/pwd:/passwd=...（连接串或属性回显；不吞尾随括号） */
    private static final Pattern KEY_VALUE_SECRET =
            Pattern.compile("(?i)\\b(password|passwd|pwd)\\b\\s*[=:]\\s*('?)[^\\s,;&'\"()]+");

    /** 对可能包含凭据的文本脱敏；null/空原样返回 */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = JDBC_URL.matcher(text).replaceAll("jdbc:$1://***");
        out = ACCESS_DENIED.matcher(out).replaceAll("$1 '***'@'***'");
        out = PG_AUTH_FAILED.matcher(out).replaceAll("$1 \"***\"");
        out = KEY_VALUE_SECRET.matcher(out).replaceAll("$1=***");
        return out;
    }

    /** 便捷断言：文本中是否仍可能包含连接凭据（供测试与本地校验使用） */
    public static boolean containsCredential(String text) {
        if (text == null || text.isEmpty()) return false;
        return JDBC_URL.matcher(text).find()
                || ACCESS_DENIED.matcher(text).find()
                || PG_AUTH_FAILED.matcher(text).find()
                || KEY_VALUE_SECRET.matcher(text).find();
    }

    /** 调试用：返回首个命中的凭据片段（生产勿用） */
    public static String firstCredentialHit(String text) {
        if (text == null) return null;
        for (Pattern p : new Pattern[]{JDBC_URL, ACCESS_DENIED, PG_AUTH_FAILED, KEY_VALUE_SECRET}) {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group();
        }
        return null;
    }
}
