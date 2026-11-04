package com.uc.aosp.android.webkit;

/** Test fixture for UC's software renderer coordinate contract; no APK code. */
public class a0 extends android.widget.AbsoluteLayout {
    public boolean throwOnDraw;
    public a0() { super(null); }
    @Override public void onDraw(android.graphics.Canvas canvas) {
        canvas.translate(getScrollX(), getScrollY());
        if (throwOnDraw) throw new IllegalStateException("fixture draw failure");
        var paint = new android.graphics.Paint();
        paint.setColor(0xff0000ff);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
