import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

public final class ImageDrawableScaleSmoke {
    private static final class IntrinsicDrawing extends Drawable {
        @Override public int getIntrinsicWidth() { return 300; }
        @Override public int getIntrinsicHeight() { return 300; }
        @Override public void draw(Canvas canvas) {
            var paint = new Paint();
            paint.setColor(0xffff0000);
            canvas.drawRect(0, 0, 300, 300, paint);
            paint.setColor(0xff00ff00);
            canvas.drawRect(135, 135, 165, 165, paint);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return -1; }
    }

    public static void main(String[] args) {
        for (var mode : new ImageView.ScaleType[]{ImageView.ScaleType.FIT_CENTER,
                ImageView.ScaleType.CENTER_INSIDE, ImageView.ScaleType.FIT_START,
                ImageView.ScaleType.FIT_END}) {
            var view = new ImageView(null);
            var drawable = new IntrinsicDrawing();
            view.setImageDrawable(drawable);
            view.setScaleType(mode);
            view.setPadding(10, 10, 10, 10);
            view.layout(0, 0, 120, 120);
            var image = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            var canvas = new Canvas(image);
            view.draw(canvas);
            check(image.getPixel(60, 60) == 0xff00ff00, mode + " must scale intrinsic drawing");
            check(image.getPixel(15, 15) == 0xffff0000, mode + " must retain padded origin");
            check(image.getPixel(5, 5) == 0, mode + " must keep padding clear");
            check(drawable.getBounds().width() == 300, mode + " intrinsic drawable bounds");
            var paint = new Paint();
            paint.setColor(0xff0000ff);
            canvas.drawRect(140, 140, 150, 150, paint);
            check(image.getPixel(145, 145) == 0xff0000ff, "canvas transform must be restored");
        }
        var stretch = new ImageView(null);
        var drawable = new IntrinsicDrawing();
        stretch.setImageDrawable(drawable);
        stretch.setScaleType(ImageView.ScaleType.FIT_XY);
        stretch.layout(0, 0, 100, 80);
        stretch.draw(new Canvas(Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)));
        check(drawable.getBounds().width() == 100 && drawable.getBounds().height() == 80,
                "FIT_XY must size drawable bounds directly");
        System.out.println("Intrinsic drawable canvas scaling, padding, FIT_XY and canvas restore passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
