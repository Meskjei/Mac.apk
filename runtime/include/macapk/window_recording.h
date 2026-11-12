// window_recording.h
//
// Own-window hardware H.264 MP4 recording for Touchstone mac-app automation.
// Uses SCShareableContent.currentProcess + SCContentFilter(desktopIndependentWindow:)
// so no macOS global Screen Recording TCC permission, desktop capture, or
// external ffmpeg process is required.

#pragma once

#include <jni.h>
#include <string>

namespace macapk {

// True when ScreenCaptureKit currentProcess + SCStream + AVAssetWriter are
// available at runtime (macOS 14.4+).
bool WindowRecordingSupported();

// Start recording the runtime's own placeholder window into `output_path`
// (an .mp4 inside the private 0700 MACAPK_CAPTURE_DIR). Blocks the caller
// (never the AppKit main thread) up to 10s until the first encoded frame is
// anchored at PTS 0, then returns JSON {"ok":true,...} or {"ok":false,"error":"..."}.
std::string WindowRecordingStart(const std::string& recording_id,
                                 const std::string& output_path,
                                 int target_width_px,
                                 int target_height_px);

// Stop `recording_id`, append final tail frame if clean, finalize MP4 moov
// (+faststart), and return JSON {"ok":true,...} or {"ok":false,"error":"..."}.
std::string WindowRecordingStop(const std::string& recording_id);

// Called immediately when FLAG_SECURE becomes active so an in-flight recording
// aborts capture right away without leaking secure frames.
void WindowRecordingNotifySecureContent();

namespace windowrecording {
void RegisterWindowRecordingMethods(JNIEnv* env);
}  // namespace windowrecording

}  // namespace macapk
