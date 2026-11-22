package com.mpi.feeverify.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数形态：规范化、分级（NARROW/MEDIUM/WIDE）、参数签名、门禁参数范围校验。
 * 相同 SQL 在不同参数范围下会产生不同参数签名，用于分别采集计划与建档。
 */
public final class Params {

    public static final List<String> KNOWN_KEYS =
            List.of("monthFrom", "monthTo", "orgCode", "settleStatus", "diagPrefix", "pageSize", "pageOffset");

    private Params() {
    }

    /** 规范化：已知键按固定顺序输出，空字符串视为 null。 */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            Object v = raw == null ? null : raw.get(key);
            if (v instanceof String s && s.isBlank()) {
                v = null;
            }
            out.put(key, v);
        }
        return out;
    }

    /** 基本校验：月份格式与先后关系。 */
    public static void validate(Map<String, Object> p) {
        Object mf = p.get("monthFrom");
        Object mt = p.get("monthTo");
        if (mf == null || mt == null) {
            throw ApiException.badRequest("参数 monthFrom/monthTo 必填（格式 YYYY-MM）");
        }
        YearMonth from = parseMonth(String.valueOf(mf));
        YearMonth to = parseMonth(String.valueOf(mt));
        if (from.isAfter(to)) {
            throw ApiException.badRequest("monthFrom 不能晚于 monthTo");
        }
    }

    public static YearMonth parseMonth(String s) {
        try {
            return YearMonth.parse(s);
        } catch (Exception e) {
            throw ApiException.badRequest("月份参数格式应为 YYYY-MM: " + s);
        }
    }

    /** 参数分级：月跨度 ≤1 为 NARROW、≤3 为 MEDIUM、其余 WIDE；未限定机构时上调一级。 */
    public static String classify(Map<String, Object> p) {
        YearMonth from = parseMonth(String.valueOf(p.get("monthFrom")));
        YearMonth to = parseMonth(String.valueOf(p.get("monthTo")));
        long span = (to.getYear() - from.getYear()) * 12L + (to.getMonthValue() - from.getMonthValue()) + 1;
        int level = span <= 1 ? 0 : span <= 3 ? 1 : 2;
        if (p.get("orgCode") == null && level < 2) {
            level++;
        }
        return switch (level) {
            case 0 -> "NARROW";
            case 1 -> "MEDIUM";
            default -> "WIDE";
        };
    }

    /** 参数签名 = 分级 + 参数值哈希。 */
    public static String signature(Map<String, Object> normalized) {
        return classify(normalized) + ":" + sha256(Jsons.write(normalized)).substring(0, 16);
    }

    /**
     * 门禁参数范围校验。
     * range 规则：monthFrom/monthTo 为精确月份；orgCode/settleStatus/diagPrefix 为
     * "ANY" | "NULL_ONLY" | 具体值；pageSizeMax 为整数上限。
     *
     * @return 违规说明列表（空 = 在已验证范围内）
     */
    public static List<String> rangeViolations(Map<String, Object> params, Map<String, Object> range) {
        List<String> violations = new ArrayList<>();
        if (range == null) {
            return List.of("门禁未绑定参数范围");
        }
        for (String key : List.of("monthFrom", "monthTo")) {
            Object bound = range.get(key);
            if (bound != null && !bound.equals(params.get(key) == null ? null : String.valueOf(params.get(key)))) {
                violations.add(key + " 超出已验证范围（绑定=" + bound + ", 实际=" + params.get(key) + "）");
            }
        }
        for (String key : List.of("orgCode", "settleStatus", "diagPrefix")) {
            Object bound = range.get(key);
            Object actual = params.get(key);
            if (bound == null || "ANY".equals(bound)) {
                continue;
            }
            if ("NULL_ONLY".equals(bound)) {
                if (actual != null) {
                    violations.add(key + " 仅限空值（该改写在此参数非空时未验证等价性）");
                }
            } else if (!bound.equals(actual == null ? null : String.valueOf(actual))) {
                violations.add(key + " 超出已验证范围（绑定=" + bound + ", 实际=" + actual + "）");
            }
        }
        Object maxPage = range.get("pageSizeMax");
        if (maxPage != null && params.get("pageSize") != null) {
            long ps = Long.parseLong(String.valueOf(params.get("pageSize")));
            if (ps > Long.parseLong(String.valueOf(maxPage))) {
                violations.add("pageSize 超过已验证上限 " + maxPage);
            }
        }
        return violations;
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
