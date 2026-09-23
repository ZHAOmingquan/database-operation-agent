package com.mingzy.dbagent.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SqlExecutorTest {

    private DataSource ds;
    private SqlExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
            for (int i = 1; i <= 25; i++) {
                st.execute("INSERT INTO t(name) VALUES ('n" + i + "')");
            }
        }
        executor = new SqlExecutor();
    }

    @Test
    void queryInjectsLimitAndReturnsRows() {
        SqlExecResult r = executor.executeQuery(ds, "select * from t", 10, 500, 30);
        assertThat(r.success()).isTrue();
        assertThat(r.columns()).containsExactly("id", "name");
        assertThat(r.rows()).hasSize(10);
        assertThat(r.rowCount()).isEqualTo(10);
    }

    @Test
    void respectsExplicitLimit() {
        SqlExecResult r = executor.executeQuery(ds, "select * from t limit 3", 10, 500, 30);
        assertThat(r.rows()).hasSize(3);
    }

    @Test
    void truncatesAtMaxRowsWhenNoLimitPossible() {
        SqlExecResult r = executor.executeQuery(ds, "select * from t limit 20", 10, 5, 30);
        assertThat(r.rows()).hasSize(5);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void writeReturnsAffectedRows() {
        SqlExecResult r = executor.executeUpdate(ds, "update t set name='x' where id <= 3", 30);
        assertThat(r.success()).isTrue();
        assertThat(r.affectedRows()).isEqualTo(3);
        assertThat(r.resultType()).isEqualTo("update");
    }

    @Test
    void badSqlReturnsErrorResult() {
        SqlExecResult r = executor.executeQuery(ds, "select * from not_exists", 10, 500, 30);
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).containsIgnoringCase("no such table");
    }
}
