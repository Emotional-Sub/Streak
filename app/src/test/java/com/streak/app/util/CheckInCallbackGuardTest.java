package com.streak.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 打卡弹层异步回调「过期结果」判定的真值表测试。
 *
 * <p>锁死 {@link CheckInCallbackGuard#isStale} 的契约：代号与 binding 是两个独立失效信号，
 * 任一不一致即过期（用 {@code ||} 而非 {@code &&}）。有人误把或改成与、或漏掉某个条件，
 * 都会在此挂测——这条规则此前只有 HabitsFragment 里的代码审查保证。</p>
 */
public class CheckInCallbackGuardTest {

    @Test
    public void sameGenerationSameSheet_notStale() {
        Object sheet = new Object();
        assertFalse("同代号同弹层：结果仍属当前弹层，不应丢弃",
                CheckInCallbackGuard.isStale(3, 3, sheet, sheet));
    }

    @Test
    public void differentGeneration_isStale() {
        Object sheet = new Object();
        // 期间开过新弹层或关过弹层（开/关都自增代号）：结果属过去那一代，必须丢弃。
        assertTrue(CheckInCallbackGuard.isStale(3, 4, sheet, sheet));
    }

    @Test
    public void differentSheet_isStale() {
        // 代号恰好相同但活动弹层已换成另一个实例：双保险仍判过期。
        assertTrue(CheckInCallbackGuard.isStale(3, 3, new Object(), new Object()));
    }

    @Test
    public void bothDifferent_isStale() {
        assertTrue(CheckInCallbackGuard.isStale(3, 4, new Object(), new Object()));
    }

    @Test
    public void sheetClosed_currentSheetNull_isStale() {
        // 弹层已关：当前 binding 置 null，与捕获时的非 null 不等，判过期。
        assertTrue(CheckInCallbackGuard.isStale(3, 3, new Object(), null));
    }

    @Test
    public void bothSheetsNull_sameGeneration_notStale() {
        // 边界：两侧 binding 均为 null 且同代号——引用相等，不过期（不会误删）。
        assertFalse(CheckInCallbackGuard.isStale(0, 0, null, null));
    }
}
