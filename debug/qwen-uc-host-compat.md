# Qwen UC host capability failure

Observed with Qwen Android 7.5.2.3069 (70502), arm64-v8a.

The downloaded `libwebviewuc.so` with GNU build ID
`f66c0e117021df906a011968ed69dd9ef3aa1fba` unconditionally checks
`socketpair(AF_UNIX, SOCK_SEQPACKET, 0, ...)` in its Linux crash handler.
On the tested macOS host this returns `EPROTONOSUPPORT`; the CHECK traps
at ELF address `0x63e5660`, before the web view becomes usable.

`arm64_native.cpp` checks that exact build ID and probes the host capability
before mapping or initializing the guest library. An unsupported core is
rejected, allowing the application's SDK to handle load failure. Cached
Mach-O wrappers still export `JNI_OnLoad` after their ELF load fails, so that
rejection supplies a real `JNI_ERR` callback rather than a null export target.
Other guest exports are unavailable. This is a load failure, not a successful
replacement implementation of the core.

The APK's bundled UC core also needs the matching ICU 70 data at
`/apex/com.android.i18n/etc/icu/icudt70l.dat`. Touchstone must package and stage
that file with the AOSP ICU libraries; copying only the `.so` files is incomplete.

Do not emulate SEQPACKET with STREAM, remove crash CHECKs, raise exception
recovery limits, or erase application data to select another core. Unknown
UC build IDs are not covered by this guard and need their own diagnosis.

Run the bounded ELF-note parser checks with:

```sh
clang++ -std=c++17 -fsanitize=address,undefined debug/uc_host_capabilities_test.cpp -o /tmp/uc-capabilities-test
/tmp/uc-capabilities-test
cmake --build runtime/translator/build --target macapk-bionic-shim -j 4
```

Real acceptance must additionally check the SDK's fallback, page rendering,
scrolling and input. A live process or a JNI load error alone is insufficient.
Use the installed application's original Android root and device identity;
keep its account files. Do not infer account preservation from a new cache path.

## SDK core selection

Rejecting the downloaded library alone does not restore the current WebView:
this SDK leaves initialization failed, and only a subsequent boot can select its
bundled core. The updater may select the incompatible cached core again later.

The verified SDK selector `com.uc.webview.internal.setup.z0` has raw class SHA256
`190cbef984498d68c753f265c77a09f3fead19053e3e52d93a6d70450c4794af`.
Its `GlobalSettings.getBoolValue(111)` branch chooses the cached update; false
keeps the initializing APK core. `ClassFileRewriter` changes only that exact
class and fingerprint, replacing the call with `pop; iconst_0; nop`. Instruction
offsets, stack effect and the SDK's core verification are preserved. Unknown SDK
versions are untouched. No application code, credentials or cached cores are
removed. The native guard remains a failsafe for this incompatible library.

Run the integration check against the locally converted application jar:

```sh
javac --release 21 -cp "$FRAMEWORK_JARS" -d "$TEST_CLASSES" \
  runtime/java/com/macapk/loader/ClassFileRewriter.java debug/U4CorePolicySmoke.java
java -Xverify:all -cp "$TEST_CLASSES:$FRAMEWORK_JARS" U4CorePolicySmoke "$APP_CLASSES_JAR"
```

`FRAMEWORK_JARS` contains both the framework and loader jars. Install the resulting
rewriter classes in both candidate jars: both currently contain the loader class.
Never bundle the smoke-test class or the supplied application jar.

## Software canvas scroll

The initial full-canvas-redraw workaround passed a short local check but failed
again after cold installation through the packaged CLI. It is not the root fix
and must not be used as the acceptance condition.

The draw trace showed UC's JavaBrowserViewRendererHelper painting a software
frame, followed by TextureView painting the retained texture into the same
canvas. Chromium's child scroll transform shifted the second copy. The pixels
were already duplicated before final screenshot composition.

The first TextureView-only changes were also rejected: skipping consumption
stalled the producer, and retaining consumption exposed a second mismatch in
software-scroll coordinates. UC cancels the negative self-scroll that AOSP
supplies to onDraw; Mac.apk normally passes viewport coordinates to widgets, so
that cancellation instead shifted the entire page. A broad TextureView software
paint exclusion would also break the runtime's camera/video composite path.

UcSoftwareCanvas scopes compatibility to the verified UC view hierarchy
(com.uc.aosp.android.webkit.a0 and subclasses). View.draw supplies content
coordinates only around this renderer's onDraw, restores them even on exception,
and keeps backgrounds/children on their existing paths. Its child TextureViews
consume frames and emit update callbacks without repainting the same software
WebView frame. Ordinary camera/video/lockCanvas and hardware texture paths remain
unchanged. The pixel regression rejects scroll drift, duplicate texture painting,
producer stalls, transform leaks and suppression of ordinary software textures.
The fixture in debug/ucsoftwarecanvas is synthetic and is never packaged.
AOSP reference:
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/TextureView.java

Packaged acceptance on 2026-11-04 used an isolated npm installation of CLI
0.1.29-qwen-validation.20261104.4, SDK
52d893e5759fc1a46726eafa5ee2168783b9be62d27443c258e1a53ab017da2d.
The running shim and both jars matched sealed hashes. At 780x1688 the actual
application passed merchant loading, consecutive scrolls, reverse scrolling,
close/reopen and scrolling again, plus session reuse and retained account data.
A new Chinese request returned fresh merchant/product cards and a finished reply.
One preceding search returned a business failure and automatic location failed;
neither is counted as a passed business/location check. No cart/order/payment
operation was performed. Shared catalog changes and npm publication remain pending.
These checks do not certify other APK/SDK versions or older macOS versions.
