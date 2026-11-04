#include "../runtime/translator/src/uc_host_capabilities.h"
#include <cassert>
#include <vector>
#include <cstdio>

int main() {
    const std::vector<uint8_t> note = {
        4,0,0,0,20,0,0,0,3,0,0,0,'G','N','U',0,
        0xf6,0x6c,0x0e,0x11,0x70,0x21,0xdf,0x90,0x6a,0x01,
        0x19,0x68,0xed,0x69,0xdd,0x9e,0xf3,0xaa,0x1f,0xba
    };
    assert(macapk_uc_needs_seqpacket("libwebviewuc.so", note.data(), note.size()));
    assert(!macapk_uc_needs_seqpacket("another.so", note.data(), note.size()));
    assert(!macapk_uc_needs_seqpacket(nullptr, note.data(), note.size()));
    for (size_t n = 0; n < note.size(); ++n)
        assert(!macapk_uc_needs_seqpacket("libwebviewuc.so", note.data(), n));
    auto other = note; other.back() ^= 1;
    assert(!macapk_uc_needs_seqpacket("libwebviewuc.so", other.data(), other.size()));
    other = note; other[0] = 255;
    assert(!macapk_uc_needs_seqpacket("libwebviewuc.so", other.data(), other.size()));
    other = note; other[4] = 255;
    assert(!macapk_uc_needs_seqpacket("libwebviewuc.so", other.data(), other.size()));
    std::vector<uint8_t> prefixed(12, 0); prefixed.insert(prefixed.end(), note.begin(), note.end());
    assert(macapk_uc_needs_seqpacket("libwebviewuc.so", prefixed.data(), prefixed.size()));
    std::puts("UC capability identity, note alignment, malformed/truncated and unrelated image checks passed");
}
