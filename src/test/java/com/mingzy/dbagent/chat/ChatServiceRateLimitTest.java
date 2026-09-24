package com.mingzy.dbagent.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceRateLimitTest {

    @Test
    void tooManyRequestsExceptionIsRateLimit() {
        var ex = org.springframework.web.client.HttpClientErrorException.TooManyRequests
                .create("rate limit", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", null, null, null);
        assertThat(ChatService.isRateLimited(ex)).isTrue();
    }

    @Test
    void rateLimitKeywordsInCauseChain() {
        assertThat(ChatService.isRateLimited(new RuntimeException("429 Too Many Requests"))).isTrue();
        assertThat(ChatService.isRateLimited(new RuntimeException(new RuntimeException("Rate limit exceeded")))).isTrue();
        assertThat(ChatService.isRateLimited(new RuntimeException("quota reached"))).isTrue();
        assertThat(ChatService.isRateLimited(new RuntimeException("用户触发限流，请稍后再试"))).isTrue();
        assertThat(ChatService.isRateLimited(new RuntimeException("模型使用超限"))).isTrue();
    }

    @Test
    void ordinaryErrorsAreNotRateLimit() {
        assertThat(ChatService.isRateLimited(new IOException("Connection refused"))).isFalse();
        assertThat(ChatService.isRateLimited(new RuntimeException("api key invalid"))).isFalse();
        assertThat(ChatService.isRateLimited(new RuntimeException((String) null))).isFalse();
    }
}
