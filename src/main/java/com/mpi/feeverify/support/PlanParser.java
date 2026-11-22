package com.mpi.feeverify.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** EXPLAIN (FORMAT JSON) 输出解析：计划哈希、估计行数、代价、实际行数、执行耗时。 */
public final class PlanParser {

    private PlanParser() {
    }

    public record PlanInfo(String planHash, long estRows, double totalCost,
                           Long actualRows, Double executionMs, String summary) {
    }

    @SuppressWarnings("unchecked")
    public static PlanInfo parse(String explainJson) {
        List<Object> top = Jsons.readList(explainJson);
        Map<String, Object> root = (Map<String, Object>) top.get(0);
        Map<String, Object> plan = (Map<String, Object>) root.get("Plan");

        long estRows = ((Number) plan.getOrDefault("Plan Rows", 0)).longValue();
        double totalCost = ((Number) plan.getOrDefault("Total Cost", 0)).doubleValue();
        Long actualRows = plan.get("Actual Total Rows") instanceof Number n ? n.longValue() : null;
        Double execMs = root.get("Execution Time") instanceof Number n ? n.doubleValue() : null;

        List<String> nodes = new ArrayList<>();
        walk(plan, nodes);
        String planHash = Params.sha256(String.join(">", nodes));
        return new PlanInfo(planHash, estRows, totalCost, actualRows, execMs, String.join(" > ", nodes));
    }

    @SuppressWarnings("unchecked")
    private static void walk(Map<String, Object> node, List<String> nodes) {
        StringBuilder label = new StringBuilder(String.valueOf(node.get("Node Type")));
        if (node.get("Relation Name") != null) {
            label.append(" on ").append(node.get("Relation Name"));
        }
        if (node.get("Index Name") != null) {
            label.append(" using ").append(node.get("Index Name"));
        }
        nodes.add(label.toString());
        Object plans = node.get("Plans");
        if (plans instanceof List<?> list) {
            for (Object child : list) {
                walk((Map<String, Object>) child, nodes);
            }
        }
    }
}
