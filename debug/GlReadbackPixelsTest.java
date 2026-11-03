package javax.microedition.khronos.egl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Random;

public final class GlReadbackPixelsTest {
    private static volatile int checksum;
    private static void original(ByteBuffer rgba, byte[] bytes, int[] argb, int width, int height) {
        rgba.position(0); rgba.get(bytes, 0, width * height * 4);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = ((height - 1 - y) * width + x) * 4;
            int r = bytes[i] & 255, g = bytes[i + 1] & 255, b = bytes[i + 2] & 255, a = bytes[i + 3] & 255;
            if (a == 0) r = g = b = 0;
            else if (a != 255) {
                r = Math.min(255, (r * 255 + (a >> 1)) / a);
                g = Math.min(255, (g * 255 + (a >> 1)) / a);
                b = Math.min(255, (b * 255 + (a >> 1)) / a);
            }
            argb[y * width + x] = a << 24 | r << 16 | g << 8 | b;
        }
    }
    private static void check(int width, int height, Random random) {
        int n = width * height;
        byte[] bytes = new byte[n * 4 + 16]; random.nextBytes(bytes);
        if (n >= 256) for (int i = 0; i < 256; i++) bytes[i * 4 + 3] = (byte)i;
        ByteBuffer source = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        source.put(bytes); source.position(0);
        IntBuffer ints = source.asIntBuffer();
        int[] expected = new int[n + 4], actual = new int[n + 4];
        Arrays.fill(expected, 0x12345678); Arrays.fill(actual, 0x12345678);
        original(source, new byte[bytes.length], expected, width, height);
        MacApkGlWindow.copyReadback(ints, actual, width, height);
        if (!Arrays.equals(expected, actual)) throw new AssertionError("pixel mismatch " + width + "x" + height);
    }
    private static double measure(Runnable operation, int[] output) {
        for (int i = 0; i < 25; i++) operation.run();
        long start = System.nanoTime();
        for (int i = 0; i < 60; i++) { operation.run(); checksum ^= output[i]; }
        return (System.nanoTime() - start) / 60e6;
    }
    public static void main(String[] args) {
        Random random = new Random(0x11aabbcc);
        for (int[] size : new int[][]{{1,1},{1,256},{256,1},{7,9},{17,16},{780,1688}}) check(size[0], size[1], random);
        System.out.println("GlReadbackPixelsTest PASS: rows, all alpha values, channel order, odd heights, capacity tail");
        if (args.length == 0) return;
        int w = 780, h = 1688, n = w * h;
        ByteBuffer source = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        byte[] data = new byte[n * 4], staging = new byte[n * 4]; random.nextBytes(data);
        for (int i = 0; i < n; i++) data[i * 4 + 3] = (byte)255;
        source.put(data); source.position(0); IntBuffer ints = source.asIntBuffer();
        int[] output = new int[n];
        for (int round = 0; round < 3; round++) {
            double oldMs = measure(() -> original(source, staging, output, w, h), output);
            double newMs = measure(() -> MacApkGlWindow.copyReadback(ints, output, w, h), output);
            System.out.printf("opaque round=%d oldMs=%.3f newMs=%.3f checksum=%d%n", round, oldMs, newMs, checksum);
        }
    }
}
