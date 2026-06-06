package dev.gzq.coloroscloneproxy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.LinearLayout;

final class BlurNavLayout extends LinearLayout {
    private static final int DOWNSAMPLE = 6;
    private static final int BLUR_RADIUS = 5;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF bounds = new RectF();
    private final int[] selfLocation = new int[2];
    private final int[] targetLocation = new int[2];

    private View blurTarget;
    private Bitmap blurBitmap;
    private Canvas blurCanvas;
    private int[] pixels;
    private int[] scratch;
    private float cornerRadius;
    private float strokeWidth;

    BlurNavLayout(Context context) {
        super(context);
        setWillNotDraw(false);
        cornerRadius = dp(28);
        strokeWidth = dp(1);
        overlayPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setColor(Color.argb(232, 255, 255, 255));
    }

    void setBlurTarget(View target) {
        blurTarget = target;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawBlurredGlass(canvas);
        super.onDraw(canvas);
    }

    private void drawBlurredGlass(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        bounds.set(0f, 0f, width, height);
        clipPath.reset();
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW);

        int save = canvas.save();
        canvas.clipPath(clipPath);
        Bitmap bitmap = captureAndBlur(width, height);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, bounds, bitmapPaint);
        }
        overlayPaint.setShader(new LinearGradient(
                0f, 0f, 0f, height,
                Color.argb(226, 255, 255, 255),
                Color.argb(186, 255, 255, 255),
                Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, overlayPaint);
        overlayPaint.setShader(null);
        canvas.restoreToCount(save);

        RectF strokeBounds = new RectF(bounds);
        strokeBounds.inset(strokeWidth / 2f, strokeWidth / 2f);
        canvas.drawRoundRect(strokeBounds, cornerRadius, cornerRadius, strokePaint);
    }

    private Bitmap captureAndBlur(int width, int height) {
        if (blurTarget == null || blurTarget.getWidth() <= 0 || blurTarget.getHeight() <= 0) {
            return null;
        }

        int bitmapWidth = Math.max(1, width / DOWNSAMPLE);
        int bitmapHeight = Math.max(1, height / DOWNSAMPLE);
        ensureBitmap(bitmapWidth, bitmapHeight);
        if (blurBitmap == null || blurCanvas == null) {
            return null;
        }

        blurBitmap.eraseColor(Color.TRANSPARENT);
        getLocationInWindow(selfLocation);
        blurTarget.getLocationInWindow(targetLocation);

        float scale = (float) bitmapWidth / (float) width;
        int save = blurCanvas.save();
        blurCanvas.scale(scale, scale);
        blurCanvas.translate(targetLocation[0] - selfLocation[0],
                targetLocation[1] - selfLocation[1]);
        blurTarget.draw(blurCanvas);
        blurCanvas.restoreToCount(save);

        boxBlur(blurBitmap, BLUR_RADIUS, 2);
        return blurBitmap;
    }

    private void ensureBitmap(int width, int height) {
        if (blurBitmap != null && blurBitmap.getWidth() == width && blurBitmap.getHeight() == height) {
            return;
        }
        blurBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        blurCanvas = new Canvas(blurBitmap);
        pixels = null;
        scratch = null;
    }

    private void boxBlur(Bitmap bitmap, int radius, int iterations) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int count = width * height;
        if (count <= 0 || radius <= 0) {
            return;
        }
        ensurePixelBuffers(count);
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < iterations; i++) {
            blurHorizontal(pixels, scratch, width, height, radius);
            blurVertical(scratch, pixels, width, height, radius);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private void ensurePixelBuffers(int count) {
        if (pixels != null && pixels.length >= count) {
            return;
        }
        pixels = new int[count];
        scratch = new int[count];
    }

    private static void blurHorizontal(int[] in, int[] out, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int a = 0;
            int r = 0;
            int g = 0;
            int b = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = in[row + clamp(i, 0, width - 1)];
                a += (color >>> 24) & 0xff;
                r += (color >>> 16) & 0xff;
                g += (color >>> 8) & 0xff;
                b += color & 0xff;
            }
            for (int x = 0; x < width; x++) {
                out[row + x] = ((a / window) << 24)
                        | ((r / window) << 16)
                        | ((g / window) << 8)
                        | (b / window);
                int remove = in[row + clamp(x - radius, 0, width - 1)];
                int add = in[row + clamp(x + radius + 1, 0, width - 1)];
                a += ((add >>> 24) & 0xff) - ((remove >>> 24) & 0xff);
                r += ((add >>> 16) & 0xff) - ((remove >>> 16) & 0xff);
                g += ((add >>> 8) & 0xff) - ((remove >>> 8) & 0xff);
                b += (add & 0xff) - (remove & 0xff);
            }
        }
    }

    private static void blurVertical(int[] in, int[] out, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            int a = 0;
            int r = 0;
            int g = 0;
            int b = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = in[clamp(i, 0, height - 1) * width + x];
                a += (color >>> 24) & 0xff;
                r += (color >>> 16) & 0xff;
                g += (color >>> 8) & 0xff;
                b += color & 0xff;
            }
            for (int y = 0; y < height; y++) {
                out[y * width + x] = ((a / window) << 24)
                        | ((r / window) << 16)
                        | ((g / window) << 8)
                        | (b / window);
                int remove = in[clamp(y - radius, 0, height - 1) * width + x];
                int add = in[clamp(y + radius + 1, 0, height - 1) * width + x];
                a += ((add >>> 24) & 0xff) - ((remove >>> 24) & 0xff);
                r += ((add >>> 16) & 0xff) - ((remove >>> 16) & 0xff);
                g += ((add >>> 8) & 0xff) - ((remove >>> 8) & 0xff);
                b += (add & 0xff) - (remove & 0xff);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
