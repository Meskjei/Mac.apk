// window_recording.mm
//
// In-process own-window H.264 MP4 recorder for Mac.apk Touchstone automation.
// Compiled with -fobjc-arc (see runtime/CMakeLists.txt).

#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <objc/runtime.h>
#include <sys/stat.h>

#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>

#include "macapk/cocoa_bridge.h"
#include "macapk/window_recording.h"

#if !__has_feature(objc_arc)
#error "window_recording.mm requires -fobjc-arc"
#endif

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"

namespace {

constexpr int64_t kMaxRawRecordingBytes = 248LL * 1024LL * 1024LL;
constexpr int32_t kRecordingTimescale = 90000;

int64_t NowEpochMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

bool IsValidRecordingId(const std::string& value) {
    if (value.empty() || value.size() > 100) return false;
    for (unsigned char ch : value) {
        const bool ok = (ch >= '0' && ch <= '9') ||
                        (ch >= 'A' && ch <= 'Z') ||
                        (ch >= 'a' && ch <= 'z') ||
                        ch == '-' || ch == '_';
        if (!ok) return false;
    }
    return true;
}

int EvenPixels(double raw) {
    if (!std::isfinite(raw) || raw < 2.0) return 0;
    const int rounded = static_cast<int>(std::llround(raw)) & ~1;
    return rounded >= 2 ? rounded : 0;
}

std::string JsonError(const std::string& code) {
    return std::string("{\"ok\":false,\"error\":\"") + code + "\"}";
}

struct StartWaiter {
    std::mutex mu;
    std::condition_variable cv;
    bool done = false;
    bool ok = false;
    int64_t started_at_ms = 0;
    int width = 0;
    int height = 0;
    std::string error;
};

struct StopWaiter {
    std::mutex mu;
    std::condition_variable cv;
    bool done = false;
    bool ok = false;
    int64_t started_at_ms = 0;
    int64_t finished_at_ms = 0;
    int duration_ms = 0;
    int width = 0;
    int height = 0;
    int64_t size = 0;
    std::string partial_error;
    std::string fatal_error;
};

}  // namespace

API_AVAILABLE(macos(14.4))
@interface MacApkOwnWindowRecordingDelegate : NSObject <SCStreamOutput, SCStreamDelegate> {
@public
    std::string _recordingId;
    NSURL* _fileURL;
    int _pixelWidth;
    int _pixelHeight;
    __weak NSWindow* _window;
    NSInteger _windowNumber;
    NSSize _initialFrameSize;
    NSRect _initialContentRect;
    CGFloat _initialScale;
    AVAssetWriter* _writer;
    AVAssetWriterInput* _writerInput;
    AVAssetWriterInputPixelBufferAdaptor* _adaptor;
    dispatch_queue_t _sampleQueue;
    SCStream* _stream;
    dispatch_source_t _timer;

    bool _streamRunning;
    bool _stopping;
    bool _finalized;
    bool _hasFirstFrame;
    CMTime _firstHostPts;
    CMTime _lastAppendedPts;
    CMTime _lastAppendHostTime;
    CVPixelBufferRef _lastPixelBuffer;
    int _frameCount;
    int64_t _startedAtMs;
    std::string _earlyStopReason;

    std::shared_ptr<StartWaiter> _startWaiter;
    std::shared_ptr<StopWaiter> _stopWaiter;
    void (^_onEnded)(MacApkOwnWindowRecordingDelegate*);
}
- (void)abortEarlyWithReason:(const std::string&)reason;
- (void)requestStopWithWaiter:(const std::shared_ptr<StopWaiter>&)waiter;
@end

@implementation MacApkOwnWindowRecordingDelegate

- (void)dealloc {
    if (_lastPixelBuffer) {
        CVPixelBufferRelease(_lastPixelBuffer);
        _lastPixelBuffer = nullptr;
    }
}

- (void)completeStartOk:(bool)ok error:(const std::string&)error {
    auto waiter = _startWaiter;
    if (!waiter) return;
    _startWaiter.reset();
    {
        std::lock_guard<std::mutex> lock(waiter->mu);
        if (waiter->done) return;
        waiter->done = true;
        waiter->ok = ok;
        waiter->started_at_ms = _startedAtMs;
        waiter->width = _pixelWidth;
        waiter->height = _pixelHeight;
        waiter->error = error;
    }
    waiter->cv.notify_all();
}

- (void)completeStopFatal:(const std::string&)error {
    auto waiter = _stopWaiter;
    _stopWaiter.reset();
    if (_onEnded) {
        auto cb = _onEnded;
        _onEnded = nil;
        cb(self);
    }
    if (!waiter) return;
    {
        std::lock_guard<std::mutex> lock(waiter->mu);
        waiter->done = true;
        waiter->ok = false;
        waiter->fatal_error = error;
    }
    waiter->cv.notify_all();
}

- (void)stopStreamEarly {
    if (_timer) {
        dispatch_source_cancel(_timer);
        _timer = nil;
    }
    if (_streamRunning && _stream) {
        _streamRunning = false;
        SCStream* activeStream = _stream;
        [activeStream stopCaptureWithCompletionHandler:^(NSError* _Nullable) {}];
    }
}

- (void)failBeforeFirstFrame:(const std::string&)reason {
    if (_finalized) return;
    _finalized = true;
    _stopping = true;
    [self stopStreamEarly];
    if (_writer) {
        [_writer cancelWriting];
    }
    if (_fileURL) {
        [[NSFileManager defaultManager] removeItemAtURL:_fileURL error:nil];
    }
    [self completeStartOk:false error:reason];
    [self completeStopFatal:reason];
}

- (void)abortEarlyWithReason:(const std::string&)reason {
    std::string copy = reason;
    dispatch_async(_sampleQueue, ^{
        if (self->_stopping) return;
        if (!self->_hasFirstFrame) {
            [self failBeforeFirstFrame:copy];
        } else if (self->_earlyStopReason.empty()) {
            self->_earlyStopReason = copy;
            [self stopStreamEarly];
        }
    });
}

- (void)stream:(SCStream*)stream
    didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
                   ofType:(SCStreamOutputType)type {
    (void)stream;
    if (type != SCStreamOutputTypeScreen || _stopping || !_earlyStopReason.empty()) return;
    if (!CMSampleBufferDataIsReady(sampleBuffer)) return;

    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, false);
    if (!attachments || CFArrayGetCount(attachments) == 0) return;
    auto* dict = (__bridge NSDictionary*)CFArrayGetValueAtIndex(attachments, 0);
    NSNumber* statusNum = dict[SCStreamFrameInfoStatus];
    if (!statusNum || statusNum.integerValue != SCFrameStatusComplete) return;

    CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!pixelBuffer) return;

    CMTime samplePts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer);
    if (!CMTIME_IS_VALID(samplePts) || !CMTIME_IS_NUMERIC(samplePts)) return;
    CMTime nowHost = CMClockGetTime(CMClockGetHostTimeClock());

    if (macapk::CocoaSecureContentActive()) {
        if (!_hasFirstFrame) {
            [self failBeforeFirstFrame:"capture_secure_window"];
        } else {
            _earlyStopReason = "capture_secure_window";
            [self stopStreamEarly];
        }
        return;
    }

    if (!_hasFirstFrame) {
        _hasFirstFrame = true;
        _firstHostPts = samplePts;
        _startedAtMs = NowEpochMs();
        if (_writer.status != AVAssetWriterStatusWriting ||
            !_writerInput.readyForMoreMediaData ||
            ![_adaptor appendPixelBuffer:pixelBuffer withPresentationTime:kCMTimeZero]) {
            _hasFirstFrame = false;
            [self failBeforeFirstFrame:"capture_compositor_unavailable"];
            return;
        }
        CVPixelBufferRetain(pixelBuffer);
        if (_lastPixelBuffer) CVPixelBufferRelease(_lastPixelBuffer);
        _lastPixelBuffer = pixelBuffer;
        _lastAppendedPts = kCMTimeZero;
        _lastAppendHostTime = nowHost;
        _frameCount = 1;
        [self completeStartOk:true error:""];
        return;
    }

    if (_writer.status != AVAssetWriterStatusWriting) return;
    CVPixelBufferRetain(pixelBuffer);
    if (_lastPixelBuffer) CVPixelBufferRelease(_lastPixelBuffer);
    _lastPixelBuffer = pixelBuffer;

    CMTime relative = CMTimeConvertScale(CMTimeSubtract(samplePts, _firstHostPts),
                                         kRecordingTimescale,
                                         kCMTimeRoundingMethod_RoundHalfAwayFromZero);
    CMTime minNext = CMTimeAdd(_lastAppendedPts, CMTimeMake(90, kRecordingTimescale));
    if (CMTimeCompare(relative, minNext) < 0) {
        relative = minNext;
    }
    if (_writerInput.readyForMoreMediaData &&
        [_adaptor appendPixelBuffer:pixelBuffer withPresentationTime:relative]) {
        _lastAppendedPts = relative;
        _lastAppendHostTime = nowHost;
        _frameCount += 1;
    }
}

- (void)stream:(SCStream*)stream didStopWithError:(NSError*)error {
    (void)stream;
    (void)error;
    dispatch_async(_sampleQueue, ^{
        self->_streamRunning = false;
        if (self->_stopping) return;
        if (!self->_hasFirstFrame) {
            [self failBeforeFirstFrame:"capture_compositor_unavailable"];
        } else if (self->_earlyStopReason.empty()) {
            self->_earlyStopReason = "capture_window_changed";
            if (self->_timer) {
                dispatch_source_cancel(self->_timer);
                self->_timer = nil;
            }
        }
    });
}

- (void)tickHeartbeatWithInvalidReason:(const std::string&)invalidReason {
    if (_stopping) return;
    if (!invalidReason.empty() && _earlyStopReason.empty()) {
        if (!_hasFirstFrame) {
            [self failBeforeFirstFrame:invalidReason];
        } else {
            _earlyStopReason = invalidReason;
            [self stopStreamEarly];
        }
        return;
    }
    if (!_earlyStopReason.empty() || !_hasFirstFrame) return;

    struct stat st {};
    if (_fileURL && ::stat(_fileURL.fileSystemRepresentation, &st) == 0 &&
        st.st_size >= kMaxRawRecordingBytes) {
        _earlyStopReason = "recording_size_limit_exceeded";
        [self stopStreamEarly];
        return;
    }

    CMTime nowHost = CMClockGetTime(CMClockGetHostTimeClock());
    Float64 idleSeconds = CMTimeGetSeconds(CMTimeSubtract(nowHost, _lastAppendHostTime));
    if (idleSeconds < 0.19 || !_lastPixelBuffer ||
        _writer.status != AVAssetWriterStatusWriting ||
        !_writerInput.readyForMoreMediaData) {
        return;
    }
    CMTime relative = CMTimeConvertScale(CMTimeSubtract(nowHost, _firstHostPts),
                                         kRecordingTimescale,
                                         kCMTimeRoundingMethod_RoundHalfAwayFromZero);
    CMTime minNext = CMTimeAdd(_lastAppendedPts, CMTimeMake(900, kRecordingTimescale));
    if (CMTimeCompare(relative, minNext) < 0) {
        relative = minNext;
    }
    if ([_adaptor appendPixelBuffer:_lastPixelBuffer withPresentationTime:relative]) {
        _lastAppendedPts = relative;
        _lastAppendHostTime = nowHost;
        _frameCount += 1;
    }
}

- (void)requestStopWithWaiter:(const std::shared_ptr<StopWaiter>&)waiter {
    dispatch_async(_sampleQueue, ^{
        self->_stopWaiter = waiter;
        if (self->_stopping) return;
        self->_stopping = true;
        if (self->_timer) {
            dispatch_source_cancel(self->_timer);
            self->_timer = nil;
        }
        if (!self->_hasFirstFrame || self->_frameCount == 0) {
            [self failBeforeFirstFrame:(self->_earlyStopReason.empty() ? "recording_empty" : self->_earlyStopReason)];
            return;
        }
        if (self->_streamRunning && self->_stream) {
            self->_streamRunning = false;
            SCStream* activeStream = self->_stream;
            [activeStream stopCaptureWithCompletionHandler:^(NSError* _Nullable) {
                dispatch_async(self->_sampleQueue, ^{
                    [self finalizeWriter];
                });
            }];
        } else {
            [self finalizeWriter];
        }
    });
}

- (void)finalizeWriter {
    if (_finalized) return;
    _finalized = true;

    if (_earlyStopReason.empty() &&
        _hasFirstFrame &&
        _lastPixelBuffer &&
        _writer.status == AVAssetWriterStatusWriting &&
        _writerInput.readyForMoreMediaData) {
        CMTime nowHost = CMClockGetTime(CMClockGetHostTimeClock());
        CMTime tailPts = CMTimeConvertScale(CMTimeSubtract(nowHost, _firstHostPts),
                                            kRecordingTimescale,
                                            kCMTimeRoundingMethod_RoundHalfAwayFromZero);
        CMTime minTail = CMTimeAdd(_lastAppendedPts, CMTimeMake(1800, kRecordingTimescale));
        if (CMTimeCompare(tailPts, minTail) >= 0 &&
            [_adaptor appendPixelBuffer:_lastPixelBuffer withPresentationTime:tailPts]) {
            _lastAppendedPts = tailPts;
            _frameCount += 1;
        }
    }

    if (_writer.status != AVAssetWriterStatusWriting) {
        [_writer cancelWriting];
        if (_fileURL) [[NSFileManager defaultManager] removeItemAtURL:_fileURL error:nil];
        [self completeStopFatal:"capture_compositor_unavailable"];
        return;
    }

    [_writerInput markAsFinished];
    const int64_t finishedAtMs = NowEpochMs();
    [_writer finishWritingWithCompletionHandler:^{
        dispatch_async(self->_sampleQueue, ^{
            struct stat st {};
            if (self->_writer.status != AVAssetWriterStatusCompleted ||
                !self->_fileURL ||
                ::stat(self->_fileURL.fileSystemRepresentation, &st) != 0 ||
                st.st_size <= 0) {
                if (self->_fileURL) [[NSFileManager defaultManager] removeItemAtURL:self->_fileURL error:nil];
                [self completeStopFatal:"recording_empty"];
                return;
            }
            AVURLAsset* asset = [AVURLAsset URLAssetWithURL:self->_fileURL options:nil];
            Float64 assetSec = CMTimeGetSeconds(asset.duration);
            Float64 fallbackSec = CMTimeGetSeconds(self->_lastAppendedPts);
            Float64 sec = (std::isfinite(assetSec) && assetSec > 0.0) ? assetSec : std::max(0.001, fallbackSec);
            int durationMs = std::max(1, static_cast<int>(std::llround(sec * 1000.0)));

            auto waiter = self->_stopWaiter;
            self->_stopWaiter.reset();
            if (self->_onEnded) {
                auto cb = self->_onEnded;
                self->_onEnded = nil;
                cb(self);
            }
            if (waiter) {
                {
                    std::lock_guard<std::mutex> lock(waiter->mu);
                    waiter->done = true;
                    waiter->ok = true;
                    waiter->started_at_ms = self->_startedAtMs;
                    waiter->finished_at_ms = std::max(self->_startedAtMs, finishedAtMs);
                    waiter->duration_ms = durationMs;
                    waiter->width = self->_pixelWidth;
                    waiter->height = self->_pixelHeight;
                    waiter->size = static_cast<int64_t>(st.st_size);
                    waiter->partial_error = self->_earlyStopReason;
                }
                waiter->cv.notify_all();
            }
        });
    }];
}

@end

namespace macapk {

namespace {

std::mutex g_rec_mu;
bool g_rec_starting = false;
API_AVAILABLE(macos(14.4)) MacApkOwnWindowRecordingDelegate* g_active_rec = nil;

}  // namespace

bool WindowRecordingSupported() {
    if (@available(macOS 14.4, *)) {
        Class shareable = NSClassFromString(@"SCShareableContent");
        Class filter = NSClassFromString(@"SCContentFilter");
        Class stream = NSClassFromString(@"SCStream");
        Class writer = NSClassFromString(@"AVAssetWriter");
        if (!shareable || !filter || !stream || !writer) return false;
        return class_getClassMethod(
                   shareable,
                   NSSelectorFromString(@"getCurrentProcessShareableContentWithCompletionHandler:")) != nullptr &&
               class_getInstanceMethod(
                   filter,
                   NSSelectorFromString(@"initWithDesktopIndependentWindow:")) != nullptr &&
               class_getInstanceMethod(
                   stream,
                   NSSelectorFromString(@"initWithFilter:configuration:delegate:")) != nullptr &&
               class_getInstanceMethod(
                   stream,
                   NSSelectorFromString(@"addStreamOutput:type:sampleHandlerQueue:error:")) != nullptr &&
               class_getInstanceMethod(
                   stream,
                   NSSelectorFromString(@"startCaptureWithCompletionHandler:")) != nullptr &&
               class_getInstanceMethod(
                   stream,
                   NSSelectorFromString(@"stopCaptureWithCompletionHandler:")) != nullptr &&
               class_getInstanceMethod(
                   writer,
                   NSSelectorFromString(@"initWithURL:fileType:error:")) != nullptr;
    }
    return false;
}

void WindowRecordingNotifySecureContent() {
    if (@available(macOS 14.4, *)) {
        MacApkOwnWindowRecordingDelegate* active = nil;
        {
            std::lock_guard<std::mutex> lock(g_rec_mu);
            active = g_active_rec;
        }
        if (active) {
            [active abortEarlyWithReason:"capture_secure_window"];
        }
    }
}

std::string WindowRecordingStart(const std::string& recording_id,
                                 const std::string& output_path,
                                 int target_width_px,
                                 int target_height_px) {
    if (!IsValidRecordingId(recording_id)) return JsonError("invalid_recording_id");
    if (output_path.empty()) return JsonError("capture_unavailable");
    if (!WindowRecordingSupported()) return JsonError("recording_unsupported");
    if (CocoaSecureContentActive()) return JsonError("capture_secure_window");
    if (!CocoaViewportFixed()) return JsonError("device_viewport_changed_restart_session");

    if (@available(macOS 14.4, *)) {
        {
            std::lock_guard<std::mutex> lock(g_rec_mu);
            if (g_active_rec != nil && g_active_rec->_recordingId == recording_id && g_active_rec->_hasFirstFrame) {
                std::ostringstream oss;
                oss << "{\"ok\":true,\"startedAt\":" << g_active_rec->_startedAtMs
                    << ",\"width\":" << g_active_rec->_pixelWidth
                    << ",\"height\":" << g_active_rec->_pixelHeight << "}";
                return oss.str();
            }
            if (g_active_rec != nil || g_rec_starting) {
                return JsonError("recording_busy");
            }
            g_rec_starting = true;
        }

        auto waiter = std::make_shared<StartWaiter>();
        NSString* nsRecId = [NSString stringWithUTF8String:recording_id.c_str()];
        NSString* nsOutPath = [NSString stringWithUTF8String:output_path.c_str()];

        dispatch_async(dispatch_get_main_queue(), ^{
            auto failEarly = ^(const std::string& reason) {
                {
                    std::lock_guard<std::mutex> lock(g_rec_mu);
                    g_rec_starting = false;
                }
                [[NSFileManager defaultManager] removeItemAtPath:nsOutPath error:nil];
                {
                    std::lock_guard<std::mutex> lock(waiter->mu);
                    if (!waiter->done) {
                        waiter->done = true;
                        waiter->ok = false;
                        waiter->error = reason;
                    }
                }
                waiter->cv.notify_all();
            };

            if (CocoaSecureContentActive()) {
                failEarly("capture_secure_window");
                return;
            }
            NSWindow* window = (__bridge NSWindow*)CocoaGetPlaceholderWindow();
            if (!window || !window.isVisible || window.isMiniaturized || window.windowNumber <= 0) {
                failEarly("capture_window_unavailable");
                return;
            }
            const NSInteger winNumber = window.windowNumber;
            const NSSize frameSize = window.frame.size;
            const NSRect contentRect = window.contentLayoutRect;
            const CGFloat scale = window.backingScaleFactor;
            if (frameSize.width <= 0 || frameSize.height <= 0 ||
                NSIsEmptyRect(contentRect) ||
                !NSContainsRect(NSMakeRect(0, 0, frameSize.width, frameSize.height), contentRect)) {
                failEarly("capture_window_unavailable");
                return;
            }
            const CGRect sourceRect = CGRectMake(
                contentRect.origin.x,
                frameSize.height - NSMaxY(contentRect),
                contentRect.size.width,
                contentRect.size.height);
            const int pixelWidth = target_width_px >= 2 ? (target_width_px & ~1) : EvenPixels(contentRect.size.width * scale);
            const int pixelHeight = target_height_px >= 2 ? (target_height_px & ~1) : EvenPixels(contentRect.size.height * scale);
            if (pixelWidth < 2 || pixelHeight < 2) {
                failEarly("capture_window_unavailable");
                return;
            }

            [SCShareableContent getCurrentProcessShareableContentWithCompletionHandler:^(
                SCShareableContent* _Nullable shareable, NSError* _Nullable scErr) {
                if (scErr || !shareable) {
                    failEarly("capture_compositor_unavailable");
                    return;
                }
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (CocoaSecureContentActive()) {
                        failEarly("capture_secure_window");
                        return;
                    }
                    if (!window.isVisible || window.isMiniaturized || window.windowNumber != winNumber ||
                        !NSEqualSizes(window.frame.size, frameSize) ||
                        !NSEqualRects(window.contentLayoutRect, contentRect) ||
                        window.backingScaleFactor != scale ||
                        !CocoaViewportFixed()) {
                        failEarly("window_resized");
                        return;
                    }
                    const pid_t selfPid = ::getpid();
                    SCWindow* ownWindow = nil;
                    for (SCWindow* candidate in shareable.windows) {
                        if (candidate.windowID == static_cast<CGWindowID>(winNumber) &&
                            candidate.owningApplication &&
                            candidate.owningApplication.processID == selfPid) {
                            ownWindow = candidate;
                            break;
                        }
                    }
                    if (!ownWindow) {
                        failEarly("capture_window_not_owned");
                        return;
                    }

                    NSURL* fileURL = [NSURL fileURLWithPath:nsOutPath isDirectory:NO];
                    [[NSFileManager defaultManager] removeItemAtURL:fileURL error:nil];
                    NSError* writerErr = nil;
                    AVAssetWriter* writer = [[AVAssetWriter alloc] initWithURL:fileURL
                                                                      fileType:AVFileTypeMPEG4
                                                                         error:&writerErr];
                    if (!writer || writerErr) {
                        failEarly("capture_unavailable");
                        return;
                    }
                    writer.shouldOptimizeForNetworkUse = YES;
                    const int bitrate = std::max(1500000, std::min(8000000, pixelWidth * pixelHeight * 3));
                    NSDictionary* compression = @{
                        AVVideoAverageBitRateKey: @(bitrate),
                        AVVideoAllowFrameReorderingKey: @NO,
                        AVVideoMaxKeyFrameIntervalKey: @(15),
                        AVVideoMaxKeyFrameIntervalDurationKey: @(0.5),
                        AVVideoProfileLevelKey: AVVideoProfileLevelH264MainAutoLevel,
                    };
                    NSDictionary* videoSettings = @{
                        AVVideoCodecKey: AVVideoCodecTypeH264,
                        AVVideoWidthKey: @(pixelWidth),
                        AVVideoHeightKey: @(pixelHeight),
                        AVVideoCompressionPropertiesKey: compression,
                    };
                    AVAssetWriterInput* input = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo
                                                                                   outputSettings:videoSettings];
                    input.expectsMediaDataInRealTime = YES;
                    AVAssetWriterInputPixelBufferAdaptor* adaptor =
                        [AVAssetWriterInputPixelBufferAdaptor
                            assetWriterInputPixelBufferAdaptorWithAssetWriterInput:input
                                                       sourcePixelBufferAttributes:@{
                                                           (NSString*)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
                                                           (NSString*)kCVPixelBufferWidthKey: @(pixelWidth),
                                                           (NSString*)kCVPixelBufferHeightKey: @(pixelHeight),
                                                       }];
                    if (![writer canAddInput:input]) {
                        failEarly("capture_unavailable");
                        return;
                    }
                    [writer addInput:input];
                    if (![writer startWriting]) {
                        failEarly("capture_unavailable");
                        return;
                    }
                    ::chmod(fileURL.fileSystemRepresentation, 0600);
                    [writer startSessionAtSourceTime:kCMTimeZero];

                    std::string queueName = "com.macapk.recording." + std::string(nsRecId.UTF8String);
                    dispatch_queue_t sampleQueue = dispatch_queue_create(
                        queueName.c_str(),
                        dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, 0));

                    auto* session = [[MacApkOwnWindowRecordingDelegate alloc] init];
                    session->_recordingId = nsRecId.UTF8String;
                    session->_fileURL = fileURL;
                    session->_pixelWidth = pixelWidth;
                    session->_pixelHeight = pixelHeight;
                    session->_window = window;
                    session->_windowNumber = winNumber;
                    session->_initialFrameSize = frameSize;
                    session->_initialContentRect = contentRect;
                    session->_initialScale = scale;
                    session->_writer = writer;
                    session->_writerInput = input;
                    session->_adaptor = adaptor;
                    session->_sampleQueue = sampleQueue;
                    session->_startWaiter = waiter;
                    session->_onEnded = ^(MacApkOwnWindowRecordingDelegate* ended) {
                        std::lock_guard<std::mutex> lock(g_rec_mu);
                        if (g_active_rec == ended) {
                            g_active_rec = nil;
                        }
                    };

                    SCContentFilter* filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:ownWindow];
                    SCStreamConfiguration* config = [[SCStreamConfiguration alloc] init];
                    config.sourceRect = sourceRect;
                    config.width = static_cast<size_t>(pixelWidth);
                    config.height = static_cast<size_t>(pixelHeight);
                    config.minimumFrameInterval = CMTimeMake(1, 30);
                    config.queueDepth = 6;
                    config.pixelFormat = kCVPixelFormatType_32BGRA;
                    config.showsCursor = NO;
                    config.ignoreShadowsSingleWindow = YES;
                    config.scalesToFit = YES;
                    config.captureResolution = SCCaptureResolutionBest;

                    SCStream* stream = [[SCStream alloc] initWithFilter:filter
                                                          configuration:config
                                                               delegate:session];
                    NSError* addErr = nil;
                    if (![stream addStreamOutput:session
                                            type:SCStreamOutputTypeScreen
                              sampleHandlerQueue:sampleQueue
                                           error:&addErr] || addErr) {
                        [writer cancelWriting];
                        failEarly("capture_compositor_unavailable");
                        return;
                    }
                    session->_stream = stream;
                    {
                        std::lock_guard<std::mutex> lock(g_rec_mu);
                        g_rec_starting = false;
                        g_active_rec = session;
                    }

                    [stream startCaptureWithCompletionHandler:^(NSError* _Nullable startErr) {
                        if (startErr) {
                            dispatch_async(sampleQueue, ^{
                                [session failBeforeFirstFrame:"capture_compositor_unavailable"];
                            });
                            return;
                        }
                        dispatch_async(sampleQueue, ^{
                            if (session->_stopping) return;
                            session->_streamRunning = true;
                            dispatch_source_t timer = dispatch_source_create(
                                DISPATCH_SOURCE_TYPE_TIMER, 0, 0, sampleQueue);
                            dispatch_source_set_timer(
                                timer,
                                dispatch_time(DISPATCH_TIME_NOW, 200 * NSEC_PER_MSEC),
                                200 * NSEC_PER_MSEC,
                                20 * NSEC_PER_MSEC);
                            __weak MacApkOwnWindowRecordingDelegate* weakSession = session;
                            dispatch_source_set_event_handler(timer, ^{
                                MacApkOwnWindowRecordingDelegate* strongSession = weakSession;
                                if (!strongSession || strongSession->_stopping) return;
                                dispatch_async(dispatch_get_main_queue(), ^{
                                    MacApkOwnWindowRecordingDelegate* mainSession = weakSession;
                                    if (!mainSession || mainSession->_stopping) return;
                                    std::string invalid;
                                    NSWindow* win = mainSession->_window;
                                    if (CocoaSecureContentActive()) {
                                        invalid = "capture_secure_window";
                                    } else if (!win || !win.isVisible || win.isMiniaturized ||
                                               win.windowNumber != mainSession->_windowNumber) {
                                        invalid = "capture_window_unavailable";
                                    } else if (!NSEqualSizes(win.frame.size, mainSession->_initialFrameSize) ||
                                               !NSEqualRects(win.contentLayoutRect, mainSession->_initialContentRect) ||
                                               win.backingScaleFactor != mainSession->_initialScale ||
                                               !CocoaViewportFixed()) {
                                        invalid = "window_resized";
                                    }
                                    dispatch_async(mainSession->_sampleQueue, ^{
                                        [mainSession tickHeartbeatWithInvalidReason:invalid];
                                    });
                                });
                            });
                            session->_timer = timer;
                            dispatch_resume(timer);
                        });
                    }];

                    __weak MacApkOwnWindowRecordingDelegate* weakTimeout = session;
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC), sampleQueue, ^{
                        MacApkOwnWindowRecordingDelegate* strongSession = weakTimeout;
                        if (strongSession && !strongSession->_hasFirstFrame && !strongSession->_stopping) {
                            [strongSession failBeforeFirstFrame:"recording_start_timeout"];
                        }
                    });
                });
            }];
        });

        std::unique_lock<std::mutex> lock(waiter->mu);
        if (!waiter->cv.wait_for(lock, std::chrono::seconds(12), [&] { return waiter->done; })) {
            lock.unlock();
            MacApkOwnWindowRecordingDelegate* active = nil;
            {
                std::lock_guard<std::mutex> g(g_rec_mu);
                g_rec_starting = false;
                if (g_active_rec && g_active_rec->_recordingId == recording_id) {
                    active = g_active_rec;
                    g_active_rec = nil;
                }
            }
            if (active) {
                [active abortEarlyWithReason:"recording_start_timeout"];
            }
            return JsonError("recording_start_timeout");
        }
        if (!waiter->ok) {
            return JsonError(waiter->error.empty() ? "capture_unavailable" : waiter->error);
        }
        std::ostringstream oss;
        oss << "{\"ok\":true,\"startedAt\":" << waiter->started_at_ms
            << ",\"width\":" << waiter->width
            << ",\"height\":" << waiter->height << "}";
        return oss.str();
    }
    return JsonError("recording_unsupported");
}

std::string WindowRecordingStop(const std::string& recording_id) {
    if (!IsValidRecordingId(recording_id)) return JsonError("invalid_recording_id");
    if (!WindowRecordingSupported()) return JsonError("recording_unsupported");

    if (@available(macOS 14.4, *)) {
        MacApkOwnWindowRecordingDelegate* session = nil;
        {
            std::lock_guard<std::mutex> lock(g_rec_mu);
            if (!g_active_rec || g_active_rec->_recordingId != recording_id) {
                return JsonError("recording_not_found");
            }
            session = g_active_rec;
        }
        auto waiter = std::make_shared<StopWaiter>();
        [session requestStopWithWaiter:waiter];
        std::unique_lock<std::mutex> lock(waiter->mu);
        if (!waiter->cv.wait_for(lock, std::chrono::seconds(20), [&] { return waiter->done; })) {
            return JsonError("capture_unavailable");
        }
        if (!waiter->ok) {
            return JsonError(waiter->fatal_error.empty() ? "recording_empty" : waiter->fatal_error);
        }
        std::ostringstream oss;
        oss << "{\"ok\":true,\"startedAt\":" << waiter->started_at_ms
            << ",\"finishedAt\":" << waiter->finished_at_ms
            << ",\"durationMs\":" << waiter->duration_ms
            << ",\"width\":" << waiter->width
            << ",\"height\":" << waiter->height
            << ",\"size\":" << waiter->size;
        if (!waiter->partial_error.empty()) {
            oss << ",\"error\":\"" << waiter->partial_error << "\"";
        }
        oss << "}";
        return oss.str();
    }
    return JsonError("recording_unsupported");
}

namespace windowrecording {

static jboolean JNICALL NativeCocoa_supportsWindowRecording(JNIEnv*, jclass) {
    return WindowRecordingSupported() ? JNI_TRUE : JNI_FALSE;
}

static jstring JNICALL NativeCocoa_startWindowRecording(JNIEnv* env, jclass,
                                                        jstring jRecordingId,
                                                        jstring jOutputPath,
                                                        jint targetWidth,
                                                        jint targetHeight) {
    if (!jRecordingId || !jOutputPath) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"invalid_recording_id\"}");
    }
    const char* rawId = env->GetStringUTFChars(jRecordingId, nullptr);
    const char* rawPath = env->GetStringUTFChars(jOutputPath, nullptr);
    std::string id = rawId ? rawId : "";
    std::string path = rawPath ? rawPath : "";
    if (rawId) env->ReleaseStringUTFChars(jRecordingId, rawId);
    if (rawPath) env->ReleaseStringUTFChars(jOutputPath, rawPath);

    std::string result = WindowRecordingStart(id, path, targetWidth, targetHeight);
    return env->NewStringUTF(result.c_str());
}

static jstring JNICALL NativeCocoa_stopWindowRecording(JNIEnv* env, jclass, jstring jRecordingId) {
    if (!jRecordingId) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"invalid_recording_id\"}");
    }
    const char* rawId = env->GetStringUTFChars(jRecordingId, nullptr);
    std::string id = rawId ? rawId : "";
    if (rawId) env->ReleaseStringUTFChars(jRecordingId, rawId);

    std::string result = WindowRecordingStop(id);
    return env->NewStringUTF(result.c_str());
}

const JNINativeMethod kWindowRecordingMethods[] = {
    {const_cast<char*>("supportsWindowRecording"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(NativeCocoa_supportsWindowRecording)},
    {const_cast<char*>("startWindowRecording"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;II)Ljava/lang/String;"),
     reinterpret_cast<void*>(NativeCocoa_startWindowRecording)},
    {const_cast<char*>("stopWindowRecording"),
     const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(NativeCocoa_stopWindowRecording)},
};

void RegisterWindowRecordingMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/NativeCocoa");
    if (!cls) {
        env->ExceptionClear();
        return;
    }
    if (env->RegisterNatives(cls, kWindowRecordingMethods,
                             sizeof(kWindowRecordingMethods) / sizeof(kWindowRecordingMethods[0])) != JNI_OK) {
        env->ExceptionClear();
        std::fprintf(stderr, "[I] window: framework jar predates NativeCocoa window recording methods\n");
    }
    env->DeleteLocalRef(cls);
}

}  // namespace windowrecording

}  // namespace macapk

#pragma clang diagnostic pop
