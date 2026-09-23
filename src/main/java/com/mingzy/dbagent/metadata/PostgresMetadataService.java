package com.mingzy.dbagent.metadata;

import com.mingzy.dbagent.metadata.model.ColumnInfo;
import com.mingzy.dbagent.metadata.model.TableInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

@Component
public class PostgresMetadataService implements MetadataService {

    @Override
    public List<TableInfo> tables(DataSource ds) {
        return new JdbcTemplate(ds).query(
            "SELECT table_name, table_type FROM information_schema.tables " +
            "WHERE table_schema = current_schema() ORDER BY table_name",
            (rs, i) -> new TableInfo(rs.getString("table_name"), rs.getString("table_type"), null));
    }

    @Override
    public List<ColumnInfo> columns(DataSource ds, String tableName) {
        return new JdbcTemplate(ds).query(
            "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, " +
            "  (tc.constraint_type = 'PRIMARY KEY') AS is_pk, " +
            "  col_description(cl.oid, c.ordinal_position) AS column_comment " +
            "FROM information_schema.columns c " +
            "JOIN pg_class cl ON cl.relname = c.table_name " +
            "LEFT JOIN information_schema.table_constraints tc ON tc.table_name = c.table_name " +
            "  AND tc.constraint_type = 'PRIMARY KEY' " +
            "LEFT JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name " +
            "  AND kcu.column_name = c.column_name AND kcu.table_name = c.table_name " +
            "WHERE c.table_schema = current_schema() AND c.table_name = ? " +
            "ORDER BY c.ordinal_position",
            (rs, i) -> new ColumnInfo(rs.getString("column_name"), rs.getString("data_type"),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    rs.getBoolean("is_pk"), rs.getString("column_default"),
                    rs.getString("column_comment")),
            tableName);
    }
}
