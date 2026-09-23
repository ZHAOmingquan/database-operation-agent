package com.mingzy.dbagent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ToolTraceRegistry {

    private final Map<Long, List<Long>> traceToResults = new ConcurrentHashMap<>();

    public void record(Long traceId, Long resultId) {
        if (traceId == null || resultId == null) return;
        traceToResults.computeIfAbsent(traceId, k -> new CopyOnWriteArrayList<>()).add(resultId);
    }

    public List<Long> results(Long traceId) {
        return traceToResults.getOrDefault(traceId, List.of());
    }

    public void clear(Long traceId) { traceToResults.remove(traceId); }
}
