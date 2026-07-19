package com.streak.app.util;

/**
 * 打卡弹层异步回调的「过期结果」判定（从 {@code HabitsFragment} 抽出的纯逻辑，便于单测）。
 *
 * <p><b>背景。</b>打卡弹层的相机/相册回调在弹层构建后异步触发：用户可能在图片复制/落库
 * 期间关掉弹层、或又打开另一个习惯的弹层。发起异步任务时会捕获当时的「弹层代号」
 * （单调递增的 int）与「弹层 binding 引用」；结果回来时必须判断它是否仍属于<b>当前那个</b>弹层。
 * 若不属于，复制/落库出的图片就成了未引用文件，必须删除、绝不串写进新弹层——否则会造成
 * 跨弹层图片错配与孤儿文件残留。</p>
 *
 * <p><b>判定规则。</b>代号或 binding 任一与当前不一致，即视为过期（stale）：</p>
 * <ul>
 *   <li>代号不同：期间开过新弹层或关过弹层（开/关都自增代号），结果属于过去那一代；</li>
 *   <li>binding 不同：当前活动弹层已不是发起时那个实例（双保险，防代号意外未变的边界）。</li>
 * </ul>
 *
 * <p>用 {@code ||} 而非 {@code &&}：两个条件是<b>独立的失效信号</b>，任一命中都说明结果已过期，
 * 必须丢弃；要求两者同时不符才丢弃会漏判「代号已换但恰好比对到同一 binding」等情形。</p>
 */
public final class CheckInCallbackGuard {

    private CheckInCallbackGuard() {
    }

    /**
     * 判断一个异步回调结果是否已过期（不再属于当前打卡弹层）。
     *
     * @param capturedGeneration 发起异步任务时捕获的弹层代号
     * @param currentGeneration  结果回来时的当前弹层代号
     * @param capturedSheet      发起时捕获的弹层 binding 引用（可为 null）
     * @param currentSheet       当前活动弹层 binding 引用（弹层已关时为 null）
     * @return true 表示结果已过期，调用方应丢弃并清理其产物（删除复制出的图片），不得写入当前弹层
     */
    public static boolean isStale(int capturedGeneration, int currentGeneration,
                                  Object capturedSheet, Object currentSheet) {
        return capturedGeneration != currentGeneration || capturedSheet != currentSheet;
    }
}
