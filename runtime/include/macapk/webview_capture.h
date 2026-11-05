#pragma once
#import <AppKit/AppKit.h>
#import <WebKit/WebKit.h>
#include <memory>
#include <vector>

// WebKit owns remote layers: their pixels must come from its asynchronous snapshot API.
class MacapkWebViewCapture {
public:
    enum class State { pending, ready, failed };
private:
    struct Entry {
        WKWebView* view;
        NSString* url;
        NSRect frame;
        CGImageRef image = nullptr;
        Entry(WKWebView* v, NSView* root) : view([v retain]), url([v.URL.absoluteString copy]),
            frame([v convertRect:v.bounds toView:root]) {}
        ~Entry() { [view release]; [url release]; if (image) CGImageRelease(image); }
    };
    struct Batch {
        NSView* root;
        uint64_t key;
        size_t pending = 0;
        bool failed = false;
        std::vector<std::shared_ptr<Entry>> entries;
        Batch(NSView* v, uint64_t k) : root([v retain]), key(k) {}
        ~Batch() { [root release]; }
    };
    std::shared_ptr<Batch> batch;
    static void collect(NSView* view, NSView* root, std::vector<WKWebView*>& result) {
        if (view.hidden || view.alphaValue <= 0.001 || view.window != root.window) return;
        if ([view isKindOfClass:[WKWebView class]]) {
            NSRect frame = [view convertRect:view.bounds toView:root];
            if (NSIntersectsRect(frame, root.bounds)) result.push_back((WKWebView*)view);
            return;
        }
        for (NSView* child in view.subviews) collect(child, root, result);
    }
public:
    void clear() { batch.reset(); }
    bool hasWebViews() const { return batch && !batch->entries.empty(); }
    State prepare(NSView* root, uint64_t key) {
        std::vector<WKWebView*> views;
        collect(root, root, views);
        if (!batch || batch->key != key || batch->root != root) {
            batch = std::make_shared<Batch>(root, key);
            for (WKWebView* view : views) {
                // shortcut: reject nested/transformed/masked web surfaces until their layer composition is supported.
                if (view.layer.superlayer != root.layer || view.layer.mask ||
                    !CATransform3DIsIdentity(view.layer.transform)) { batch->failed = true; continue; }
                auto entry = std::make_shared<Entry>(view, root);
                batch->entries.push_back(entry);
                batch->pending++;
                auto current = batch;
                WKSnapshotConfiguration* config = [[[WKSnapshotConfiguration alloc] init] autorelease];
                config.rect = view.bounds;
                config.afterScreenUpdates = YES;
                [view takeSnapshotWithConfiguration:config completionHandler:^(NSImage* image, NSError* error) {
                    const bool sameURL = (entry->url == nil && view.URL == nil) ||
                        [entry->url isEqualToString:view.URL.absoluteString];
                    if (!error && image && view.window == current->root.window && !view.hidden && sameURL &&
                        NSEqualRects(entry->frame, [view convertRect:view.bounds toView:current->root])) {
                        CGImageRef pixels = [image CGImageForProposedRect:nullptr context:nil hints:nil];
                        if (pixels) entry->image = CGImageRetain(pixels);
                    }
                    if (!entry->image) current->failed = true;
                    current->pending--;
                }];
            }
        }
        if (views.size() != batch->entries.size()) batch->failed = true;
        for (size_t i = 0; i < views.size() && i < batch->entries.size(); ++i) {
            auto entry = batch->entries[i];
            const bool sameURL = (entry->url == nil && views[i].URL == nil) ||
                [entry->url isEqualToString:views[i].URL.absoluteString];
            if (entry->view != views[i] || !sameURL || !NSEqualRects(entry->frame, [views[i] convertRect:views[i].bounds toView:root]))
                batch->failed = true;
        }
        return batch->failed ? State::failed : batch->pending ? State::pending : State::ready;
    }
    bool draw(CGContextRef context, CALayer* layer, CGRect rect) const {
        if (!batch || batch->failed || batch->pending) return false;
        for (const auto& entry : batch->entries) if (entry->view.layer == layer && entry->image) {
            CGContextDrawImage(context, rect, entry->image);
            return true;
        }
        return false;
    }
};
