package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiReplyCleanerTest {

    @Test
    void stripsSingleThinkBlock() {
        String raw = "<think>The user is asking me to reply with \"ok\".</think>\n\nok";
        assertThat(AiReplyCleaner.stripThinkBlocks(raw)).isEqualTo("ok");
    }

    @Test
    void stripsMultipleAndThinkingVariant() {
        String raw = "<think>a</think>第一<thinking>b</thinking>段<think>c</think>";
        assertThat(AiReplyCleaner.stripThinkBlocks(raw)).isEqualTo("第一段");
    }

    @Test
    void multilineThinkBlock() {
        String raw = "<think>\nline1\nline2\n</think>最终回答";
        assertThat(AiReplyCleaner.stripThinkBlocks(raw)).isEqualTo("最终回答");
    }

    @Test
    void plainReplyUnchanged() {
        assertThat(AiReplyCleaner.stripThinkBlocks("系统有 42 个用户")).isEqualTo("系统有 42 个用户");
    }

    @Test
    void nullSafe() {
        assertThat(AiReplyCleaner.stripThinkBlocks(null)).isEqualTo("");
    }
}
