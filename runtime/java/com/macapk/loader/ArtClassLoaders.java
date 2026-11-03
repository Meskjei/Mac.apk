package com.macapk.loader;

/** ART exposes a BootClassLoader object where HotSpot exposes null. */
public final class ArtClassLoaders {
    private ArtClassLoaders() {}
    private static final ClassLoader BOOT = new ClassLoader(null) {
        @Override protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            String binary = name.replace('/', '.');
            try { return Class.forName(binary, false, null); }
            catch (ClassNotFoundException notInHotSpotBoot) {
                // The Android framework lives in our host classpath, separately
                // from APK classes. Never expose the app through the boot loader.
                if (binary.startsWith("android.") || binary.startsWith("dalvik.")
                        || binary.startsWith("libcore.") || binary.startsWith("com.android.")
                        || binary.startsWith("java.") || binary.startsWith("javax.")
                        || binary.startsWith("org.xml.") || binary.startsWith("org.w3c."))
                    return Class.forName(binary, false, ArtClassLoaders.class.getClassLoader());
                throw notInHotSpotBoot;
            }
        }
    };

    public static ClassLoader getClassLoader(Class<?> type) {
        if (type.isPrimitive()) return null;
        ClassLoader loader = type.getClassLoader();
        return loader == null ? BOOT : loader;
    }
}
