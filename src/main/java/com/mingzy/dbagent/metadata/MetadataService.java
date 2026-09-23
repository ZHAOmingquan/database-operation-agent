package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;

import javax.sql.DataSource;
import java.util.List;

public interface MetadataService {
    List<TableInfo> tables(DataSource ds);
    List<ColumnInfo> columns(DataSource ds, String tableName);
}
