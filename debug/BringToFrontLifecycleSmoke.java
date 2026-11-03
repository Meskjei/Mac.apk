import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Reordering a tab/header must not simulate removing it from its window. */
public final class BringToFrontLifecycleSmoke {
    static class Group extends FrameLayout {
        int added, removed, layout, invalidated;
        Group(Context context) { super(context); }
        @Override public void onViewAdded(View child) { added++; }
        @Override public void onViewRemoved(View child) { removed++; }
        @Override public void requestLayout() { layout++; super.requestLayout(); }
        @Override public void invalidate() { invalidated++; super.invalidate(); }
    }
    static class Tab extends FrameLayout {
        int attached, detached, selected = 4;
        Tab(Context context) { super(context); }
        @Override public void onAttachedToWindow() { attached++; }
        @Override public void onDetachedFromWindow() { detached++; selected = 0; }
    }
    public static void main(String[] args) {
        Context context = new android.app.Application();
        Group parent = new Group(context);
        Tab tab = new Tab(context);
        View sibling = new View(context), descendant = new View(context);
        ViewGroup.LayoutParams params = new FrameLayout.LayoutParams(100, 80);
        parent.addView(tab, params); tab.addView(descendant); parent.addView(sibling);
        parent.macapkDispatchAttachedToWindow();
        var token = tab.getWindowToken();
        int attached = tab.attached, added = parent.added, removed = parent.removed;
        parent.layout = parent.invalidated = 0;
        tab.bringToFront();
        check(parent.getChildAt(1) == tab, "draw/hit-test child order");
        check(tab.selected == 4, "tab selection reset by a spurious detach");
        check(tab.attached == attached && tab.detached == 0, "lifecycle changed");
        check(parent.added == added && parent.removed == removed, "hierarchy callbacks fired");
        check(tab.getParent() == parent && tab.getLayoutParams() == params, "parent/layout params changed");
        check(tab.isAttachedToWindow() && descendant.isAttachedToWindow() && token == tab.getWindowToken(), "window identity lost");
        check(parent.layout > 0 && parent.invalidated > 0, "new drawing order not scheduled");
        for (int i = 0; i < 10; i++) { sibling.bringToFront(); tab.bringToFront(); }
        check(tab.detached == 0 && tab.attached == attached, "repeated reorder detached tab");
        parent.bringChildToFront(new View(context));
        new View(context).bringToFront();
        check(parent.getChildCount() == 2, "unknown/orphan child was inserted");
        parent.removeView(tab);
        check(tab.detached == 1 && tab.selected == 0 && !descendant.isAttachedToWindow(), "real removal no longer detaches");
        System.out.println("bringToFront preserves attachment, tab state, hierarchy and window; real removal still detaches");
        System.exit(0);
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
