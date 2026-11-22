package com.mpi.feeverify.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Jackson 便捷封装。 */
public final class Jsons {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsons() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> readMap(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static List<Map<String, Object>> readListOfMaps(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static List<Object> readList(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 把行中的 PGobject(jsonb) 值解析为原生 JSON 结构，便于 API 输出。 */
    public static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, unwrap(v)));
        return out;
    }

    public static List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(Jsons::normalizeRow).toList();
    }

    /** 容错读取：入参可以是已解析的 Map、JSON 字符串或 PGobject。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        if (v == null) {
            return null;
        }
        Object u = unwrap(v);
        if (u instanceof Map) {
            return (Map<String, Object>) u;
        }
        return readMap(String.valueOf(u));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        if (v == null) {
            return List.of();
        }
        Object u = unwrap(v);
        if (u instanceof List) {
            return (List<Object>) u;
        }
        return readList(String.valueOf(u));
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> asListOfMaps(Object v) {
        if (v == null) {
            return List.of();
        }
        Object u = unwrap(v);
        if (u instanceof List) {
            return (List<Map<String, Object>>) u;
        }
        return readListOfMaps(String.valueOf(u));
    }

    private static Object unwrap(Object v) {
        if (v instanceof org.postgresql.util.PGobject pg) {
            String value = pg.getValue();
            if (value == null) {
                return null;
            }
            try {
                return MAPPER.readValue(value, Object.class);
            } catch (Exception e) {
                return value;
            }
        }
        return v;
    }
}
