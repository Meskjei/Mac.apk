#include "macapk/webview_capture.h"
#include <cassert>
#include <cstdio>
#include <fstream>

static bool spinUntil(bool (^test)()) {
    NSDate* end = [NSDate dateWithTimeIntervalSinceNow:15];
    while (!test() && end.timeIntervalSinceNow > 0)
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    return test();
}

int main(int argc, char** argv) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        NSWindow* window = [[[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 100, 100)
            styleMask:NSWindowStyleMaskBorderless backing:NSBackingStoreBuffered defer:NO] autorelease];
        NSView* root = window.contentView; root.wantsLayer = YES;
        WKWebView* web = [[[WKWebView alloc] initWithFrame:NSMakeRect(20, 20, 60, 60)] autorelease];
        [root addSubview:web];
        [window orderBack:nil];
        [web loadHTMLString:@"<html><body style='margin:0;background:rgb(255,0,0)'>capture</body></html>" baseURL:nil];
        assert(spinUntil(^bool { return !web.loading && web.URL != nil; }));
        MacapkWebViewCapture capture;
        auto* reader = &capture;
        assert(capture.prepare(root, 1) == MacapkWebViewCapture::State::pending);
        assert(spinUntil(^bool { return reader->prepare(root, 1) != MacapkWebViewCapture::State::pending; }));
        assert(capture.prepare(root, 1) == MacapkWebViewCapture::State::ready);
        uint8_t pixels[100 * 100 * 4] = {};
        CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
        CGContextRef ctx = CGBitmapContextCreate(pixels, 100, 100, 8, 400, cs,
            kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
        assert(capture.draw(ctx, web.layer, CGRectMake(20, 20, 60, 60)));
        size_t at = (50 * 100 + 50) * 4;
        assert(pixels[at] > 240 && pixels[at+1] < 10 && pixels[at+2] < 10);
        assert(!capture.draw(ctx, root.layer, CGRectMake(0, 0, 100, 100)));
        web.hidden = YES;
        assert(capture.prepare(root, 2) == MacapkWebViewCapture::State::ready);
        assert(!capture.draw(ctx, web.layer, CGRectMake(20, 20, 60, 60)));
        web.hidden = NO;
        assert(capture.prepare(root, 3) == MacapkWebViewCapture::State::pending);
        web.frame = NSMakeRect(10, 10, 60, 60);
        assert(capture.prepare(root, 3) == MacapkWebViewCapture::State::failed);
        assert(spinUntil(^bool { return reader->prepare(root, 4) != MacapkWebViewCapture::State::pending; }));
        assert(capture.prepare(root, 4) == MacapkWebViewCapture::State::ready);
        [web loadHTMLString:@"<html><body>new page</body></html>" baseURL:[NSURL URLWithString:@"https://fixture.invalid/new"]];
        assert(spinUntil(^bool { return !web.loading && [web.URL.absoluteString containsString:@"fixture.invalid/new"]; }));
        assert(capture.prepare(root, 4) == MacapkWebViewCapture::State::failed);
        // A previous asynchronous callback cannot restore a discarded request's image.
        capture.clear(); web.hidden = YES;
        assert(capture.prepare(root, 5) == MacapkWebViewCapture::State::ready);
        assert(!capture.draw(ctx, web.layer, CGRectMake(20, 20, 60, 60)));
        CGContextRelease(ctx); CGColorSpaceRelease(cs);
        [window orderOut:nil];
        std::puts("Native WebKit capture: pixels, hidden views, geometry changes and fresh requests passed");
        if (argc > 1) std::ofstream(argv[1]) << "passed\n";
    }
}
