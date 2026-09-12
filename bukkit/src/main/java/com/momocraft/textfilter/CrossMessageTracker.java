package com.momocraft.textfilter;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CrossMessageTracker {

    private final TextFilter plugin;
    private final ConcurrentHashMap<UUID, List<TrackingState>> trackingStates;

    public CrossMessageTracker(TextFilter plugin) {
        this.plugin = plugin;
        this.trackingStates = new ConcurrentHashMap<>();
    }

    /** 内部返回结构：fuzzy 前缀匹配的结果 —— 命中的 remaining 字符数 + 在传入文本内的索引。 */
    private static class FuzzyPrefixMatch {
        final int matchedLen;
        final int[] positionsInCurrent; // 在传入文本内的索引（按命中顺序）
        // 部分匹配时：最后一个命中字符之后到文本末尾的遗留间隔字符计数。
        // 这些字符位于"已命中字符"与"下一条消息"之间，必须带入 state 继续计数，
        // 否则跨消息匹配会凭空吞掉上一条消息尾部的夹字，绕过 max-char-gap 限制。
        final int trailingChinese;
        final int trailingEnglish;
        final int trailingOthers;

        FuzzyPrefixMatch(int matchedLen, int[] positionsInCurrent) {
            this(matchedLen, positionsInCurrent, 0, 0, 0);
        }

        FuzzyPrefixMatch(int matchedLen, int[] positionsInCurrent, int trailingChinese, int trailingEnglish, int trailingOthers) {
            this.matchedLen = matchedLen;
            this.positionsInCurrent = positionsInCurrent;
            this.trailingChinese = trailingChinese;
            this.trailingEnglish = trailingEnglish;
            this.trailingOthers = trailingOthers;
        }

        static final FuzzyPrefixMatch NONE = new FuzzyPrefixMatch(0, new int[0]);
    }

    /** 内部返回结构：suffix fuzzy 匹配的结果。 */
    private static class FuzzySuffixMatch {
        final boolean matched;
        final int[] positionsInCurrent;

        FuzzySuffixMatch(boolean matched, int[] positionsInCurrent) {
            this.matched = matched;
            this.positionsInCurrent = positionsInCurrent;
        }

        static final FuzzySuffixMatch NONE = new FuzzySuffixMatch(false, new int[0]);
    }

    public TrackingResult checkAndTrack(Player player, String text, String context) {
        if (text == null || text.isEmpty()) {
            cleanupExpired(player.getUniqueId());
            return null;
        }

        UUID playerId = player.getUniqueId();
        // 双域匹配（与单消息"整剔轮 → 常规轮"两轮对齐）：
        //   整剔域：整体剔除 MiniMessage 标签块（含载荷），标签内字符不参与匹配与间隔计算；
        //   载荷域：复原标签后的常规形态（载荷提取为可见文本）。
        // 整剔域优先（如 "傻" + "<click:run_command:/say 一二>逼</click>" 中载荷 "一二" 不作为间隔干扰）；
        // 载荷域兜底（如 "傻" + "<click:run_command:/say 逼>一</click>" 中载荷 "逼" 才是词后缀，整剔域已无该字符）。
        // 命中时 positions 携带所在域标记，调用方据此选择对应打码映射，避免索引错位。
        TextProcessor processor = new TextProcessor(text);
        DomainViews stripped = buildDomain(processor.getStrippedTagsText(), true);
        DomainViews processed = buildDomain(processor.getProcessedText(), false);

        List<TrackingResult> results = new ArrayList<>();

        // 说明：单消息检测（连续字符 > 跨字符）由调用方先行完成且未命中时才会进入跨消息追踪，
        // 此处不再重复做当前消息独立检测，避免"提示与替换走不同逻辑"导致的不一致。

        // ---- 1. 走已有的 tracking state：带夹字 fuzzy 的前缀 / suffix 匹配（整剔域优先，载荷域兜底）----
        List<TrackingState> states = trackingStates.get(playerId);
        List<TrackingState> toRemove = new ArrayList<>();

        if (states != null && !states.isEmpty()) {
            for (TrackingState state : states) {
                TrackingResult r = matchStateOnDomains(state, context, stripped, processed);
                if (r != null) {
                    results.add(r);
                }
                if (state.markedForRemoval) {
                    toRemove.add(state);
                }
            }

            states.removeAll(toRemove);
            if (states.isEmpty()) {
                trackingStates.remove(playerId);
            }
        }

        // ---- 2. 无现有命中时，为当前消息新建 partial tracking state（正向 + 反向；整剔域优先，载荷域仅补建缺失词）----
        if (results.isEmpty()) {
            buildPartialStates(playerId, stripped, true);
            buildPartialStates(playerId, processed, false);
        }

        cleanupExpired(playerId);

        if (!results.isEmpty()) {
            return results.get(0);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 双域视图：整剔域 / 载荷域
    // ------------------------------------------------------------------

    /** 单个匹配域视图：raw 为 normalize 后文本（matchBannedWord 用），
     *  compressed 为压缩重复字符后的文本，compressedToRaw 供压缩索引回映到视图索引。 */
    private static class DomainViews {
        final String raw;
        final String compressed;
        final int[] compressedToRaw;
        /** true = 整剔视图（getStrippedTagsText），false = 载荷提取视图（getProcessedText）。 */
        final boolean strippedView;

        DomainViews(String raw, String compressed, int[] compressedToRaw, boolean strippedView) {
            this.raw = raw;
            this.compressed = compressed;
            this.compressedToRaw = compressedToRaw;
            this.strippedView = strippedView;
        }
    }

    private DomainViews buildDomain(String normalizedText, boolean strippedView) {
        if (normalizedText == null) normalizedText = "";
        // 压缩连续重复字符（"傻1111逼" 视作 "傻1逼"），与单消息压缩检测语义一致
        StringBuilder builder = new StringBuilder();
        int[] map = ColorCodeUtils.compressRepeatingChars(normalizedText, builder);
        return new DomainViews(normalizedText, builder.toString(), map, strippedView);
    }

    /** 对单个 state 依次在整剔域、载荷域上匹配（哪个域先有进展用哪个域）。
     *  命中时返回携带 positions 所在域标记的 TrackingResult；无命中返回 null（state 更新照常进行）。 */
    private TrackingResult matchStateOnDomains(TrackingState state, String context,
            DomainViews stripped, DomainViews processed) {
        int levelChatboxGap = plugin.getConfigManager().getChatboxGapForLevel(state.level);
        CharGapLimits charGapLimits = plugin.getConfigManager().getMaxCharGapForLevel(state.level);
        boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
        String lowerBanned = CharacterMapper.normalize(state.bannedWord.toLowerCase());

        // 生命周期消息计数：从"检测到可能的违禁词"的下一条消息起，之后的每条消息
        // （无论是否有进展、是否被单消息检测替换/阻止、是否已触发成功）都累计计入
        // max-chatbox-gap + 1 条检测预算；中途消息只消耗预算、不得重置或暂停计数。
        // 因此 "傻" → "二逼"(被替换) → "逼"… 中 "二逼" 也消耗一条预算，触发一次
        // "傻逼" 后仍可继续检测，直到累计消息数超过 max-chatbox-gap + 1 才停止。
        state.gapCounter++;
        state.lastUpdateTime = System.currentTimeMillis();
        if (levelChatboxGap <= 0 || state.gapCounter > levelChatboxGap + 1) {
            state.markedForRemoval = true;
            return null;
        }

        if (state.hasMatched) {
            // 触发后仍处于检测预算内的消息：只需在 char-gap 限制内 fuzzy 命中
            // banned 词尾部（正向或反转词）即继续触发 —— 触发一次"傻逼"后，单独
            // 出现的"逼"也应继续视为违规并触发，直到预算耗尽。
            // 与旧实现的区别：旧 fuzzySuffixMatch 在夹字超限时仍返回已匹配的尾部字符
            // （"一二逼" 只要含 "逼" 即命中），导致间隔夹字超限的消息也误触发；
            // 现改为候选扫描全程不得超限，超限候选直接失败（chinese=0 时 "一逼"/
            // "一二逼" 因夹字超限不触发），单独 "逼" 则可正常触发。
            SuffixHit hit = matchBannedSuffixOnDomains(state, stripped, processed);
            if (hit != null) {
                return new TrackingResult(true, true, hit.bannedWord, state.level, context, hit.positions, hit.strippedView);
            }
            return null;
        }

        String remaining = lowerBanned.substring(state.currentPosition);
        // 整剔域优先；载荷域兜底。传入 state 携带的遗留间隔：上一条消息尾部的夹字必须继续计入
        FuzzyPrefixMatch pm = prefixMatch(stripped.compressed, remaining, charGapLimits, state, fuzzyMatch);
        DomainViews used = stripped;
        if (pm.matchedLen <= 0) {
            pm = prefixMatch(processed.compressed, remaining, charGapLimits, state, fuzzyMatch);
            used = processed;
        }

        if (pm.matchedLen > 0) {
            state.currentPosition = state.currentPosition + pm.matchedLen;
            // 部分匹配时记录新的遗留间隔；完整匹配时 trailing 为 0
            state.pendingChinese = pm.trailingChinese;
            state.pendingEnglish = pm.trailingEnglish;
            state.pendingOthers = pm.trailingOthers;
            // 记住最后一次在当前消息中匹配到的位置（供触发时替换），映射回视图索引
            int[] mappedPositions = mapToOriginal(pm.positionsInCurrent, used.compressedToRaw);
            state.lastMatchPositions = mappedPositions;

            if (state.currentPosition >= lowerBanned.length()) {
                state.hasMatched = true;
                return new TrackingResult(true, true, state.bannedWord, state.level, context, mappedPositions, used.strippedView);
            }
            if (pm.trailingChinese > charGapLimits.chinese
                    || pm.trailingEnglish > charGapLimits.english
                    || pm.trailingOthers > charGapLimits.others) {
                // 遗留间隔已超限：该 state 永远无法完成匹配，直接移除（避免阻挡后续新 state）
                state.markedForRemoval = true;
            }
        } else {
            // 两域均无进展：本条消息未推进匹配进度。这类消息可能是
            // 1) 已被单消息检测成功替换的消息（如 "二逼" 被打码成 "**"），
            // 2) 因夹字超出 char-gap 而被阻止的消息（如 "一逼"）。
            // 它们只消耗一条消息级检测预算（开头已统一 gapCounter++），不把其字符计入
            // char-gap 遗留间隔、也不因此移除 state —— 否则任意一次被替换/被阻止的消息
            // 都会永久杀死未完成的跨消息检测，导致后续仍在预算内的消息无法接上。
            state.lastMatchPositions = new int[0];
        }
        return null;
    }

    private static FuzzyPrefixMatch prefixMatch(String compressedText, String remaining, CharGapLimits limits,
            TrackingState state, boolean fuzzyMatch) {
        return fuzzyMatch
                ? fuzzyPrefixMatch(compressedText, remaining, limits, state.pendingChinese, state.pendingEnglish, state.pendingOthers)
                : strictPrefixMatch(compressedText, remaining, limits, state.pendingChinese, state.pendingEnglish, state.pendingOthers);
    }

    /** 为当前消息新建 partial tracking state（正向 + 反向，基于压缩文本）。
     *  整剔域调用时 allowOverwrite=true（进度优先覆盖）；载荷域调用时仅补建缺失词，避免覆盖整剔域进度。 */
    private void buildPartialStates(UUID playerId, DomainViews domain, boolean allowOverwrite) {
        for (Map.Entry<String, List<String>> entry : plugin.getConfigManager().getBannedWordsByLevel().entrySet()) {
            String level = entry.getKey();
            int levelChatboxGap = plugin.getConfigManager().getChatboxGapForLevel(level);

            if (levelChatboxGap <= 0) {
                continue;
            }

            boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
            CharGapLimits charGapLimits = plugin.getConfigManager().getMaxCharGapForLevel(level);
            boolean reverseEnabled = plugin.getConfigManager().isReverseMatchEnable()
                    && plugin.getConfigManager().isReverseMatchEnableForLevel(level);

            for (String bannedWord : entry.getValue()) {
                if (bannedWord == null || bannedWord.isEmpty()) continue;

                String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
                if (lowerBanned.isEmpty()) continue;

                // 追踪目标：正向词 + （启用反向时）反转词；回文词跳过重复目标
                List<String> targets = new ArrayList<>();
                targets.add(bannedWord);
                if (reverseEnabled && bannedWord.length() >= 2) {
                    String reversed = ColorCodeUtils.reverseWord(bannedWord);
                    if (!CharacterMapper.normalize(reversed.toLowerCase()).equals(lowerBanned)) {
                        targets.add(reversed);
                    }
                }

                // 为所有部分匹配的词都建 state（若同级词表中其它同首字符词排在前面，会导致后面的词跨消息永远接不上）
                for (String target : targets) {
                    String lowerTarget = CharacterMapper.normalize(target.toLowerCase());
                    char firstChar = lowerTarget.charAt(0);
                    int s = 0;
                    while (s < domain.compressed.length()) {
                        int idx = domain.compressed.indexOf(firstChar, s);
                        if (idx < 0) break;
                        String tail = domain.compressed.substring(idx);
                        // 消息内新建 state：词首之前的字符不算间隔（pre-gap 为 0）
                        FuzzyPrefixMatch pm = fuzzyMatch
                                ? fuzzyPrefixMatch(tail, lowerTarget, charGapLimits, 0, 0, 0)
                                : strictPrefixMatch(tail, lowerTarget, charGapLimits, 0, 0, 0);
                        if (pm.matchedLen > 0 && pm.matchedLen < lowerTarget.length()) {
                            // 遗留间隔已超限：从该位置起步永远无法完成匹配，不建 state
                            if (pm.trailingChinese > charGapLimits.chinese
                                    || pm.trailingEnglish > charGapLimits.english
                                    || pm.trailingOthers > charGapLimits.others) {
                                break;
                            }
                            // tail 内索引平移回域全局，再映射回视图索引
                            int[] globalPos = new int[pm.positionsInCurrent.length];
                            for (int k = 0; k < pm.positionsInCurrent.length; k++) {
                                int cp = pm.positionsInCurrent[k] + idx;
                                globalPos[k] = (cp >= 0 && cp < domain.compressedToRaw.length) ? domain.compressedToRaw[cp] : cp;
                            }
                            if (allowOverwrite) {
                                addTrackingState(playerId, target, level, pm.matchedLen, globalPos,
                                        pm.trailingChinese, pm.trailingEnglish, pm.trailingOthers);
                            } else {
                                addTrackingStateIfAbsent(playerId, target, level, pm.matchedLen, globalPos,
                                        pm.trailingChinese, pm.trailingEnglish, pm.trailingOthers);
                            }
                            break;
                        }
                        s = idx + 1;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Strict / Fuzzy prefix matching（传入文本为压缩后文本，返回压缩域索引）
    // ------------------------------------------------------------------

    /** 严格前缀匹配：仅从 text[0] 起逐字符对齐，遇不匹配就停。
     *  带跨消息遗留间隔校验：遗留间隔已超限时直接无法匹配；部分匹配时计算新的遗留间隔。 */
    private static FuzzyPrefixMatch strictPrefixMatch(String text, String prefix, CharGapLimits limits,
            int pendingChinese, int pendingEnglish, int pendingOthers) {
        if (pendingChinese > limits.chinese || pendingEnglish > limits.english || pendingOthers > limits.others) {
            return FuzzyPrefixMatch.NONE;
        }
        List<Integer> pos = new ArrayList<>();
        int i = 0;
        while (i < text.length() && i < prefix.length() && text.charAt(i) == prefix.charAt(i)) {
            pos.add(i);
            i++;
        }
        int[] arr = new int[pos.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = pos.get(k);

        if (pos.size() > 0 && pos.size() < prefix.length()) {
            // 部分匹配：最后一个命中字符（索引 i-1）之后的全部字符都是遗留间隔
            int tC = 0, tE = 0, tO = 0;
            for (int k = i; k < text.length(); k++) {
                switch (CharacterMapper.classify(text.charAt(k))) {
                    case CHINESE: tC++; break;
                    case ENGLISH: tE++; break;
                    default: tO++; break;
                }
            }
            return new FuzzyPrefixMatch(pos.size(), arr, tC, tE, tO);
        }
        return new FuzzyPrefixMatch(pos.size(), arr);
    }

    /** Fuzzy 前缀匹配：允许按 chinese/english/others 三类计数夹字，任一类超过上限即放弃窗口，
     *  但只要能匹配到 remaining 的字符就继续 —— 符合"夹字也能跨消息接上"的需求。
     *  跨消息语义：上一条消息的命中字符与本条消息首字符相邻，因此
     *  "state 遗留间隔 + 本条消息开头到首个命中字符之间的全部字符"都计入间隔，
     *  任一类超限即放行 —— 否则会凭空吞掉消息边界的夹字，绕过 max-char-gap 限制。
     *  返回匹配到的 remaining 字符数，以及它们在 text 中的绝对索引（压缩域，供回映）。 */
    private static FuzzyPrefixMatch fuzzyPrefixMatch(String text, String remaining, CharGapLimits limits,
            int pendingChinese, int pendingEnglish, int pendingOthers) {
        if (text == null || remaining == null || remaining.isEmpty()) return FuzzyPrefixMatch.NONE;
        int maxScanDist = remaining.length() + Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int maxEnd = Math.min(text.length(), maxScanDist);

        // outer：只从 remaining 首字符出现的位置启动，避免 O(n^2)
        char firstChar = remaining.charAt(0);
        int start = 0;   // indexOf 搜索起点
        int accFrom = 0; // 尚未累计进 pre-gap 的起始位置
        int preChinese = pendingChinese, preEnglish = pendingEnglish, preOthers = pendingOthers;
        while (start < maxEnd) {
            int firstIdx = text.indexOf(firstChar, start);
            if (firstIdx < 0 || firstIdx >= maxEnd) break;

            // 累计 [accFrom, firstIdx) 的字符到 pre-gap
            for (int k = accFrom; k < firstIdx; k++) {
                switch (CharacterMapper.classify(text.charAt(k))) {
                    case CHINESE: preChinese++; break;
                    case ENGLISH: preEnglish++; break;
                    default: preOthers++; break;
                }
            }
            accFrom = firstIdx;
            start = firstIdx;

            // 首个命中字符之前的间隔已超限：后续候选起点更大、间隔只增不减，直接放行
            if (preChinese > limits.chinese || preEnglish > limits.english || preOthers > limits.others) {
                break;
            }

            List<Integer> positions = new ArrayList<>();
            int textIdx = firstIdx;
            int remIdx = 0;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            int innerMaxEnd = Math.min(text.length(), firstIdx + maxScanDist);

            while (textIdx < innerMaxEnd && remIdx < remaining.length()) {
                if (text.charAt(textIdx) == remaining.charAt(remIdx)) {
                    positions.add(textIdx);
                    remIdx++;
                } else {
                    switch (CharacterMapper.classify(text.charAt(textIdx))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                }
                textIdx++;
            }

            if (remIdx > 0) {
                // 部分匹配时：最后一个命中字符之后的全部字符都是遗留间隔，带入 state 继续计数
                int tC = 0, tE = 0, tO = 0;
                if (remIdx < remaining.length()) {
                    int lastPos = positions.get(positions.size() - 1);
                    for (int k = lastPos + 1; k < text.length(); k++) {
                        switch (CharacterMapper.classify(text.charAt(k))) {
                            case CHINESE: tC++; break;
                            case ENGLISH: tE++; break;
                            default: tO++; break;
                        }
                    }
                }
                int[] arr = new int[positions.size()];
                for (int k = 0; k < arr.length; k++) arr[k] = positions.get(k);
                return new FuzzyPrefixMatch(remIdx, arr, tC, tE, tO);
            }
            // 本候选失败：该候选字符本身计入下一个候选的 pre-gap
            start = firstIdx + 1;
        }
        return FuzzyPrefixMatch.NONE;
    }

    // ------------------------------------------------------------------
    // Strict / Fuzzy suffix matching（用于 hasMatched=true 后继续追踪命中字符，压缩域索引）
    // ------------------------------------------------------------------

    private static FuzzySuffixMatch strictSuffixMatch(String text, String banned) {
        List<Integer> pos = new ArrayList<>();
        int ti = text.length() - 1;
        int bi = banned.length() - 1;
        while (ti >= 0 && bi >= 0 && text.charAt(ti) == banned.charAt(bi)) {
            pos.add(0, ti);
            ti--;
            bi--;
        }
        if (pos.isEmpty()) return FuzzySuffixMatch.NONE;
        int[] arr = new int[pos.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = pos.get(k);
        return new FuzzySuffixMatch(true, arr);
    }

    /** 从文本右端向左，按 fuzzy 规则匹配 banned 后缀；匹配到的字符索引（压缩域）存入返回值。
     *  候选语义：从最右侧 banned 尾字符候选开始向左扫描匹配。任一候选的扫描过程中间隔超限
     *  即视为该候选失败（继续尝试更左的候选）；只有"完整匹配 banned，或到达扫描左边界时仍只
     *  匹配到部分尾部字符、且全程未超限"才算命中。据此 chinese=0 时 "一逼"/"一二逼" 因夹字
     *  超限不命中，而单独出现的 "逼" 可正常命中（已匹配到词尾字符且左侧无超限夹字）。 */
    private static FuzzySuffixMatch fuzzySuffixMatch(String text, String banned, CharGapLimits limits) {
        if (text == null || banned == null || banned.isEmpty()) return FuzzySuffixMatch.NONE;
        int maxScanDist = banned.length() + Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int textStart = Math.max(0, text.length() - maxScanDist);

        char lastChar = banned.charAt(banned.length() - 1);
        int p = text.length() - 1;
        while (p >= textStart) {
            int lastIdx = text.lastIndexOf(lastChar, p);
            if (lastIdx < textStart) break;
            p = lastIdx;

            List<Integer> positions = new ArrayList<>();
            int textIdx = p;
            int bi = banned.length() - 1;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            int scanLeftBound = Math.max(0, p - maxScanDist);
            boolean exceeded = false;

            while (textIdx >= scanLeftBound && bi >= 0) {
                if (text.charAt(textIdx) == banned.charAt(bi)) {
                    positions.add(0, textIdx);
                    bi--;
                } else {
                    switch (CharacterMapper.classify(text.charAt(textIdx))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        // 本候选夹字超限：该词尾无法在 char-gap 限制内接上 remaining 前缀，候选失败
                        exceeded = true;
                        break;
                    }
                }
                textIdx--;
            }

            if (exceeded) {
                p = lastIdx - 1;
                continue;
            }
            if (!positions.isEmpty()) {
                int[] arr = new int[positions.size()];
                for (int k = 0; k < arr.length; k++) arr[k] = positions.get(k);
                return new FuzzySuffixMatch(true, arr);
            }
            p = lastIdx - 1;
        }
        return FuzzySuffixMatch.NONE;
    }

    /** hasMatched 后继续追踪的命中结果：实际命中的目标词（正向或反转）、打码位置、所在域。 */
    private static class SuffixHit {
        final String bannedWord;
        final int[] positions;
        final boolean strippedView;

        SuffixHit(String bannedWord, int[] positions, boolean strippedView) {
            this.bannedWord = bannedWord;
            this.positions = positions;
            this.strippedView = strippedView;
        }
    }

    /** hasMatched 后对当前消息做双域后缀追踪：整剔域优先、载荷域兜底；正向词优先、reverse 启用时反转词兜底。
     *  命中条件见 fuzzySuffixMatch：夹字超限的候选直接失败，不因"含词尾字符"而误触发。 */
    private SuffixHit matchBannedSuffixOnDomains(TrackingState state, DomainViews stripped, DomainViews processed) {
        boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
        CharGapLimits limits = plugin.getConfigManager().getMaxCharGapForLevel(state.level);

        List<String> targets = new ArrayList<>();
        targets.add(state.bannedWord);
        boolean reverseEnabled = plugin.getConfigManager().isReverseMatchEnable()
                && plugin.getConfigManager().isReverseMatchEnableForLevel(state.level);
        if (reverseEnabled && state.bannedWord.length() >= 2) {
            String reversed = ColorCodeUtils.reverseWord(state.bannedWord);
            if (!CharacterMapper.normalize(reversed.toLowerCase())
                    .equals(CharacterMapper.normalize(state.bannedWord.toLowerCase()))) {
                targets.add(reversed);
            }
        }

        DomainViews[] domains = { stripped, processed };
        for (DomainViews domain : domains) {
            for (String target : targets) {
                String lowerTarget = CharacterMapper.normalize(target.toLowerCase());
                FuzzySuffixMatch m = fuzzyMatch
                        ? fuzzySuffixMatch(domain.compressed, lowerTarget, limits)
                        : strictSuffixMatch(domain.compressed, lowerTarget);
                if (m.matched) {
                    int[] positions = mapToOriginal(m.positionsInCurrent, domain.compressedToRaw);
                    return new SuffixHit(target, positions, domain.strippedView);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** 压缩文本索引 -> 原始 strippedText 索引 */
    private static int[] mapToOriginal(int[] compressedPositions, int[] compressedToOriginal) {
        if (compressedPositions == null || compressedPositions.length == 0) {
            return new int[0];
        }
        int[] out = new int[compressedPositions.length];
        for (int k = 0; k < out.length; k++) {
            int p = compressedPositions[k];
            out[k] = (p >= 0 && p < compressedToOriginal.length) ? compressedToOriginal[p] : p;
        }
        return out;
    }

    private void addTrackingState(UUID playerId, String bannedWord, String level, int currentPosition, int[] positions,
            int pendingChinese, int pendingEnglish, int pendingOthers) {
        List<TrackingState> states = trackingStates.computeIfAbsent(playerId, k -> new ArrayList<>());
        // 同一玩家同一词同一等级只保留一个未完成的 partial state：
        // 避免重复消息（如连发多条"民"）堆积重复 state 造成重复提示。
        // 进度更靠前的优先；同进度时遗留间隔更少的优先（间隔越少越有可能完成匹配）。
        for (TrackingState existing : states) {
            if (!existing.hasMatched && existing.bannedWord.equals(bannedWord) && existing.level.equals(level)) {
                existing.lastUpdateTime = System.currentTimeMillis();
                int existingPendingSum = existing.pendingChinese + existing.pendingEnglish + existing.pendingOthers;
                int newPendingSum = pendingChinese + pendingEnglish + pendingOthers;
                if (currentPosition > existing.currentPosition
                        || (currentPosition == existing.currentPosition && newPendingSum < existingPendingSum)) {
                    existing.currentPosition = currentPosition;
                    existing.pendingChinese = pendingChinese;
                    existing.pendingEnglish = pendingEnglish;
                    existing.pendingOthers = pendingOthers;
                    existing.lastMatchPositions = positions == null ? new int[0] : Arrays.copyOf(positions, positions.length);
                }
                return;
            }
        }
        states.add(new TrackingState(bannedWord, level, currentPosition, positions,
                pendingChinese, pendingEnglish, pendingOthers));
    }

    /** 与 addTrackingState 类似，但仅在不存在同词同等级未完成 state 时新建（载荷域补建用，避免覆盖整剔域进度）。 */
    private void addTrackingStateIfAbsent(UUID playerId, String bannedWord, String level, int currentPosition, int[] positions,
            int pendingChinese, int pendingEnglish, int pendingOthers) {
        List<TrackingState> states = trackingStates.computeIfAbsent(playerId, k -> new ArrayList<>());
        for (TrackingState existing : states) {
            if (!existing.hasMatched && existing.bannedWord.equals(bannedWord) && existing.level.equals(level)) {
                return; // 已有同词同等级 state：不覆盖
            }
        }
        states.add(new TrackingState(bannedWord, level, currentPosition, positions,
                pendingChinese, pendingEnglish, pendingOthers));
    }

    private void cleanupExpired(UUID playerId) {
        List<TrackingState> states = trackingStates.get(playerId);
        if (states != null) {
            long now = System.currentTimeMillis();
            states.removeIf(state -> now - state.lastUpdateTime > 60000);
            if (states.isEmpty()) {
                trackingStates.remove(playerId);
            }
        }
    }

    public void cleanupAll() {
        long now = System.currentTimeMillis();
        trackingStates.forEach((uuid, states) -> {
            states.removeIf(state -> now - state.lastUpdateTime > 60000);
            if (states.isEmpty()) {
                trackingStates.remove(uuid);
            }
        });
    }

    public void removePlayer(UUID playerId) {
        trackingStates.remove(playerId);
    }

    // ------------------------------------------------------------------
    // Nested classes
    // ------------------------------------------------------------------

    public static class TrackingState {
        final String bannedWord;
        final String level;
        int currentPosition;
        int gapCounter;
        boolean hasMatched;
        long lastUpdateTime;
        /** 最近一次（current 消息中）命中 remaining 前缀的字符在 strippedText 中的索引（已回映）。 */
        int[] lastMatchPositions;
        /** 跨消息遗留间隔：最后一个命中字符之后（含未命中消息的全部字符）累计的间隔字符数，
         *  在下一条消息匹配时作为初始 pre-gap 继续计入，确保消息边界的夹字不绕过 max-char-gap。 */
        int pendingChinese;
        int pendingEnglish;
        int pendingOthers;
        /** 运行时标记：本条消息处理中被判定需移除（间隔超限等），由 checkAndTrack 统一移除。 */
        boolean markedForRemoval;

        TrackingState(String bannedWord, String level, int currentPosition) {
            this(bannedWord, level, currentPosition, new int[0], 0, 0, 0);
        }

        TrackingState(String bannedWord, String level, int currentPosition, int[] positions,
                int pendingChinese, int pendingEnglish, int pendingOthers) {
            this.bannedWord = bannedWord;
            this.level = level;
            this.currentPosition = currentPosition;
            this.gapCounter = 0;
            this.hasMatched = false;
            this.lastUpdateTime = System.currentTimeMillis();
            this.lastMatchPositions = positions == null ? new int[0] : Arrays.copyOf(positions, positions.length);
            this.pendingChinese = pendingChinese;
            this.pendingEnglish = pendingEnglish;
            this.pendingOthers = pendingOthers;
        }
    }

    public static class TrackingResult {
        private final boolean matched;
        private final boolean crossMessageMatch;
        private final String bannedWord;
        private final String level;
        private final String context;
        /** 跨消息命中时：当前消息内应被打码的字符索引（所在域见 positionsInStrippedView）；可能为空（需走 listener 侧 fallback 替换）。
         *  非跨消息命中（独立检测）时为 null。 */
        private final int[] matchedPositionsInCurrent;
        /** positions 所在的匹配域：true = 整剔视图（getStrippedTagsText），false = 载荷提取视图（getProcessedText）。
         *  调用方据此选择 replaceInOriginalWithStrippedMask 或 replaceInOriginalWithMask 打码。 */
        private final boolean positionsInStrippedView;

        public TrackingResult(boolean matched, boolean crossMessageMatch, String bannedWord, String level, String context) {
            this(matched, crossMessageMatch, bannedWord, level, context, null, true);
        }

        public TrackingResult(boolean matched, boolean crossMessageMatch, String bannedWord, String level, String context,
                              int[] matchedPositionsInCurrent) {
            this(matched, crossMessageMatch, bannedWord, level, context, matchedPositionsInCurrent, true);
        }

        public TrackingResult(boolean matched, boolean crossMessageMatch, String bannedWord, String level, String context,
                              int[] matchedPositionsInCurrent, boolean positionsInStrippedView) {
            this.matched = matched;
            this.crossMessageMatch = crossMessageMatch;
            this.bannedWord = bannedWord;
            this.level = level;
            this.context = context;
            this.matchedPositionsInCurrent = matchedPositionsInCurrent;
            this.positionsInStrippedView = positionsInStrippedView;
        }

        public boolean isMatched() { return matched; }
        public boolean isCrossMessageMatch() { return crossMessageMatch; }
        public String getBannedWord() { return bannedWord; }
        public String getLevel() { return level; }
        public String getContext() { return context; }

        /** 命中索引（所在域见 isPositionsInStrippedView()），若 tracker 侧未提供则返回 null。 */
        public int[] getMatchedPositionsInCurrent() { return matchedPositionsInCurrent; }

        /** positions 所在匹配域：true = 整剔视图，false = 载荷提取视图。 */
        public boolean isPositionsInStrippedView() { return positionsInStrippedView; }
    }
}
