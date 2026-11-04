import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

/** A software WebView frame must not be overwritten by a retained hardware texture. */
public final class TextureViewSoftwareCanvasSmoke {
    static Canvas target(Bitmap bitmap, boolean hardware) {
        return new Canvas(bitmap) {
            @Override public boolean isHardwareAccelerated() { return hardware; }
        };
    }
    public static void main(String[] args) {
        try { runTest(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static void runTest() {
        var webView = new com.uc.aosp.android.webkit.a0() {};
        webView.layout(0, 0, 3, 2);
        var texture = new SurfaceTexture(false);
        var view = new TextureView(null);
        webView.addView(view);
        view.layout(0, 0, 3, 2);
        view.setSurfaceTexture(texture);
        int[] updates = {0};
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {}
            public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture t) { updates[0]++; }
        });
        var surface = new Surface(texture);
        Canvas producer = surface.lockHardwareCanvas();
        producer.drawColor(0xffff0000);
        surface.unlockCanvasAndPost(producer);

        Bitmap bitmap = Bitmap.createBitmapWithCanvasBacking(3, 2);
        Canvas software = target(bitmap, false);
        software.drawColor(0xff0000ff);
        view.draw(software);
        check(bitmap.getPixel(1, 1) == 0xff0000ff, "software frame overwritten by texture");
        check(updates[0] == 1, "software consumer stalled the producer update");
        view.draw(target(bitmap, true));
        check(bitmap.getPixel(1, 1) == 0xffff0000, "hardware texture frame was lost");
        check(updates[0] == 1, "retained frame emitted a duplicate update");

        producer = surface.lockHardwareCanvas();
        producer.drawColor(0xff00ff00);
        surface.unlockCanvasAndPost(producer);
        software.drawColor(0xff0000ff);
        view.draw(software);
        check(bitmap.getPixel(1, 1) == 0xff0000ff, "new texture leaked into software frame");
        check(updates[0] == 2, "new producer frame was not consumed");
        view.draw(target(bitmap, true));
        check(bitmap.getPixel(1, 1) == 0xff00ff00, "new hardware frame was consumed or lost");

        // The runtime also composites camera/video/lockCanvas on software Canvas.
        // Excluding every software TextureView would silently break that path.
        webView.removeView(view);
        software.drawColor(0xff0000ff);
        view.draw(software);
        check(bitmap.getPixel(1, 1) == 0xff00ff00, "ordinary software texture was suppressed");

        Bitmap viewport = Bitmap.createBitmapWithCanvasBacking(40, 30);
        Canvas canvas = target(viewport, false);
        webView.layout(0, 0, 40, 30);
        webView.scrollTo(5, 9);
        webView.draw(canvas);
        check(viewport.getPixel(1, 1) == 0xff0000ff, "UC viewport drifted by its scroll offset");
        check(viewport.getPixel(39, 29) == 0xff0000ff, "UC viewport was clipped after scroll");
        var matrix = new android.graphics.Matrix();
        canvas.getMatrix(matrix);
        check(matrix.isIdentity(), "UC content transform leaked into siblings");
        webView.throwOnDraw = true;
        try { webView.draw(canvas); throw new AssertionError("fixture did not throw"); }
        catch (IllegalStateException expected) {}
        canvas.getMatrix(matrix);
        check(matrix.isIdentity(), "UC exception leaked the content transform");
        var ordinary = new android.view.View(null) {
            @Override public void onDraw(Canvas c) {
                var paint = new android.graphics.Paint();
                paint.setColor(0xff00ff00);
                c.drawRect(0, 0, 40, 30, paint);
            }
        };
        ordinary.layout(0, 0, 40, 30);
        ordinary.scrollTo(5, 9);
        ordinary.draw(canvas);
        check(viewport.getPixel(39, 29) == 0xff00ff00, "ordinary widget coordinates changed");
        surface.release(); texture.release();
        System.out.println("UC scroll coordinates, texture isolation, producer updates and ordinary textures passed");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
