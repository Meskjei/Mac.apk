package com.macapk.loader;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Opt-in, app-scoped loopback control. Completion acknowledges delivery, never a business outcome. */
public final class AutomationServer {
    private final String appId, token;
    private final Path captures;
    private final ServerSocket listener;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private String frameId, frameGeometry;
    private final long pid = ProcessHandle.current().pid();

    private AutomationServer(String app, String credential, int port, Path directory) throws Exception {
        appId = app; token = credential; captures = directory;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.getOwner(directory).equals(Files.getOwner(Path.of(System.getProperty("user.home")))))
            throw new IllegalArgumentException("private_capture_directory_required");
        listener = new ServerSocket();
        listener.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
    }
    public static void start(String appId) throws Exception {
        String token = System.getenv("TOUCHSTONE_AUTOMATION_TOKEN"), port = System.getenv("TOUCHSTONE_AUTOMATION_PORT");
        if (token == null && port == null) return;
        if (token == null || !token.matches("[a-fA-F0-9]{64}") || port == null) throw new IllegalArgumentException("invalid_automation_configuration");
        int number = Integer.parseInt(port);
        if (number < 1024 || number > 65535 || System.getenv("MACAPK_CAPTURE_DIR") == null) throw new IllegalArgumentException("invalid_automation_configuration");
        AutomationServer server = new AutomationServer(appId, token, number, Path.of(System.getenv("MACAPK_CAPTURE_DIR")));
        Thread thread = new Thread(server::serve, "Mac.apk automation");
        thread.setDaemon(true); thread.start();
    }
    static JSONObject readRequest(java.io.InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == 10) {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                return new JSONObject(text);
            }
            if (bytes.size() >= 65536) throw new IllegalArgumentException("request_too_large");
            bytes.write(value);
        }
        throw new IllegalArgumentException("incomplete_request");
    }
    private void serve() {
        for (;;) try (Socket socket = listener.accept()) {
            socket.setSoTimeout(5000);
            JSONObject response = new JSONObject();
            try {
                JSONObject request = readRequest(socket.getInputStream());
                String credential = request.optString("token", "");
                if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), credential.getBytes(StandardCharsets.UTF_8)))
                    throw new IllegalArgumentException("unauthorized");
                String id = request.optString("id", "");
                if (!id.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("invalid_request_id");
                response.put("id", id);
                if (!seen.add(id)) throw new IllegalArgumentException("duplicate_request_id");
                if (seen.size() > 4096) seen.remove(seen.iterator().next());
                response.put("result", execute(request)); response.put("ok", true);
            } catch (Throwable error) {
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                String message = cause.getMessage();
                String code = message != null && message.matches("[a-z_]{1,80}") ? message : "operation_failed_result_unknown";
                response.put("ok", false); response.put("error", code);
            }
            byte[] output = (response.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(output);
        } catch (Throwable ignored) { /* A disconnected observer never cancels or replays an action. */ }
    }
    private <T> T ui(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) { task.cancel(false); throw new IllegalStateException("ui_timeout_result_unknown"); }
    }
    private boolean viewportLocked;
    private String geometry() throws Exception {
        if (viewportLocked && !NativeCocoa.viewportFixed()) throw new IllegalStateException("device_viewport_changed_restart_session");
        Activity active = ActivityHost.peek();
        long[] size = TouchDispatcher.automationGeometry();
        if (active == null || active.isFinishing() || size[0] <= 0 || size[1] <= 0) throw new IllegalStateException("app_not_ready");
        StringBuilder value = new StringBuilder().append(System.identityHashCode(active)).append(':')
            .append(size[0]).append(':').append(size[1]).append(':').append(size[2]);
        for (WindowOverlayStack.Layer layer : WindowOverlayStack.snapshot())
            if (layer.view.getVisibility() == View.VISIBLE) value.append(':').append(System.identityHashCode(layer.view)).append(':').append(layer.bounds);
        return value.toString();
    }
    private JSONObject info() throws Exception {
        return ui(() -> {
            Activity active = ActivityHost.peek();
            long[] size = TouchDispatcher.automationGeometry();
            if (active == null || active.isFinishing() || size[0] <= 0 || size[1] <= 0) throw new IllegalStateException("app_not_ready");
            viewportLocked = NativeCocoa.lockViewport() || viewportLocked;
            android.content.pm.PackageInfo packageInfo = active.getPackageManager().getPackageInfo(appId, 0);
            return new JSONObject().put("protocol", 1).put("runtime", "macapk").put("appId", appId).put("pid", pid)
                .put("appVersion", packageInfo.versionName).put("appBuild", String.valueOf(packageInfo.getLongVersionCode()))
                .put("viewportFixed", NativeCocoa.viewportFixed()).put("width", size[0]).put("height", size[1]).put("foreground", !ActivityHost.peek().isFinishing())
                .put("capabilities", new JSONArray(new String[]{"screenshot", "tree", "tap", "swipe", "longPress", "type", "clearText"}))
                .put("fontEnvironment", new JSONObject().put("fontSetId", android.graphics.MacApkSystemFonts.fontSetId())
                    .put("nativeEmoji", android.graphics.MacApkSystemFonts.nativeEmojiConfigured() ? "configured" : "missing")
                    .put("javaEmojiRenderer", android.graphics.MacApkEmoji.available() ? "bitmap" : "host")
                    .put("verification", "configuration"))
                .put("runtimeBuild", System.getenv("MACAPK_AUTOMATION_BUILD_ID"));
        });
    }
    private void requireFrame(JSONObject request) throws Exception {
        String expected = frameId;
        if (expected == null || !expected.equals(request.optString("frameId", ""))) throw new IllegalStateException("stale_frame");
        ui(() -> {
            long[] size = TouchDispatcher.automationGeometry();
            if (!geometry().equals(frameGeometry) || request.getDouble("pointWidth") != size[0] || request.getDouble("pointHeight") != size[1])
                throw new IllegalStateException("window_changed");
            return null;
        });
    }
    private String captureGeometry() throws Exception {
        Activity a = ActivityHost.peek();
        if (a == null || (a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)
            throw new IllegalStateException("capture_secure_or_unavailable_window");
        for (WindowOverlayStack.Layer layer : WindowOverlayStack.snapshot())
            if (layer.params != null && (layer.params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)
                throw new IllegalStateException("capture_secure_window");
        return geometry();
    }
    private JSONObject screenshot() throws Exception {
        String before = ui(this::captureGeometry);
        Path done = captures.resolve("macapk_capture_" + pid + ".done"), output = captures.resolve("macapk_capture_" + pid + "_composite.png");
        Files.deleteIfExists(done); Files.deleteIfExists(output);
        Path request = captures.resolve("macapk_capture_req_" + pid);
        Files.writeString(request, "composite-current-v1", StandardOpenOption.CREATE_NEW);
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!Files.exists(done, LinkOption.NOFOLLOW_LINKS) && System.nanoTime() < until) Thread.sleep(25);
            if (!Files.exists(done, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(done).contains("composite")) throw new IllegalStateException("capture_incomplete");
            byte[] png = Files.readAllBytes(output);
            if (png.length > 12 * 1024 * 1024) throw new IllegalStateException("capture_too_large");
            var image = ImageIO.read(new java.io.ByteArrayInputStream(png));
            return ui(() -> {
                long[] size = TouchDispatcher.automationGeometry();
                if (!before.equals(captureGeometry()) || image == null || image.getWidth() != size[0] || image.getHeight() != size[1])
                    throw new IllegalStateException("capture_window_changed");
                frameId = UUID.randomUUID().toString(); frameGeometry = before;
                return new JSONObject().put("png", Base64.getEncoder().encodeToString(png)).put("width", size[0]).put("height", size[1])
                    .put("pointWidth", size[0]).put("pointHeight", size[1]).put("scale", 1).put("frameId", frameId);
            });
        } finally { Files.deleteIfExists(request); }
    }
    private JSONObject tree() throws Exception {
        return ui(() -> {
            if (frameId == null || !geometry().equals(frameGeometry)) throw new IllegalStateException("stale_frame");
            JSONArray nodes = new JSONArray();
            View root = ActivityHost.peek().macapkContentView();
            ViewTextDump.snapshot(root, StatusBarDriver.heightPx(root), (v, bounds, depth, window, text, desc, editable) -> {
                try { nodes.put(new JSONObject().put("index", nodes.length()).put("depth", depth).put("window", window)
                    .put("type", v.getClass().getName()).put("x", bounds.left).put("y", bounds.top)
                    .put("width", bounds.width()).put("height", bounds.height()).put("value", text)
                    .put("label", desc == null ? null : desc.toString()).put("enabled", v.isEnabled()).put("focused", v.isFocused())
                    .put("editable", editable)); } catch (org.json.JSONException e) { throw new IllegalStateException("invalid_tree"); }
            });
            long[] size = TouchDispatcher.automationGeometry();
            return new JSONObject().put("nodes", nodes).put("partial", true).put("pointWidth", size[0]).put("pointHeight", size[1]);
        });
    }
    private static double coordinate(JSONObject request, String name) throws Exception {
        double n = request.getDouble(name);
        if (!Double.isFinite(n) || n < 0 || n >= 1) throw new IllegalArgumentException("invalid_coordinates");
        return n;
    }
    private JSONObject execute(JSONObject request) throws Exception {
        String command = request.getString("command");
        if (command.equals("info")) return info();
        if (command.equals("screenshot")) return screenshot();
        if (command.equals("tree")) return tree();
        if (command.equals("quit")) {
            if (!"1".equals(System.getenv("MACAPK_AUTOMATION_OWNED"))) throw new IllegalStateException("process_not_owned");
            new Thread(() -> { try { Thread.sleep(150); ActivityHost.macapkTerminate(3000, true); } catch (Throwable ignored) {} System.exit(0); }, "automation quit").start();
            return new JSONObject().put("quitting", true);
        }
        if (command.equals("type") || command.equals("clearText")) {
            String text = command.equals("type") ? request.getString("text") : "";
            if (text.length() > 16000) throw new IllegalArgumentException("text_too_large");
            frameId = null;
            return TouchDispatcher.automationInput(() -> {
                geometry();
                InputConnection connection = InputMethodManager.macapkActiveConnection();
                if (connection == null) throw new IllegalStateException("no_focused_editor");
                if (command.equals("clearText")) {
                    android.widget.TextView field = InputMethodManager.macapkServedEditor();
                    if (field == null || !field.onCheckIsTextEditor()) throw new IllegalStateException("clear_not_supported_for_editor");
                    field.setText("");
                    if (field.getText().length() != 0)
                        throw new IllegalStateException("clear_failed_result_unknown");
                } else if (!connection.commitText(text, 1)) throw new IllegalStateException("input_rejected_result_unknown");
                return new JSONObject().put("delivered", true);
            });
        }
        if (!command.equals("tap") && !command.equals("swipe") && !command.equals("longPress")) throw new IllegalArgumentException("unsupported_command");
        requireFrame(request);
        double x = coordinate(request,"x"), y = coordinate(request,"y");
        double endX = command.equals("swipe") ? coordinate(request,"endX") : x, endY = command.equals("swipe") ? coordinate(request,"endY") : y;
        int duration = command.equals("tap") ? 60 : request.getInt("durationMs");
        if (duration < 1 || duration > 5000) throw new IllegalArgumentException("invalid_duration");
        String expected = frameGeometry; frameId = null;
        Activity target = ActivityHost.peek();
        long[] size = TouchDispatcher.automationGeometry();
        TouchDispatcher.automationInput(() -> { if (!geometry().equals(expected)) throw new IllegalStateException("window_changed"); TouchDispatcher.automationPointer(MotionEvent.ACTION_DOWN, (float)(x*size[0]), (float)(y*size[1])); return null; });
        try {
            int steps = command.equals("swipe") ? Math.max(2, duration / 16) : 1;
            long started = System.nanoTime(), total = TimeUnit.MILLISECONDS.toNanos(duration);
            for (int i=1;i<=steps;i++) {
                long remaining = started + total * i / steps - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                // Coalesce expired MOVE slots instead of adding UI processing time to every sleep.
                if (command.equals("swipe")) i = gestureStep(i, steps, System.nanoTime() - started, total);
                if (command.equals("swipe")) {
                    double fraction=(double)i/steps;
                    TouchDispatcher.automationInput(() -> { if (!geometry().equals(expected)) throw new IllegalStateException("window_changed_result_unknown"); TouchDispatcher.automationPointer(MotionEvent.ACTION_MOVE,(float)((x+(endX-x)*fraction)*size[0]),(float)((y+(endY-y)*fraction)*size[1])); return null; });
                }
            }
        } finally {
            TouchDispatcher.automationInput(() -> { if (geometry().equals(expected)) TouchDispatcher.automationPointer(MotionEvent.ACTION_UP,(float)(endX*size[0]),(float)(endY*size[1])); else { TouchDispatcher.automationPointer(target, MotionEvent.ACTION_CANCEL,(float)(endX*size[0]),(float)(endY*size[1])); throw new IllegalStateException("window_changed_result_unknown"); } return null; });
        }
        return new JSONObject().put("delivered", true);
    }
    static int gestureStep(int scheduled, int steps, long elapsed, long duration) {
        return Math.max(scheduled, (int)Math.min(steps, Math.max(0, elapsed) * steps / duration));
    }
}
