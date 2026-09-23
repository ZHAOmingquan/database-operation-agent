package com.mingzy.dbagent.sysconfig;

public record SysConfig(Long id, String configKey, String configValue, String description, String updatedAt) {
}
