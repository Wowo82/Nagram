/*
 * Ported from NextAlone/Nnngram (FilterRulesActivity.kt), GPLv3.
 * Original: Copyright (C) 2019-2026 qwq233 <qwq233@qwq2333.top>
 * Adapted to plain Java for Nagram (Wowo82/Nagram, branch main-yz).
 */

package xyz.nextalone.nagram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;

/**
 * 「消息过滤规则」设置页：规则列表 + 新增/编辑/排序/删除。
 *
 * <p>UI 文案直接硬编码中文，避免为此功能修改 strings.xml。</p>
 */
public class MessageFilterRulesActivity extends BaseNekoSettingsActivity {

    private int headerRow;
    private int addRow;
    private int rulesStartRow;
    private int rulesEndRow;
    private int hintRow;

    private final List<MessageFilterRules.Rule> rules = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        rules.clear();
        rules.addAll(MessageFilterRules.loadRules());
        return super.onFragmentCreate();
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        super.onResume();
        rules.clear();
        rules.addAll(MessageFilterRules.loadRules());
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        headerRow = rowCount++;
        addRow = rowCount++;

        rulesStartRow = rowCount;
        rowCount += rules.size();
        rulesEndRow = rowCount;

        hintRow = rowCount++;
    }

    @Override
    protected boolean useCardStyle() {
        return true;
    }

    @Override
    protected String getActionBarTitle() {
        return "消息过滤规则";
    }

    @Override
    public BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == addRow) {
            showEditDialog(-1);
        } else if (position >= rulesStartRow && position < rulesEndRow) {
            showEditDialog(position - rulesStartRow);
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position >= rulesStartRow && position < rulesEndRow) {
            showRuleMenu(position - rulesStartRow);
            return true;
        }
        return super.onItemLongClick(view, position, x, y);
    }

    private void showError(String text) {
        BulletinFactory.of(this).createErrorBulletin(text).show();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void notifyChanged() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void showRuleMenu(final int index) {
        if (index < 0 || index >= rules.size()) return;
        final Context context = getParentActivity();
        if (context == null) return;

        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();

        if (index > 0) {
            labels.add("上移");
            actions.add(() -> {
                if (index < rules.size()) {
                    Collections.swap(rules, index, index - 1);
                    MessageFilterRules.saveRules(rules);
                    notifyChanged();
                }
            });
        }
        if (index < rules.size() - 1) {
            labels.add("下移");
            actions.add(() -> {
                if (index < rules.size() - 1) {
                    Collections.swap(rules, index, index + 1);
                    MessageFilterRules.saveRules(rules);
                    notifyChanged();
                }
            });
        }

        final boolean enabled = rules.get(index).enabled;
        labels.add(enabled ? "禁用" : "启用");
        actions.add(() -> {
            if (index < rules.size()) {
                MessageFilterRules.Rule r = rules.get(index);
                r.enabled = !r.enabled;
                MessageFilterRules.saveRules(rules);
                notifyChanged();
            }
        });

        labels.add("删除");
        actions.add(() -> new AlertDialog.Builder(context)
                .setTitle("删除规则")
                .setMessage("确定要删除这条过滤规则吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (index < rules.size()) {
                        rules.remove(index);
                        MessageFilterRules.saveRules(rules);
                        notifyChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show());

        new AlertDialog.Builder(context)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    private void addDialogLabel(LinearLayout container, Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        label.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(4));
        container.addView(label);
    }

    private RadioButton addRadioButton(Context context, RadioGroup group, int id, String text, boolean checked) {
        RadioButton button = new RadioButton(context);
        button.setId(id);
        button.setText(text);
        button.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        button.setChecked(checked);
        group.addView(button);
        return button;
    }

    private void showEditDialog(final int editIndex) {
        final Context context = getParentActivity();
        if (context == null) return;

        final boolean editing = editIndex >= 0 && editIndex < rules.size();
        final MessageFilterRules.Rule source = editing ? rules.get(editIndex) : null;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);

        addDialogLabel(container, context, "匹配类型");
        final RadioGroup matchGroup = new RadioGroup(context);
        addRadioButton(context, matchGroup, 1000 + MessageFilterRules.MATCH_CONTAINS, "包含文本", source == null ? true : source.matchType == MessageFilterRules.MATCH_CONTAINS);
        addRadioButton(context, matchGroup, 1000 + MessageFilterRules.MATCH_REGEX, "正则表达式", source != null && source.matchType == MessageFilterRules.MATCH_REGEX);
        addRadioButton(context, matchGroup, 1000 + MessageFilterRules.MATCH_SENDER_ID, "发送者 ID", source != null && source.matchType == MessageFilterRules.MATCH_SENDER_ID);
        addRadioButton(context, matchGroup, 1000 + MessageFilterRules.MATCH_VIA_BOT, "via Bot", source != null && source.matchType == MessageFilterRules.MATCH_VIA_BOT);
        container.addView(matchGroup);

        addDialogLabel(container, context, "匹配内容");
        final EditTextBoldCursor valueEdit = new EditTextBoldCursor(context);
        valueEdit.setBackground(null);
        valueEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        valueEdit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        valueEdit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        valueEdit.setHintColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        valueEdit.setHintText("输入要匹配的文本 / 正则 / 数字 ID");
        valueEdit.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)
        );
        if (source != null && source.matchValue != null) {
            valueEdit.setText(source.matchValue);
        }
        container.addView(valueEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));

        final CheckBox caseInsensitiveBox = new CheckBox(context);
        caseInsensitiveBox.setText("忽略大小写");
        caseInsensitiveBox.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        caseInsensitiveBox.setChecked(source != null && source.caseInsensitive);
        container.addView(caseInsensitiveBox);

        addDialogLabel(container, context, "执行动作");
        final RadioGroup actionGroup = new RadioGroup(context);
        addRadioButton(context, actionGroup, 2000 + MessageFilterRules.ACTION_HIDE, "隐藏消息", source == null || source.action == MessageFilterRules.ACTION_HIDE);
        addRadioButton(context, actionGroup, 2000 + MessageFilterRules.ACTION_COLLAPSE, "折叠显示", source != null && source.action == MessageFilterRules.ACTION_COLLAPSE);
        addRadioButton(context, actionGroup, 2000 + MessageFilterRules.ACTION_SPOILER, "剧透遮挡", source != null && source.action == MessageFilterRules.ACTION_SPOILER);
        container.addView(actionGroup);

        new AlertDialog.Builder(context)
                .setTitle(editing ? "编辑规则" : "添加规则")
                .setView(container)
                .setPositiveButton("保存", (dialog, which) -> {
                    int matchType = matchGroup.getCheckedRadioButtonId() == -1
                            ? MessageFilterRules.MATCH_CONTAINS
                            : matchGroup.getCheckedRadioButtonId() - 1000;
                    int action = actionGroup.getCheckedRadioButtonId() == -1
                            ? MessageFilterRules.ACTION_HIDE
                            : actionGroup.getCheckedRadioButtonId() - 2000;
                    String value = valueEdit.getText().toString().trim();
                    boolean caseInsensitive = caseInsensitiveBox.isChecked();

                    if (matchType != MessageFilterRules.MATCH_VIA_BOT && TextUtils.isEmpty(value)) {
                        showError("匹配内容不能为空");
                        return;
                    }
                    if (matchType == MessageFilterRules.MATCH_REGEX) {
                        try {
                            Pattern.compile(value, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
                        } catch (Exception e) {
                            showError("正则表达式无效");
                            return;
                        }
                    }
                    if (matchType == MessageFilterRules.MATCH_SENDER_ID && !isLong(value)) {
                        showError("发送者 ID 必须是数字");
                        return;
                    }
                    if (matchType == MessageFilterRules.MATCH_VIA_BOT
                            && !TextUtils.isEmpty(value) && !"*".equals(value) && !isLong(value)) {
                        showError("via Bot 需填写 * 或数字 ID");
                        return;
                    }

                    String id = editing ? source.id : UUID.randomUUID().toString();
                    if (TextUtils.isEmpty(id)) {
                        id = UUID.randomUUID().toString();
                    }
                    MessageFilterRules.Rule rule = new MessageFilterRules.Rule(
                            id, matchType, value, action,
                            editing ? source.enabled : true, caseInsensitive);
                    if (editing) {
                        rules.set(editIndex, rule);
                    } else {
                        rules.add(rule);
                    }
                    MessageFilterRules.saveRules(rules);
                    notifyChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static boolean isLong(String value) {
        if (TextUtils.isEmpty(value)) return false;
        try {
            Long.parseLong(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String describeRule(MessageFilterRules.Rule rule) {
        String type;
        switch (rule.matchType) {
            case MessageFilterRules.MATCH_CONTAINS:
                type = "包含文本";
                break;
            case MessageFilterRules.MATCH_REGEX:
                type = "正则表达式";
                break;
            case MessageFilterRules.MATCH_SENDER_ID:
                type = "发送者 ID";
                break;
            case MessageFilterRules.MATCH_VIA_BOT:
                type = "via Bot";
                break;
            default:
                type = "";
                break;
        }
        String action;
        switch (rule.action) {
            case MessageFilterRules.ACTION_HIDE:
                action = "隐藏消息";
                break;
            case MessageFilterRules.ACTION_COLLAPSE:
                action = "折叠显示";
                break;
            case MessageFilterRules.ACTION_SPOILER:
                action = "剧透遮挡";
                break;
            default:
                action = "";
                break;
        }
        String base = type + " · " + action;
        if (!rule.enabled) {
            base = base + " · 已禁用";
        }
        return base;
    }

    private class ListAdapter extends BaseListAdapter {

        ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial, boolean divider) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText("消息过滤规则");
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == addRow) {
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
                        cell.setText("＋ 添加规则", divider);
                    }
                    break;
                }
                case TYPE_DETAIL_SETTINGS: {
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    int index = position - rulesStartRow;
                    if (index >= 0 && index < rules.size()) {
                        MessageFilterRules.Rule rule = rules.get(index);
                        String title = rule.matchValue == null ? "" : rule.matchValue;
                        if (TextUtils.isEmpty(title)) {
                            title = "(任意 via Bot)";
                        } else if (rule.matchType != MessageFilterRules.MATCH_SENDER_ID && title.length() > 40) {
                            title = title.substring(0, 40);
                        }
                        cell.setTextAndValue(title, describeRule(rule), position != rulesEndRow - 1);
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText("规则按顺序匹配消息的正文和标题。当前版本仅「隐藏消息」动作会生效，命中的消息不会显示在聊天列表中；「折叠显示」「剧透遮挡」为预留动作。\n\n发送者 ID 为数字（用户、群组或频道 ID）。via Bot 留空或填写 * 表示任意通过 inline bot 发送的消息。");
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
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
            }
            if (position == addRow) {
                return TYPE_SETTINGS;
            }
            if (position == hintRow) {
                return TYPE_INFO_PRIVACY;
            }
            if (position >= rulesStartRow && position < rulesEndRow) {
                return TYPE_DETAIL_SETTINGS;
            }
            return TYPE_SHADOW;
        }
    }
}
