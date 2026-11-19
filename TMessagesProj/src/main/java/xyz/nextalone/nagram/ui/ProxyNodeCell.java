/*
 * Nagram 新增：代理池节点行。
 * 两行布局——第一行地址、第二行入库时间，右侧显示延迟；左侧勾选圈仅在选择模式显示。
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
 *  ○  1.2.3.4:1080                    23ms
 *     入库 11-19 18:13
 * </pre>
 */
@SuppressLint("ViewConstructor")
public class ProxyNodeCell extends FrameLayout {

    private static final int ROW_HEIGHT = 64;
    private static final int TEXT_LEFT = 56;
    private static final int TEXT_LEFT_NO_CHECK = 20;
    private static final int TEXT_RIGHT = 78;

    private final CheckIndicator checkView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView pingView;
    private final Paint dividerPaint = new Paint();
    private boolean needDivider;
    private boolean showCheck = true;

    public ProxyNodeCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setMinimumHeight(AndroidUtilities.dp(ROW_HEIGHT));

        checkView = new CheckIndicator(context);
        addView(checkView, LayoutHelper.createFrame(22, 22,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 0 : 21, 0, LocaleController.isRTL ? 21 : 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                LocaleController.isRTL ? TEXT_RIGHT : TEXT_LEFT, 12, LocaleController.isRTL ? TEXT_LEFT : TEXT_RIGHT, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                LocaleController.isRTL ? TEXT_RIGHT : TEXT_LEFT, 33, LocaleController.isRTL ? TEXT_LEFT : TEXT_RIGHT, 0));

        pingView = new TextView(context);
        pingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pingView.setTypeface(Typeface.DEFAULT_BOLD);
        pingView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
        pingView.setSingleLine(true);
        addView(pingView, LayoutHelper.createFrame(60, LayoutHelper.MATCH_PARENT,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 20 : 0, 0, LocaleController.isRTL ? 0 : 20, 0));

        dividerPaint.setColor(Theme.getColor(Theme.key_divider));
        dividerPaint.setStrokeWidth(AndroidUtilities.dp(1));

        // 行内点击交给列表处理，这里保持不可点击以免抢走列表事件
        setClickable(false);
    }

    /**
     * @param ping empty 表示尚未测速
     * @param showCheck 是否显示左侧勾选圈（仅选择模式）
     * @param checked 是否已勾选
     */
    public void setNode(CharSequence title, CharSequence subtitle, CharSequence ping, int pingColor,
                        boolean showCheck, boolean checked, boolean divider) {
        final boolean checkChanged = this.showCheck != showCheck;
        this.showCheck = showCheck;
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
        checkView.setVisibility(showCheck ? VISIBLE : GONE);
        checkView.setChecked(checked);

        if (checkChanged) {
            final int left = AndroidUtilities.dp(showCheck ? TEXT_LEFT : TEXT_LEFT_NO_CHECK);
            final int right = AndroidUtilities.dp(TEXT_RIGHT);
            updateMargins(titleView, left, right);
            updateMargins(subtitleView, left, right);
        }

        needDivider = divider;
        invalidate();
    }

    private static void updateMargins(View view, int left, int right) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (LocaleController.isRTL) {
            lp.leftMargin = right;
            lp.rightMargin = left;
        } else {
            lp.leftMargin = left;
            lp.rightMargin = right;
        }
        view.setLayoutParams(lp);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            final int y = getMeasuredHeight() - 1;
            final int start = AndroidUtilities.dp(showCheck ? TEXT_LEFT : TEXT_LEFT_NO_CHECK);
            final int end = getMeasuredWidth();
            if (LocaleController.isRTL) {
                canvas.drawLine(0, y, end - start, y, dividerPaint);
            } else {
                canvas.drawLine(start, y, end, y, dividerPaint);
            }
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
            strokePaint.setColor(Theme.getColor(Theme.key_switchTrack));
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
