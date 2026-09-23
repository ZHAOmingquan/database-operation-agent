package com.mingzy.dbagent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
