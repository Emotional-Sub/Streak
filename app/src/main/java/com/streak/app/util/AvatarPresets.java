package com.streak.app.util;

import com.streak.app.R;

/**
 * 内置默认头像：用 "preset:索引" 形式存进 avatarUri，
 * 渲染时映射到对应 drawable，无需复制文件，重装/换机也不会失效。
 */
public final class AvatarPresets {
    public static final String PREFIX = "preset:";

    private static final int[] DRAWABLES = {
            R.drawable.avatar_preset_1,
            R.drawable.avatar_preset_2,
            R.drawable.avatar_preset_3,
            R.drawable.avatar_preset_4,
            R.drawable.avatar_preset_5,
            R.drawable.avatar_preset_6,
    };

    private AvatarPresets() {
    }

    public static int count() {
        return DRAWABLES.length;
    }

    /** 第 index 个预置头像对应的 avatarUri 值。 */
    public static String uriFor(int index) {
        return PREFIX + index;
    }

    public static boolean isPreset(String avatarUri) {
        return avatarUri != null && avatarUri.startsWith(PREFIX);
    }

    /** 取预置头像的 drawable 资源 id；非预置或越界返回 0。 */
    public static int drawableFor(String avatarUri) {
        if (!isPreset(avatarUri)) {
            return 0;
        }
        try {
            int index = Integer.parseInt(avatarUri.substring(PREFIX.length()));
            if (index >= 0 && index < DRAWABLES.length) {
                return DRAWABLES[index];
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /** 第 index 个预置头像的 drawable 资源 id；越界返回第一个，避免调用方传入陈旧索引时崩溃。 */
    public static int drawableAt(int index) {
        if (index < 0 || index >= DRAWABLES.length) {
            return DRAWABLES[0];
        }
        return DRAWABLES[index];
    }
}
