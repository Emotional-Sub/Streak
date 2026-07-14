package com.streak.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.streak.app.model.HabitItem;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 习惯二维码编解码单测。HabitQrCodec 依赖 android.net.Uri / TextUtils，
 * 纯 JVM 无法运行，故用 Robolectric 提供框架实现。
 * 重点：encode→decode 往返一致、不可信输入被清洗/截断、伪造前缀被拒。
 */
@RunWith(RobolectricTestRunner.class)
public class HabitQrCodecTest {

    private static HabitItem habit(String title, String content, String category,
                                   String reminder, List<String> tags) {
        HabitItem item = new HabitItem();
        item.setTitle(title);
        item.setContent(content);
        item.setCategory(category);
        item.setReminderTime(reminder);
        item.setTags(tags == null ? new ArrayList<>() : new ArrayList<>(tags));
        return item;
    }

    // ---- 往返一致 ----

    @Test
    public void encodeDecode_roundTrip_preservesFields() {
        HabitItem item = habit("晨跑打卡", "坚持晨跑 30 分钟", "运动", "06:30",
                Arrays.asList("晨练", "有氧"));
        String encoded = HabitQrCodec.encode(item);
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(encoded);

        assertNotNull(decoded);
        assertEquals("晨跑打卡", decoded.title);
        assertEquals("坚持晨跑 30 分钟", decoded.content);
        assertEquals("运动", decoded.category);
        assertEquals("06:30", decoded.reminderTime);
        assertEquals("晨练,有氧", decoded.tags);
    }

    @Test
    public void encode_startsWithScheme() {
        String encoded = HabitQrCodec.encode(habit("A", "", "学习", "20:00", null));
        assertTrue(encoded.startsWith(HabitQrCodec.SCHEME));
    }

    @Test
    public void roundTrip_unicodeAndSpaces() {
        HabitItem item = habit("读书 Reading", "每天 20 页 & 做笔记", "阅读", "21:00", null);
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(HabitQrCodec.encode(item));
        assertNotNull(decoded);
        assertEquals("读书 Reading", decoded.title);
        assertEquals("每天 20 页 & 做笔记", decoded.content);
    }

    @Test
    public void roundTrip_noTags_leavesTagsNull() {
        HabitItem item = habit("无标签", "内容", "生活", "12:00", new ArrayList<>());
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(HabitQrCodec.encode(item));
        assertNotNull(decoded);
        assertNull(decoded.tags);
    }

    // ---- 前缀校验 ----

    @Test
    public void decode_nullOrNonMatching_returnsNull() {
        assertNull(HabitQrCodec.decode(null));
        assertNull(HabitQrCodec.decode(""));
        assertNull(HabitQrCodec.decode("https://example.com"));
    }

    @Test
    public void decode_forgedPrefix_returnsNull() {
        // "streak-habit/v1EVIL?..." 不得因松散前缀匹配而通过
        assertNull(HabitQrCodec.decode(HabitQrCodec.SCHEME + "EVIL?t=x"));
    }

    @Test
    public void decode_exactSchemeNoQuery_returnsNull() {
        // 严格等于 SCHEME 但没有标题 -> 无有效习惯，返回 null
        assertNull(HabitQrCodec.decode(HabitQrCodec.SCHEME));
    }

    @Test
    public void decode_emptyTitle_returnsNull() {
        assertNull(HabitQrCodec.decode(HabitQrCodec.SCHEME + "?t=%20%20"));
    }

    // ---- 不可信输入清洗 ----

    @Test
    public void decode_stripsControlChars() {
        // 标题里混入换行/制表符，解码后应被过滤掉
        HabitItem item = habit("正常标题", "第一行\n第二行\t制表", "学习", "20:00", null);
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(HabitQrCodec.encode(item));
        assertNotNull(decoded);
        assertEquals("第一行第二行制表", decoded.content);
    }

    @Test
    public void decode_truncatesOverlongTitle() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longTitle.append('A');
        }
        HabitItem item = habit(longTitle.toString(), "", "学习", "20:00", null);
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(HabitQrCodec.encode(item));
        assertNotNull(decoded);
        // MAX_TITLE = 60
        assertEquals(60, decoded.title.length());
    }

    @Test
    public void decode_nullReminderWhenEmpty() {
        HabitItem item = habit("标题", "内容", "学习", "", null);
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(HabitQrCodec.encode(item));
        assertNotNull(decoded);
        assertNull(decoded.reminderTime);
    }

    @Test
    public void encode_nullFields_doNotCrash() {
        HabitItem bare = new HabitItem();
        bare.setTitle("仅标题");
        bare.setContent(null);
        String encoded = HabitQrCodec.encode(bare);
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(encoded);
        assertNotNull(decoded);
        assertEquals("仅标题", decoded.title);
    }
}
