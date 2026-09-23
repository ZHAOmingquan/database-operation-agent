package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void masksMysqlAccessDenied() {
        String msg = "Failed to initialize pool: Access denied for user 'root'@'192.168.110.88' (using password: YES)";
        String out = SensitiveDataMasker.scrub(msg);
        assertThat(out).doesNotContain("root").doesNotContain("192.168.110.88");
        assertThat(out).contains("'***'@'***'");
    }

    @Test
    void masksPostgresAuthFailed() {
        String msg = "FATAL: password authentication failed for user \"ming\"";
        String out = SensitiveDataMasker.scrub(msg);
        assertThat(out).doesNotContain("ming").contains("\"***\"");
    }

    @Test
    void masksJdbcUrlWithParams() {
        String msg = "Cannot get connection: jdbc:mysql://192.168.1.5:3306/mytest?user=root&password=Abc123!";
        String out = SensitiveDataMasker.scrub(msg);
        assertThat(out).doesNotContain("192.168.1.5").doesNotContain("Abc123");
        assertThat(out).contains("jdbc:mysql://***");
    }

    @Test
    void masksKeyValueSecrets() {
        String out = SensitiveDataMasker.scrub("conn props: password=secret123, pwd: 'p@ss', keep=ok");
        assertThat(out).doesNotContain("secret123").doesNotContain("p@ss").contains("keep=ok");
    }

    @Test
    void keepsTrailingParenWhenMaskingAuthFlag() {
        String out = SensitiveDataMasker.scrub("Access denied (using password: YES)");
        assertThat(out).isEqualTo("Access denied (using password=***)");
    }

    @Test
    void keepsOrdinarySqlErrorsIntact() {
        String msg = "Unknown column 'foo' in 'field list'";
        assertThat(SensitiveDataMasker.scrub(msg)).isEqualTo(msg);
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(SensitiveDataMasker.scrub(null)).isNull();
        assertThat(SensitiveDataMasker.scrub("")).isEmpty();
    }

    @Test
    void containsCredentialDetectsLeaks() {
        assertThat(SensitiveDataMasker.containsCredential("x jdbc:postgresql://10.0.0.1:5432/db y")).isTrue();
        assertThat(SensitiveDataMasker.containsCredential("Access denied for user 'u'@'h'")).isTrue();
        assertThat(SensitiveDataMasker.containsCredential("正常的中文错误信息")).isFalse();
        assertThat(SensitiveDataMasker.firstCredentialHit("正常的中文错误信息")).isNull();
    }
}
