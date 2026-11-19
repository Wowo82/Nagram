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
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;

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

    private static final String PREF_KEY = "nagram_message_filter_rules";

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
    }

    public static void invalidateCache() {
        cache = null;
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
                // via bot 允许空值表示“任意”，其它类型空值无意义
                if (TextUtils.isEmpty(r.matchValue) && r.matchType != MATCH_VIA_BOT) continue;
                Pattern p = null;
                if (r.matchType == MATCH_REGEX) {
                    try {
                        p = Pattern.compile(r.matchValue, r.caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
                    } catch (Throwable e) {
                        continue; // 编译失败的正则直接跳过
                    }
                }
                built.add(new Compiled(r, p));
            }
            cache = built;
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
        List<Compiled> rules = compiled();
        if (rules.isEmpty()) return ACTION_NONE;
        int best = ACTION_NONE;
        for (Compiled c : rules) {
            boolean hit;
            switch (c.rule.matchType) {
                case MATCH_REGEX: {
                    Pattern p = c.pattern;
                    if (p == null) continue;
                    hit = (text != null && p.matcher(text).find())
                            || (caption != null && p.matcher(caption).find());
                    break;
                }
                case MATCH_CONTAINS: {
                    String needle = c.rule.matchValue;
                    boolean ci = c.rule.caseInsensitive;
                    hit = containsText(text, needle, ci) || containsText(caption, needle, ci);
                    break;
                }
                case MATCH_SENDER_ID: {
                    long target = parseLong(c.rule.matchValue, Long.MIN_VALUE);
                    if (target == Long.MIN_VALUE) continue;
                    hit = senderId == target;
                    break;
                }
                case MATCH_VIA_BOT: {
                    if (viaBotId == 0) {
                        hit = false;
                    } else {
                        String raw = c.rule.matchValue == null ? "" : c.rule.matchValue.trim();
                        if (raw.isEmpty() || "*".equals(raw)) {
                            hit = true;
                        } else {
                            hit = parseLong(raw, Long.MIN_VALUE) == viaBotId;
                        }
                    }
                    break;
                }
                default:
                    hit = false;
                    break;
            }
            if (hit && c.rule.action > best) {
                best = c.rule.action;
            }
            if (best == ACTION_HIDE) {
                break; // 已是最高优先级
            }
        }
        return best;
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

    private static long parseLong(String value, long fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
