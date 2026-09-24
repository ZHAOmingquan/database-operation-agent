package com.mingzy.dbagent.common;

/** 清理模型输出：去掉 <think>/<thinking> 推理块，仅保留最终回答（推理模型如 MiniMax-M 系列会输出此类块） */
public final class AiReplyCleaner {

    private AiReplyCleaner() {
    }

    public static String stripThinkBlocks(String reply) {
        if (reply == null) return "";
        return reply.replaceAll("(?is)<think>.*?</think>", "")
                .replaceAll("(?is)<thinking>.*?</thinking>", "")
                .trim();
    }
}
