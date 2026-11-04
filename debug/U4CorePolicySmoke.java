import com.macapk.loader.ClassFileRewriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.jar.JarFile;

/** Integration check against a user-provided converted SDK jar; never bundles application code. */
public class U4CorePolicySmoke {
    public static void main(String[] args) throws Exception {
        String name = "com.uc.webview.internal.setup.z0";
        byte[] original;
        try (var jar = new JarFile(args[0])) {
            original = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class")).readAllBytes();
        }
        byte[] patched = ClassFileRewriter.maybeRewrite(original.clone(), name).bytes;
        if (patched.length != original.length) throw new AssertionError("instruction offsets changed");
        int changes = 0;
        for (int i = 0; i < original.length; i++) if (original[i] != patched[i]) changes++;
        if (changes != 3) throw new AssertionError("expected exactly one three-byte call-site replacement, got " + changes);
        byte[] different = original.clone(), marker = "prepareToDecide".getBytes(StandardCharsets.UTF_8);
        int at = -1;
        for (int i = 0; i <= different.length - marker.length; i++) {
            if (Arrays.equals(different, i, i + marker.length, marker, 0, marker.length)) { at = i; break; }
        }
        if (at < 0) throw new AssertionError("missing SDK fixture marker");
        different[at] ^= 1;
        if (!Arrays.equals(different, ClassFileRewriter.maybeRewrite(different.clone(), name).bytes))
            throw new AssertionError("an unverified SDK contract was modified");
        if (!Arrays.equals(original, ClassFileRewriter.maybeRewrite(original.clone(), "example.OtherSdk").bytes))
            throw new AssertionError("an unrelated class was modified");
        try (var loader = new URLClassLoader(new java.net.URL[]{new java.io.File(args[0]).toURI().toURL()}, U4CorePolicySmoke.class.getClassLoader()) {
            @Override protected Class<?> findClass(String className) throws ClassNotFoundException {
                return className.equals(name) ? defineClass(className, patched, 0, patched.length) : super.findClass(className);
            }
        }) {
            Class<?> selector = Class.forName(name, false, loader);
            if (selector.getDeclaredConstructors().length == 0) throw new AssertionError("selector did not verify");
        }
        System.out.println("Known UC SDK policy, unknown-contract isolation, unchanged offsets and JVM verification passed");
    }
}
