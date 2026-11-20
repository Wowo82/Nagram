/*
 * 消息「屏蔽」快捷入口：与消息过滤器（MessageFilter）/ 消息过滤规则（MessageFilterRules）联动。
 */
package xyz.nextalone.nagram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.List;

/**
 * 长按消息菜单中的「屏蔽」。
 *
 * <p>两个动作都会写入现有的过滤体系，做到与设置页共享同一份配置：</p>
 * <ul>
 *   <li>屏蔽发送者 → 向 {@link MessageFilterRules} 追加一条 {@code MATCH_SENDER_ID} 规则（隐藏）；</li>
 *   <li>屏蔽文字 → 把文本转义后追加到 {@link MessageFilter} 的全局正则（多个关键词以 {@code |} 分隔）。</li>
 * </ul>
 */
public final class MessageBlocker {

    /** 作为过滤关键词时最多截取的字符数，避免把整段长文塞进正则。 */
    private static final int MAX_KEYWORD_LENGTH = 30;

    private MessageBlocker() {
    }

    /**
     * 「屏蔽」按钮（多选菜单 / 单条消息「更多」菜单）的统一入口。
     *
     * <p>不弹二次选择框：直接取第一条有文字的消息，把文字写进消息过滤器；
     * 整批消息都没有文字时退化为屏蔽发送者。</p>
     *
     * @return true 表示已经写入或本来就存在
     */
    public static boolean blockMessages(BaseFragment fragment, List<MessageObject> msgs) {
        if (fragment == null || msgs == null || msgs.isEmpty()) {
            return false;
        }
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return false;
        }
        String keyword = "";
        MessageObject senderSource = null;
        for (int i = 0, N = msgs.size(); i < N; i++) {
            final MessageObject msg = msgs.get(i);
            if (msg == null) {
                continue;
            }
            final String text = buildKeyword(msg);
            if (!TextUtils.isEmpty(text)) {
                keyword = text;
                break;
            }
            if (senderSource == null && getSenderId(msg) != 0) {
                senderSource = msg;
            }
        }
        if (!TextUtils.isEmpty(keyword)) {
            return blockKeyword(context, keyword);
        }
        if (senderSource != null) {
            return blockSender(context, getSenderId(senderSource), getSenderName(senderSource));
        }
        Toast.makeText(context, "这条消息没有可匹配的文字", Toast.LENGTH_SHORT).show();
        return false;
    }

    /**
     * 工具条「屏蔽」的一键入口：把选中文字（没有选区时用整条消息正文）直接写入消息过滤器。
     *
     * <p>不再弹出「屏蔽发送者 / 屏蔽文字 / 编辑过滤器」选择框，点一下即生效。</p>
     *
     * @return true 表示关键词已写入或本来就存在
     */
    public static boolean blockText(BaseFragment fragment, MessageObject msg, CharSequence selectedText) {
        if (fragment == null || msg == null) {
            return false;
        }
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return false;
        }
        return blockKeyword(context, pickKeyword(msg, selectedText));
    }

    /** 向过滤规则追加「发送者」规则。 */
    private static boolean blockSender(Context context, long senderId, String name) {
        if (senderId == 0) {
            return false;
        }
        List<MessageFilterRules.Rule> rules = MessageFilterRules.loadRules();
        final String value = String.valueOf(senderId);
        for (MessageFilterRules.Rule r : rules) {
            if (r != null && r.matchType == MessageFilterRules.MATCH_SENDER_ID
                    && value.equals(r.matchValue)) {
                r.action = MessageFilterRules.ACTION_HIDE;
                r.enabled = true;
                MessageFilterRules.saveRules(rules);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.messageFilterRulesChanged);
                Toast.makeText(context, "该发送者已在过滤列表中", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        MessageFilterRules.Rule rule = new MessageFilterRules.Rule();
        rule.matchType = MessageFilterRules.MATCH_SENDER_ID;
        rule.matchValue = value;
        rule.action = MessageFilterRules.ACTION_HIDE;
        rule.enabled = true;
        rule.caseInsensitive = false;
        rules.add(rule);
        MessageFilterRules.saveRules(rules);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.messageFilterRulesChanged);
        Toast.makeText(context, "已屏蔽发送者" + (TextUtils.isEmpty(name) ? "" : "：" + name) + "，已立即生效", Toast.LENGTH_SHORT).show();
        return true;
    }

    /**
     * 把关键词追加到消息过滤器的全局正则。
     *
     * <p>只转义正则元字符，存成「关键词1|关键词2」这种可读形式，不再使用 {@code \Q...\E}。</p>
     *
     * @return true 表示已写入或关键词已存在
     */
    private static boolean blockKeyword(Context context, String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(context, "这条消息没有可匹配的文字", Toast.LENGTH_SHORT).show();
            return false;
        }
        final String old = MessageFilter.normalizeRaw(MessageFilter.getRaw());
        if (MessageFilter.containsKeyword(old, keyword)) {
            Toast.makeText(context, "关键词已在消息过滤器中", Toast.LENGTH_SHORT).show();
            return true;
        }
        final String quoted = MessageFilter.escapeKeyword(keyword);
        MessageFilter.setRaw(TextUtils.isEmpty(old) ? quoted : old + "|" + quoted);
        Toast.makeText(context, "已加入消息过滤器：" + keyword + "，已立即生效", Toast.LENGTH_SHORT).show();
        return true;
    }

    /** 优先用选中文字，没有选区时退回整条消息的正文。 */
    private static String pickKeyword(MessageObject msg, CharSequence selectedText) {
        String keyword = normalizeKeyword(selectedText);
        if (TextUtils.isEmpty(keyword)) {
            keyword = buildKeyword(msg);
        }
        return keyword;
    }

    /**
     * 把选中文字压成一行关键词。
     *
     * <p>多行选中时只取第一行，避免把整段话塞进过滤器；超长时截断。</p>
     */
    private static String normalizeKeyword(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String s = text.toString().trim();
        int lineEnd = s.indexOf('\n');
        if (lineEnd >= 0) {
            s = s.substring(0, lineEnd).trim();
        }
        s = s.replaceAll("\\s+", " ");
        if (s.length() > MAX_KEYWORD_LENGTH) {
            s = s.substring(0, MAX_KEYWORD_LENGTH);
        }
        return s;
    }

    /** 取消息正文/标题，按首个句读切短，避免把整段广告塞进正则。 */
    private static String buildKeyword(MessageObject msg) {
        CharSequence text = msg.messageText;
        if (TextUtils.isEmpty(text)) {
            text = msg.caption;
        }
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        return shorten(text.toString().trim().replace('\n', ' '));
    }

    /**
     * 把一段正文压成适合做过滤关键词的短语。
     *
     * <p>优先取第一个句读（逗号 / 句号 / 问号 / 叹号 / 分号）之前的部分，
     * 这样「这个 vpn 在国外运营，国外合法…」只会写入「这个 vpn 在国外运营」。
     * 切完太短（不足 6 个字符）时退回按长度截断，避免误伤整类消息。</p>
     */
    private static String shorten(String s) {
        final String seps = "，。！？；、,.;!?|";
        int cut = -1;
        for (int i = 0, N = s.length(); i < N && i < MAX_KEYWORD_LENGTH; i++) {
            if (seps.indexOf(s.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut >= 6) {
            s = s.substring(0, cut);
        } else if (s.length() > MAX_KEYWORD_LENGTH) {
            s = s.substring(0, MAX_KEYWORD_LENGTH);
        }
        return s.trim();
    }

    /**
     * 与过滤引擎使用同一个 ID：直接取 {@link MessageObject#getSenderId()}，
     * 避免转发消息写入的 ID 和匹配时的 ID 不一致。
     */
    private static long getSenderId(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return 0;
        }
        return msg.getSenderId();
    }

    private static String getSenderName(MessageObject msg) {
        final long senderId = getSenderId(msg);
        if (senderId == 0) {
            return "";
        }
        final MessagesController controller = MessagesController.getInstance(msg.currentAccount);
        if (senderId > 0) {
            TLRPC.User user = controller.getUser(senderId);
            if (user != null) {
                String name = ContactsController.formatName(user.first_name, user.last_name);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
                if (!TextUtils.isEmpty(user.username)) {
                    return "@" + user.username;
                }
            }
            return String.valueOf(senderId);
        }
        TLRPC.Chat chat = controller.getChat(-senderId);
        if (chat != null && !TextUtils.isEmpty(chat.title)) {
            return chat.title;
        }
        return String.valueOf(senderId);
    }
}
