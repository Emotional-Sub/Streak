package com.streak.app.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Room TypeConverter 单测。TypeConverter 是纯 JVM 逻辑（Gson 序列化），
 * 不依赖 Android framework，可直接跑在本地 JUnit——正是存储层最该覆盖的部分。
 * 重点验证：往返一致、null/空串兜底成空集合、损坏 JSON 不抛异常。
 */
public class ConvertersTest {

    // ---- List<String> 往返 ----

    @Test
    public void stringList_roundTrip_preservesOrderAndContent() {
        List<String> original = Arrays.asList("2026-01-01", "2026-01-02", "2026-01-03");
        String json = Converters.fromStringList(original);
        List<String> restored = Converters.toStringList(json);
        assertEquals(original, restored);
    }

    @Test
    public void stringList_emptyList_roundTripsToEmpty() {
        String json = Converters.fromStringList(new ArrayList<>());
        List<String> restored = Converters.toStringList(json);
        assertTrue(restored.isEmpty());
    }

    @Test
    public void fromStringList_null_returnsNull() {
        // 写出侧：null 存成 SQL NULL（列可空）
        assertNull(Converters.fromStringList(null));
    }

    @Test
    public void toStringList_null_returnsEmptyNotNull() {
        // 读回侧：NULL 列兜底成空 List，保持「字段永不为 null」语义
        List<String> result = Converters.toStringList(null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void toStringList_emptyString_returnsEmpty() {
        assertTrue(Converters.toStringList("").isEmpty());
    }

    @Test
    public void toStringList_corruptJson_returnsEmptyNotCrash() {
        // 损坏/非法 JSON 不应抛异常，兜底成空集合
        List<String> result = Converters.toStringList("{not valid json[");
        assertTrue(result.isEmpty());
    }

    @Test
    public void stringList_unicodeContent_roundTrips() {
        List<String> original = Arrays.asList("晨练", "有氧", "英语");
        List<String> restored = Converters.toStringList(Converters.fromStringList(original));
        assertEquals(original, restored);
    }

    // ---- Map<String,String> 往返 ----

    @Test
    public void stringMap_roundTrip_preservesEntries() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("2026-01-01", "今天状态不错");
        original.put("2026-01-02", "有点累但坚持了");
        Map<String, String> restored = Converters.toStringMap(Converters.fromStringMap(original));
        assertEquals(original, restored);
    }

    @Test
    public void stringMap_emptyMap_roundTripsToEmpty() {
        Map<String, String> restored = Converters.toStringMap(Converters.fromStringMap(new HashMap<>()));
        assertTrue(restored.isEmpty());
    }

    @Test
    public void fromStringMap_null_returnsNull() {
        assertNull(Converters.fromStringMap(null));
    }

    @Test
    public void toStringMap_null_returnsEmptyNotNull() {
        assertTrue(Converters.toStringMap(null).isEmpty());
    }

    @Test
    public void toStringMap_emptyString_returnsEmpty() {
        assertTrue(Converters.toStringMap("").isEmpty());
    }

    @Test
    public void toStringMap_corruptJson_returnsEmptyNotCrash() {
        assertTrue(Converters.toStringMap("]]garbage{{").isEmpty());
    }

    @Test
    public void stringMap_unicodeValues_roundTrip() {
        Map<String, String> original = new HashMap<>();
        original.put("2026-02-14", "打卡备注：坚持！");
        Map<String, String> restored = Converters.toStringMap(Converters.fromStringMap(original));
        assertEquals("打卡备注：坚持！", restored.get("2026-02-14"));
    }
}
