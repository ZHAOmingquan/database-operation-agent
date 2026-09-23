package com.mingzy.dbagent.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlClassifierTest {

    @Test
    void queries() {
        assertThat(SqlClassifier.kind("select * from t")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("  SELECT 1")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("with a as (select 1) select * from a")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("-- 注释\nselect 1")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("/* c */ select 1")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("show tables")).isEqualTo(SqlKind.QUERY);
        assertThat(SqlClassifier.kind("explain select 1")).isEqualTo(SqlKind.QUERY);
    }

    @Test
    void writes() {
        assertThat(SqlClassifier.kind("insert into t values (1)")).isEqualTo(SqlKind.WRITE);
        assertThat(SqlClassifier.kind("UPDATE t SET a=1")).isEqualTo(SqlKind.WRITE);
        assertThat(SqlClassifier.kind("delete from t")).isEqualTo(SqlKind.WRITE);
        assertThat(SqlClassifier.kind("truncate table t")).isEqualTo(SqlKind.WRITE);
    }

    @Test
    void ddls() {
        assertThat(SqlClassifier.kind("create table t(a int)")).isEqualTo(SqlKind.DDL);
        assertThat(SqlClassifier.kind("drop table t")).isEqualTo(SqlKind.DDL);
        assertThat(SqlClassifier.kind("alter table t add column b int")).isEqualTo(SqlKind.DDL);
    }

    @Test
    void unknownDefaultsToWrite() {
        assertThat(SqlClassifier.kind("vacuum")).isEqualTo(SqlKind.WRITE);
    }

    @Test
    void deleteLikeDetection() {
        assertThat(SqlClassifier.isDeleteLike("delete from t")).isTrue();
        assertThat(SqlClassifier.isDeleteLike("DELETE FROM t WHERE id=1")).isTrue();
        assertThat(SqlClassifier.isDeleteLike("drop table t")).isTrue();
        assertThat(SqlClassifier.isDeleteLike("truncate table t")).isTrue();
        assertThat(SqlClassifier.isDeleteLike("-- 注释\ndelete from t")).isTrue();
        assertThat(SqlClassifier.isDeleteLike("/* c */ DROP TABLE t")).isTrue();
        assertThat(SqlClassifier.isDeleteLike("insert into t values(1)")).isFalse();
        assertThat(SqlClassifier.isDeleteLike("update t set a=1")).isFalse();
        assertThat(SqlClassifier.isDeleteLike("select * from t")).isFalse();
    }
}
