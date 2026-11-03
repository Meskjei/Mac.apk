// dalvik.system.PathClassLoader -- the loader ART uses for the app's own code
// (and for any colon-separated list of jar/apk/dex files). Apps and libraries
// reach it two ways: reflectively (`Class.forName("dalvik.system.PathClassLoader")`,
// `new PathClassLoader(path, parent)`) and via introspection
// (`getClassLoader() instanceof PathClassLoader`). Without the class present,
// both fail (ClassNotFoundException / a never-true instanceof).
//
// AOSP (libcore/dalvik/src/main/java/dalvik/system/PathClassLoader.java):
//   public PathClassLoader(String dexPath, ClassLoader parent)
//   public PathClassLoader(String dexPath, String librarySearchPath, ClassLoader parent)
// extends BaseDexClassLoader; it adds NO behavior of its own -- it is purely a
// named subclass so the loader-identity checks resolve. We mirror exactly that:
// a thin BaseDexClassLoader subclass with the two public ctors.
//
// AppClassLoader extends this class, preserving Android's loader ancestry.
package dalvik.system;

public class PathClassLoader extends BaseDexClassLoader {

    /** Mac.apk-internal: forwards to BaseDexClassLoader's URL[] ctor.
     *
     *  Exists so AppClassLoader can sit UNDER PathClassLoader in the hierarchy.
     *  On a device the app's loader IS a PathClassLoader, and code checks that:
     *  MultiDex.install, SoLoader/ReLinker, and hot-patch frameworks branch on
     *  `instanceof PathClassLoader`. We extended BaseDexClassLoader directly, so
     *  those checks failed where Android succeeds -- while `instanceof
     *  BaseDexClassLoader` (which SmashHit/GameActivity cast to) passed, which is
     *  why it went unnoticed.
     *
     *  PathClassLoader adds nothing over BaseDexClassLoader except the dex-path
     *  conversion its String ctors perform, and a URL[] caller has already done
     *  that conversion -- so forwarding is behaviour-preserving. */
    protected PathClassLoader(java.net.URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /** AOSP: dexPath is a colon-separated list of .jar/.apk/.dex code paths;
     *  parent is the delegation parent. Real .dex/.apk entries are converted to
     *  JVM bytecode via BaseDexClassLoader's buildUrls path; class-file jars are
     *  used directly. */
    public PathClassLoader(String dexPath, ClassLoader parent) {
        super(dexPath, null, null, parent);
        macapkOpenNow();
    }

    /** AOSP: second ctor adds an explicit native library search path.
     *  DelegateLastClassLoader constructs through this one, so it inherits the
     *  open-now below; macapkOpenNow calls URLClassLoader.findResource
     *  non-virtually, so DelegateLast's child-first overrides do not run on
     *  its half-constructed instance. */
    public PathClassLoader(String dexPath, String librarySearchPath,
                           ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
        macapkOpenNow();
    }

}
