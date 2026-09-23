package com.mingzy.dbagent.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ChatService {
    public void handleUserMessage(long sessionId, String content, Long datasourceId, Long modelId) {
        log.info("chat message: session={} content={}", sessionId, content);
    }
}
