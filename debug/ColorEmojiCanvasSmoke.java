import android.graphics.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class ColorEmojiCanvasSmoke {
    public static void main(String[] args) throws Exception {
        String[] samples = {"🍔", "🍟", "🥩", "😀", "❤️", "🧑‍🍳", "🇨🇳", "1️⃣"};
        BufferedImage image = new BufferedImage(440, 170, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics(); g.setColor(java.awt.Color.BLACK);
        for (int i = 0; i < samples.length; i++) {
            int x = i * 55;
            if (!MacApkEmoji.draw(g, samples[i], x, 60, 40)) throw new AssertionError("no colour glyph: " + samples[i]);
            int coloured = 0;
            for (int y = 0; y < 80; y++) for (int px = x; px < x + 55; px++) {
                int c = image.getRGB(px, y), r = c >> 16 & 255, b = c & 255, green = c >> 8 & 255;
                if ((c >>> 24) > 0 && Math.max(r, Math.max(green, b)) - Math.min(r, Math.min(green, b)) > 25) coloured++;
            }
            if (coloured < 20) throw new AssertionError("no coloured pixels: " + samples[i]);
        }
        g.setColor(new java.awt.Color(0,0,0,128));
        if (!MacApkEmoji.draw(g, "🍔", 0, 150, 40)) throw new AssertionError("alpha glyph unavailable");
        for (int y = 85; y < 170; y++) for (int x = 0; x < 55; x++) if ((image.getRGB(x,y) >>> 24) > 128) throw new AssertionError("text alpha ignored");
        g.dispose(); ImageIO.write(image, "png", new File(args[0]));
        Bitmap bitmap = Bitmap.createBitmap(440, 80, Bitmap.Config.ARGB_8888);
        if (MacApkFont.hasEmoji("123#*") || MacApkFont.emojiClusterEnd("1",0,1) != -1
            || !MacApkFont.hasEmoji("1️⃣") || MacApkFont.emojiClusterEnd("1️⃣",0,3) != 3)
            throw new AssertionError("keycap clustering changes plain numbers");
        Canvas canvas = new Canvas(bitmap); Paint paint = new Paint(); paint.setTextSize(40);
        canvas.drawText("🍔🍟🥩😀❤️🧑‍🍳🇨🇳1️⃣", 0, 60, paint);
        int ink = 0; for (int y=0;y<80;y++) for (int x=0;x<350;x++) if ((bitmap.getPixel(x,y) >>> 24) != 0) ink++;
        if (ink < 500) throw new AssertionError("Canvas did not use the colour painter");
        System.out.println("CBDT colour pixels, VS16, ZWJ, flag, keycap, alpha and Canvas routing passed; ink="+ink);
        System.exit(0);
    }
}
