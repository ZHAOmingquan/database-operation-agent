package com.mingzy.dbagent.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ConfirmationService {

    public enum Decision { APPROVED, REJECTED, EXPIRED }

    private final ChatDao chatDao;
    private final WsSessionRegistry ws;
    private final long timeoutSeconds;
    private final Map<Long, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    public ConfirmationService(ChatDao chatDao, WsSessionRegistry ws,
                               @Value("${app.confirm.timeout-seconds:60}") long timeoutSeconds) {
        this.chatDao = chatDao;
        this.ws = ws;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 发起确认请求并挂起等待（超时自动过期）。返回值 null=已批准，否则为拒绝/超时原因。 */
    public String requestAndWait(long sessionId, Long messageId, Long datasourceId,
                                 String datasourceName, String sql) {
        String expiresAt = LocalDateTime.now().plusSeconds(timeoutSeconds)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long id = chatDao.insertConfirm(new ConfirmRequest(null, sessionId, messageId, datasourceId,
                datasourceName, sql, "pending", null, expiresAt));
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(id, future);
        ws.send(sessionId, "confirm_request", Map.of(
                "id", id, "sql", sql, "datasourceName", datasourceName,
                "expiresInSeconds", timeoutSeconds));
        try {
            Decision d = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (d == Decision.APPROVED) return null;
            return "用户拒绝了该操作，未执行";
        } catch (Exception e) {
            chatDao.updateConfirmStatus(id, "expired");
            ws.send(sessionId, "confirm_result", Map.of("id", id, "status", "expired"));
            return "用户在时限内未确认，操作已取消（超时 " + timeoutSeconds + " 秒）";
        } finally {
            pending.remove(id);
        }
    }

    public void approve(long confirmId) {
        ConfirmRequest c = chatDao.findConfirm(confirmId);
        if (c == null) throw new IllegalArgumentException("确认请求不存在: " + confirmId);
        if (!"pending".equals(c.status())) return;
        chatDao.updateConfirmStatus(confirmId, "approved");
        ws.send(c.sessionId(), "confirm_result", Map.of("id", confirmId, "status", "approved"));
        CompletableFuture<Decision> f = pending.get(confirmId);
        if (f != null) f.complete(Decision.APPROVED);
    }

    public void reject(long confirmId) {
        ConfirmRequest c = chatDao.findConfirm(confirmId);
        if (c == null) throw new IllegalArgumentException("确认请求不存在: " + confirmId);
        if (!"pending".equals(c.status())) return;
        chatDao.updateConfirmStatus(confirmId, "rejected");
        ws.send(c.sessionId(), "confirm_result", Map.of("id", confirmId, "status", "rejected"));
        CompletableFuture<Decision> f = pending.get(confirmId);
        if (f != null) f.complete(Decision.REJECTED);
    }
}
