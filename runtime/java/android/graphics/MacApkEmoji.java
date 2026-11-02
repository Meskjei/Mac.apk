package android.graphics;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;

/** Shape with the font's GSUB, but paint its CBDT PNGs: AWT reports zero
 *  advances for this colour font. Android advances remain owned by Paint. */
public final class MacApkEmoji {
    private MacApkEmoji() {}
    private record Glyph(BufferedImage image, int x, int y, int advance) {}
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static byte[] bytes;
    private static Font font;
    private static int cblc, cbdt;
    private static final LinkedHashMap<String, Glyph> cache = new LinkedHashMap<>(128, .75f, true) {
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Glyph> e) { return size() > 128; }
    };
    static {
        try (var in = MacApkEmoji.class.getResourceAsStream("/fonts/NotoColorEmoji.ttf")) {
            if (in != null) {
                bytes = in.readAllBytes();
                ByteBuffer b = ByteBuffer.wrap(bytes);
                for (int i = 0, n = u16(b, 4); i < n; i++) {
                    int p = 12 + 16 * i, tag = b.getInt(p), offset = b.getInt(p + 8);
                    if (tag == 0x43424c43) cblc = offset;
                    if (tag == 0x43424454) cbdt = offset;
                }
                if (cblc > 0 && cbdt > 0) font = MacApkFont.createFontFromBytes(bytes).deriveFont(32f);
            }
        } catch (Exception e) { font = null; }
    }
    public static boolean available() { return font != null; }
    private static int u16(ByteBuffer b, int p) { return Short.toUnsignedInt(b.getShort(p)); }
    private static synchronized Glyph glyph(String cluster) {
        if (font == null || cluster.length() > 64) return null;
        if (cache.containsKey(cluster)) return cache.get(cluster);
        Glyph result = null;
        try {
            char[] chars = cluster.toCharArray();
            var shape = font.layoutGlyphVector(FRC, chars, 0, chars.length, Font.LAYOUT_LEFT_TO_RIGHT);
            if (shape.getNumGlyphs() != 1 || shape.getGlyphCode(0) == 0) return null;
            int id = shape.getGlyphCode(0);
            ByteBuffer b = ByteBuffer.wrap(bytes);
            int sizes = b.getInt(cblc + 4);
            for (int si = 0; si < sizes && si < 16 && result == null; si++) {
                int size = cblc + 8 + si * 48;
                if (id < u16(b, size + 40) || id > u16(b, size + 42)) continue;
                int array = cblc + b.getInt(size), count = b.getInt(size + 8);
                for (int i = 0; i < count && i < 65536; i++) {
                    int p = array + 8 * i, first = u16(b, p), last = u16(b, p + 2);
                    if (id < first || id > last) continue;
                    int sub = array + b.getInt(p + 4), format = u16(b, sub), image = u16(b, sub + 2);
                    if ((format != 1 && format != 3) || (image != 17 && image != 18)) break;
                    int entry = sub + 8 + (id - first) * (format == 1 ? 4 : 2);
                    int offset = format == 1 ? b.getInt(entry) : u16(b, entry);
                    int at = cbdt + b.getInt(sub + 4) + offset, metrics = image == 17 ? 5 : 8;
                    int x = b.get(at + 2), y = b.get(at + 3), advance = Byte.toUnsignedInt(b.get(at + 4));
                    int length = b.getInt(at + metrics), start = at + metrics + 4;
                    if (advance <= 0 || length <= 0 || length > 1024 * 1024 || start < 0 || start > bytes.length - length) break;
                    BufferedImage png = ImageIO.read(new ByteArrayInputStream(bytes, start, length));
                    if (png != null) result = new Glyph(png, x, y, advance);
                    break;
                }
            }
        } catch (Exception e) { /* Unavailable glyphs keep the existing host fallback. */ }
        cache.put(cluster, result);
        return result;
    }
    public static boolean draw(Graphics2D g, String cluster, float x, float baseline, float size) {
        Glyph glyph = glyph(cluster);
        if (glyph == null || size <= 0 || !Float.isFinite(size)) return false;
        double scale = MacApkFont.emojiAdvance(size) / glyph.advance;
        var at = new AffineTransform();
        at.translate(x + glyph.x * scale, baseline - glyph.y * scale); at.scale(scale, scale);
        Graphics2D child = (Graphics2D) g.create();
        try {
            // drawImage does not inherit the solid text colour's alpha.
            if (g.getPaint() instanceof Color c && child.getComposite() instanceof AlphaComposite a)
                child.setComposite(AlphaComposite.getInstance(a.getRule(), a.getAlpha() * c.getAlpha() / 255f));
            child.drawImage(glyph.image, at, null);
        } finally { child.dispose(); }
        return true;
    }
}
