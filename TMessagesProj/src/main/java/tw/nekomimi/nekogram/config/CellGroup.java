package tw.nekomimi.nekogram.config;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;

import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;

public class CellGroup {
    public static final int ITEM_TYPE_DIVIDER = 1;
    public static final int ITEM_TYPE_TEXT_SETTINGS_CELL = 2;
    public static final int ITEM_TYPE_TEXT_CHECK = 3;
    public static final int ITEM_TYPE_HEADER = 4;
    public static final int ITEM_TYPE_TEXT_DETAIL = 5;
    public static final int ITEM_TYPE_TEXT = 6;

    public BaseFragment thisFragment;
    public RecyclerListView listView;
    public RecyclerListView.SelectionAdapter listAdapter;
    /** 当前可见的行（adapter 数据源）。折叠时被隐藏的 cell 不在此列表中。 */
    public ArrayList<AbstractConfigCell> rows;
    /** 全部已注册的行，不受折叠影响。 */
    public final ArrayList<AbstractConfigCell> allRows = new ArrayList<>();

    public CallBackSettingsChanged callBackSettingsChanged;

    /** 是否启用「点击分组标题折叠」模式。 */
    public boolean collapsible;
    /** 折叠状态持久化 key（一般为页面类名）。 */
    private String collapseKey;
    /** 已折叠的分组（以分组标题 cell 为标识）。 */
    private final HashSet<AbstractConfigCell> collapsedSections = new HashSet<>();
    /** 折叠状态是否已从磁盘恢复。 */
    private boolean collapseLoaded;

    public CellGroup(BaseFragment thisFragment) {
        this.thisFragment = thisFragment;
        this.rows = new ArrayList<>();
    }

    public void setListAdapter(RecyclerListView lv, RecyclerListView.SelectionAdapter la) {
        this.listView = lv;
        this.listAdapter = la;
    }

    public AbstractConfigCell appendCell(AbstractConfigCell cell) {
        cell.bindCellGroup(this);
        this.allRows.add(cell);
        this.rows.add(cell);
        return cell;
    }

    public AbstractConfigCell appendCell(AbstractConfigCell cell, boolean display) {
        cell.bindCellGroup(this);
        if (display) { // For censored features, don't show it forever.
            this.allRows.add(cell);
            this.rows.add(cell);
        }
        return cell;
    }

    // ---------------------------------------------------------------- 折叠

    /** 折叠前的完整行列表（业务数据），applyCollapse 以它为源重建 rows。 */
    private ArrayList<AbstractConfigCell> fullRows;
    /** beginEdit 时的折叠状态备份。 */
    private HashSet<AbstractConfigCell> collapseBackup;

    /**
     * 启用折叠模式。默认全部折叠（与参考界面一致），用户展开状态会被记住。
     *
     * @param key 持久化标识，通常传页面类名
     */
    public void setCollapsible(String key) {
        this.collapsible = true;
        this.collapseKey = "neko_collapsed_" + key;
        loadCollapseState();
        fullRows = new ArrayList<>(rows);
        applyCollapse();
    }

    private void loadCollapseState() {
        if (collapseLoaded) {
            return;
        }
        collapseLoaded = true;
        final SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        final String saved = prefs.getString(collapseKey, null);
        collapsedSections.clear();
        if (saved == null) {
            // 首次进入：全部收起
            for (AbstractConfigCell cell : rows) {
                if (cell.getType() == ITEM_TYPE_HEADER) {
                    collapsedSections.add(cell);
                }
            }
            return;
        }
        if (saved.isEmpty()) {
            return;
        }
        for (String part : saved.split(",")) {
            try {
                final int index = Integer.parseInt(part.trim());
                if (index >= 0 && index < rows.size()) {
                    final AbstractConfigCell cell = rows.get(index);
                    if (cell.getType() == ITEM_TYPE_HEADER) {
                        collapsedSections.add(cell);
                    }
                }
            } catch (NumberFormatException ignore) {
            }
        }
    }

    private void persistCollapseState() {
        if (collapseKey == null || fullRows == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fullRows.size(); i++) {
            if (collapsedSections.contains(fullRows.get(i))) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(i);
            }
        }
        MessagesController.getGlobalMainSettings().edit().putString(collapseKey, sb.toString()).apply();
    }

    public boolean isCollapsed(AbstractConfigCell header) {
        return collapsible && collapsedSections.contains(header);
    }

    /** 切换某个分组的折叠状态，返回切换后是否处于展开状态。 */
    public boolean toggleSection(AbstractConfigCell header) {
        if (header == null || header.getType() != ITEM_TYPE_HEADER) {
            return true;
        }
        if (collapsedSections.contains(header)) {
            collapsedSections.remove(header);
        } else {
            collapsedSections.add(header);
        }
        persistCollapseState();
        applyCollapse();
        return !collapsedSections.contains(header);
    }

    /**
     * 进入「编辑行」状态：临时展开所有分组，让子类的 setCanNotChange() 在完整的行列表上
     * 做增删与重排，避免被折叠隐藏的行 indexOf 返回 -1 导致插入位置错乱。
     */
    public void beginEdit() {
        if (!collapsible) {
            return;
        }
        if (collapseBackup == null) {
            collapseBackup = new HashSet<>(collapsedSections);
        }
        if (fullRows != null) {
            rows.clear();
            rows.addAll(fullRows);
        }
        collapsedSections.clear();
    }

    /** 结束编辑：以编辑后的完整列表为新基准，恢复折叠状态并重建可见行。 */
    public void endEdit() {
        if (!collapsible) {
            return;
        }
        fullRows = new ArrayList<>(rows);
        collapsedSections.clear();
        if (collapseBackup != null) {
            collapsedSections.addAll(collapseBackup);
            collapseBackup = null;
        }
        applyCollapse();
    }

    /**
     * 按折叠状态重建可见行列表。分组的边界是「当前标题行 → 下一个标题行之前」，
     * 分隔行（ShadowSectionCell）始终保留，用作卡片之间的间隔。
     */
    public void applyCollapse() {
        if (!collapsible) {
            return;
        }
        if (fullRows == null) {
            fullRows = new ArrayList<>(rows);
        }
        final ArrayList<AbstractConfigCell> result = new ArrayList<>(fullRows.size());
        boolean skipItems = false;
        for (AbstractConfigCell cell : fullRows) {
            final int type = cell.getType();
            if (type == ITEM_TYPE_HEADER) {
                skipItems = collapsedSections.contains(cell);
                result.add(cell);
                continue;
            }
            if (type == ITEM_TYPE_DIVIDER) {
                result.add(cell);
                continue;
            }
            if (skipItems) {
                continue;
            }
            result.add(cell);
        }
        rows.clear();
        rows.addAll(result);
    }

    /**
     * 安全刷新：目标行被折叠隐藏时直接跳过，避免 notifyItemChanged(-1) 崩溃。
     */
    public void notifyItemChangedSafe(RecyclerView.Adapter<?> adapter, AbstractConfigCell cell) {
        final int index = rows.indexOf(cell);
        if (adapter != null && index >= 0 && index < adapter.getItemCount()) {
            adapter.notifyItemChanged(index);
        }
    }

    /**
     * 计算某可见位置所属分组的起止位置（含标题行）。
     * 分组以标题行开始，到下一个标题行之前结束；第一个标题行之前的内容单独成组。
     */
    public int getSectionStart(int position) {
        if (position < 0 || position >= rows.size()) {
            return position;
        }
        for (int i = position; i >= 0; i--) {
            if (rows.get(i).getType() == ITEM_TYPE_HEADER) {
                return i;
            }
        }
        return 0;
    }

    public int getSectionEnd(int position) {
        if (position < 0 || position >= rows.size()) {
            return position;
        }
        final int start = getSectionStart(position);
        for (int i = start + 1; i < rows.size(); i++) {
            if (rows.get(i).getType() == ITEM_TYPE_HEADER) {
                return i - 1;
            }
        }
        return rows.size() - 1;
    }

    // ---------------------------------------------------------------- 回调

    public interface CallBackSettingsChanged {
        void run(String key, Object newValue);
    }

    public void runCallback(String key, Object newValue) {
        if (callBackSettingsChanged == null) return;
        try {
            callBackSettingsChanged.run(key, newValue);
        } catch (Exception e) {

        }
    }

    public interface OnBindViewHolder {
        void onBindViewHolder(RecyclerView.ViewHolder holder);
    }

    //Utils
    public static void hideItemFromRecyclerView(View cell, boolean hide) {
        if (cell == null) return;
        if (cell != null) return; //TODO hideItemFromRecyclerView
        ViewGroup.LayoutParams params = cell.getLayoutParams();
        if (hide) {
            cell.setVisibility(View.GONE);
            params.height = 0;
        } else {
            cell.setVisibility(View.VISIBLE);
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        cell.setLayoutParams(params);
    }

    public boolean needSetDivider(AbstractConfigCell cell) {
        final int index = rows.indexOf(cell);
        if (index < 0 || index + 1 >= rows.size()) {
            return true;
        }
        return !(rows.get(index + 1) instanceof ConfigCellDivider);
    }

}
