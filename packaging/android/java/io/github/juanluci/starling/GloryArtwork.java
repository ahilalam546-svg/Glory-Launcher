package io.github.juanluci.starling;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.Base64;
import android.view.View;
import java.io.File;

/**
 * Custom obsidian-glass artwork, voxel landscape banner, pixel avatar/thumbnail views, and
 * vector navigation icons for Glory Launcher (pure android.graphics, zero external assets).
 */
final class GloryArtwork {
    private GloryArtwork() {}

    enum IconKind {
        HOME,
        WORLDS,
        MODS,
        SHADERS,
        CONTROLS
    }

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Deep obsidian backdrop with emerald + violet aurora radial glows matching the mockup. */
    static final class AuroraBackdropDrawable extends Drawable {
        private final Paint bgPaint = new Paint();
        private final Paint emeraldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint violetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        AuroraBackdropDrawable() {
            bgPaint.setColor(0xFF0B0E14);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            float w = Math.max(1f, bounds.width());
            float h = Math.max(1f, bounds.height());
            emeraldPaint.setShader(new RadialGradient(
                    w * 0.24f,
                    h * 0.08f,
                    Math.max(w, h) * 0.65f,
                    new int[] {0x2B10B981, 0x1010B981, 0x0010B981},
                    new float[] {0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            violetPaint.setShader(new RadialGradient(
                    w * 0.84f,
                    h * 0.14f,
                    Math.max(w, h) * 0.60f,
                    new int[] {0x267C3AED, 0x0D7C3AED, 0x007C3AED},
                    new float[] {0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b, bgPaint);
            canvas.drawRect(b, emeraldPaint);
            canvas.drawRect(b, violetPaint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    static Drawable glassCard(Context context, int fillColor, int borderColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(context, radiusDp));
        gd.setStroke(Math.max(1, dp(context, 1)), borderColor);
        return gd;
    }

    static Drawable clickableGlassCard(
            Context context, int fillColor, int borderColor, float radiusDp) {
        Drawable base = glassCard(context, fillColor, borderColor, radiusDp);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(context, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), base, mask);
    }

    static Drawable activeNavPill(Context context) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x3A10B981, 0x1D10B981, 0x14192231});
        bg.setCornerRadius(dp(context, 10));
        bg.setStroke(Math.max(1, dp(context, 1)), 0x6610B981);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(context, 10));
        return new RippleDrawable(ColorStateList.valueOf(0x3010B981), bg, mask);
    }

    static Drawable inactiveNavPill(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dp(context, 10));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(context, 10));
        return new RippleDrawable(ColorStateList.valueOf(0x1CFFFFFF), bg, mask);
    }

    static Drawable emeraldCta(Context context) {
        int radius = dp(context, 24);
        GradientDrawable glow = new GradientDrawable();
        glow.setColor(0x3010B981);
        glow.setCornerRadius(radius + dp(context, 4));

        GradientDrawable pill = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF19CC8F, 0xFF10B981, 0xFF059669});
        pill.setCornerRadius(radius);
        pill.setStroke(Math.max(1, dp(context, 1)), 0x80A7F3D0);

        LayerDrawable layered = new LayerDrawable(new Drawable[] {glow, pill});
        int inset = dp(context, 3);
        layered.setLayerInset(1, inset, inset, inset, inset);

        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), layered, mask);
    }

    static Drawable chipDrawable(Context context, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0x3310B981 : 0xCC141C28);
        bg.setCornerRadius(dp(context, 9));
        bg.setStroke(
                Math.max(1, dp(context, 1)),
                active ? 0x9910B981 : 0x26FFFFFF);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(context, 9));
        return new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), bg, mask);
    }

    /** Crisp custom vector icons matching the left sidebar and bottom cards. */
    static final class NavIconDrawable extends Drawable {
        private final IconKind kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;

        NavIconDrawable(Context context, IconKind kind, int color, float sizeDp) {
            this.kind = kind;
            this.sizePx = dp(context, sizeDp);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, dp(context, 1.7f)));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setTintColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float s = Math.min(w, h) / 24f;
            float ox = b.left + (w - 24f * s) * 0.5f;
            float oy = b.top + (h - 24f * s) * 0.5f;
            canvas.save();
            canvas.translate(ox, oy);
            canvas.scale(s, s);
            switch (kind) {
                case HOME: {
                    Path p = new Path();
                    p.moveTo(3f, 10.5f);
                    p.lineTo(12f, 3.5f);
                    p.lineTo(21f, 10.5f);
                    p.moveTo(5.5f, 9f);
                    p.lineTo(5.5f, 20f);
                    p.lineTo(18.5f, 20f);
                    p.lineTo(18.5f, 9f);
                    p.moveTo(10f, 20f);
                    p.lineTo(10f, 14f);
                    p.lineTo(14f, 14f);
                    p.lineTo(14f, 20f);
                    canvas.drawPath(p, paint);
                    break;
                }
                case WORLDS: {
                    canvas.drawCircle(12f, 12f, 8.5f, paint);
                    canvas.drawLine(3.6f, 12f, 20.4f, 12f, paint);
                    RectF oval = new RectF(8f, 3.6f, 16f, 20.4f);
                    canvas.drawOval(oval, paint);
                    break;
                }
                case MODS: {
                    Path p = new Path();
                    p.moveTo(12f, 3f);
                    p.lineTo(20f, 7.5f);
                    p.lineTo(20f, 16.5f);
                    p.lineTo(12f, 21f);
                    p.lineTo(4f, 16.5f);
                    p.lineTo(4f, 7.5f);
                    p.close();
                    p.moveTo(4f, 7.5f);
                    p.lineTo(12f, 12f);
                    p.lineTo(20f, 7.5f);
                    p.moveTo(12f, 12f);
                    p.lineTo(12f, 21f);
                    canvas.drawPath(p, paint);
                    break;
                }
                case SHADERS: {
                    Path p = new Path();
                    p.moveTo(12f, 4f);
                    p.lineTo(20f, 9f);
                    p.lineTo(12f, 14f);
                    p.lineTo(4f, 9f);
                    p.close();
                    p.moveTo(4f, 13.5f);
                    p.lineTo(12f, 18.5f);
                    p.lineTo(20f, 13.5f);
                    canvas.drawPath(p, paint);
                    break;
                }
                case CONTROLS: {
                    RectF pad = new RectF(3f, 7f, 21f, 18f);
                    canvas.drawRoundRect(pad, 4f, 4f, paint);
                    canvas.drawLine(6.5f, 12.5f, 10.5f, 12.5f, paint);
                    canvas.drawLine(8.5f, 10.5f, 8.5f, 14.5f, paint);
                    canvas.drawPoint(15f, 11.5f, paint);
                    canvas.drawPoint(17.5f, 13.5f, paint);
                    break;
                }
            }
            canvas.restore();
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

    /**
     * Voxel mountain sunset hero panorama inside a rounded glass frame, faithfully recreating
     * Option 1's warm horizon atmosphere without shipping large PNGs.
     */
    static final class HeroLandscapeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pixelPaint = new Paint();
        private final Path clipPath = new Path();
        private final RectF boundsRect = new RectF();
        private final float radius;
        private LinearGradient skyShader;
        private RadialGradient sunGlowShader;
        private int lastW;
        private int lastH;

        HeroLandscapeView(Context context) {
            super(context);
            this.radius = dp(context, 16);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (w != lastW || h != lastH) {
                lastW = w;
                lastH = h;
                boundsRect.set(0f, 0f, w, h);
                clipPath.reset();
                clipPath.addRoundRect(boundsRect, radius, radius, Path.Direction.CW);
                skyShader = new LinearGradient(
                        0f, 0f, 0f, h * 0.72f,
                        new int[] {0xFF1F1D3A, 0xFF4C2C55, 0xFFB8564B, 0xFFF3A752},
                        new float[] {0f, 0.36f, 0.72f, 1f},
                        Shader.TileMode.CLAMP);
                sunGlowShader = new RadialGradient(
                        w * 0.56f, h * 0.58f, h * 0.56f,
                        new int[] {0xDDFDE68A, 0x66F59E0B, 0x00F59E0B},
                        new float[] {0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP);
            }

            canvas.save();
            canvas.clipPath(clipPath);

            // Sunset sky
            paint.setShader(skyShader);
            canvas.drawRect(boundsRect, paint);
            paint.setShader(null);

            // Radiant sun glow + voxel sun square
            paint.setShader(sunGlowShader);
            canvas.drawRect(boundsRect, paint);
            paint.setShader(null);
            float sunSize = Math.max(16f, h * 0.19f);
            float sunX = w * 0.56f - sunSize * 0.5f;
            float sunY = h * 0.48f - sunSize * 0.5f;
            pixelPaint.setColor(0xFFFEF3C7);
            canvas.drawRect(sunX, sunY, sunX + sunSize, sunY + sunSize, pixelPaint);

            // Soft upper twilight stars & pixel clouds
            pixelPaint.setColor(0x55FFFFFF);
            float unit = Math.max(4f, h / 22f);
            drawCloud(canvas, w * 0.18f, h * 0.22f, unit, 0x3AFFE4B5);
            drawCloud(canvas, w * 0.73f, h * 0.17f, unit * 0.85f, 0x33FFD7AA);

            // Distant indigo voxel peaks
            drawVoxelRidge(canvas, w, h, 0.62f, 0.26f, 11, 0xFF2E274A, 0x44C084FC);
            // Mid-range plum-slate mountain range
            drawVoxelRidge(canvas, w, h, 0.70f, 0.20f, 14, 0xFF1A1F34, 0x55F59E0B);
            // Foreground dark obsidian & emerald pine cliffs
            drawVoxelRidge(canvas, w, h, 0.80f, 0.16f, 18, 0xFF0E1821, 0x6610B981);

            // Alpine valley water shimmer strip at bottom right
            pixelPaint.setColor(0xFF132234);
            canvas.drawRect(0f, h * 0.84f, w, h, pixelPaint);
            pixelPaint.setColor(0x55FBBF24);
            canvas.drawRect(w * 0.44f, h * 0.86f, w * 0.68f, h * 0.90f, pixelPaint);
            pixelPaint.setColor(0x3010B981);
            canvas.drawRect(w * 0.10f, h * 0.88f, w * 0.36f, h * 0.92f, pixelPaint);

            // Subtle inner glass frame border
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, radius * 0.08f));
            paint.setColor(0x38FFFFFF);
            canvas.drawRoundRect(boundsRect, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }

        private void drawCloud(Canvas canvas, float x, float y, float u, int color) {
            pixelPaint.setColor(color);
            canvas.drawRect(x, y, x + u * 6f, y + u * 1.4f, pixelPaint);
            canvas.drawRect(x + u, y - u, x + u * 4.2f, y, pixelPaint);
        }

        private void drawVoxelRidge(
                Canvas canvas,
                int w,
                int h,
                float baseFrac,
                float ampFrac,
                int columns,
                int bodyColor,
                int edgeHighlight) {
            float step = w / (float) columns;
            float blockStep = Math.max(4f, h / 18f);
            for (int i = 0; i < columns; i++) {
                float norm = (i / (float) Math.max(1, columns - 1));
                double wave = Math.sin(norm * Math.PI * 2.3 + baseFrac * 9.0) * 0.55
                        + Math.cos(norm * Math.PI * 4.7 + columns) * 0.45;
                float peakY = h * (baseFrac - (float) ((wave * 0.5 + 0.5) * ampFrac));
                float snappedY = Math.round(peakY / blockStep) * blockStep;
                float x0 = i * step;
                float x1 = x0 + step + 1f;
                pixelPaint.setColor(bodyColor);
                canvas.drawRect(x0, snappedY, x1, h, pixelPaint);
                pixelPaint.setColor(edgeHighlight);
                canvas.drawRect(x0, snappedY, x1, snappedY + blockStep * 0.45f, pixelPaint);
            }
        }
    }

    /** Crisp pixel-art Minecraft head avatar (from active skin PNG or Steve fallback). */
    static final class PixelAvatarView extends View {
        private final Paint bmpPaint = new Paint();
        private final Paint fillPaint = new Paint();
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private Bitmap headBitmap;
        private Bitmap hatBitmap;
        private final float cornerRadius;

        PixelAvatarView(Context context) {
            super(context);
            this.cornerRadius = dp(context, 8);
            bmpPaint.setFilterBitmap(false);
            bmpPaint.setAntiAlias(false);
        }

        void setSkinFile(File skinFile) {
            headBitmap = null;
            hatBitmap = null;
            if (skinFile != null && skinFile.isFile()) {
                Bitmap full = BitmapFactory.decodeFile(skinFile.getPath());
                if (full != null) {
                    int w = full.getWidth();
                    int h = full.getHeight();
                    if (w >= 64 && h >= 32) {
                        int scale = Math.max(1, w / 64);
                        headBitmap = Bitmap.createBitmap(
                                full, 8 * scale, 8 * scale, 8 * scale, 8 * scale);
                        if (h >= 64 * scale) {
                            hatBitmap = Bitmap.createBitmap(
                                    full, 40 * scale, 8 * scale, 8 * scale, 8 * scale);
                        }
                    }
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            rect.set(0f, 0f, w, h);
            clipPath.reset();
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            if (headBitmap != null) {
                canvas.drawBitmap(headBitmap, null, rect, bmpPaint);
                if (hatBitmap != null) {
                    canvas.drawBitmap(hatBitmap, null, rect, bmpPaint);
                }
            } else {
                drawSteveFallback(canvas, w, h);
            }
            canvas.restore();
        }

        private void drawSteveFallback(Canvas canvas, int w, int h) {
            float u = w / 8f;
            float v = h / 8f;
            fillPaint.setColor(0xFFC6906B);
            canvas.drawRect(0, 0, w, h, fillPaint);
            // Hair
            fillPaint.setColor(0xFF3E2618);
            canvas.drawRect(0, 0, w, 2f * v, fillPaint);
            canvas.drawRect(0, 2f * v, u, 3f * v, fillPaint);
            canvas.drawRect(7f * u, 2f * v, w, 3f * v, fillPaint);
            // Eyes
            fillPaint.setColor(0xFFFFFFFF);
            canvas.drawRect(1f * u, 4f * v, 2f * u, 5f * v, fillPaint);
            canvas.drawRect(6f * u, 4f * v, 7f * u, 5f * v, fillPaint);
            fillPaint.setColor(0xFF436EE8);
            canvas.drawRect(2f * u, 4f * v, 3f * u, 5f * v, fillPaint);
            canvas.drawRect(5f * u, 4f * v, 6f * u, 5f * v, fillPaint);
            // Nose & mouth
            fillPaint.setColor(0xFF9E6B4F);
            canvas.drawRect(3f * u, 5f * v, 5f * u, 6f * v, fillPaint);
            fillPaint.setColor(0xFF6B3E2B);
            canvas.drawRect(2f * u, 6f * v, 6f * u, 7f * v, fillPaint);
            fillPaint.setColor(0xFF8C5A3E);
            canvas.drawRect(3f * u, 6f * v, 5f * u, 7f * v, fillPaint);
        }
    }

    /** Crisp rounded thumbnail for World icons & Server favicons with voxel fallbacks. */
    static final class ThumbnailView extends View {
        static final int KIND_WORLD = 0;
        static final int KIND_SERVER = 1;

        private final int kind;
        private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint();
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private final float cornerRadius;
        private Bitmap bitmap;

        ThumbnailView(Context context, int kind) {
            super(context);
            this.kind = kind;
            this.cornerRadius = dp(context, 7);
        }

        void setWorldIcon(File iconFile) {
            bitmap = (iconFile != null && iconFile.isFile())
                    ? BitmapFactory.decodeFile(iconFile.getPath())
                    : null;
            invalidate();
        }

        void setServerIconBase64(String base64Png) {
            bitmap = null;
            if (base64Png != null && !base64Png.isEmpty()) {
                try {
                    String clean = base64Png;
                    int comma = clean.indexOf(',');
                    if (comma >= 0) {
                        clean = clean.substring(comma + 1);
                    }
                    byte[] raw = Base64.decode(clean, Base64.DEFAULT);
                    bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                } catch (IllegalArgumentException ignored) {
                    bitmap = null;
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            rect.set(0f, 0f, w, h);
            clipPath.reset();
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, rect, bmpPaint);
            } else if (kind == KIND_WORLD) {
                drawGrassTile(canvas, w, h);
            } else {
                drawServerTile(canvas, w, h);
            }
            canvas.restore();
        }

        private void drawGrassTile(Canvas canvas, int w, int h) {
            fillPaint.setColor(0xFF7B5033);
            canvas.drawRect(0, 0, w, h, fillPaint);
            fillPaint.setColor(0xFF603C24);
            canvas.drawRect(w * 0.15f, h * 0.55f, w * 0.42f, h * 0.80f, fillPaint);
            canvas.drawRect(w * 0.62f, h * 0.65f, w * 0.85f, h * 0.88f, fillPaint);
            fillPaint.setColor(0xFF10B981);
            canvas.drawRect(0, 0, w, h * 0.34f, fillPaint);
            fillPaint.setColor(0xFF34D399);
            canvas.drawRect(0, 0, w, h * 0.16f, fillPaint);
            fillPaint.setColor(0xFF059669);
            canvas.drawRect(w * 0.12f, h * 0.34f, w * 0.30f, h * 0.46f, fillPaint);
            canvas.drawRect(w * 0.56f, h * 0.34f, w * 0.78f, h * 0.50f, fillPaint);
        }

        private void drawServerTile(Canvas canvas, int w, int h) {
            fillPaint.setColor(0xFF172033);
            canvas.drawRect(0, 0, w, h, fillPaint);
            fillPaint.setColor(0xFF26354D);
            canvas.drawRect(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.42f, fillPaint);
            canvas.drawRect(w * 0.14f, h * 0.54f, w * 0.86f, h * 0.78f, fillPaint);
            fillPaint.setColor(0xFF10B981);
            canvas.drawRect(w * 0.22f, h * 0.26f, w * 0.34f, h * 0.34f, fillPaint);
            fillPaint.setColor(0xFF38BDF8);
            canvas.drawRect(w * 0.22f, h * 0.62f, w * 0.34f, h * 0.70f, fillPaint);
        }
    }
}
