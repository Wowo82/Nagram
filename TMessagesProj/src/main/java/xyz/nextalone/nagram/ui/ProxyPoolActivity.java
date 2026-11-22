/*
 * 来源：Nagram（Wowo82/Nagram, main-yz）新增文件，本工程原创，无外部拷贝代码。
 * 用途：从 GitHub 公开代理列表拉取未打码的节点（含入库时间），支持直接点击添加、多选批量添加、
 *      TCP 延迟测试，并可切换订阅加速地址。
 * 说明：网络请求与延迟测试都在后台线程执行，结果切回 UI 线程刷新；不依赖新增资源或字符串。
 */

package xyz.nextalone.nagram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.SimpleActionBar;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;

public class ProxyPoolActivity extends BaseNekoSettingsActivity {

    private static final String RAW_PATH = "watchttvv/free-proxy-list/main/proxy.txt";
    private static final String RAW_URL = "https://raw.githubusercontent.com/" + RAW_PATH;

    /** 订阅地址候选，索引与 SOURCE_SHORT 一一对应。 */
    private static final String[] SOURCE_NAMES = {
            "直连（GitHub）",
            "jsDelivr CDN",
            "ghproxy 加速",
            "ghfast 加速",
    };
    private static final String[] SOURCE_SHORT = {"直连", "jsDelivr", "ghproxy", "ghfast"};
    private static final String[] SOURCE_URLS = {
            RAW_URL,
            "https://cdn.jsdelivr.net/gh/watchttvv/free-proxy-list@main/proxy.txt",
            "https://ghproxy.net/" + RAW_URL,
            "https://ghfast.top/" + RAW_URL,
    };
    private static final String PREF_SOURCE = "nagram_proxy_pool_source";

    // 整行按空白切分后的第一段，"scheme://host:port"
    private static final Pattern PROXY_PATTERN = Pattern.compile(
            "^(?i)(socks5|socks|http|https)://([^/:\\s]+):([0-9]{1,5})$");

    // 入库时间，形如 "11-19 18:13"
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})");

    // 地区，形如 "[机房] 俄罗斯 莫斯科"
    private static final Pattern REGION_PATTERN = Pattern.compile("\\[([^\\]]{1,8})]\\s*(.+)$");

    private static final int MENU_REFRESH = 1;
    private static final int MENU_SOURCE = 2;
    private static final int MENU_SELECT = 3;

    private static final int PING_NONE = -1;
    private static final int PING_FAILED = 0;

    private final ArrayList<ProxyNode> nodes = new ArrayList<>();
    private final AtomicBoolean pinging = new AtomicBoolean(false);
    private ExecutorService pingExecutor;

    private int headerRow;
    private int statusRow;
    private int nodeStartRow;
    private int addSelectedRow;
    private int infoRow;

    private boolean loading;
    private boolean error;
    private boolean loadedOnce;
    private boolean selectionMode;

    @Override
    protected String getActionBarTitle() {
        return "代理池";
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        headerRow = rowCount++;
        statusRow = rowCount++;
        nodeStartRow = rowCount;
        rowCount += nodes.size();
        addSelectedRow = selectionMode ? rowCount++ : -1;
        infoRow = rowCount++;
    }

    @Override
    public SimpleActionBar createSimpleActionBar(Context context) {
        SimpleActionBar bar = super.createSimpleActionBar(context);

        SimpleActionBarMenu menu = bar.createMenu();
        menu.clearItems();
        menu.addItem(MENU_REFRESH, R.drawable.msg_reset);
        menu.addItem(MENU_SELECT, R.drawable.msg_add);

        bar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SOURCE) {
                    showSourceDialog();
                } else if (id == MENU_REFRESH) {
                    loadProxies();
                } else if (id == MENU_SELECT) {
                    onSelectMenuClick();
                }
            }
        });

        return bar;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);

        if (!loadedOnce) {
            loadedOnce = true;
            loadProxies();
        }

        return view;
    }

    @Override
    public void onFragmentDestroy() {
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
            pingExecutor = null;
        }
        pinging.set(false);
        super.onFragmentDestroy();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == headerRow) {
            showSourceDialog();
            return;
        }
        if (position == addSelectedRow) {
            submitSelection();
            return;
        }
        if (position == statusRow) {
            if (error && !loading) {
                loadProxies();
            } else if (!loading && !selectionMode) {
                pingAll();
            }
            return;
        }
        int index = position - nodeStartRow;
        if (index >= 0 && index < nodes.size()) {
            ProxyNode node = nodes.get(index);
            if (selectionMode) {
                node.selected = !node.selected;
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(position);
                    listAdapter.notifyItemChanged(statusRow);
                    if (addSelectedRow >= 0) {
                        listAdapter.notifyItemChanged(addSelectedRow);
                    }
                }
            } else {
                addNode(node);
            }
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        int index = position - nodeStartRow;
        if (index < 0 || index >= nodes.size()) {
            return false;
        }
        final ProxyNode node = nodes.get(index);
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(R.drawable.msg_copy, "复制节点", () -> {
            AndroidUtilities.addToClipboard(node.shareText());
            BulletinFactory.of(ProxyPoolActivity.this).createCopyLinkBulletin().show();
        });
        options.add(R.drawable.msg_speed, "测试此节点", () -> pingNode(node, index));
        if (selectionMode) {
            options.add(R.drawable.msg_contacts, "全选 / 取消全选", () -> {
                boolean selectAll = countSelected() < nodes.size();
                for (int i = 0; i < nodes.size(); i++) {
                    nodes.get(i).selected = selectAll;
                }
                refreshItemStates();
            });
        } else {
            options.add(R.drawable.msg_add, "添加到代理列表", () -> addNode(node));
        }
        options.show();
        return true;
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private int countSelected() {
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).selected) {
                count++;
            }
        }
        return count;
    }

    private CharSequence getStatusText() {
        if (loading) {
            return "正在获取代理列表…";
        }
        if (error) {
            return "获取失败，点击重试";
        }
        if (selectionMode) {
            return "已选 " + countSelected() + " / " + nodes.size() + " 个，点右上角 + 完成";
        }
        String text = "共 " + nodes.size() + " 个节点 · 点击节点直接添加";
        if (pinging.get()) {
            text = "正在测速…  " + text;
        }
        return text;
    }

    private int getSourceIndex() {
        return MessagesController.getGlobalMainSettings().getInt(PREF_SOURCE, 0);
    }

    private void setSourceIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt(PREF_SOURCE, index).apply();
    }

    private void showSourceDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("订阅地址");
        builder.setItems(SOURCE_NAMES, (DialogInterface dialog, int which) -> {
            if (which == getSourceIndex()) {
                return;
            }
            setSourceIndex(which);
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            loadProxies();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void refreshRows() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void refreshItemStates() {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void enterSelectionMode() {
        selectionMode = true;
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).selected = false;
        }
        refreshRows();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).selected = false;
        }
        refreshRows();
    }

    private void onSelectMenuClick() {
        if (loading) {
            return;
        }
        if (!selectionMode) {
            enterSelectionMode();
            return;
        }
        if (countSelected() == 0) {
            exitSelectionMode();
            return;
        }
        submitSelection();
    }

    private void loadProxies() {
        if (loading) {
            return;
        }
        loading = true;
        error = false;
        pinging.set(false);
        refreshRows();

        final String url = SOURCE_URLS[Math.max(0, Math.min(SOURCE_URLS.length - 1, getSourceIndex()))];
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<ProxyNode> fetched = null;
            try {
                fetched = fetchProxies(url);
            } catch (Throwable ignore) {
                // 网络异常或解析异常统一按失败处理
            }

            final ArrayList<ProxyNode> result = fetched;
            AndroidUtilities.runOnUIThread(() -> {
                // 页面可能已经销毁
                if (fragmentView == null || listAdapter == null) {
                    return;
                }
                loading = false;
                if (result == null) {
                    error = true;
                } else {
                    nodes.clear();
                    nodes.addAll(result);
                    error = false;
                }
                refreshRows();
            });
        });
    }

    /** 直接添加单个节点（普通模式下点击整行）。 */
    private void addNode(ProxyNode node) {
        if (node == null || node.settings == null) {
            return;
        }
        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(node.settings);
        SharedConfig.ProxyInfo result = SharedConfig.addProxy(info);
        boolean existed = result != null && result != info;
        SharedConfig.saveProxyList();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

        if (existed) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                    "已在列表中：" + node.label()).show();
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                    "已添加 " + node.label()).show();
        }
    }

    /** 提交多选结果。 */
    private void submitSelection() {
        int selected = 0;
        int added = 0;
        for (int i = 0; i < nodes.size(); i++) {
            ProxyNode node = nodes.get(i);
            if (!node.selected || node.settings == null) {
                continue;
            }
            selected++;
            SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(node.settings);
            SharedConfig.ProxyInfo result = SharedConfig.addProxy(info);
            if (result == info) {
                added++;
            }
        }

        if (selected == 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "请先选择要添加的代理").show();
            return;
        }

        SharedConfig.saveProxyList();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        if (added > 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                    added == selected ? "已添加 " + added + " 个代理"
                            : "已添加 " + added + " 个，跳过 " + (selected - added) + " 个已存在").show();
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                    "所选节点均已在代理列表中").show();
        }
        exitSelectionMode();
    }

    /* 延迟测试 */

    private void pingAll() {
        if (loading || nodes.isEmpty()) {
            return;
        }
        if (!pinging.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).ping = PING_NONE;
        }
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }

        if (pingExecutor == null) {
            pingExecutor = Executors.newFixedThreadPool(8);
        }
        final ArrayList<ProxyNode> snapshot = new ArrayList<>(nodes);
        for (int i = 0; i < snapshot.size(); i++) {
            final int index = i;
            final ProxyNode node = snapshot.get(i);
            pingExecutor.execute(() -> {
                final int result = tcpPing(node.address, node.port);
                node.ping = result;
                AndroidUtilities.runOnUIThread(() -> {
                    if (fragmentView == null || listAdapter == null) {
                        return;
                    }
                    int position = nodeStartRow + index;
                    if (index < nodes.size() && nodes.get(index) == node) {
                        listAdapter.notifyItemChanged(position);
                        if (statusRow >= 0) {
                            listAdapter.notifyItemChanged(statusRow);
                        }
                    }
                });
            });
        }

        // 全部测完后复位状态，最多等待 30 秒避免异常情况下一直显示"正在测速"
        Utilities.globalQueue.postRunnable(() -> {
            final long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                boolean done = true;
                for (int i = 0; i < snapshot.size(); i++) {
                    if (snapshot.get(i).ping == PING_NONE) {
                        done = false;
                        break;
                    }
                }
                if (done) {
                    break;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            pinging.set(false);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView != null && listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            });
        });
    }

    private void pingNode(ProxyNode node, int index) {
        node.ping = PING_NONE;
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(nodeStartRow + index);
        }
        if (pingExecutor == null) {
            pingExecutor = Executors.newFixedThreadPool(4);
        }
        pingExecutor.execute(() -> {
            final int result = tcpPing(node.address, node.port);
            node.ping = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView != null && listAdapter != null && index < nodes.size() && nodes.get(index) == node) {
                    listAdapter.notifyItemChanged(nodeStartRow + index);
                }
            });
        });
    }

    private static int tcpPing(String host, int port) {
        long start = System.currentTimeMillis();
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            long cost = System.currentTimeMillis() - start;
            return (int) Math.max(1, cost);
        } catch (Throwable ignore) {
            return PING_FAILED;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* 列表数据 */

    private static ArrayList<ProxyNode> fetchProxies(String url) throws IOException {
        ArrayList<ProxyNode> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }

            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                ProxyNode node = parseLine(line);
                if (node == null) {
                    continue;
                }
                if (!seen.add(node.key())) {
                    continue;
                }
                result.add(node);
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }

    private static ProxyNode parseLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
            return null;
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        Matcher matcher = PROXY_PATTERN.matcher(parts[0]);
        if (!matcher.matches()) {
            return null;
        }

        String scheme = matcher.group(1) == null ? "" : matcher.group(1).toLowerCase(Locale.US);
        String host = matcher.group(2);
        if (host == null || host.isEmpty()) {
            return null;
        }
        // 打码后的 IP（如 195.19.XX.138）不可用，直接丢弃
        if (host.indexOf('X') >= 0 || host.indexOf('x') >= 0) {
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException ignore) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }

        ProxySettings.Type type = scheme.startsWith("socks")
                ? ProxySettings.Type.SOCKS5
                : ProxySettings.Type.WEB;

        ProxySettings settings;
        try {
            settings = ProxySettings.builder()
                    .setType(type)
                    .setAddress(host)
                    .setPort(port)
                    .build();
        } catch (Throwable ignore) {
            return null;
        }

        // 本分支的 WEB 代理是 t.me/webproxy 形式（必须带 secret 且忽略端口），
        // 普通 http://host:port 无法工作，isValid() 会把它挡掉，避免加入不可用节点。
        if (settings == null || !settings.isValid()) {
            return null;
        }

        String time = "";
        Matcher timeMatcher = TIME_PATTERN.matcher(trimmed);
        if (timeMatcher.find()) {
            time = timeMatcher.group(1) + " " + timeMatcher.group(2);
        }

        String region = "";
        Matcher regionMatcher = REGION_PATTERN.matcher(trimmed);
        if (regionMatcher.find()) {
            region = regionMatcher.group(2).trim();
        }

        return new ProxyNode(host, port, type, settings, time, region);
    }

    private static class ProxyNode {
        final String address;
        final int port;
        final ProxySettings.Type type;
        final ProxySettings settings;
        final String time;
        final String region;
        int ping = PING_NONE;
        boolean selected;

        ProxyNode(String address, int port, ProxySettings.Type type, ProxySettings settings, String time, String region) {
            this.address = address;
            this.port = port;
            this.type = type;
            this.settings = settings;
            this.time = time;
            this.region = region;
        }

        String label() {
            return address + ":" + port;
        }

        String protocol() {
            return type == ProxySettings.Type.SOCKS5 ? "SOCKS5" : "HTTP";
        }

        String key() {
            return type + "://" + address + ":" + port;
        }

        String shareText() {
            return (type == ProxySettings.Type.SOCKS5 ? "socks5://" : "http://") + address + ":" + port;
        }

        String pingText() {
            if (ping == PING_NONE) {
                return "";
            }
            if (ping == PING_FAILED) {
                return "超时";
            }
            return ping + "ms";
        }
    }

    private int pingColor(int ping) {
        if (ping == PING_NONE) {
            return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3);
        }
        if (ping == PING_FAILED) {
            return 0xFFE53935;
        }
        if (ping <= 200) {
            return 0xFF43A047;
        }
        if (ping <= 600) {
            return 0xFFFB8C00;
        }
        return 0xFFE53935;
    }

    private class ListAdapter extends BaseListAdapter {

        ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_CHECKBOX2) {
                ProxyNodeCell cell = new ProxyNodeCell(mContext);
                cell.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(cell);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload, boolean divider) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == headerRow) {
                        headerCell.setText("节点 · " + SOURCE_SHORT[Math.max(0, Math.min(SOURCE_SHORT.length - 1, getSourceIndex()))] + "  ▾");
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    if (position == addSelectedRow) {
                        settingsCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
                        settingsCell.setText("添加所选（" + countSelected() + "）", divider);
                    } else if (position == statusRow) {
                        settingsCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                        settingsCell.setText(getStatusText(), divider);
                    } else {
                        settingsCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == infoRow) {
                        infoCell.setText("数据来源为 GitHub 公开代理列表（watchttvv/free-proxy-list），仅收录未打码的节点。"
                                + "点击节点直接加入代理列表；点击右上角 + 进入多选；点击标题栏的「节点」一行可切换订阅加速地址；"
                                + "点击状态行可测试全部节点延迟，长按单个节点可复制或单独测速。");
                    }
                    break;
                }
                case TYPE_CHECKBOX2: {
                    int index = position - nodeStartRow;
                    if (index >= 0 && index < nodes.size() && holder.itemView instanceof ProxyNodeCell) {
                        ProxyNode node = nodes.get(index);
                        final String time = node.time.isEmpty() ? node.protocol() : "入库 " + node.time;
                        ((ProxyNodeCell) holder.itemView).setNode(
                                node.label(), time, node.pingText(), pingColor(node.ping),
                                selectionMode, node.selected, divider);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return TYPE_HEADER;
            } else if (position == statusRow || position == addSelectedRow) {
                return TYPE_SETTINGS;
            } else if (position == infoRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_CHECKBOX2;
        }
    }
}
