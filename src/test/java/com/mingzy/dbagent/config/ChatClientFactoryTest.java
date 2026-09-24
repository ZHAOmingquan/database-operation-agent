package com.mingzy.dbagent.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatClientFactoryTest {

    @Test
    void baseUrlWithV1Suffix() {
        String[] normalized = ChatClientFactory.normalize("https://api.minimaxi.com/v1");
        assertThat(normalized[0]).isEqualTo("https://api.minimaxi.com/v1");
        assertThat(normalized[1]).isEqualTo("/chat/completions");
    }

    @Test
    void baseUrlWithoutV1() {
        String[] normalized = ChatClientFactory.normalize("https://api.openai.com/");
        assertThat(normalized[0]).isEqualTo("https://api.openai.com");
        assertThat(normalized[1]).isEqualTo("/v1/chat/completions");
    }

    @Test
    void retryTemplateGivesUpFastForFairQueueing() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> ChatClientFactory.RETRY_TEMPLATE.execute(ctx -> {
            attempts.incrementAndGet();
            throw new org.springframework.web.client.ResourceAccessException("boom");
        })).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        // 共 3 次尝试：限流/抖动最多再试 2 次，避免长时间占用全局队列
        assertThat(attempts.get()).isEqualTo(3);
    }
}
