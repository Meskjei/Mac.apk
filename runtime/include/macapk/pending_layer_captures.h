#pragma once
#include <unordered_map>
#include <vector>

struct PendingLayerCapture {
    void* layer = nullptr;
    const void* drawable = nullptr;
    const char* tag = nullptr;
    unsigned idleTicks = 0;
};

// The caller owns each retained drawable and releases every returned record.
class PendingLayerCaptures {
    std::unordered_map<void*, PendingLayerCapture> pending;
public:
    PendingLayerCapture take(void* layer) {
        auto it = pending.find(layer);
        if (it == pending.end()) return {};
        auto result = it->second;
        pending.erase(it);
        return result;
    }
    const void* put(void* layer, const void* drawable, const char* tag) {
        auto previous = take(layer);
        pending.emplace(layer, PendingLayerCapture{layer, drawable, tag, 0});
        return previous.drawable;
    }
    std::vector<PendingLayerCapture> tick() {
        std::vector<PendingLayerCapture> due;
        for (auto it = pending.begin(); it != pending.end();) {
            if (++it->second.idleTicks >= 3) {
                due.push_back(it->second);
                it = pending.erase(it);
            } else ++it;
        }
        return due;
    }
};
