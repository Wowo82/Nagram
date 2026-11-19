/*
 * 消息「屏蔽」快捷入口：与消息过滤器（MessageFilter）/ 消息过滤规则（MessageFilterRules）联动。
 */
package xyz.nextalone.nagram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;
import java.util.regex.Pattern;

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

    /** 弹出屏蔽方式选择框。 */
    public static void showDialog(BaseFragment fragment, MessageObject msg) {
        if (fragment == null || msg == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final long senderId = getSenderId(msg);
        final String keyword = buildKeyword(msg);

        final CharSequence[] items;
        final int[] actions; // 0 = 发送者，1 = 文字，2 = 编辑过滤器
        if (senderId != 0) {
            items = new CharSequence[]{
                    "屏蔽发送者：" + getSenderName(msg),
                    TextUtils.isEmpty(keyword) ? "屏蔽这条消息的文字" : "屏蔽包含「" + keyword + "」的消息",
                    "编辑消息过滤器…"
            };
            actions = new int[]{0, 1, 2};
        } else {
            items = new CharSequence[]{
                    TextUtils.isEmpty(keyword) ? "屏蔽这条消息的文字" : "屏蔽包含「" + keyword + "」的消息",
                    "编辑消息过滤器…"
            };
            actions = new int[]{1, 2};
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle("屏蔽");
        builder.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which < 0 || which >= actions.length) {
                    return;
                }
                switch (actions[which]) {
                    case 0:
                        blockSender(context, senderId, getSenderName(msg));
                        break;
                    case 1:
                        blockKeyword(context, keyword);
                        break;
                    default:
                        MessageFilter.showDialog(fragment);
                        break;
                }
            }
        });
        builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** 向过滤规则追加「发送者」规则。 */
    private static void blockSender(Context context, long senderId, String name) {
        if (senderId == 0) {
            return;
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
                return;
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
        Toast.makeText(context, "已屏蔽发送者" + (TextUtils.isEmpty(name) ? "" : "：" + name) + "，重新进入会话后生效", Toast.LENGTH_SHORT).show();
    }

    /** 把关键词追加到消息过滤器的全局正则。 */
    private static void blockKeyword(Context context, String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(context, "这条消息没有可匹配的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        final String quoted = Pattern.quote(keyword);
        final String old = MessageFilter.getRaw();
        if (!TextUtils.isEmpty(old) && (old.contains(quoted) || old.contains(keyword))) {
            Toast.makeText(context, "关键词已在消息过滤器中", Toast.LENGTH_SHORT).show();
            return;
        }
        MessageFilter.setRaw(TextUtils.isEmpty(old) ? quoted : old + "|" + quoted);
        Toast.makeText(context, "已加入消息过滤器：" + keyword + "，重新进入会话后生效", Toast.LENGTH_SHORT).show();
    }

    /** 取消息正文/标题，过长时截断。 */
    private static String buildKeyword(MessageObject msg) {
        CharSequence text = msg.messageText;
        if (TextUtils.isEmpty(text)) {
            text = msg.caption;
        }
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String s = text.toString().trim().replace('\n', ' ');
        if (s.length() > MAX_KEYWORD_LENGTH) {
            s = s.substring(0, MAX_KEYWORD_LENGTH);
        }
        return s;
    }

    private static long getSenderId(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || msg.messageOwner.from_id == null) {
            return 0;
        }
        TLRPC.Peer peer = msg.messageOwner.from_id;
        if (peer.user_id != 0) {
            return peer.user_id;
        }
        if (peer.channel_id != 0) {
            return -peer.channel_id;
        }
        if (peer.chat_id != 0) {
            return -peer.chat_id;
        }
        return 0;
    }

    private static String getSenderName(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || msg.messageOwner.from_id == null) {
            return "";
        }
        final TLRPC.Peer peer = msg.messageOwner.from_id;
        final MessagesController controller = MessagesController.getInstance(msg.currentAccount);
        if (peer.user_id != 0) {
            TLRPC.User user = controller.getUser(peer.user_id);
            if (user != null) {
                String name = ContactsController.formatName(user.first_name, user.last_name);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
                if (!TextUtils.isEmpty(user.username)) {
                    return "@" + user.username;
                }
            }
            return String.valueOf(peer.user_id);
        }
        if (peer.channel_id != 0) {
            TLRPC.Chat chat = controller.getChat(peer.channel_id);
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                return chat.title;
            }
        }
        if (peer.chat_id != 0) {
            TLRPC.Chat chat = controller.getChat(peer.chat_id);
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                return chat.title;
            }
        }
        return "";
    }
}
