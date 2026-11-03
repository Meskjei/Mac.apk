import com.macapk.loader.AppClassLoader;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;
import java.nio.file.Files;
import java.util.Arrays;

public final class NativeArtifactLookupSmoke {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("macapk-plugin-lookup");
        var install = root.resolve("data/app/~~fixture/plugin-fixture");
        var libraries = install.resolve("lib/arm64");
        Files.createDirectories(libraries);
        var apk = install.resolve("base.apk");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(apk))) { }
        var plugin = libraries.resolve("libplugin.so");
        Files.copy(java.nio.file.Path.of(args[0]), plugin);
        var metadata = libraries.resolve("libversion.so");
        Files.writeString(metadata, "1.2.3");
        var stub = root.resolve("stub.dylib"); Files.writeString(stub, "not the plugin");
        try (var app = new AppClassLoader(new java.net.URL[0], NativeArtifactLookupSmoke.class.getClassLoader(),
                stub.toString(), root.toFile(), null, apk.toString())) {
            check(plugin.toString().equals(app.findLibrary("plugin")));
            check(metadata.toString().equals(app.findLibrary("version")));
            check(app.findLibrary("../plugin") == null);
            String search = libraries.toString();
            BaseDexClassLoader[] loaders = {
                new BaseDexClassLoader("", null, search, app),
                new DexClassLoader(plugin.toString(), null, search, app),
                new PathClassLoader("", search, app),
            };
            for (var loader : loaders) {
                check(search.equals(loader.getLdLibraryPath()));
                check(plugin.toString().equals(loader.findLibrary("plugin")));
                check(metadata.toString().equals(loader.findLibrary("version")));
                loader.close();
            }
            try (var dynamic = new DexClassLoader(plugin.toString(), null, search, null)) {
                check(Integer.valueOf(42).equals(dynamic.loadClass("fixture.DynamicValue").getMethod("value").invoke(null)));
            }
            check(Arrays.equals(Files.readAllBytes(plugin), Files.readAllBytes(java.nio.file.Path.of(args[0]))));
            try { System.load(app.findLibrary("plugin")); throw new AssertionError("non-ELF fabricated a load"); }
            catch (UnsatisfiedLinkError expected) { }
        } finally {
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("native artifact lookup: original ZIP/metadata, dynamic DEX and native search paths passed");
    }
}
