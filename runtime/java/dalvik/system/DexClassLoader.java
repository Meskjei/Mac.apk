// dalvik.system.DexClassLoader -- the Android-specific class loader
// that loads classes from .dex / .jar / .apk files. Apps create one
// to dynamically load plugins, ad-network adapters, or DRM-wrapped
// code paths. Reaches through PathClassLoader in real AOSP.
//
// Mac.apk's ClassLoader hierarchy is OpenJDK's URLClassLoader chain. Loading
// from a .dex / .apk file requires a DEX->JVM-bytecode step; we run
// com.macapk.dex (the same in-process converter the primary app dex uses) over
// each supplied code path at construction time and serve the resulting jars
// through URLClassLoader. The converted jar is a persistent, content-addressed
// entry in the app's code_cache, so the next launch reuses it instead of
// converting again (MacApkDexConverter, docs/dynamic-dex-cache_2026-09-24.md). `Class.forName("dalvik.system.DexClassLoader")` and
// `new DexClassLoader(...)` resolve, AND findClass over a real downloaded-plugin
// .dex now succeeds, which the prior shell could not do.
package dalvik.system;

import java.net.URL;

// Real AOSP hierarchy: DexClassLoader extends BaseDexClassLoader extends
// ClassLoader. We mirror that so apps that introspect the loader chain (or cast
// to BaseDexClassLoader) see the expected ancestry.
public class DexClassLoader extends BaseDexClassLoader {

    /** AOSP signature: takes a colon-separated list of .dex/.jar files,
     *  an optimized-DEX directory (cache is managed internally), a native
     *  library search path, and a parent ClassLoader. BaseDexClassLoader converts each code path
     *  to a jar of JVM bytecode so findClass over it works. */
    public DexClassLoader(String dexPath, String optimizedDirectory,
                           String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
        // Hold every converted jar open from construction, as ART maps its
        // dex elements here (see BaseDexClassLoader.macapkOpenNow).
        macapkOpenNow();
    }

    /** AOSP also exposes a no-arg-style constructor used by some
     *  reflective code paths. */
    protected DexClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

}
