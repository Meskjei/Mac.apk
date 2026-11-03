# Touchstone app-scoped automation (experimental)

The selected APK process exposes an opt-in loopback listener. Nothing listens unless both `TOUCHSTONE_AUTOMATION_PORT` (1024–65535) and `TOUCHSTONE_AUTOMATION_TOKEN` (64 hex characters) are supplied. `MACAPK_CAPTURE_DIR` must be an existing directory owned by the current user with mode 0700. Use a new private directory for every process.

Touchstone launches the runtime with a private session file, checks protocol/app ID/PID, and reuses DeviceDriver, Bridge, RunHost and its report pipeline. This environment is `mac-app`, excluded from physical-device acceptance. Disconnecting does not close the APK.

The protocol is one UTF-8 JSON line per loopback TCP connection, bounded at 64KiB. Requests contain `id` (UUID), `command`, `token` and arguments; responses echo `id` and contain `ok` and `result` or an error code. Authentication precedes dispatch. Recent request IDs cannot be replayed. Clients must not retry disconnected input commands: their outcomes are unknown. Errors omit app text and credentials.

- `info`: protocol 1, runtime `macapk`, appId, PID, drawable dimensions and capabilities.
- `screenshot`: in-process self capture, waits for native completion and requires the composite PNG. Returns base64 PNG, pixel dimensions, matching point dimensions, scale 1 and frameId. FLAG_SECURE windows are refused. No desktop capture or other-app capture is used.
- `tree`: partial visible native View hierarchy using ViewTextDump's clipping and coordinate calculation. No asynchronous web DOM query and no claim of complete uiautomator coverage.
- `tap`, `swipe`, `longPress`: normalized coordinates [0,1), pointWidth, pointHeight and frameId. Input runs on the UI/GL thread as Android finger events. Activity, overlay or surface changes invalidate the frame. Completion acknowledges delivery, not application/business success.
- `type`: whole Unicode string through the current InputConnection. `clearText` supports the native TextView editor belonging to that same connection, using its actual widget text-change path; unsupported editors fail explicitly. No focused connection means no delivery.
- `quit`: only with `MACAPK_AUTOMATION_OWNED=1`, bounded Activity teardown then process exit. No cross-app launch, shell, system-key or install capability is advertised.

All APKs, accounts, captures and logs are private artifacts and must not be committed. Credentials must not be printed. Self-capture uses the private directory when configured, retaining the legacy /tmp diagnostic paths otherwise.

Compile `debug/AutomationProtocolTest.java` against `runtime/build/lib/android-framework.jar:runtime/build/lib/macapk-loader.jar`, then run `com.macapk.loader.AutomationProtocolTest`. It checks framing, size bounds, strict UTF-8, JSON and Unicode preservation. Capture, focus and input receipts additionally require a running app.

Compatibility remains per APK/core combination. Qwen's built-in UC core and downloaded updates require different service capabilities; the latter currently needs unsupported separate-process services. The endpoint does not silently replace guest libraries. Record the actual core used in validation.

Location uses the existing CoreLocation bridge, with both macOS authorization for `Mac.apk Location` and a separate Android permission per installed app. A bare `--android-root` development instance has no installed registry identity, so it cannot persist that app permission. Copying the location helper alone does not make location ready. Use the regular installation/registration and run-lock path, keep the signed location and install helpers discoverable, and let the user answer both permission prompts. Never synthesize grants or treat a saved delivery address as a current location fix.


### Fixed device viewport

Touchstone launches with `MACAPK_FIXED_VIEWPORT=1`. The first ready `info` freezes the native content size, app canvas and backing scale and returns `viewportFixed: true`. Resizing, zoom, fullscreen, per-Activity desktop window policies and orientation overrides cannot redefine that session's display. Capture/input reject unexpected geometry or backing-scale changes; restart instead of recalibrating onto a different device. `info` and owned `quit` remain available for recovery. Ordinary desktop launches retain their existing window policy. Clients must reject absent/false `viewportFixed` for automation, including older runtimes.
