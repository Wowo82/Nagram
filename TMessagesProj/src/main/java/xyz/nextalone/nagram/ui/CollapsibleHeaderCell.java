/*
 * 移植自 NextAlone/Nnngram 设置页的折叠分组样式（GPLv3）。
 * 用作设置页分组标题行：左侧标题、右侧折叠箭头。
 */
package xyz.nextalone.nagram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.LayoutHelper;

/**
 * 分组标题行。继承 {@link HeaderCell}，保留其文字渲染能力，
 * 额外在右侧绘制折叠箭头（展开时箭头朝下，折叠时朝右）。
 */
@SuppressLint("ViewConstructor")
public class CollapsibleHeaderCell extends HeaderCell {

    private final ImageView arrowView;
    private boolean expanded;

    public CollapsibleHeaderCell(Context context) {
        super(context, Theme.key_windowBackgroundWhiteBlackText, 20, 0, false, null);
        setHeight(52);

        arrowView = new ImageView(context);
        arrowView.setScaleType(ImageView.ScaleType.CENTER);
        arrowView.setImageResource(R.drawable.msg_arrowright);
        arrowView.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText5));
        arrowView.setAlpha(0.75f);
        addView(arrowView, LayoutHelper.createFrame(24, 24,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL,
                0, 0, 16, 0));

        // 给标题留出箭头空间，避免长标题压到箭头
        if (getTextView() != null) {
            getTextView().setPadding(0, 0, AndroidUtilities.dp(30), 0);
        }
    }

    public void setTextAndExpanded(CharSequence text, boolean expanded) {
        setText(text);
        setExpanded(expanded);
    }

    public void setExpanded(boolean expanded) {
        if (this.expanded == expanded) {
            return;
        }
        this.expanded = expanded;
        arrowView.animate().rotation(expanded ? 90f : 0f).setDuration(160).start();
    }

    public boolean isExpanded() {
        return expanded;
    }

    @Override
    public void setEnabled(boolean value, boolean animated) {
        super.setEnabled(value, animated);
        arrowView.setAlpha(value ? 0.75f : 0.4f);
    }
}
