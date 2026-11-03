#include "macapk/egl_extensions.h"
#include <cassert>
#include <thread>
#include <vector>

int main(int argc, char**) {
    const char* all = "EGL_KHR_surfaceless_context EGL_EXT_buffer_age EGL_KHR_partial_update EGL_KHR_swap_buffers_with_damage EGL_OTHER EGL_EXT_buffer_age_suffix";
    assert(MacapkFilterEglExtensions(all, false, false) == all);
    assert(MacapkFilterEglExtensions("  EGL_KHR_partial_update   EGL_OTHER ", false, true) == "EGL_OTHER");
    assert(MacapkFilterEglExtensions("EGL_EXT_buffer_age_suffix", false, true) == "EGL_EXT_buffer_age_suffix");
    assert(MacapkFilterEglExtensions("EGL_KHR_partial_update_suffix", false, true) == "EGL_KHR_partial_update_suffix");
    assert(MacapkFilterEglExtensions(all, true, true) == "EGL_EXT_buffer_age EGL_KHR_swap_buffers_with_damage EGL_OTHER EGL_EXT_buffer_age_suffix");
    assert(MacapkFilterEglExtensions("", true, true).empty());
    const bool enabled = argc > 1;
    const char* result = MacapkEglExtensionsForBridge(all, true, 0x3055);
    if (!enabled) { assert(result == all); return 0; }
    assert(std::string(result) == MacapkFilterEglExtensions(all, true, true));
    assert(MacapkEglExtensionsForBridge(nullptr, true, 0x3055) == nullptr);
    assert(MacapkEglExtensionsForBridge(all, false, 0x3055) == all);
    assert(MacapkEglExtensionsForBridge(all, true, 0x3053) == all);
    std::vector<std::thread> readers;
    for (int i = 0; i < 8; ++i) readers.emplace_back([&] {
        for (int j = 0; j < 100; ++j) {
            std::string transient = all;
            assert(MacapkEglExtensionsForBridge(transient.c_str(), true, 0x3055) == result);
            MacapkEglExtensionsForBridge("EGL_OTHER EGL_KHR_partial_update", true, 0x3055);
            assert(std::string(result) == "EGL_EXT_buffer_age EGL_KHR_swap_buffers_with_damage EGL_OTHER EGL_EXT_buffer_age_suffix");
        }
    });
    for (auto& reader : readers) reader.join();
}
