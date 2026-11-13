package io.github.modao7067.luma.shared;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

final class ToolbarIcon extends Drawable {
    enum Kind { KEYBOARD, WINDOWS, MORE, BACK }

    private static final int ICON_COLOR = 0xffc6d3df;
    private static final float STROKE_WIDTH = 1.7f;

    private final Kind kind;
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path rearWindowPath = new Path();
    private final RectF arcBounds = new RectF();
    private int count = 1;

    ToolbarIcon(Kind kind) {
        this.kind = kind;
        strokePaint.setColor(ICON_COLOR);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(STROKE_WIDTH);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setColor(ICON_COLOR);
        fillPaint.setStyle(Paint.Style.FILL);

        badgePaint.setColor(0xffabefdc);
        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setTextAlign(Paint.Align.CENTER);
        badgePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badgePaint.setTextSize(7.6f);

        if (kind == Kind.WINDOWS) {
            buildRearWindowPath();
        }
    }

    void setCount(int newCount) {
        if (this.count != newCount) {
            this.count = newCount;
            invalidateSelf();
        }
    }

    private void buildRearWindowPath() {
        float r = 2.8f;
        float d = r * 2f;
        rearWindowPath.reset();
        rearWindowPath.moveTo(7.2f, 16.5f);
        rearWindowPath.lineTo(4.0f + r, 16.5f);
        arcBounds.set(4.0f, 16.5f - d, 4.0f + d, 16.5f);
        rearWindowPath.arcTo(arcBounds, 90f, 90f, false);
        rearWindowPath.lineTo(4.0f, 4.0f + r);
        arcBounds.set(4.0f, 4.0f, 4.0f + d, 4.0f + d);
        rearWindowPath.arcTo(arcBounds, 180f, 90f, false);
        rearWindowPath.lineTo(16.5f - r, 4.0f);
        arcBounds.set(16.5f - d, 4.0f, 16.5f, 4.0f + d);
        rearWindowPath.arcTo(arcBounds, 270f, 90f, false);
        rearWindowPath.lineTo(16.5f, 7.2f);
    }

    @Override
    public void draw(Canvas canvas) {
        if(kind==Kind.BACK){
            canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
            Path back=new Path();back.moveTo(14.5f,6f);back.lineTo(8.5f,12f);back.lineTo(14.5f,18f);canvas.drawPath(back,strokePaint);canvas.restore();return;
        }
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);

        if (kind == Kind.KEYBOARD) {
            canvas.drawRoundRect(3.25f, 5.25f, 20.75f, 18.75f, 3.2f, 3.2f, strokePaint);
            float[] cols = {6.9f, 10.3f, 13.7f, 17.1f};
            float[] rows = {8.85f, 11.85f};
            for (float y : rows) {
                for (float x : cols) {
                    canvas.drawCircle(x, y, 0.92f, fillPaint);
                }
            }
            canvas.drawLine(8.5f, 15.55f, 15.5f, 15.55f, strokePaint);
        } else if (kind == Kind.WINDOWS) {
            canvas.drawPath(rearWindowPath, strokePaint);
            canvas.drawRoundRect(7.5f, 7.5f, 20.0f, 20.0f, 2.8f, 2.8f, strokePaint);
            if (count > 1) {
                String label = count > 9 ? "9+" : String.valueOf(count);
                Paint.FontMetrics fm = badgePaint.getFontMetrics();
                float cy = 13.75f - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(label, 13.75f, cy, badgePaint);
            }
        } else {
            canvas.drawCircle(12.0f, 5.5f, 1.35f, fillPaint);
            canvas.drawCircle(12.0f, 12.0f, 1.35f, fillPaint);
            canvas.drawCircle(12.0f, 18.5f, 1.35f, fillPaint);
        }

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        strokePaint.setAlpha(alpha);
        fillPaint.setAlpha(alpha);
        badgePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        strokePaint.setColorFilter(filter);
        fillPaint.setColorFilter(filter);
        badgePaint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
