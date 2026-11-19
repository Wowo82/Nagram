/*
 * 移植自 NextAlone/Nnngram 的 iOS 风格设置页（GPLv3）。
 * 为 Nagram 设置页提供「每个分组一张圆角卡片」的背景绘制。
 */
package xyz.nextalone.nagram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组卡片背景。
 *
 * <p>列表本身不绘制背景，由本装饰器按「分组」画圆角矩形：
 * 折叠状态下每个分组只有标题行，自然成为一张独立卡片；
 * 展开状态下标题行与组内设置项合并成一张卡片。</p>
 */
public class CardSettingsDecoration extends RecyclerView.ItemDecoration {

    public interface GroupProvider {
        /** 该位置所属分组的首个可见 position。 */
        int getGroupStart(int position);

        /** 该位置所属分组的最后一个可见 position。 */
        int getGroupEnd(int position);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GroupProvider provider;
    private final int horizontalPadding;
    private final float radius;
    private final RectF rect = new RectF();
    private final List<int[]> groups = new ArrayList<>();

    public CardSettingsDecoration(GroupProvider provider) {
        this(provider, 10, 14);
    }

    public CardSettingsDecoration(GroupProvider provider, int horizontalPaddingDp, float radiusDp) {
        this.provider = provider;
        this.horizontalPadding = AndroidUtilities.dp(horizontalPaddingDp);
        this.radius = AndroidUtilities.dp(radiusDp);
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        paint.setStyle(Paint.Style.FILL);
    }

    /** 主题切换后调用，刷新卡片颜色。 */
    public void updateColors() {
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (provider == null || parent.getChildCount() == 0) {
            return;
        }
        final RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) {
            return;
        }
        final LinearLayoutManager linear = (LinearLayoutManager) lm;
        final int first = linear.findFirstVisibleItemPosition();
        final int last = linear.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return;
        }

        groups.clear();
        int pos = first;
        while (pos <= last) {
            final int start = provider.getGroupStart(pos);
            final int end = provider.getGroupEnd(pos);
            if (start < 0 || end < start) {
                // 该行不参与卡片绘制（标题、说明、分隔行）
                pos++;
                continue;
            }
            groups.add(new int[]{start, end});
            pos = end + 1;
        }

        final int left = parent.getPaddingLeft() + horizontalPadding;
        final int right = parent.getWidth() - parent.getPaddingRight() - horizontalPadding;

        for (int[] group : groups) {
            float top = Float.MAX_VALUE;
            float bottom = -Float.MAX_VALUE;
            boolean found = false;
            for (int i = 0; i < parent.getChildCount(); i++) {
                final View child = parent.getChildAt(i);
                if (child == null) {
                    continue;
                }
                final int p = parent.getChildAdapterPosition(child);
                if (p < group[0] || p > group[1]) {
                    continue;
                }
                found = true;
                top = Math.min(top, child.getTop());
                bottom = Math.max(bottom, child.getBottom());
            }
            if (found && bottom > top) {
                rect.set(left, top, right, bottom);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }
    }
}
