package com.mingzy.dbagent.chat;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 多人提问全局 FIFO 排队：同一时间只处理一个模型调用，其余任务排队等待。
 * 入队与队列前进时通过 WS 推送排队位置（queued），开始处理时推送 queue_start。
 */
@Slf4j
@Component
public class ChatQueue {

    /** 一个待处理的提问 */
    public record ChatTask(long sessionId, String content, Long datasourceId, Long modelId) {
    }

    private final BlockingQueue<ChatTask> queue = new LinkedBlockingQueue<>();
    private final WsSessionRegistry ws;
    private final ChatService chatService;

    public ChatQueue(WsSessionRegistry ws, ChatService chatService) {
        this.ws = ws;
        this.chatService = chatService;
    }

    @PostConstruct
    void start() {
        Thread worker = new Thread(this::loop, "chat-queue-worker");
        worker.setDaemon(true);
        worker.start();
    }

    public void enqueue(long sessionId, String content, Long datasourceId, Long modelId) {
        ChatTask task = new ChatTask(sessionId, content, datasourceId, modelId);
        queue.offer(task);
        int position = positionOf(task);
        log.debug("chat task enqueued: sessionId={}, position={}", sessionId, position);
        ws.send(sessionId, "queued", Map.of("position", position));
    }

    /** 任务在队列中的位置（前面还有几个等待的任务）；已被 worker 取走则返回 0 */
    private int positionOf(ChatTask task) {
        int i = 0;
        for (ChatTask t : queue) {
            if (t == task) return i;
            i++;
        }
        return 0;
    }

    private void loop() {
        while (true) {
            ChatTask task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ws.send(task.sessionId(), "queue_start", Map.of());
            try {
                chatService.handleUserMessage(task.sessionId(), task.content(), task.datasourceId(), task.modelId());
            } catch (Exception e) {
                log.error("chat task failed: sessionId={}", task.sessionId(), e);
            } finally {
                notifyPositions();
            }
        }
    }

    /** 一个任务处理完后，向所有等待者刷新最新排队位置 */
    private void notifyPositions() {
        int i = 0;
        for (ChatTask t : queue) {
            ws.send(t.sessionId(), "queued", Map.of("position", i++));
        }
    }
}
