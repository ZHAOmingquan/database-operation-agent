package com.mingzy.dbagent.dict;

public record DictItem(Long id, String dictType, String dictKey, String dictLabel,
                       String parentKey, int sort, boolean enabled) {
}
