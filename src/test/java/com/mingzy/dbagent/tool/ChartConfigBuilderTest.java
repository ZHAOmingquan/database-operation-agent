package com.mingzy.dbagent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartConfigBuilderTest {

    private static final List<String> COLS = List.of("status", "cnt");
    private static final List<List<Object>> ROWS = List.of(
            List.of("1", 1036L), List.of("0", 30264L));

    @Test
    void normalizeChartTypeSupportsAliases() {
        assertThat(ChartConfigBuilder.normalizeChartType("bar")).isEqualTo("bar");
        assertThat(ChartConfigBuilder.normalizeChartType("LINE")).isEqualTo("line");
        assertThat(ChartConfigBuilder.normalizeChartType("饼图")).isEqualTo("pie");
        assertThat(ChartConfigBuilder.normalizeChartType("折线图")).isEqualTo("line");
        assertThat(ChartConfigBuilder.normalizeChartType("unknown")).isEqualTo("bar");
        assertThat(ChartConfigBuilder.normalizeChartType(null)).isEqualTo("bar");
    }

    @Test
    void buildUsesExplicitFields() {
        Map<String, Object> cfg = ChartConfigBuilder.build("pie", "用户状态分布", "status", "cnt", COLS, ROWS);
        assertThat(cfg).containsEntry("chartType", "pie")
                .containsEntry("title", "用户状态分布")
                .containsEntry("xField", "status")
                .containsEntry("yField", "cnt");
    }

    @Test
    void buildAutoDerivesFieldsWhenMissing() {
        Map<String, Object> cfg = ChartConfigBuilder.build("bar", null, null, null, COLS, ROWS);
        assertThat(cfg).containsEntry("title", "统计图表")
                .containsEntry("xField", "status")
                .containsEntry("yField", "cnt");
    }

    @Test
    void buildFallsBackWhenFieldNotInColumns() {
        Map<String, Object> cfg = ChartConfigBuilder.build("line", "趋势", "not-exist", "not-exist", COLS, ROWS);
        assertThat(cfg).containsEntry("xField", "status").containsEntry("yField", "cnt");
    }

    @Test
    void buildRejectsEmptyRows() {
        assertThatThrownBy(() -> ChartConfigBuilder.build("bar", null, null, null, COLS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 行");
    }

    @Test
    void buildRejectsWhenNoNumericColumn() {
        List<List<Object>> noNumeric = List.of(List.of("a", "b"), List.of("c", "d"));
        assertThatThrownBy(() -> ChartConfigBuilder.build("bar", null, null, null, COLS, noNumeric))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数值列");
    }
}
