import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Pixel-level regression for a transparent shape carrying a faint soft shadow. */
public final class ShapeShadowSmoke {
    private static Bitmap draw(float radius) {
        Bitmap image = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0);
        paint.setShadowLayer(radius, 0, 4, 0x14000000);
        canvas.drawRect(new RectF(48, 48, 112, 112), paint);
        return image;
    }

    private static int alpha(Bitmap image, int x, int y) {
        return image.getPixel(x, y) >>> 24;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Bitmap soft = draw(24);
        int max = 0;
        for (int y = 0; y < 160; y++) for (int x = 0; x < 160; x++) {
            max = Math.max(max, alpha(soft, x, y));
        }
        check(max > 0 && max <= 20, "shadow alpha must be applied once, got " + max);
        check(alpha(soft, 40, 80) > 0, "24px blur must extend beyond the old 1px stamps");
        check(alpha(soft, 48, 52) < alpha(soft, 80, 80), "rectangle corner must soften");
        check(alpha(draw(3), 40, 80) == 0, "requested radius must affect the halo");

        Bitmap clipped = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(clipped);
        canvas.clipRect(0, 0, 90, 160);
        canvas.translate(10, 0);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0);
        paint.setShadowLayer(12, 0, 0, 0x40000000);
        canvas.drawRoundRect(new RectF(40, 40, 100, 100), 12, 12, paint);
        check(alpha(clipped, 70, 70) > 0, "translated rounded shadow missing");
        check(alpha(clipped, 95, 70) == 0, "blur must respect destination clip");
        paint.clearShadowLayer();
        paint.setColor(0xffff0000);
        canvas.drawRect(20, 120, 40, 140, paint);
        check(clipped.getPixel(35, 130) == 0xffff0000, "shadow must restore transform/composite state");
        check(alpha(clipped, 20, 130) == 0, "subsequent drawing must retain original translation");

        Bitmap stroked = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        Canvas strokeCanvas = new Canvas(stroked);
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(0);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(4);
        strokePaint.setShadowLayer(3, 0, 0, 0xff000000);
        strokeCanvas.drawRect(40, 40, 120, 120, strokePaint);
        check(alpha(stroked, 40, 80) > 0, "stroke shadow missing");
        check(alpha(stroked, 80, 80) == 0, "stroke shadow must not fill its interior");
        System.out.println("shape shadows: alpha, radius, softened corners, stroke, clip and canvas state passed");
    }
}
