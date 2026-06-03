package dev.gzq.coloroscloneproxy;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

final class StrokeRoundRectDrawable extends Drawable {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final float strokeWidth;

    StrokeRoundRectDrawable(int fillColor, int strokeColor, float radius, float strokeWidth) {
        fillPaint.setColor(fillColor);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setColor(strokeColor);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        this.radius = radius;
        this.strokeWidth = strokeWidth;
    }

    @Override
    public void draw(Canvas canvas) {
        RectF rect = new RectF(getBounds());
        float inset = strokeWidth / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        strokePaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
