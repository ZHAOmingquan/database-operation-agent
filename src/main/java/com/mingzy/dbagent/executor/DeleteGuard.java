package com.mingzy.dbagent.executor;

import com.mingzy.dbagent.sysconfig.SysConfigService;
import org.springframework.stereotype.Component;

/**
 * 删除操作守卫：仅当系统配置 developer_mode 开启时才允许 DELETE/DROP/TRUNCATE，
 * 否则返回拒绝消息提示用户开启开发者模式。
 */
@Component
public class DeleteGuard {

    public static final String DENY_MESSAGE = "请开启开发者模式，确保你对删除后果了解";

    private final SysConfigService configService;

    public DeleteGuard(SysConfigService configService) { this.configService = configService; }

    /** 返回 null 表示允许执行；否则返回拒绝原因（提示用户开启开发者模式） */
    public String checkAllowed(String sql) {
        if (!SqlClassifier.isDeleteLike(sql)) return null;
        return configService.getBool(SysConfigService.DEVELOPER_MODE, false) ? null : DENY_MESSAGE;
    }
}
