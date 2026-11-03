#include "macapk/pending_layer_captures.h"
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>

int main() {
    PendingLayerCaptures pending;
    int layers[8], drawables[8];
    for (int i = 0; i < 8; ++i) assert(!pending.put(&layers[i], &drawables[i], "layer"));
    for (int i = 0; i < 8; ++i) {
        auto got = pending.take(&layers[i]);
        assert(got.layer == &layers[i] && got.drawable == &drawables[i]);
        assert(!pending.take(&layers[i]).drawable);
    }
    for (uintptr_t i = 1; i < 1000; ++i) {
        auto key = reinterpret_cast<void*>(i);
        assert(!pending.put(key, &drawables[0], "layer2"));
        assert(pending.take(key).drawable == &drawables[0]);
    }
    pending.put(&layers[0], &drawables[0], "layer");
    assert(pending.tick().empty());
    assert(pending.tick().empty());
    assert(pending.put(&layers[0], &drawables[1], "layer2") == &drawables[0]);
    assert(pending.tick().empty()); // Replacing a request resets its idle grace.
    assert(pending.tick().empty());
    auto due = pending.tick();
    assert(due.size() == 1 && due[0].drawable == &drawables[1]);
    assert(std::strcmp(due[0].tag, "layer2") == 0);
    assert(pending.tick().empty() && !pending.take(&layers[0]).drawable);
    std::cout << "Capture retention: eight layers, repeated transitions, ownership and idle grace passed\n";
}
