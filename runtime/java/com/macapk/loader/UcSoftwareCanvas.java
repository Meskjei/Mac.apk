package com.macapk.loader;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewParent;

/** Coordinate/texture compatibility for UC's Android software WebView renderer. */
public final class UcSoftwareCanvas {
    private UcSoftwareCanvas() {}

    private static final ClassValue<Boolean> CONTENT_VIEW = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                if (c.getName().equals("com.uc.aosp.android.webkit.a0")) return true;
            }
            return false;
        }
    };

    public static int beginContentDraw(View view, Canvas canvas) {
        int x = view.getScrollX(), y = view.getScrollY();
        if ((x == 0 && y == 0) || canvas.isHardwareAccelerated()
                || !CONTENT_VIEW.get(view.getClass())) return -1;
        // AOSP enters onDraw in content coordinates. Mac.apk's widgets normally
        // use viewport coordinates; UC cancels AOSP's -scroll itself, so supply
        // it only around this renderer, leaving backgrounds and children alone.
        int save = canvas.save();
        canvas.translate(-x, -y);
        return save;
    }

    public static boolean ownsSoftwareFrame(View child, Canvas canvas) {
        if (canvas.isHardwareAccelerated()) return false;
        for (ViewParent parent = child.getParent(); parent instanceof View;
                parent = ((View) parent).getParent()) {
            if (CONTENT_VIEW.get(parent.getClass())) return true;
        }
        return false;
    }
}
