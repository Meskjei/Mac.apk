import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.view.HardwareCanvas;
public final class SoftwareCanvasProfileSmoke {
    public static void main(String[] args) {
        boolean expected = !"1".equals(System.getenv("MACAPK_SOFTWARE_CANVAS"));
        var node = new RenderNode("profile-test");
        if (new Canvas().isHardwareAccelerated() != expected
                || new HardwareCanvas(){}.isHardwareAccelerated() != expected
                || node.beginRecording(12,8).isHardwareAccelerated() != expected)
            throw new AssertionError("Canvas implementations disagree with the selected profile");
        node.endRecording();
        System.out.println("Canvas hardware contract: " + expected);
    }
}
