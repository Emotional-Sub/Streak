package com.streak.app.db;

import androidx.room.TypeConverter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Room 类型转换器：SQLite 只认基本类型，HabitItem 的 tags/completedDates（List）
 * 与 notes（Map）需序列化成 TEXT 列存储。沿用项目既有的 Gson，读回时对 null 兜底成空集合，
 * 保持与旧 JSON 存储时代一致的「字段永不为 null」语义。
 */
public final class Converters {
    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() {}.getType();

    private Converters() {
    }

    @TypeConverter
    public static String fromStringList(List<String> list) {
        return list == null ? null : GSON.toJson(list);
    }

    @TypeConverter
    public static List<String> toStringList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = GSON.fromJson(json, STRING_LIST);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @TypeConverter
    public static String fromStringMap(Map<String, String> map) {
        return map == null ? null : GSON.toJson(map);
    }

    @TypeConverter
    public static Map<String, String> toStringMap(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<String, String> map = GSON.fromJson(json, STRING_MAP);
            return map == null ? new HashMap<>() : map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
