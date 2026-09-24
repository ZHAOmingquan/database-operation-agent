package com.mingzy.dbagent.chat;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 多人提问全局 FIFO 排队：最多 CONCURRENCY 个任务并行处理（受模型 RPM 限制），其余排队等待。
 * 入队与队列前进时通过 WS 推送排队位置（queued），开始处理时推送 queue_start。
 */
@Slf4j
@Component
public class ChatQueue {

    /** 最大并行处理数：与前端横幅显示阈值（>5 提示）保持一致 */
    static final int CONCURRENCY = 5;

    /** 一个待处理的提问 */
    public record ChatTask(long sessionId, String content, Long datasourceId, Long modelId) {
    }

    private final BlockingQueue<ChatTask> queue = new LinkedBlockingQueue<>();
    private final Object enqueueLock = new Object();
    private final WsSessionRegistry ws;
    private final ChatService chatService;

    public ChatQueue(WsSessionRegistry ws, ChatService chatService) {
        this.ws = ws;
        this.chatService = chatService;
    }

    @PostConstruct
    void start() {
        for (int i = 1; i <= CONCURRENCY; i++) {
            Thread worker = new Thread(this::loop, "chat-queue-worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void enqueue(long sessionId, String content, Long datasourceId, Long modelId) {
        ChatTask task = new ChatTask(sessionId, content, datasourceId, modelId);
        // 先取位置再入队并先发 queued：保证客户端必先收到 queued、后收 queue_start（否则先到的
        // queue_start 清了排队状态，随后到的 queued 又会把横幅状态设回来且再无事件清除）
        int position;
        synchronized (enqueueLock) {
            position = queue.size();
            queue.offer(task);
        }
        log.debug("chat task enqueued: sessionId={}, position={}", sessionId, position);
        ws.send(sessionId, "queued", Map.of("position", position));
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
