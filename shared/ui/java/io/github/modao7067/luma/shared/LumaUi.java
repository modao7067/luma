package io.github.modao7067.luma.shared;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

final class LumaUi {
    static final int BG_SURFACE = 0xf219202e;
    static final int BG_CARD = 0x12ffffff;
    static final int BG_CARD_ACTIVE = 0x1eabefdc;
    static final int STROKE_SUBTLE = 0x1fffffff;
    static final int STROKE_ACCENT = 0x55abefdc;
    static final int TEXT_PRIMARY = 0xffeef4fa;
    static final int TEXT_SECONDARY = 0xff97a7b7;
    static final int ACCENT = 0xffabefdc;
    static final int ACCENT_SURFACE = 0x24abefdc;
    static final int DANGER = 0xffed8796;
    static final int DANGER_SURFACE = 0x24ed8796;
    static final int WARN = 0xffeed49f;
    static final int WARN_SURFACE = 0x24eed49f;

    static int dp(Context c, float n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable roundedRect(int fillColor, float radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fillColor);
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, strokeColor);
        }
        return d;
    }

    static Drawable rippleSurface(int fillColor, int rippleColor, float radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable content = roundedRect(fillColor, radiusPx, strokeWidthPx, strokeColor);
        GradientDrawable mask = roundedRect(Color.WHITE, radiusPx, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static final class Sheet {
        final Dialog dialog;
        final LinearLayout body;
        private final LinearLayout card;
        private boolean dismissible = true;

        Sheet(Activity activity, String titleText, String subtitleText) {
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(true);

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
            overlay.setPadding(dp(activity, 12), dp(activity, 24), dp(activity, 12), dp(activity, 12));
            overlay.setOnClickListener(v -> { if (dismissible) dismiss(); });

            card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setClickable(true);
            card.setBackground(roundedRect(BG_SURFACE, dp(activity, 26), Math.max(1, dp(activity, 0.8f)), STROKE_SUBTLE));
            card.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 20));
            card.setElevation(dp(activity, 16));

            View handle = new View(activity);
            handle.setBackground(roundedRect(0x36ffffff, dp(activity, 2), 0, 0));
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(activity, 14);
            card.addView(handle, handleLp);

            if (titleText != null && !titleText.isEmpty()) {
                TextView title = new TextView(activity);
                title.setText(titleText);
                title.setTextColor(TEXT_PRIMARY);
                title.setTextSize(18f);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                card.addView(title, new LinearLayout.LayoutParams(-1, -2));
            }

            if (subtitleText != null && !subtitleText.isEmpty()) {
                TextView sub = new TextView(activity);
                sub.setText(subtitleText);
                sub.setTextColor(TEXT_SECONDARY);
                sub.setTextSize(13f);
                sub.setPadding(0, dp(activity, 4), 0, 0);
                card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            }

            ScrollView scroll = new ScrollView(activity);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
            scrollLp.topMargin = dp(activity, 14);

            body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
            card.addView(scroll, scrollLp);

            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            overlay.addView(card, cardLp);
            dialog.setContentView(overlay);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setGravity(Gravity.BOTTOM);
                window.setDimAmount(0.54f);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                        WindowManager.LayoutParams attrs = window.getAttributes();
                        attrs.setBlurBehindRadius(dp(activity, 20));
                        window.setAttributes(attrs);
                    } catch (Throwable ignored) {}
                }
            }
        }

        void show() {
            card.setTranslationY(dp(card.getContext(), 42));
            card.setAlpha(0f);
            dialog.show();
            card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(210L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
        }

        void setDismissible(boolean allowed) {
            dismissible = allowed;
            dialog.setCancelable(allowed);
            dialog.setCanceledOnTouchOutside(allowed);
        }

        void dismiss() {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }

    static TextView primaryButton(Context c, String label, View.OnClickListener onClick) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextColor(0xff0d1821);
        btn.setTextSize(15f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13));
        btn.setBackground(rippleSurface(ACCENT, 0x33000000, dp(c, 16), 0, 0));
        if (onClick != null) btn.setOnClickListener(onClick);
        return btn;
    }

    static TextView secondaryButton(Context c, String label, View.OnClickListener onClick) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextColor(TEXT_PRIMARY);
        btn.setTextSize(15f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13));
        btn.setBackground(rippleSurface(0x16ffffff, 0x26ffffff, dp(c, 16), 1, 0x18ffffff));
        if (onClick != null) btn.setOnClickListener(onClick);
        return btn;
    }

    static TextView dangerButton(Context c, String label, View.OnClickListener onClick) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextColor(DANGER);
        btn.setTextSize(15f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13));
        btn.setBackground(rippleSurface(DANGER_SURFACE, 0x33ed8796, dp(c, 16), 1, 0x38ed8796));
        if (onClick != null) btn.setOnClickListener(onClick);
        return btn;
    }

    static TextView chipButton(Context c, String label, boolean accent, View.OnClickListener onClick) {
        TextView chip = new TextView(c);
        chip.setText(label);
        chip.setTextColor(accent ? ACCENT : TEXT_PRIMARY);
        chip.setTextSize(12.5f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        chip.setBackground(rippleSurface(
            accent ? ACCENT_SURFACE : 0x16ffffff,
            0x28ffffff,
            dp(c, 12),
            1,
            accent ? STROKE_ACCENT : 0x1affffff
        ));
        if (onClick != null) chip.setOnClickListener(onClick);
        return chip;
    }

    static TextView badge(Context c, String text, int fgColor, int bgColor) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(fgColor);
        b.setTextSize(11.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
        b.setBackground(roundedRect(bgColor, dp(c, 10), 0, 0));
        return b;
    }

    static LinearLayout menuRow(Context c, String titleText, String subtitleText, String rightBadge, boolean highlightBadge, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        row.setBackground(rippleSurface(BG_CARD, 0x22ffffff, dp(c, 16), 1, 0x12ffffff));

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(c);
        title.setText(titleText);
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(15.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(title);

        if (subtitleText != null && !subtitleText.isEmpty()) {
            TextView sub = new TextView(c);
            sub.setText(subtitleText);
            sub.setTextColor(TEXT_SECONDARY);
            sub.setTextSize(12.5f);
            sub.setPadding(0, dp(c, 2), 0, 0);
            textCol.addView(sub);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        if (rightBadge != null && !rightBadge.isEmpty()) {
            TextView b = badge(
                c,
                rightBadge,
                highlightBadge ? ACCENT : TEXT_SECONDARY,
                highlightBadge ? ACCENT_SURFACE : 0x16ffffff
            );
            row.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }

        if (onClick != null) row.setOnClickListener(onClick);
        return row;
    }

    static EditText inputField(Context c, String initialText, String hint) {
        EditText input = new EditText(c);
        input.setSingleLine(true);
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_SECONDARY);
        if (hint != null) input.setHint(hint);
        if (initialText != null) {
            input.setText(initialText);
            input.setSelection(initialText.length());
        }
        input.setTextSize(15.5f);
        input.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        input.setBackground(roundedRect(0x16ffffff, dp(c, 14), 1, STROKE_ACCENT));
        return input;
    }

    interface IntChange {
        void onChanged(int value);
    }

    static LinearLayout windowNameRow(Context c, TextView name, View dot) {
        LinearLayout row = new LinearLayout(c) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int maxName = Integer.MAX_VALUE;
                if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                    maxName = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                    for (int i = 1; i < getChildCount(); i++) {
                        View child = getChildAt(i);
                        if (child.getVisibility() == View.GONE) continue;
                        measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                        maxName -= child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                    }
                }
                maxName = Math.max(0, maxName);
                if (name.getMaxWidth() != maxName) name.setMaxWidth(maxName);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(name, new LinearLayout.LayoutParams(-2, -2));
        row.addView(dot, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 20)));
        return row;
    }

    static View sliderCard(Context c, String labelText, String unit, int min, int max, int current, IntChange onChange) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        card.setBackground(roundedRect(BG_CARD, dp(c, 16), 1, 0x12ffffff));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(c);
        title.setText(labelText);
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(14.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView valBadge = badge(c, current + (unit == null ? "" : unit), ACCENT, ACCENT_SURFACE);
        header.addView(valBadge, new LinearLayout.LayoutParams(-2, -2));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        SeekBar bar = new SeekBar(c);
        bar.setMin(min);
        bar.setMax(max);
        bar.setSplitTrack(false);
        final int thumbSize = dp(c, 18);
        final int thumbRadius = thumbSize / 2;
        final int trackHeight = dp(c, 3);
        bar.setPadding(thumbRadius, dp(c, 10), thumbRadius, dp(c, 2));

        GradientDrawable bgTrack = roundedRect(0x22ffffff, trackHeight / 2f, 0, 0);
        bgTrack.setSize(-1, trackHeight);
        GradientDrawable fgTrack = roundedRect(ACCENT, trackHeight / 2f, 0, 0);
        fgTrack.setSize(-1, trackHeight);
        ClipDrawable clip = new ClipDrawable(fgTrack, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable track = new LayerDrawable(new Drawable[]{bgTrack, new ColorDrawable(Color.TRANSPARENT), clip});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.secondaryProgress);
        track.setId(2, android.R.id.progress);
        for (int i = 0; i < 3; i++) {
            track.setLayerHeight(i, trackHeight);
            track.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(null);
        bar.setSecondaryProgressTintList(null);
        bar.setProgressDrawable(track);
        bar.setProgress(Math.max(min, Math.min(max, current)));

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(thumbSize, thumbSize);
        thumb.setColor(Color.WHITE);
        bar.setThumb(thumb);
        bar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setThumbOffset(thumbRadius);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                valBadge.setText(v + (unit == null ? "" : unit));
                if (fromUser && onChange != null) onChange.onChanged(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        card.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    static LinearLayout buttonRow(Context c, View left, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp1.rightMargin = dp(c, 6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp2.leftMargin = dp(c, 6);
        row.addView(left, lp1);
        row.addView(right, lp2);
        return row;
    }

    static LinearLayout.LayoutParams vSpace(Context c, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, topDp);
        return lp;
    }
}
