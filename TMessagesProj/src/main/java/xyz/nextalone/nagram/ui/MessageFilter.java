/*
 * 移植自 NextAlone/Nnngram 的「消息过滤器」功能（GPLv3）。
 * 与「消息过滤规则」不同：这里只有一个全局正则，命中即隐藏整条消息。
 */
package xyz.nextalone.nagram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 消息过滤器。
 *
 * <p>与 {@link MessageFilterRules} 的区别：</p>
 * <ul>
 *   <li>消息过滤器：一个正则表达式（多个关键词用 {@code |} 连接），凡是命中的消息整条隐藏，
 *       相当于全局关键词黑名单，配置项沿用 Nullgram 的 {@code messageFilter1}。</li>
 *   <li>消息过滤规则：可配置多条规则，支持包含文本 / 正则 / 发送者 ID / via Bot 四种匹配，
 *       命中后可选隐藏、折叠或剧透。</li>
 * </ul>
 */
public class MessageFilter {

    /** 与 Nullgram 保持一致的存储键，便于直接复用已有配置。 */
    private static final String KEY = "messageFilter1";

    private static volatile Pattern cachedPattern;
    private static volatile String cachedRaw;
    /** 是否已经把历史格式迁移成「关键词1|关键词2」。 */
    private static volatile boolean normalizedOnce;

    /** 读取原始正则文本，未设置时返回空串。 */
    public static String getRaw() {
        return MessagesController.getGlobalMainSettings().getString(KEY, "");
    }

    /** 写入正则文本；空串表示关闭过滤器。存储前统一规范化，保证是「关键词1|关键词2」形式。 */
    public static void setRaw(String value) {
        final String normalized = normalizeRaw(value);
        MessagesController.getGlobalMainSettings().edit().putString(KEY, normalized).apply();
        cachedPattern = null;
        cachedRaw = null;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.messageFilterRulesChanged);
    }

    /** 转义正则元字符，但保持关键词本身可读（不使用 \Q...\E 包裹）。 */
    public static String escapeKeyword(String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            return "";
        }
        final String meta = "\\.^$*+?()[]{}|";
        final StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            final char c = keyword.charAt(i);
            if (meta.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 规范化过滤器内容。
     *
     * <p>1. 把历史写入的 {@code \Q...\E} 片段展开成普通文本（内部元字符转义）；<br>
     * 2. 丢弃空分支和多余的 {@code |}，只保留用单个竖线连接的有效关键词。</p>
     */
    public static String normalizeRaw(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        String s = raw;
        if (s.contains("\\Q")) {
            final StringBuilder out = new StringBuilder(s.length());
            int i = 0;
            while (i < s.length()) {
                final int q = s.indexOf("\\Q", i);
                if (q < 0) {
                    out.append(s, i, s.length());
                    break;
                }
                out.append(s, i, q);
                final int e = s.indexOf("\\E", q + 2);
                if (e < 0) {
                    out.append(s, q, s.length());
                    break;
                }
                out.append(escapeKeyword(s.substring(q + 2, e)));
                i = e + 2;
            }
            s = out.toString();
        }
        final StringBuilder out = new StringBuilder(s.length());
        final StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '|') {
                appendAlternative(out, current);
            } else {
                current.append(c);
            }
        }
        appendAlternative(out, current);
        return out.toString();
    }

    private static void appendAlternative(StringBuilder out, StringBuilder current) {
        // 只丢弃纯空分支（首尾 / 连续 |），其余内容原样保留，避免改动正则里的空格
        final String part = current.toString();
        current.setLength(0);
        if (part.trim().isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append('|');
        }
        out.append(part);
    }

    /** 规范化后的过滤器里是否已经包含这个关键词（按 | 精确比较，忽略转义差异）。 */
    public static boolean containsKeyword(String normalizedRaw, String keyword) {
        if (TextUtils.isEmpty(normalizedRaw) || TextUtils.isEmpty(keyword)) {
            return false;
        }
        final String quoted = escapeKeyword(keyword);
        final StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i <= normalizedRaw.length(); i++) {
            final char c = i < normalizedRaw.length() ? normalizedRaw.charAt(i) : '|';
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '|') {
                if (current.toString().trim().equals(quoted)) {
                    return true;
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return false;
    }

    /** 编译后的正则；未配置或语法错误时返回 null。 */
    public static Pattern getPattern() {
        String raw = getRaw();
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        // 一次性把历史数据（\Q...\E、多余竖线）就地规范化落库，
        // 避免用户不打开弹窗就永远看到旧格式。语义等价，不需要广播。
        if (!normalizedOnce) {
            normalizedOnce = true;
            final String normalized = normalizeRaw(raw);
            if (!normalized.equals(raw)) {
                MessagesController.getGlobalMainSettings().edit().putString(KEY, normalized).apply();
                raw = normalized;
                cachedRaw = null;
                cachedPattern = null;
            }
        }
        if (raw.equals(cachedRaw)) {
            return cachedPattern;
        }
        Pattern pattern = null;
        try {
            pattern = Pattern.compile(raw);
        } catch (PatternSyntaxException ignore) {
        }
        cachedRaw = raw;
        cachedPattern = pattern;
        return pattern;
    }

    /** 正文或标题命中即返回 true。 */
    public static boolean matches(CharSequence messageText, CharSequence caption) {
        final Pattern pattern = getPattern();
        if (pattern == null) {
            return false;
        }
        if (messageText != null && pattern.matcher(messageText).find()) {
            return true;
        }
        return caption != null && pattern.matcher(caption).find();
    }

    public static boolean shouldHide(MessageObject msg) {
        if (msg == null) {
            return false;
        }
        return matches(msg.messageText, msg.caption);
    }

    /** 弹出单行正则编辑框。 */
    public static void showDialog(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintText("关键词，多个用 | 分隔");
        editText.setHintColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        editText.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setSingleLine(true);
        editText.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        editText.setText(normalizeRaw(getRaw()));

        final TextView desc = new TextView(context);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        desc.setTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider));
        desc.setLineSpacing(AndroidUtilities.dp(2), 1f);
        desc.setText("用正则表达式匹配消息文本，命中的消息会被直接隐藏。留空则关闭。\n"
                + "示例：广告|推广|请输入验证码\n"
                + "提示：这只是单词黑名单；需要按发送者、via Bot 过滤或只折叠/剧透时，请使用「消息过滤规则」。");

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        container.addView(desc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 10));
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle("消息过滤器");
        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String value = editText.getText().toString().trim();
            if (!TextUtils.isEmpty(value)) {
                try {
                    Pattern.compile(value);
                } catch (PatternSyntaxException e) {
                    android.widget.Toast.makeText(context, "正则表达式无效：" + e.getDescription(),
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            setRaw(value);
        });
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        fragment.showDialog(dialog);
    }
}
