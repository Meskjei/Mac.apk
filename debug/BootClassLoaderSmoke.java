import com.macapk.loader.ClassFileRewriter;
import java.io.InputStream;

public final class BootClassLoaderSmoke {
    public static final class Probe {
        public static ClassLoader boot() { return Object.class.getClassLoader(); }
        public static ClassLoader primitive() { return int.class.getClassLoader(); }
        public static ClassLoader own() { return Probe.class.getClassLoader(); }
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        byte[] raw;
        try (InputStream in = BootClassLoaderSmoke.class.getResourceAsStream("BootClassLoaderSmoke$Probe.class")) {
            check(in != null); raw = in.readAllBytes();
        }
        ClassFileRewriter.Result result = ClassFileRewriter.maybeRewrite(raw, "BootClassLoaderSmoke$Probe");
        boolean art = !"0".equals(System.getenv("MACAPK_ART_BOOT_CLASSLOADER"));
        check(result.rewritten == art);
        class FixtureLoader extends ClassLoader {
            FixtureLoader() { super(BootClassLoaderSmoke.class.getClassLoader()); }
            Class<?> define(byte[] bytes) { return defineClass("BootClassLoaderSmoke$Probe", bytes, 0, bytes.length); }
        }
        FixtureLoader defining = new FixtureLoader();
        Class<?> probe = defining.define(result.bytes);
        ClassLoader boot = (ClassLoader)probe.getMethod("boot").invoke(null);
        if (art) {
            check(boot != null);
            check(boot.loadClass("java.lang.String") == String.class);
            check(boot.loadClass("android.app.Activity") == android.app.Activity.class);
            try { boot.loadClass("BootClassLoaderSmoke"); throw new AssertionError("boot exposed app class"); }
            catch (ClassNotFoundException expected) { }
        } else check(boot == null);
        check(probe.getMethod("primitive").invoke(null) == null);
        check(probe.getMethod("own").invoke(null) == defining);
        System.out.println("ART boot class-loader rewrite checks passed");
    }
}
