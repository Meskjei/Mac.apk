package com.macapk.loader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** javac -cp runtime/build/lib/{android-framework,macapk-loader}.jar ...; java ... com.macapk.loader.AutomationProtocolTest */
public final class AutomationProtocolTest {
    private static void reject(byte[] bytes) throws Exception {
        try { AutomationServer.readRequest(new ByteArrayInputStream(bytes)); }
        catch (Exception expected) { return; }
        throw new AssertionError("accepted invalid request");
    }
    public static void main(String[] args) throws Exception {
        String text = "{\"command\":\"type\",\"text\":\"中文\\n😀\"}\n";
        var request = AutomationServer.readRequest(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        if (!request.getString("text").equals("中文\n😀")) throw new AssertionError("unicode changed");
        reject("{}".getBytes(StandardCharsets.UTF_8));
        reject(new byte[]{(byte)0xc3, (byte)0x28, 10});
        byte[] huge = new byte[65538]; Arrays.fill(huge, (byte)' '); huge[65537] = 10; reject(huge);
        reject("not json\n".getBytes(StandardCharsets.UTF_8));
        if (AutomationServer.gestureStep(1, 28, 225, 450) != 14
                || AutomationServer.gestureStep(14, 28, 200, 450) != 14
                || AutomationServer.gestureStep(1, 28, 900, 450) != 28)
            throw new AssertionError("gesture schedule accumulated or replayed expired moves");
        System.out.println("AutomationProtocolTest PASS");
    }
}
