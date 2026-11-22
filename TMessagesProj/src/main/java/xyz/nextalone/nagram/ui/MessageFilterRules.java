/*
 * Ported from NextAlone/Nnngram (MessageFilterRules.kt), GPLv3.
 * Original: Copyright (C) 2019-2026 qwq233 <qwq233@qwq2333.top>
 * Adapted to plain Java for Nagram (Wowo82/Nagram, branch main-yz).
 */

package xyz.nextalone.nagram.ui;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 消息过滤规则的数据模型、持久化与匹配引擎。
 *
 * <p>存储：SharedPreferences（{@link MessagesController#getGlobalMainSettings()}），
 * key 为 {@link #PREF_KEY}，值为 JSON 数组字符串。规则的正则会被预编译并缓存，
 * 规则变更后调用 {@link #invalidateCache()} 使缓存失效。</p>
 */
public final class MessageFilterRules {

    /** 与上游 Nnngram 的 Config.filterRules 一致。 */
    private static final String PREF_KEY = "filterRules";
    /** 早期移植版本用过的键，读取时迁移一次。 */
    private static final String LEGACY_PREF_KEY = "nagram_message_filter_rules";

    /** 动作：不处理。 */
    public static final int ACTION_NONE = 0;
    /** 动作：剧透遮挡。 */
    public static final int ACTION_SPOILER = 1;
    /** 动作：折叠显示。 */
    public static final int ACTION_COLLAPSE = 2;
    /** 动作：隐藏消息（优先级最高）。 */
    public static final int ACTION_HIDE = 3;

    /** 匹配：对正文/标题做正则 find。 */
    public static final int MATCH_REGEX = 0;
    /** 匹配：朴素包含。 */
    public static final int MATCH_CONTAINS = 1;
    /** 匹配：发送者 id。 */
    public static final int MATCH_SENDER_ID = 2;
    /** 匹配：通过 inline bot 发送。 */
    public static final int MATCH_VIA_BOT = 3;

    private static volatile List<Compiled> cache;

    /**
     * 规则代际：每次规则变更自增。
     *
     * <p>MessageObject 会把判定结果缓存到实例上。规则改过之后，别的会话里早就构造好的
     * 消息对象仍然带着旧结论（NONE），切回去看还是没过滤——表现为「部分频道规则不生效」，
     * 而且只能靠重启进程恢复。带上代际后，任何旧缓存都会在下次判定时自动作废。</p>
     */
    private static volatile int generation;

    private MessageFilterRules() {
    }

    /** 单条规则。字段公开以便设置页直接读写。 */
    public static class Rule {
        public String id;
        public int matchType;
        public String matchValue;
        public int action;
        public boolean enabled;
        public boolean caseInsensitive;

        public Rule() {
            id = UUID.randomUUID().toString();
            matchType = MATCH_CONTAINS;
            matchValue = "";
            action = ACTION_HIDE;
            enabled = true;
            caseInsensitive = false;
        }

        public Rule(String id, int matchType, String matchValue, int action, boolean enabled, boolean caseInsensitive) {
            this.id = id;
            this.matchType = matchType;
            this.matchValue = matchValue;
            this.action = action;
            this.enabled = enabled;
            this.caseInsensitive = caseInsensitive;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("matchType", matchType);
            o.put("matchValue", matchValue);
            o.put("action", action);
            o.put("enabled", enabled);
            o.put("caseInsensitive", caseInsensitive);
            return o;
        }

        public static Rule fromJson(JSONObject o) {
            String rid = o.optString("id", "");
            if (TextUtils.isEmpty(rid)) {
                rid = UUID.randomUUID().toString();
            }
            return new Rule(
                    rid,
                    o.optInt("matchType", MATCH_REGEX),
                    o.optString("matchValue", ""),
                    o.optInt("action", ACTION_HIDE),
                    o.optBoolean("enabled", true),
                    o.optBoolean("caseInsensitive", false)
            );
        }
    }

    private static final class Compiled {
        final Rule rule;
        final Pattern pattern;

        Compiled(Rule rule, Pattern pattern) {
            this.rule = rule;
            this.pattern = pattern;
        }
    }

    /** 从 SharedPreferences 读取规则，解析失败返回空列表（不抛异常）。 */
    public static List<Rule> loadRules() {
        List<Rule> out = new ArrayList<>();
        try {
            SharedPreferences prefs = MessagesController.getGlobalMainSettings();
            String json = prefs.getString(PREF_KEY, "");
            if (TextUtils.isEmpty(json) || "[]".equals(json)) {
                // 迁移旧版本存下的规则
                String legacy = prefs.getString(LEGACY_PREF_KEY, "");
                if (!TextUtils.isEmpty(legacy) && !"[]".equals(legacy)) {
                    json = legacy;
                    prefs.edit().putString(PREF_KEY, legacy).apply();
                }
            }
            if (TextUtils.isEmpty(json) || "[]".equals(json)) {
                return out;
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(Rule.fromJson(o));
                }
            }
        } catch (Throwable ignored) {
            out.clear();
        }
        return out;
    }

    /** 写回规则并清空缓存。 */
    public static void saveRules(List<Rule> rules) {
        try {
            JSONArray arr = new JSONArray();
            if (rules != null) {
                for (Rule r : rules) {
                    if (r == null) continue;
                    arr.put(r.toJson());
                }
            }
            MessagesController.getGlobalMainSettings()
                    .edit()
                    .putString(PREF_KEY, arr.toString())
                    .apply();
        } catch (Throwable ignored) {
            // 序列化失败时保持旧值，避免把存储写坏
        }
        invalidateCache();
        // 规则变化后通知聊天页立即重新过滤已加载的消息
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.messageFilterRulesChanged);
        // 探针：确认「设置页保存」这一步真的写进去了
        logRuleSet("saved");
    }

    public static void invalidateCache() {
        cache = null;
        generation++;
    }

    /** 当前规则代际，用于让消息上缓存的过滤结果自动失效。 */
    public static int generation() {
        return generation;
    }

    private static List<Compiled> compiled() {
        List<Compiled> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (MessageFilterRules.class) {
            local = cache;
            if (local != null) {
                return local;
            }
            List<Compiled> built = new ArrayList<>();
            for (Rule r : loadRules()) {
                if (r == null || !r.enabled) continue;
                // 与上游一致：空匹配值直接跳过（via bot 的「任意」用 * 表示）
                if (TextUtils.isEmpty(r.matchValue)) continue;
                Pattern p = null;
                if (r.matchType == MATCH_REGEX) {
                    p = compileSafe(r.matchValue, r.caseInsensitive);
                    if (p == null) {
                        continue; // 编译失败的正则直接跳过
                    }
                }
                built.add(new Compiled(r, p));
            }
            cache = built;
            if (probeEnabled()) {
                logRuleSet("cache-build");
            }
            return built;
        }
    }

    /**
     * 给定消息字段返回应执行的动作，多规则命中时取动作值最大者。
     *
     * @param text     正文
     * @param caption  标题/说明
     * @param senderId 发送者 id
     * @param viaBotId via bot 的 user id，0 表示无 / 未解析
     */
    public static int match(CharSequence text, CharSequence caption, long senderId, long viaBotId) {
        return match(text, caption, senderId, viaBotId, null);
    }

    /**
     * 上游签名：额外传入 via bot 的 username。
     * 有些 via bot 消息只有 username、没有数值 id。
     */
    public static int match(CharSequence text, CharSequence caption, long senderId, long viaBotId, String viaBotName) {
        return match(text, caption, null, senderId, viaBotId, viaBotName);
    }

    /**
     * 带额外匹配串的重载。
     *
     * <p>媒体消息带 caption 时，{@code messageText} 只剩说明文字，文件名完全不参与匹配，
     * 表现为「按文件名后缀过滤时灵时不灵」。调用方把文件名单独传进来即可。</p>
     */
    public static int match(CharSequence text, CharSequence caption, CharSequence extra, CharSequence extra2,
                            long senderId, long viaBotId, String viaBotName) {
        List<Compiled> rules = compiled();
        if (rules.isEmpty()) return ACTION_NONE;
        int best = ACTION_NONE;
        for (Compiled c : rules) {
            if (matchesRule(c, text, caption, extra, extra2, senderId, viaBotId, viaBotName) && c.rule.action > best) {
                best = c.rule.action;
            }
            if (best == ACTION_HIDE) {
                break; // 已是最高优先级
            }
        }
        return best;
    }

    /**
     * 单条规则的匹配判定。
     *
     * <p>从 {@link #match} 里抽出来，让探针能复用完全相同的逻辑，
     * 避免「日志显示命中、实际没生效」这种因两份实现漂移导致的误判。</p>
     *
     * @param extra  附加匹配串（媒体文件名）
     * @param extra2 附加匹配串（按钮文字、网页预览标题与描述）
     */
    private static boolean matchesRule(Compiled c, CharSequence text, CharSequence caption, CharSequence extra, CharSequence extra2,
                                       long senderId, long viaBotId, String viaBotName) {
        switch (c.rule.matchType) {
            case MATCH_REGEX: {
                Pattern p = c.pattern;
                if (p == null) return false;
                return (text != null && p.matcher(text).find())
                        || (caption != null && p.matcher(caption).find())
                        || (extra != null && p.matcher(extra).find())
                        || (extra2 != null && p.matcher(extra2).find());
            }
            case MATCH_CONTAINS: {
                String needle = c.rule.matchValue;
                boolean ci = c.rule.caseInsensitive;
                return containsText(text, needle, ci)
                        || containsText(caption, needle, ci)
                        || containsText(extra, needle, ci)
                        || containsText(extra2, needle, ci);
            }
            case MATCH_SENDER_ID: {
                long target = parseLong(c.rule.matchValue, Long.MIN_VALUE);
                if (target == Long.MIN_VALUE) return false;
                return samePeer(senderId, target);
            }
            case MATCH_VIA_BOT: {
                final boolean hasViaBot = viaBotId != 0 || !TextUtils.isEmpty(viaBotName);
                if (!hasViaBot) return false;
                String raw = c.rule.matchValue == null ? "" : c.rule.matchValue.trim();
                if (raw.isEmpty() || "*".equals(raw)) return true;
                return parseLong(raw, Long.MIN_VALUE) == viaBotId;
            }
            default:
                return false;
        }
    }

    /** 判断一条消息是否应被完全隐藏。 */
    public static boolean shouldHide(MessageObject msg) {
        if (msg == null) return false;
        // 「消息过滤器」优先：全局关键词黑名单，命中即隐藏整条消息
        if (MessageFilter.shouldHide(msg)) return true;
        long senderId = 0;
        long viaBotId = 0;
        if (msg.messageOwner != null) {
            senderId = msg.getSenderId();
            viaBotId = msg.messageOwner.via_bot_id;
        }
        return match(msg.messageText, msg.caption, senderId, viaBotId) == ACTION_HIDE;
    }

    private static boolean containsText(CharSequence src, String needle, boolean caseInsensitive) {
        if (src == null || TextUtils.isEmpty(needle)) return false;
        if (!caseInsensitive) {
            return TextUtils.indexOf(src, needle) >= 0;
        }
        String s = src.toString().toLowerCase(Locale.ROOT);
        return s.contains(needle.toLowerCase(Locale.ROOT));
    }

    /**
     * 发送者 ID 比较，兼容几种常见写法。
     *
     * <p>同一个频道可能拿到两种值：普通消息走 {@code from_id} 得到 {@code -channelId}，
     * 而 {@code post} 分支直接返回正的 {@code channel_id}；超级群/频道又常被写成
     * {@code -100xxxxxxxxxx}。用户只填一种写法时另一种就匹配不到，表现为「规则时灵时不灵」。
     * 这里统一剥掉符号与 -100 前缀再比。</p>
     */
    private static boolean samePeer(long a, long b) {
        if (a == 0 || b == 0) {
            return false;
        }
        if (a == b) {
            return true;
        }
        return barePeerId(a) == barePeerId(b);
    }

    private static long barePeerId(long id) {
        final long v = id < 0 ? -id : id;
        // -100xxxxxxxxxx 形式的超级群 / 频道
        return v >= 1000000000000L ? v - 1000000000000L : v;
    }

    // ==================== 探针：跟随「日志」开关，用于排除过滤规则不生效 ====================

    /** 探针统一前缀。日志里 grep 这一个词，就能看到规则加载、匹配、应用全链路。 */
    public static final String PROBE_TAG = "FILTER_PROBE";

    /** 探针是否可用：直接跟随设置里的日志开关（BuildVars.LOGS_ENABLED）。 */
    public static boolean probeEnabled() {
        return BuildVars.LOGS_ENABLED;
    }

    /** 「有效规则/存储规则」的简短描述，供批次汇总拼接。 */
    public static String rulesSummary() {
        if (!probeEnabled()) return "rules=?";
        return "rules=" + compiled().size() + "/" + loadRules().size();
    }

    /**
     * 打印规则集快照。
     *
     * <p>能直接回答「规则到底存进去没有」：stored 是 SharedPreferences 里的条数，
     * active 是实际参与匹配的条数，被跳过的规则会标明原因（未启用 / 空值 / 正则编译失败）。</p>
     *
     * @param scene 触发场景，如 saved / cache-build / rules-changed
     */
    public static void logRuleSet(String scene) {
        if (!probeEnabled()) return;
        List<Rule> stored = loadRules();
        List<Compiled> active = compiled();
        FileLog.d(PROBE_TAG + " ruleset[" + scene + "] stored=" + stored.size() + " active=" + active.size());
        for (int i = 0; i < stored.size(); i++) {
            final Rule r = stored.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(PROBE_TAG).append("   #").append(i).append(' ')
                    .append(matchTypeName(r.matchType)).append('/').append(actionName(r.action))
                    .append(" ci=").append(r.caseInsensitive)
                    .append(" value=\"").append(clip(r.matchValue, 80)).append('"');
            if (!r.enabled) {
                sb.append(" [未启用，不参与匹配]");
            } else if (TextUtils.isEmpty(r.matchValue)) {
                sb.append(" [匹配值为空，跳过]");
            } else if (r.matchType == MATCH_REGEX && compileSafe(r.matchValue, r.caseInsensitive) == null) {
                sb.append(" [正则编译失败，跳过]");
            } else if (r.matchType == MATCH_CONTAINS && looksLikeRegex(r.matchValue)) {
                sb.append(" [类型存成了「包含文本」但内容是正则，这条永远命中不了]");
            }
            FileLog.d(sb.toString());
        }
        if (stored.isEmpty()) {
            FileLog.d(PROBE_TAG + "   （规则集为空：所有消息都会放行）");
        }
    }

    /** 探针：一条消息命中了规则。 */
    public static void logHit(int msgId, CharSequence text, CharSequence caption, CharSequence extra, CharSequence extra2,
                              long senderId, long viaBotId, String viaBotName, int act, boolean byMessageFilter) {
        if (!probeEnabled()) return;
        FileLog.d(PROBE_TAG + " HIT msg=" + msgId + " act=" + actionName(act)
                + " src=" + (byMessageFilter ? "FILTER(关键词过滤器)" : "RULE(过滤规则)")
                + " | " + describeMatch(text, caption, extra, extra2, senderId, viaBotId, viaBotName));
    }

    /** 探针：一条消息未命中任何规则，输出完整的输入与逐条判定，用于查「为什么没过滤」。 */
    public static void logMiss(int msgId, CharSequence text, CharSequence caption, CharSequence extra, CharSequence extra2,
                               long senderId, long viaBotId, String viaBotName) {
        if (!probeEnabled()) return;
        FileLog.d(PROBE_TAG + " MISS msg=" + msgId + " act=NONE | "
                + describeMatch(text, caption, extra, extra2, senderId, viaBotId, viaBotName));
    }

    private static String describeMatch(CharSequence text, CharSequence caption, CharSequence extra, CharSequence extra2,
                                        long senderId, long viaBotId, String viaBotName) {
        List<Compiled> rules = compiled();
        StringBuilder sb = new StringBuilder("rules=").append(rules.size());
        int winner = -1;
        int best = ACTION_NONE;
        for (int i = 0; i < rules.size(); i++) {
            final Compiled c = rules.get(i);
            final boolean hit = matchesRule(c, text, caption, extra, extra2, senderId, viaBotId, viaBotName);
            sb.append(" [").append(i).append(':').append(matchTypeName(c.rule.matchType))
                    .append('/').append(actionName(c.rule.action)).append(" \"")
                    .append(clip(c.rule.matchValue, 40)).append("\"→").append(hit ? "HIT" : "miss").append(']');
            if (hit && c.rule.action > best) {
                best = c.rule.action;
                winner = i;
            }
        }
        if (winner >= 0) {
            sb.append(" winner=#").append(winner);
        }
        sb.append(" | text=").append(quote(text))
                .append(" caption=").append(quote(caption))
                .append(" file=").append(quote(extra))
                .append(" rich=").append(quote(extra2))
                .append(" sender=").append(senderId)
                .append(" viaBot=").append(viaBotId);
        return sb.toString();
    }

    public static String actionName(int action) {
        switch (action) {
            case ACTION_SPOILER:
                return "SPOILER";
            case ACTION_COLLAPSE:
                return "COLLAPSE";
            case ACTION_HIDE:
                return "HIDE";
            case ACTION_NONE:
            default:
                return "NONE";
        }
    }

    public static String matchTypeName(int type) {
        switch (type) {
            case MATCH_REGEX:
                return "REGEX";
            case MATCH_CONTAINS:
                return "CONTAINS";
            case MATCH_SENDER_ID:
                return "SENDER_ID";
            case MATCH_VIA_BOT:
                return "VIA_BOT";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }

    /**
     * 判断一段文本看起来是不是正则表达式。
     *
     * <p>用于拦住一类静默失效：把整条正则粘进「包含文本」类型的规则里，它会被当成
     * 普通字符串做 contains，永远不会命中，而界面上看不出任何异常。</p>
     */
    public static boolean looksLikeRegex(String value) {
        if (TextUtils.isEmpty(value)) return false;
        final String v = value.trim();
        if (v.startsWith("(?") || v.startsWith("^") || v.endsWith("$")) return true;
        if (v.contains("\\s") || v.contains("\\S") || v.contains("\\d") || v.contains("\\w")) return true;
        if (v.contains("(?:") || v.contains("[\\s\\S]")) return true;
        if (v.contains("{0,") || v.contains("{1,")) return true;
        return false;
    }

    private static Pattern compileSafe(String regex, boolean caseInsensitive) {
        try {
            return Pattern.compile(regex, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 截断换行与超长文本，避免一条日志把整篇文章刷进文件。 */
    private static String clip(CharSequence src, int max) {
        if (src == null) return "null";
        String s = src.toString().replace('\n', ' ').replace('\r', ' ');
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…(" + s.length() + ")";
    }

    private static String quote(CharSequence src) {
        return src == null ? "null" : "\"" + clip(src, 120) + "\"";
    }

    private static long parseLong(String value, long fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
