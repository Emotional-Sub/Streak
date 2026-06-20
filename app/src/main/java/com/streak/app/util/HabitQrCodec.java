package com.streak.app.util;

import android.net.Uri;

import com.streak.app.model.HabitItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 习惯二维码编解码。格式：
 *   streak-habit/v1?t=标题&c=内容&cat=分类&r=提醒&tags=标签1,标签2
 * 不含照片（本地 file:// 跨设备无意义）。
 */
public final class HabitQrCodec {

    public static final String SCHEME = "streak-habit/v1";

    private HabitQrCodec() {
    }

    /** 把习惯编码成可放进二维码的字符串。 */
    public static String encode(HabitItem habit) {
        Uri.Builder builder = Uri.parse(SCHEME).buildUpon();
        builder.appendQueryParameter("t", nonNull(habit.getTitle()));
        builder.appendQueryParameter("c", nonNull(habit.getContent()));
        builder.appendQueryParameter("cat", nonNull(habit.getCategory()));
        builder.appendQueryParameter("r", nonNull(habit.getReminderTime()));
        if (habit.getTags() != null && !habit.getTags().isEmpty()) {
            builder.appendQueryParameter("tags", TextUtilsJoin(habit.getTags()));
        }
        return builder.build().toString();
    }

    /** 解析扫到的内容；非本格式返回 null。 */
    public static Decoded decode(String raw) {
        if (raw == null || !raw.startsWith(SCHEME)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(raw);
            String title = uri.getQueryParameter("t");
            if (title == null || title.trim().isEmpty()) {
                return null;
            }
            Decoded decoded = new Decoded();
            decoded.title = title;
            decoded.content = nonNull(uri.getQueryParameter("c"));
            decoded.category = nonNull(uri.getQueryParameter("cat"));
            decoded.reminderTime = uri.getQueryParameter("r");
            decoded.tags = uri.getQueryParameter("tags");
            return decoded;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static String TextUtilsJoin(List<String> tags) {
        List<String> cleaned = new ArrayList<>();
        for (String tag : tags) {
            if (tag != null && !tag.trim().isEmpty()) {
                cleaned.add(tag.trim());
            }
        }
        return android.text.TextUtils.join(",", cleaned);
    }

    /** 解析结果，字段直接对应 HabitEditorActivity 的 EXTRA_TPL_*。 */
    public static class Decoded {
        public String title;
        public String content;
        public String category;
        public String reminderTime;
        public String tags;
    }
}
