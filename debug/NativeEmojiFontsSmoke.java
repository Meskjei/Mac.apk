import android.graphics.MacApkFont;
import android.graphics.MacApkSystemFonts;
import android.graphics.Paint;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public class NativeEmojiFontsSmoke {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("macapk-emoji-fonts-");
        System.setProperty("user.home", home.toString());
        Paint paint = new Paint(); paint.setTextSize(32);
        float latin = paint.measureText("Markdown 中文 123"), emoji = paint.measureText("🍔🍟🥩");
        String directory = MacApkSystemFonts.materialize();
        if (directory == null) throw new AssertionError("font set unavailable");
        Path font = Path.of(directory, "NotoColorEmoji.ttf");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(font)));
        if (!hash.equals("15671215ab769fdc7162a045d56fd7d7e477c51b04e6b3c761d914d8fdd6cc44")) throw new AssertionError("font provenance changed");
        String xml = Files.readString(Path.of(directory, "fonts.xml"));
        if (!xml.contains("lang=\"und-Zsye\"") || !xml.contains("NotoColorEmoji.ttf")) throw new AssertionError("native fallback absent");
        if (!MacApkSystemFonts.nativeEmojiConfigured() || !Path.of(directory).getFileName().toString().equals(MacApkSystemFonts.fontSetId())) throw new AssertionError("diagnostic not tied to published set");
        if (!MacApkSystemFonts.materialize().equals(directory)) throw new AssertionError("warm cache changed");
        if (latin != paint.measureText("Markdown 中文 123") || emoji != paint.measureText("🍔🍟🥩") || emoji <= 0) throw new AssertionError("Java measurement changed or became zero");
        var fallback = MacApkFont.fallbackBaseFor(0x1f354, MacApkFont.get(32, 0));
        if (fallback != null && fallback.getFontName().contains("Noto Color Emoji")) throw new AssertionError("bitmap font leaked into AWT");
        System.out.println("Native emoji resource, Android fallback, warm cache, diagnostics and unchanged nonzero Java advances passed: " + latin + "/" + emoji);
        System.exit(0);
    }
}
