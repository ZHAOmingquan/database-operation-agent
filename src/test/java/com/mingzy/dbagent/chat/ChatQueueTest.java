package com.mingzy.dbagent.chat;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 全局 FIFO 排队：最多 5 路并行处理，排队者收到位置推送，开始前收到 queue_start */
class ChatQueueTest {

    private final WsSessionRegistry ws = mock(WsSessionRegistry.class);
    private final ChatService chatService = mock(ChatService.class);
    private final ChatQueue queue = new ChatQueue(ws, chatService);

    @Test
    void upToFiveConcurrentWithQueuePositions() throws Exception {
        int workers = ChatQueue.CONCURRENCY;
        CountDownLatch entered = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        doAnswer(inv -> {
            entered.countDown();
            maxConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
            release.await(10, TimeUnit.SECONDS);
            running.decrementAndGet();
            return null;
        }).when(chatService).handleUserMessage(anyLong(), any(), any(), any());

        queue.start();
        for (long s = 1; s <= workers; s++) queue.enqueue(s, "q" + s, null, null);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).isEqualTo(workers); // 5 个任务全部并行处理中

        // 5 个 worker 全忙，后续任务按等待数收到位置 0/1/2
        queue.enqueue(6L, "q6", null, null);
        verify(ws, timeout(3000)).send(eq(6L), eq("queued"), eq(Map.of("position", 0)));
        queue.enqueue(7L, "q7", null, null);
        verify(ws, timeout(3000)).send(eq(7L), eq("queued"), eq(Map.of("position", 1)));
        queue.enqueue(8L, "q8", null, null);
        verify(ws, timeout(3000)).send(eq(8L), eq("queued"), eq(Map.of("position", 2)));

        release.countDown();
        verify(chatService, timeout(5000).times(workers + 3)).handleUserMessage(anyLong(), any(), any(), any());
    }

    @Test
    void queuedAlwaysPrecedesQueueStart() throws Exception {
        CountDownLatch firstTaskEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(inv -> {
            firstTaskEntered.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return null;
        }).when(chatService).handleUserMessage(anyLong(), any(), any(), any());

        queue.start();
        queue.enqueue(1L, "q1", null, null);
        assertThat(firstTaskEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // worker 正忙时入队：同一会话必须先收到 queued(位置) 再收到 queue_start，
        // 反之 queue_start 先到的瞬间会被随后的 queued 把排队状态设回，前端横幅不再清除
        queue.enqueue(2L, "q2", null, null);
        InOrder order = inOrder(ws);
        order.verify(ws, timeout(3000)).send(eq(2L), eq("queued"), eq(Map.of("position", 0)));
        releaseFirst.countDown();
        order.verify(ws, timeout(5000)).send(eq(2L), eq("queue_start"), any());
    }
}
