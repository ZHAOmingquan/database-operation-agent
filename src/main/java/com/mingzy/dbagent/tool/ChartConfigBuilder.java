package com.mingzy.dbagent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** render_chart 工具的图表配置构建：把查询结果的列/行与 AI 声明的展示意图包装为前端 ECharts 配置（纯函数，便于单测）。 */
public final class ChartConfigBuilder {

    private ChartConfigBuilder() {}

    /** bar（柱状）/line（折线）/pie（饼图）；未知值回退为 bar（容忍中文与大小写差异） */
    public static String normalizeChartType(String chartType) {
        if (chartType == null) return "bar";
        String t = chartType.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "line", "折线", "折线图" -> "line";
            case "pie", "饼图" -> "pie";
            case "bar", "柱状", "柱状图", "条形图" -> "bar";
            default -> "bar";
        };
    }

    /**
     * 构建图表配置：{chartType, title, xField, yField}。
     * 字段映射：优先采用 AI 指定的 x/y（须存在于结果列中）；缺省时 x 取第一列、y 取第一个数值列。
     * 无法确定数值列时抛 IllegalArgumentException（由工具层转为给模型的提示文本）。
     */
    public static Map<String, Object> build(String chartType, String title, String xField, String yField,
                                            List<String> columns, List<List<Object>> rows) {
        if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("统计查询未返回任何列");
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("统计查询返回 0 行数据，无法生成图表");

        String x = xField != null && columns.contains(xField) ? xField : columns.get(0);
        String y;
        if (yField != null && columns.contains(yField) && !yField.equals(x)) {
            y = yField;
        } else {
            y = firstNumericColumn(columns, rows, x);
            if (y == null) throw new IllegalArgumentException("未能识别数值列，请确保统计 SQL 包含数值型聚合列（如 count(*)）");
        }

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("chartType", normalizeChartType(chartType));
        cfg.put("title", title == null || title.isBlank() ? "统计图表" : title.trim());
        cfg.put("xField", x);
        cfg.put("yField", y);
        return cfg;
    }

    /** 取第一个包含数值的列（排除 xField）；未找到返回 null */
    static String firstNumericColumn(List<String> columns, List<List<Object>> rows, String xField) {
        for (int c = 0; c < columns.size(); c++) {
            String name = columns.get(c);
            if (name.equals(xField)) continue;
            for (List<Object> row : rows) {
                if (c < row.size() && row.get(c) instanceof Number) return name;
            }
        }
        return null;
    }
}
