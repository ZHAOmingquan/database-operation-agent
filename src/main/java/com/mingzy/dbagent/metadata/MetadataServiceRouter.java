package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.common.DbType;
import com.mingzy.dbagent.datasource.Datasource;
import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

@Component
public class MetadataServiceRouter {

    private final MysqlMetadataService mysql;
    private final PostgresMetadataService postgres;

    public MetadataServiceRouter(MysqlMetadataService mysql, PostgresMetadataService postgres) {
        this.mysql = mysql;
        this.postgres = postgres;
    }

    private MetadataService of(Datasource ds) {
        return ds.type() == DbType.mysql ? mysql : postgres;
    }

    public List<TableInfo> tables(Datasource ds, DataSource pool) { return of(ds).tables(pool); }

    public List<ColumnInfo> columns(Datasource ds, DataSource pool, String tableName) {
        return of(ds).columns(pool, tableName);
    }
}
