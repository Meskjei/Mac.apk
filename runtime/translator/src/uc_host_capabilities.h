#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

// This core unconditionally CHECKs Linux's crash-report socket before it can use
// a host configuration. Reject its load so UC can choose its bundled fallback.
inline bool macapk_uc_needs_seqpacket(const char* name, const uint8_t* notes, size_t size) {
    static constexpr uint8_t build[] = {
        0xf6,0x6c,0x0e,0x11,0x70,0x21,0xdf,0x90,0x6a,0x01,
        0x19,0x68,0xed,0x69,0xdd,0x9e,0xf3,0xaa,0x1f,0xba
    };
    if (!name || !std::strstr(name, "libwebviewuc.so")) return false;
    for (size_t at = 0; size - at >= 12;) {
        uint32_t names, bytes, type;
        std::memcpy(&names, notes + at, 4);
        std::memcpy(&bytes, notes + at + 4, 4);
        std::memcpy(&type, notes + at + 8, 4);
        at += 12;
        const size_t paddedName = (size_t(names) + 3) & ~size_t(3);
        const size_t paddedData = (size_t(bytes) + 3) & ~size_t(3);
        if (paddedName > size - at || paddedData > size - at - paddedName) return false;
        if (type == 3 && names == 4 && bytes == sizeof(build)
                && !std::memcmp(notes + at, "GNU\0", 4)
                && !std::memcmp(notes + at + paddedName, build, sizeof(build))) return true;
        at += paddedName + paddedData;
    }
    return false;
}
