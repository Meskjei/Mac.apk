import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;

public final class SurfaceTextureCanvasSmoke {
    public static void main(String[] args) {
        SurfaceTexture a = new SurfaceTexture(false), b = new SurfaceTexture(false);
        a.setDefaultBufferSize(23, 7); b.setDefaultBufferSize(9, 13);
        Surface sa = new Surface(a), sb = new Surface(b);
        var ca = sa.lockHardwareCanvas();
        check(ca.getWidth()==23 && ca.getHeight()==7, "texture buffer geometry");
        ca.drawColor(0xff112233); sa.unlockCanvasAndPost(ca);
        // Reusing the producer's backing must not mutate the queued frame.
        ca = sa.lockCanvas(null); ca.drawColor(0xffabcdef);
        Bitmap first = a.macapkLatchBitmap();
        check(first != null && first.getPixel(10,3)==0xff112233, "queued frame ownership");
        var cb = sb.lockHardwareCanvas(); cb.drawColor(0x804080c0); sb.unlockCanvasAndPost(cb);
        Bitmap other = b.macapkLatchBitmap();
        check(other.getWidth()==9 && other.getHeight()==13, "separate target geometry");
        check(other.getPixel(3,4)==0x804080c0, "alpha and target routing");
        sa.unlockCanvasAndPost(ca);
        check(a.macapkLatchBitmap().getPixel(10,3)==0xffabcdef, "new frame arrives");
        a.setDefaultBufferSize(5,4); ca=sa.lockCanvas(null);
        check(ca.getWidth()==5 && ca.getHeight()==4, "buffer resize");
        sa.unlockCanvasAndPost(ca); sa.release();
        try { sa.lockCanvas(null); throw new AssertionError("released surface accepted"); }
        catch (IllegalStateException expected) {}
        System.out.println("Surface canvas texture routing, geometry, alpha and frame ownership passed");
        System.exit(0); // The standalone JVM owns the framework main-looper thread.
    }
    private static void check(boolean b,String message) { if(!b)throw new AssertionError(message); }
}
