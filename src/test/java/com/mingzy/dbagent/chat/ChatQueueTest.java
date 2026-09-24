package com.mingzy.dbagent.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 全局 FIFO 排队：worker 单线程串行处理，排队者收到位置推送，开始前收到 queue_start */
class ChatQueueTest {

    private final WsSessionRegistry ws = mock(WsSessionRegistry.class);
    private final ChatService chatService = mock(ChatService.class);
    private final ChatQueue queue = new ChatQueue(ws, chatService);

    @Test
    void tasksAreSerializedAndQueuedWithPosition() throws Exception {
        CountDownLatch firstTaskEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(inv -> {
            long sessionId = inv.getArgument(0);
            if (sessionId == 1L) {
                firstTaskEntered.countDown();
                releaseFirst.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(chatService).handleUserMessage(anyLong(), any(), any(), any());

        queue.start();
        queue.enqueue(1L, "q1", null, null);
        assertThat(firstTaskEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // worker 正在处理 session 1，后续任务排队并收到位置
        queue.enqueue(2L, "q2", null, null);
        verify(ws, timeout(3000)).send(eq(2L), eq("queued"), eq(Map.of("position", 0)));

        queue.enqueue(3L, "q3", null, null);
        verify(ws, timeout(3000)).send(eq(3L), eq("queued"), eq(Map.of("position", 1)));

        releaseFirst.countDown();

        // session 2 开始处理（其排队期间收到过位置刷新），且三个任务全部串行执行
        verify(ws, timeout(5000)).send(eq(2L), eq("queue_start"), any());
        verify(chatService, timeout(5000).times(1)).handleUserMessage(eq(2L), eq("q2"), any(), any());
        verify(chatService, timeout(5000).times(1)).handleUserMessage(eq(3L), eq("q3"), any(), any());
        verify(chatService, times(1)).handleUserMessage(eq(1L), eq("q1"), any(), any());
    }
}
