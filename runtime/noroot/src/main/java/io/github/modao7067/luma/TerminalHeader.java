package io.github.modao7067.luma;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

final class TerminalHeader extends LinearLayout {
    final ImageButton back, keyboard, windows;
    final TextView title;
    final ToolbarIcon windowsIcon;

    TerminalHeader(Context context, View status) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setPadding(dp(4), 0, dp(8), 0);
        setBackgroundColor(Color.BLACK);

        back = button(new BackIcon(), 48);
        back.setContentDescription("返回会话");
        addView(back);

        title = new TextView(context);
        title.setTextColor(0xffe4edf3);
        title.setTextSize(16f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout group = new LinearLayout(context);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setBaselineAligned(false);
        group.addView(title, new LinearLayout.LayoutParams(-2, -1));
        group.addView(status, new LinearLayout.LayoutParams(dp(18), -1));
        group.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int max = Math.max(1,r-l-(status.getVisibility()==View.GONE?0:dp(18)));
            if(title.getMaxWidth()!=max)title.setMaxWidth(max);
        });
        addView(group, new LinearLayout.LayoutParams(0, -1, 1f));

        keyboard = button(new ToolbarIcon(ToolbarIcon.Kind.KEYBOARD), 45);
        keyboard.setContentDescription("显示或隐藏键盘");
        addView(keyboard);
        windowsIcon = new ToolbarIcon(ToolbarIcon.Kind.WINDOWS);
        windows = button(windowsIcon, 45);
        windows.setContentDescription("多窗口：点击切换，长按新建");
        addView(windows);
    }

    void setSessionLocked(boolean locked) {
        windows.setVisibility(locked ? View.GONE : View.VISIBLE);
        windows.setEnabled(!locked);
        title.setClickable(!locked);
        title.setLongClickable(!locked);
    }

    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    private ImageButton button(Drawable icon, int size) {
        ImageButton button = new ImageButton(getContext());
        button.setImageDrawable(icon);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp((size-24)/2f);
        button.setPadding(pad,pad,pad,pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(size),dp(size));
        params.gravity = Gravity.CENTER_VERTICAL;
        button.setLayoutParams(params);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE); mask.setCornerRadius(dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),null,mask));
        return button;
    }

    private static final class BackIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        BackIcon() {
            paint.setColor(0xffe4edf3); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.9f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            path.moveTo(14.5f,6f); path.lineTo(8.5f,12f); path.lineTo(14.5f,18f);
        }
        public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left,getBounds().top);
            canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
            canvas.drawPath(path,paint); canvas.restore();
        }
        public int getIntrinsicWidth(){return 24;}
        public int getIntrinsicHeight(){return 24;}
        public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
