#pragma once

#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>

inline std::string MacapkFilterEglExtensions(const char* extensions, bool noSurfaceless,
                                              bool noPartialUpdate) {
    std::string filtered;
    const char* cursor = extensions;
    while (*cursor) {
        while (*cursor == ' ') ++cursor;
        const char* start = cursor;
        while (*cursor && *cursor != ' ') ++cursor;
        std::string token(start, cursor);
        if (token.empty() || (noSurfaceless && token == "EGL_KHR_surfaceless_context")
            || (noPartialUpdate && token == "EGL_KHR_partial_update")) continue;
        if (!filtered.empty()) filtered += ' ';
        filtered += token;
    }
    return filtered;
}

// A bridge profile may promise fewer capabilities than its host EGL driver.
// Keep the returned EGL string valid across concurrent queries and source buffers.
inline const char* MacapkEglExtensionsForBridge(const char* extensions, bool hasDisplay, int name) {
    static const bool noSurfaceless = [] {
        const char* e = std::getenv("MACAPK_EGL_SURFACELESS_CONTEXT");
        return e && std::strcmp(e, "0") == 0;
    }();
    static const bool noPartialUpdate = [] {
        const char* e = std::getenv("MACAPK_EGL_PARTIAL_UPDATE");
        return e && std::strcmp(e, "0") == 0;
    }();
    if (!extensions || !hasDisplay || name != 0x3055 /* EGL_EXTENSIONS */
        || (!noSurfaceless && !noPartialUpdate)) return extensions;
    static std::mutex mutex;
    static std::map<std::string, std::string> strings;
    std::lock_guard<std::mutex> lock(mutex);
    auto found = strings.find(extensions);
    if (found == strings.end()) found = strings.emplace(extensions,
        MacapkFilterEglExtensions(extensions, noSurfaceless, noPartialUpdate)).first;
    return found->second.c_str();
}
