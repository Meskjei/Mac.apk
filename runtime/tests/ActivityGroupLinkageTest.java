package com.macapk.loader;

import android.app.Activity;
import android.app.ActivityGroup;

public final class ActivityGroupLinkageTest {
    public static void main(String[] args) throws Exception {
        Object ordinaryContext = new Object();
        if (ordinaryContext instanceof ActivityGroup) throw new AssertionError("ordinary context is not an activity group");
        if (!Activity.class.isAssignableFrom(ActivityGroup.class)) throw new AssertionError("activity group must be an Activity type");
        if (ActivityGroup.class.isAssignableFrom(Activity.class)) throw new AssertionError("ordinary activities must take the non-group path");
        ActivityGroup.class.getConstructor();
        ActivityGroup.class.getConstructor(boolean.class);
        System.out.println("ActivityGroup linkage and non-group context checks passed");
    }
}
