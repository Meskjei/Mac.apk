import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.nio.file.Files;

public final class OsChmodSmoke {
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        var dir = Files.createTempDirectory("macapk-chmod");
        var file = dir.resolve("file");
        var link = dir.resolve("link");
        try {
            Files.writeString(file, "test");
            Os.chmod(file.toString(), 0600);
            if (((Integer)Files.getAttribute(file, "unix:mode") & 07777) != 0600) throw new AssertionError();
            Files.createSymbolicLink(link, file);
            Os.chmod(link.toString(), 0755);
            if (((Integer)Files.getAttribute(file, "unix:mode") & 07777) != 0755) throw new AssertionError();
            try { Os.chmod(dir.resolve("missing").toString(), 0600); throw new AssertionError(); }
            catch (ErrnoException expected) { if (expected.errno != OsConstants.ENOENT) throw new AssertionError(); }
            try { Os.chmod(file.resolve("child").toString(), 0600); throw new AssertionError(); }
            catch (ErrnoException expected) { if (expected.errno != OsConstants.ENOTDIR) throw new AssertionError(); }
        } finally { Files.deleteIfExists(link); Files.deleteIfExists(file); Files.delete(dir); }
        System.out.println("Os.chmod mode, symlink and errno checks passed");
    }
}
