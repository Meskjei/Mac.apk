# Android local page transitions appeared to ignore input

## Reproduction and cause

On base `c3f489d0`, 淘宝闪购 Android 12.10.61 (1610) accepts homepage input. Open 名粥阁·潮汕砂锅粥(阿里店), then tap a product picture: the guest navigates to another activity, but the screen keeps showing the shop. Subsequent gestures therefore appear ineffective.

Both native SurfaceView producers received the same process-global ANativeWindow/CAMetalLayer. The second `eglCreateWindowSurface` failed with EGL_BAD_ALLOC (12291), followed by EGL_BAD_SURFACE and failed swaps. Input delivery was working.

## Change

- Surface exposes its owning SurfaceView's existing stable compositor key.
- The shim preserves per-Surface native window identity and references, with a separate EGL layer per SurfaceView. TextureView streams and ownerless legacy windows retain their respective paths.
- Engines that already published their native window retain that path: their `surfaceCreated` handshake may block AppKit, so querying the prepared window must not wait for the main queue.
- Reuse the existing per-surface compositor for placement, z order and buffer size. Every native SurfaceView below its window punches its own hole.
- Hide a surface while its owning activity is stopped under another activity. Restore it on Back; paused but visible translucent activities are unaffected.
- Launching a translucent/floating activity preserves the activity below it, using the existing manifest window-style record. Explicit host covers and moving the task to the background retain their stop behavior.
- Supply `ActivityGroup` for legacy type checks. The actual APK's `AKPopContainer.onCreateView` checks `context instanceof ActivityGroup`; without the type its `onCreate` aborts and leaves an empty black window. Embedded activity hosting remains explicitly unsupported.

## Validation

`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` passed. Actual local application checks passed: homepage → shop → product detail, upward swipe in the detail, Back → shop → homepage. The item was 广式煎饺（3个）, showing original ¥26 and discounted ¥12, with 单点不送. These are displayed item prices, not a checkout quote. No cart or order action was taken.

The old runtime log fails the regression check; the candidate log passes with two distinct native windows/layers, successful EGL surfaces, no failed presents, and a hidden → shown transition on Back:

```sh
python3 debug/native_surface_windows_check.py /path/to/runtime-stdout.log
```

`ActivityGroupLinkageTest` and `pending_layer_captures_test.cpp` passed. With the completed compatibility fixes, actual AI reply → store popup → product detail → swipe → close detail → close popup was verified in the local app. The popup preserves the AI page beneath it. Product detail distinguishes estimated ¥8.5 from discounted ¥12; it still says 单点不送. No add-to-cart/order action was taken.

Platform validation task: [android_local_price_input_fix_20261105](https://bot.alibaba-inc.com/investigations/android_local_price_input_fix_20261105). It uses the source-built candidate runtime and the catalog APK with its real package SHA verified by the normal preparation worker. A local test hook changes only the runtime launch profile after actual package installation and verification; it does not change platform validation, task execution or results.

The first platform round completed traditional-page collection, but AI collection was interrupted by the first macOS location prompt. Its recovered AI reading includes a pre-existing traditional detail image, so that round is not accepted as an AI comparison. Location was allowed only after the user explicitly approved; the helper reported authorization status 3. A separate round after authorization is recorded at [android_local_price_input_fix_after_auth_20261105](https://bot.alibaba-inc.com/investigations/android_local_price_input_fix_after_auth_20261105).

That second round captured the real AI query and reply (text ¥12, card ¥8.5), then exposed the missing ActivityGroup type in the popup. It was paused and is not accepted as a complete comparison. Final validation uses the completed fixes: [android_local_price_input_fix_final_20261105](https://bot.alibaba-inc.com/investigations/android_local_price_input_fix_final_20261105).

Final platform execution completed (`round_complete`, 1 complete pair, 0 pending):

- Traditional: `run_qza80wa68n`, pass, 4 actions; confirmed product detail at promotional ¥12.
- AI: `run_e4xdetyeq8`, pass, 16 actions; the trace contains the new query, reply, store popup and same-product detail. The detail explicitly shows estimated ¥8.5 alongside discounted ¥12.
- Comparison remains uncertain (0 valid pairs, 1 uncertain): account/offer context was not established, the AI evidence review timed out, and the estimate is not a demonstrated checkout price. The raw AI reading labels ¥8.5 as promotion; the visible estimate label takes precedence when interpreting it. No claim of a verified payable price difference is made.
- The final runtime log passes `native_surface_windows_check.py`: 3 distinct native window creations, successful presents, covered-page hide/restore, and no activity `onCreate` failure. Both the original EGL failure log and the earlier popup-failure log fail their respective regression assertions.

The verified source is `4366338` on `codex/android-price-input-fix-20261105` (following `e2d615a`). Runtime build identities: FRAMEWORK_ID `7ceb6681767433da`, JAVA_ID `c3b4db367943217c`, JARS_ID `ab835c6cdc7a6f1b`, NATIVE_ID `b44f68c8245cbb72`. This is local source-runtime acceptance, not a production service deployment or a published CLI/SDK upgrade.

The distributable candidate installer was terminated before installation (SIGKILL); the cause is unresolved. It is not published or release-accepted. Other Android apps, games, physical phones and iOS are outside this validation. GitNexus does not index the two large native source files; their JNI/dlsym callers and lifecycle paths were reviewed directly.
