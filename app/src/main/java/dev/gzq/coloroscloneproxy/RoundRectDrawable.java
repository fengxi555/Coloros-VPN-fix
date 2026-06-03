package dev.gzq.coloroscloneproxy;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

class RoundRectDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;

    RoundRectDrawable(int color, float radius) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        this.radius = radius;
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRoundRect(new RectF(getBounds()), radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
