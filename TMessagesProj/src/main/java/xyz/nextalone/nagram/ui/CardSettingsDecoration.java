/*
 * 来源：Nagram（Wowo82/Nagram）新增文件，本工程原创。
 * 用途：为设置列表提供「每行一张圆角卡片」的间距（配合 RecyclerListView 的 forcedSections 使用）。
 * 说明：只调整 ItemDecoration 的上下间距，不参与背景绘制；卡片背景由 legacy sections 装饰器绘制。
 */

package xyz.nextalone.nagram.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Cells.EmptyCell;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;

public class CardSettingsDecoration extends RecyclerView.ItemDecoration {

    private final int gap;

    public CardSettingsDecoration() {
        this.gap = AndroidUtilities.dp(4);
    }

    public static boolean isCardRow(View view) {
        return !(view instanceof HeaderCell
                || view instanceof TextInfoPrivacyCell
                || view instanceof ShadowSectionCell
                || view instanceof EmptyCell
                || view instanceof GraySectionCell);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (isCardRow(view)) {
            outRect.top += gap;
            outRect.bottom += gap;
        }
    }
}
