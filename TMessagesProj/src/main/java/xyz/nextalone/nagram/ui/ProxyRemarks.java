/*
 * 来源：Nagram（Wowo82/Nagram）新增文件，本工程原创。
 * 用途：为代理连接保存/读取用户自定义备注，备注保存在 mainconfig 中，键为 proxy_remark_<type>_<host>_<port>。
 * 说明：不修改 SharedConfig.ProxyInfo 结构，避免影响代理列表的序列化与同步逻辑。
 */

package xyz.nextalone.nagram.ui;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.proxy.ProxySettings;

public class ProxyRemarks {

    private static final String PREFIX = "proxy_remark_";

    private static String keyOf(ProxySettings settings) {
        if (settings == null) {
            return null;
        }
        return PREFIX + settings.getType() + "_" + settings.getAddress() + "_" + settings.getPort();
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static String get(ProxySettings settings) {
        String key = keyOf(settings);
        if (key == null) {
            return "";
        }
        String value = preferences().getString(key, "");
        return value == null ? "" : value;
    }

    public static String get(SharedConfig.ProxyInfo info) {
        return info == null ? "" : get(info.settings);
    }

    public static void set(ProxySettings settings, String remark) {
        String key = keyOf(settings);
        if (key == null) {
            return;
        }
        SharedPreferences.Editor editor = preferences().edit();
        if (remark == null || remark.trim().isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, remark.trim());
        }
        editor.apply();
    }

    public static void set(SharedConfig.ProxyInfo info, String remark) {
        if (info != null) {
            set(info.settings, remark);
        }
    }
}
