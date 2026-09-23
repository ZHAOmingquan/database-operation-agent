package com.mingzy.dbagent.sysconfig;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysConfigService {

    public static final String DEVELOPER_MODE = "developer_mode";

    private final SysConfigDao dao;

    public SysConfigService(SysConfigDao dao) { this.dao = dao; }

    public List<SysConfig> list() { return dao.findAll(); }

    public String get(String key, String defaultValue) {
        SysConfig c = dao.findByKey(key);
        return c == null ? defaultValue : c.configValue();
    }

    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    /** 更新配置值（仅允许已存在的配置键；当前全部为 true/false 布尔开关） */
    public SysConfig set(String key, String value) {
        SysConfig c = dao.findByKey(key);
        if (c == null) throw new IllegalArgumentException("配置不存在: " + key);
        String v = value == null ? "" : value.trim().toLowerCase();
        if (!"true".equals(v) && !"false".equals(v)) {
            throw new IllegalArgumentException("配置值必须为 true 或 false");
        }
        dao.updateValue(key, v);
        return dao.findByKey(key);
    }
}
