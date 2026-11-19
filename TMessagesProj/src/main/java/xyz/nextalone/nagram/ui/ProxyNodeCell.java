/*
 * Nagram 新增：代理池节点行。
 * 两行布局——第一行地址、第二行入库时间，右侧显示延迟。
 */
package xyz.nextalone.nagram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * 代理池中的单个节点：
 * <pre>
 *  ○  socks5://1.2.3.4:1080            23ms
 *     入库 11-19 18:13
 * </pre>
 */
@SuppressLint("ViewConstructor")
public class ProxyNodeCell extends FrameLayout {

    private final CheckIndicator checkView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView pingView;
    private final Paint dividerPaint = new Paint();
    private boolean needDivider;

    public ProxyNodeCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setMinimumHeight(AndroidUtilities.dp(60));

        checkView = new CheckIndicator(context);
        addView(checkView, LayoutHelper.createFrame(24, 24,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 0 : 20, 0, LocaleController.isRTL ? 20 : 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                LocaleController.isRTL ? 72 : 56, 10, LocaleController.isRTL ? 56 : 72, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                LocaleController.isRTL ? 72 : 56, 31, LocaleController.isRTL ? 56 : 72, 0));

        pingView = new TextView(context);
        pingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pingView.setTypeface(Typeface.DEFAULT_BOLD);
        pingView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
        pingView.setSingleLine(true);
        addView(pingView, LayoutHelper.createFrame(64, LayoutHelper.MATCH_PARENT,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 18 : 0, 0, LocaleController.isRTL ? 0 : 18, 0));

        dividerPaint.setColor(Theme.getColor(Theme.key_divider));
        dividerPaint.setStrokeWidth(AndroidUtilities.dp(1));

        // 行内点击交给列表处理，这里保持可点击以便选中反馈
        setClickable(false);
    }

    /**
     * @param ping empty 表示尚未测速
     * @param checked 是否已勾选
     */
    public void setNode(CharSequence title, CharSequence subtitle, CharSequence ping, int pingColor,
                        boolean checked, boolean divider) {
        titleView.setText(title);
        if (TextUtils.isEmpty(subtitle)) {
            subtitleView.setVisibility(GONE);
        } else {
            subtitleView.setVisibility(VISIBLE);
            subtitleView.setText(subtitle);
        }
        if (TextUtils.isEmpty(ping)) {
            pingView.setText("");
        } else {
            pingView.setText(ping);
            pingView.setTextColor(pingColor);
        }
        checkView.setChecked(checked);
        needDivider = divider;
        invalidate();
    }

    public void setChecked(boolean checked) {
        checkView.setChecked(checked);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            final int y = getMeasuredHeight() - 1;
            final int start = AndroidUtilities.dp(LocaleController.isRTL ? 0 : 56);
            final int end = getMeasuredWidth() - AndroidUtilities.dp(LocaleController.isRTL ? 56 : 0);
            canvas.drawLine(start, y, end, y, dividerPaint);
        }
    }

    private static class CheckIndicator extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private boolean checked;

        CheckIndicator(Context context) {
            super(context);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(AndroidUtilities.dp(1.5f));
            strokePaint.setColor(Theme.getColor(Theme.key_checkbox));
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(Theme.getColor(Theme.key_switch2TrackChecked));
            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setStrokeWidth(AndroidUtilities.dp(2));
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);
            checkPaint.setColor(0xFFFFFFFF);
            setWillNotDraw(false);
        }

        void setChecked(boolean value) {
            if (checked == value) {
                return;
            }
            checked = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float r = Math.min(getWidth(), getHeight()) / 2f - AndroidUtilities.dp(1.5f);
            if (checked) {
                canvas.drawCircle(cx, cy, r, fillPaint);
                checkPath.reset();
                checkPath.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                checkPath.lineTo(cx - r * 0.10f, cy + r * 0.34f);
                checkPath.lineTo(cx + r * 0.46f, cy - r * 0.30f);
                canvas.drawPath(checkPath, checkPaint);
            } else {
                canvas.drawCircle(cx, cy, r, strokePaint);
            }
        }
    }
}
