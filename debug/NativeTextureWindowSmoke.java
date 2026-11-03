import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;

/** Attach to a running debug runtime with args "fixture.dylib;result.txt". */
public final class NativeTextureWindowSmoke {
    static native void render(Surface first, Surface second);
    public static void agentmain(String arguments, Instrumentation ignored) throws Exception {
        String[] paths = arguments.split(";", 2);
        System.load(paths[0]);
        SurfaceTexture first = new SurfaceTexture(false);
        SurfaceTexture second = new SurfaceTexture(false);
        first.setDefaultBufferSize(3, 2);
        second.setDefaultBufferSize(2, 3);
        Surface a = new Surface(first), b = new Surface(second);
        try {
            render(a, b);
            Bitmap red = first.macapkLatchBitmap(), green = second.macapkLatchBitmap();
            if (red == null || green == null || red.getWidth() != 3 || red.getHeight() != 2
                    || green.getWidth() != 2 || green.getHeight() != 3
                    || red.getPixel(1, 1) != 0xffff0000 || green.getPixel(1, 1) != 0xff00ff00)
                throw new AssertionError("native EGL frames were lost or routed to the wrong stream");
            if (first.macapkHasProducer() || second.macapkHasProducer())
                throw new AssertionError("destroy did not disconnect the producer");
            Files.writeString(Path.of(paths[1]), "Native EGL: capability policy/lookup, distinct windows, identity/refcounts, dimensions, red/green pixels, swap damage, and destruction passed\n");
        } finally {
            a.release(); b.release(); first.release(); second.release();
        }
    }
}
