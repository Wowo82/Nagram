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

    /** 读取原始正则文本，未设置时返回空串。 */
    public static String getRaw() {
        return MessagesController.getGlobalMainSettings().getString(KEY, "");
    }

    /** 写入正则文本；空串表示关闭过滤器。 */
    public static void setRaw(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        MessagesController.getGlobalMainSettings().edit().putString(KEY, normalized).apply();
        cachedPattern = null;
        cachedRaw = null;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.messageFilterRulesChanged);
    }

    /** 编译后的正则；未配置或语法错误时返回 null。 */
    public static Pattern getPattern() {
        final String raw = getRaw();
        if (TextUtils.isEmpty(raw)) {
            return null;
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
        editText.setText(getRaw());

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
