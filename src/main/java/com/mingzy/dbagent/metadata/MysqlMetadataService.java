package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

@Component
public class MysqlMetadataService implements MetadataService {

    @Override
    public List<TableInfo> tables(DataSource ds) {
        return new JdbcTemplate(ds).query(
            "SELECT table_name, table_type, table_comment FROM information_schema.tables " +
            "WHERE table_schema = DATABASE() ORDER BY table_name",
            (rs, i) -> new TableInfo(rs.getString("table_name"), rs.getString("table_type"),
                    rs.getString("table_comment")));
    }

    @Override
    public List<ColumnInfo> columns(DataSource ds, String tableName) {
        return new JdbcTemplate(ds).query(
            "SELECT column_name, column_type, is_nullable, column_key, column_default, column_comment " +
            "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? " +
            "ORDER BY ordinal_position",
            (rs, i) -> new ColumnInfo(rs.getString("column_name"), rs.getString("column_type"),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    "PRI".equalsIgnoreCase(rs.getString("column_key")),
                    rs.getString("column_default"), rs.getString("column_comment")),
            tableName);
    }
}
