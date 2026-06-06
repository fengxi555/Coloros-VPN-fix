package dev.gzq.coloroscloneproxy;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class GlassPanelDrawable extends Drawable {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final float strokeWidth;
    private final int topColor;
    private final int bottomColor;

    GlassPanelDrawable(int topColor, int bottomColor, int strokeColor, float radius, float strokeWidth) {
        this.topColor = topColor;
        this.bottomColor = bottomColor;
        this.radius = radius;
        this.strokeWidth = strokeWidth;
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setColor(strokeColor);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        fillPaint.setShader(new LinearGradient(
                0f, bounds.top, 0f, bounds.bottom,
                topColor, bottomColor, Shader.TileMode.CLAMP));
        RectF rect = new RectF(bounds);
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
