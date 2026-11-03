import android.app.Application;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class InflaterAttachRootSmoke {
    private static final String XML = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent" android:layout_height="84px">
            <LinearLayout android:layout_width="wrap_content" android:layout_height="38px"/>
        </LinearLayout>
        """;

    public static void main(String[] args) {
        var resources = new Resources(null, null, null) {
            @Override public java.io.InputStream openRawResource(int id) {
                return new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8));
            }
        };
        var context = new Application() {
            @Override public Resources getResources() { return resources; }
        };
        var inflater = LayoutInflater.from(context);
        var root = new FrameLayout(context);
        View result = inflater.inflate(0x7f010001, root, true);
        check(result == root, "attached inflation must return the supplied container");
        check(root.getChildCount() == 1, "attach exactly one layout");
        ((android.view.ViewGroup) result).getChildAt(0).setPadding(30, 15, 30, 15);
        var layout = (android.view.ViewGroup) root.getChildAt(0);
        check(layout.getPaddingTop() == 15, "caller must configure the outer layout");
        check(layout.getChildAt(0).getPaddingTop() == 0, "inner row must keep its padding");

        var detachedRoot = new FrameLayout(context);
        View detached = inflater.inflate(0x7f010001, detachedRoot, false);
        check(detached != detachedRoot && detached.getParent() == null, "detached result");
        check(detachedRoot.getChildCount() == 0, "detached parent stays empty");
        check(detached.getLayoutParams() instanceof FrameLayout.LayoutParams, "parent layout params");
        check(inflater.inflate(0x7f010001, null, true) != null, "null root returns new layout");
        var implicitRoot = new FrameLayout(context);
        check(inflater.inflate(0x7f010001, implicitRoot) == implicitRoot, "two-argument overload");
        System.out.println("Inflater attach/detach return values and container padding passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
