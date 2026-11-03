import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.nio.file.Files;

public final class DynamicCodePathSmoke {
    private static void check(ClassLoader loader) throws Exception {
        Class<?> type = loader.loadClass("fixture.DynamicValue");
        if (!Integer.valueOf(42).equals(type.getMethod("value").invoke(null))) throw new AssertionError();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("raw DEX, DEX JAR, JVM JAR required");
        for (String path : args) {
            check(new BaseDexClassLoader(path, null, null, null));
            check(new DexClassLoader(path, null, null, null));
            check(new PathClassLoader(path, null));
            BaseDexClassLoader appended = new BaseDexClassLoader("", null, null, null);
            appended.addDexPath(path);
            check(appended);
        }
        try {
            new BaseDexClassLoader(new File("missing-code.dex").getAbsolutePath(), null, null, null)
                .loadClass("fixture.DynamicValue");
            throw new AssertionError("missing code path fabricated a class");
        } catch (ClassNotFoundException expected) { }
        var invalid = Files.createTempFile("macapk-invalid-dex", ".bin");
        try {
            Files.writeString(invalid, "not a code archive");
            try {
                new BaseDexClassLoader(invalid.toString(), null, null, null).loadClass("fixture.DynamicValue");
                throw new AssertionError("invalid code path fabricated a class");
            } catch (ClassNotFoundException expected) { }
        } finally { Files.delete(invalid); }
        var nativeDir = Files.createTempDirectory("macapk-native-path");
        var nativeFile = nativeDir.resolve("libfixture.so");
        try {
            Files.write(nativeFile, new byte[]{0x7f, 'E', 'L', 'F'});
            String search = nativeDir.resolve("missing") + File.pathSeparator + nativeDir;
            BaseDexClassLoader loader = new BaseDexClassLoader("", null, search, null);
            if (!search.equals(loader.getLdLibraryPath())
                    || !nativeFile.toString().equals(loader.findLibrary("fixture"))
                    || loader.findLibrary("missing") != null || loader.findLibrary("../fixture") != null)
                throw new AssertionError("native search path contract");
        } finally { Files.delete(nativeFile); Files.delete(nativeDir); }
        System.out.println("dynamic code-path checks passed (12 loads + missing path)");
    }
}
