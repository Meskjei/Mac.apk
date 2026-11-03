import com.macapk.loader.ByteBufferReadCopy;
import com.macapk.loader.ClassFileRewriter;
import java.nio.ByteBuffer;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BufferReadCopySmoke {
    private static native void nativeWrite(ByteBuffer buffer, int seed);
    public static final class Probe {
        public ByteBuffer buffer;
        public byte[] output;
        public int outputOffset;
        public void read(int count) {
            ByteBuffer a = buffer;
            byte[] source = a.array();
            ByteBuffer b = buffer;
            int position = b.arrayOffset();
            byte[] target = output;
            int offset = outputOffset;
            System.arraycopy(source, position, target, offset, count);
        }
        public void mutate(int count) {
            ByteBuffer a = buffer;
            byte[] source = a.array();
            ByteBuffer b = buffer;
            int position = b.arrayOffset();
            byte[] target = output;
            int offset = outputOffset;
            System.arraycopy(source, position, target, offset, count);
            source[0] = 99;
        }
        public byte[] escape() { return buffer.array(); }
        public int escapeOffset(int count) {
            ByteBuffer a = buffer;
            byte[] source = a.array();
            ByteBuffer b = buffer;
            int position = b.arrayOffset();
            byte[] target = output;
            int offset = outputOffset;
            int saved = position;
            System.arraycopy(source, position, target, offset, count);
            return saved;
        }
        public byte branchEscape(int count) {
            ByteBuffer a = buffer;
            byte[] source = a.array();
            ByteBuffer b = buffer;
            int position = b.arrayOffset();
            byte[] target = output;
            int offset = outputOffset;
            System.arraycopy(source, position, target, offset, count);
            return count > 0 ? source[0] : 0;
        }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void fails(Object probe, String method, Class<?> error, Object... args) throws Exception {
        try {
            probe.getClass().getMethod(method, args.length == 0 ? new Class<?>[0] : new Class<?>[]{int.class}).invoke(probe, args);
            throw new AssertionError("expected " + error);
        } catch (InvocationTargetException expected) { check(error.isInstance(expected.getCause())); }
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        byte[] original;
        try (var in = BufferReadCopySmoke.class.getResourceAsStream("BufferReadCopySmoke$Probe.class")) {
            original = in.readAllBytes();
        }
        boolean enabled = "1".equals(System.getenv("MACAPK_BUFFER_ARRAYCOPY"));
        var result = ClassFileRewriter.maybeRewrite(original, "BufferReadCopySmoke$Probe");
        check(result.rewritten == enabled);
        class FixtureLoader extends ClassLoader {
            Class<?> define(byte[] data) { return defineClass("BufferReadCopySmoke$Probe", data, 0, data.length); }
        }
        Class<?> type = new FixtureLoader().define(result.bytes);
        Object probe = type.getConstructor().newInstance();
        type.getField("outputOffset").setInt(probe, 2);
        ByteBuffer direct = ByteBuffer.allocateDirect(64);
        type.getField("buffer").set(probe, direct);
        type.getField("output").set(probe, new byte[80]);
        if (!enabled) {
            fails(probe, "read", UnsupportedOperationException.class, 16);
            System.out.println("buffer read-copy opt-out preserved");
            return;
        }
        for (ByteBuffer buffer : new ByteBuffer[]{direct, direct.duplicate().position(4).slice(),
                ByteBuffer.wrap(new byte[80]).position(4).slice()}) {
            type.getField("buffer").set(probe, buffer);
            byte[] output = new byte[80]; type.getField("output").set(probe, output);
            for (int seed = 0; seed < 24; ++seed) {
                if (buffer.isDirect()) nativeWrite(buffer, seed);
                else for (int i = 0; i < buffer.capacity(); ++i) buffer.put(i, (byte)(seed + i));
                buffer.clear().position(3).mark().limit(10);
                System.gc();
                type.getMethod("read", int.class).invoke(probe, 16);
                for (int i = 0; i < 16; ++i) check(output[i + 2] == (byte)(seed + i));
                check(buffer.position() == 3 && buffer.limit() == 10);
                buffer.reset(); check(buffer.position() == 3);
                buffer.clear();
            }
        }
        type.getField("buffer").set(probe, direct);
        fails(probe, "read", IndexOutOfBoundsException.class, -1);
        fails(probe, "mutate", UnsupportedOperationException.class, 16);
        fails(probe, "escape", UnsupportedOperationException.class);
        fails(probe, "escapeOffset", UnsupportedOperationException.class, 16);
        fails(probe, "branchEscape", UnsupportedOperationException.class, 16);
        type.getField("buffer").set(probe, direct.asReadOnlyBuffer());
        fails(probe, "read", UnsupportedOperationException.class, 16);
        ByteBufferReadCopy.array(direct);
        try { ByteBufferReadCopy.offset(ByteBuffer.allocateDirect(64)); throw new AssertionError(); }
        catch (UnsupportedOperationException expected) { }
        if (args.length > 1) {
            try (var jar = new java.util.jar.JarFile(args[1])) {
                String name = "com/uc/base/net/unet/impl/UnetSyncBodyStream.class";
                byte[] raw = jar.getInputStream(jar.getJarEntry(name)).readAllBytes();
                var actual = ClassFileRewriter.maybeRewrite(raw, name.replace('/', '.').replace(".class", ""));
                check(actual.rewritten);
                Files.write(Path.of(args[2]), actual.bytes);
            }
        }
        System.out.println("buffer read-copy: JNI writes, GC, slices, state, errors and non-escaping scope passed");
    }
}
