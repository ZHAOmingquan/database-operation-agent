package com.mingzy.dbagent.dict;

/**
 * @param extValue 扩展值：model_provider 字典用来存厂商的 base_url
 */
public record DictItem(Long id, String dictType, String dictKey, String dictLabel,
                       String parentKey, int sort, boolean enabled, String extValue) {

    /** 兼容旧调用：无扩展值 */
    public DictItem(Long id, String dictType, String dictKey, String dictLabel,
                    String parentKey, int sort, boolean enabled) {
        this(id, dictType, dictKey, dictLabel, parentKey, sort, enabled, null);
    }
}
