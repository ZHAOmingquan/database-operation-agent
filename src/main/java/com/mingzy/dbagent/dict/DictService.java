package com.mingzy.dbagent.dict;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DictService {

    private final DictDao dao;
    public DictService(DictDao dao) { this.dao = dao; }

    public List<DictItem> list(String type, String parentKey, Boolean enabledOnly) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("dict_type 不能为空");
        return dao.find(type, parentKey, enabledOnly);
    }

    public List<DictItem> modelIds(String provider) {
        return dao.find("model_id", provider, true);
    }

    public DictItem save(Long id, DictItem item) {
        boolean duplicate = dao.find(item.dictType(), null, null).stream()
                .anyMatch(d -> d.dictKey().equals(item.dictKey())
                        && java.util.Objects.equals(d.parentKey(), item.parentKey())
                        && (id == null || !d.id().equals(id)));
        if (duplicate) throw new IllegalArgumentException("字典项已存在: " + item.dictKey());
        if (id == null) {
            return dao.findById(dao.insert(item));
        }
        dao.update(new DictItem(id, item.dictType(), item.dictKey(), item.dictLabel(),
                item.parentKey(), item.sort(), item.enabled()));
        return dao.findById(id);
    }

    public void delete(long id) { dao.delete(id); }
}
