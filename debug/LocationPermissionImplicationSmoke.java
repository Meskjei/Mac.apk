package com.macapk.loader;
import java.util.Map;
public final class LocationPermissionImplicationSmoke {
    private static final String F = SpecialAccessStore.PERM_ACCESS_FINE_LOCATION;
    private static final String C = SpecialAccessStore.PERM_ACCESS_COARSE_LOCATION;
    public static void main(String[] args) {
        check(C, Map.of(F, "granted"), true);
        check(C, Map.of(F, "granted_fixed"), true);
        check(C, Map.of(F, "denied"), false);
        check(C, Map.of(F, "denied_fixed"), false);
        check(C, Map.of(), false);
        check(F, Map.of(C, "granted"), false);
        check(C, Map.of(F, "granted", C, "denied"), false);
        check(C, Map.of(F, "granted", C, "denied_fixed"), false);
        check(C, Map.of(C, "granted"), true);
        System.out.println("Location permission implication and explicit denial passed");
    }
    private static void check(String p, Map<String,String> states, boolean expected) {
        if (SpecialAccessStore.locationGranted(p, states) != expected)
            throw new AssertionError(p + " " + states);
    }
}
