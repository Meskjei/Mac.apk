import com.macapk.loader.AppClassLoader;
import dalvik.system.BaseDexClassLoader;
import java.nio.file.Files;

public final class NativeProfileFailureSmoke {
    public static void main(String[] args) throws Exception {
        var directory = args.length == 0 ? Files.createTempDirectory("macapk-profile-native")
            : Files.createDirectories(java.nio.file.Path.of(args[0]));
        var source = directory.resolve("libunsupported_fixture.so");
        try {
            Files.write(source, new byte[]{0x7f,'E','L','F'});
            var loader = new BaseDexClassLoader("", null, directory.toString(), null);
            boolean excluded = System.getenv("MACAPK_UNSUPPORTED_NATIVE_LIBS") != null;
            for (int route = 0; route < 2; route++) {
                try {
                    String path = route == 0 ? loader.findLibrary("unsupported_fixture")
                        : AppClassLoader.resolveNativeCodePath(null, source.toFile());
                    if (excluded || !source.toString().equals(path)) throw new AssertionError();
                } catch (UnsatisfiedLinkError error) {
                    if (!excluded || !error.getMessage().contains("unsupported by this runtime profile")) throw error;
                }
            }
            Files.writeString(source, "not an ELF: metadata archive");
            if (!source.toString().equals(loader.findLibrary("unsupported_fixture")))
                throw new AssertionError("metadata path changed");
            if (args.length > 0) {
                var sibling = Files.createTempDirectory("macapk-other-native");
                var other = sibling.resolve(source.getFileName());
                try {
                    Files.write(other, new byte[]{0x7f,'E','L','F'});
                    if (!other.toString().equals(AppClassLoader.resolveNativeCodePath(null, other.toFile())))
                        throw new AssertionError("path exclusion affected another artifact with the same name");
                } finally { Files.delete(other); Files.delete(sibling); }
            }
            System.out.println("native profile: explicit failure before translation; opt-out and metadata path preserved");
        } finally { Files.delete(source); Files.delete(directory); }
    }
}
