package com.momocraft.textfilter;

/**
 * 模糊匹配的字符间隔上限（按字符类型分别配置）。
 * <p>
 * 当违禁词字符之间被插入间隔字符时：
 * <ul>
 *   <li>chinese: 允许的中文间隔字符数量上限</li>
 *   <li>english: 允许的英文间隔字符数量上限</li>
 *   <li>others: 允许的其他（数字/标点/符号等）间隔字符数量上限</li>
 * </ul>
 * 只有当三类间隔字符的数量同时不超过对应上限时，该模糊匹配才成立；
 * 任意一类超出上限，则该词直接放行（不视为违禁词）。
 */
public class CharGapLimits {

    public final int chinese;
    public final int english;
    public final int others;

    public CharGapLimits(int chinese, int english, int others) {
        this.chinese = Math.max(0, chinese);
        this.english = Math.max(0, english);
        this.others = Math.max(0, others);
    }

    /** 兼容旧的单值 max-char-gap 配置：三类使用同一个上限 */
    public static CharGapLimits uniform(int gap) {
        return new CharGapLimits(gap, gap, gap);
    }

    @Override
    public String toString() {
        return "CharGapLimits{chinese=" + chinese + ", english=" + english + ", others=" + others + "}";
    }
}
