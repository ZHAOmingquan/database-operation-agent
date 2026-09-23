package com.mingzy.dbagent.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LimitInjectorTest {

    @Test
    void appendsLimitWhenAbsent() {
        assertThat(LimitInjector.inject("select * from t", 10)).isEqualTo("select * from t LIMIT 10");
        assertThat(LimitInjector.inject("select * from t;", 10)).isEqualTo("select * from t LIMIT 10");
        assertThat(LimitInjector.inject("select * from t \n;", 10)).isEqualTo("select * from t LIMIT 10");
    }

    @Test
    void keepsExistingLimit() {
        assertThat(LimitInjector.inject("select * from t limit 5", 10)).isEqualTo("select * from t limit 5");
        assertThat(LimitInjector.inject("select * from t LIMIT 5 OFFSET 2", 10)).isEqualTo("select * from t LIMIT 5 OFFSET 2");
    }

    @Test
    void ignoresLimitInsideStringLiteral() {
        String sql = "select 'limit 5' as x from t";
        assertThat(LimitInjector.inject(sql, 10)).isEqualTo(sql + " LIMIT 10");
    }
}
