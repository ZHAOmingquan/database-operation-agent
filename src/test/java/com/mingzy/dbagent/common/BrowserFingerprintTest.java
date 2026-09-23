package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserFingerprintTest {

    @Test
    void requireRejectsMissingOrBlank() {
        assertThatThrownBy(() -> BrowserFingerprint.require(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("缺少浏览器指纹");
        assertThatThrownBy(() -> BrowserFingerprint.require("   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("缺少浏览器指纹");
    }

    @Test
    void requireTrimsValidValue() {
        assertThat(BrowserFingerprint.require(" abc123 ")).isEqualTo("abc123");
    }

    @Test
    void fromQueryParsesFpParam() {
        assertThat(BrowserFingerprint.fromQuery("fp=abc")).isEqualTo("abc");
        assertThat(BrowserFingerprint.fromQuery("a=1&fp=xyz&b=2")).isEqualTo("xyz");
        assertThat(BrowserFingerprint.fromQuery(null)).isNull();
        assertThat(BrowserFingerprint.fromQuery("")).isNull();
        assertThat(BrowserFingerprint.fromQuery("a=1&b=2")).isNull();
    }
}
