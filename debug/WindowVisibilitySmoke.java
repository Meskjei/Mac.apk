import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

public final class WindowVisibilitySmoke {
    static final List<String> events = new ArrayList<>();
    static class Group extends FrameLayout {
        Group(Context context) { super(context); }
        @Override public void onAttachedToWindow() { events.add("group.attach"); }
        @Override public void onWindowVisibilityChanged(int visibility) {
            events.add("group." + visibility);
        }
    }
    static class Child extends View {
        Child(Context context) { super(context); }
        @Override public void onAttachedToWindow() { events.add("child.attach"); }
        @Override public void onWindowVisibilityChanged(int visibility) {
            events.add("child." + visibility);
        }
    }
    public static void main(String[] args) {
        Context context = new android.app.Application();
        Group group = new Group(context);
        group.addView(new Child(context));
        group.macapkDispatchAttachedToWindow();
        if (!events.equals(List.of("group.attach", "group.0", "child.attach", "child.0")))
            throw new AssertionError(events);
        events.clear();
        group.macapkDispatchAttachedToWindow();
        if (!events.isEmpty()) throw new AssertionError("duplicate attach: " + events);
        group.dispatchWindowVisibilityChanged(View.GONE);
        if (!events.equals(List.of("group.8", "child.8"))) throw new AssertionError(events);
        if (group.getChildAt(0).getWindowVisibility() != View.GONE) throw new AssertionError("stale visibility");
        events.clear();
        Child late = new Child(context);
        group.addView(late);
        if (late.getWindowVisibility() != View.GONE || !events.equals(List.of("child.attach", "child.8")))
            throw new AssertionError("late child: " + events);
        System.out.println("Window visibility: delivered after attach, once, and propagated to children");
    }
}
