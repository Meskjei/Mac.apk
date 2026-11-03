#include "macapk/opaque_pixels.h"
#include <cassert>
#include <vector>
int main() {
    std::vector<uint8_t> pixels(24, 0);
    pixels[3] = pixels[7] = pixels[15] = pixels[19] = 255;
    assert(MacapkAlpha8Opaque(pixels.data(), 2, 2, 12, 3));
    pixels[19] = 254;
    assert(!MacapkAlpha8Opaque(pixels.data(), 2, 2, 12, 3));
    assert(!MacapkAlpha8Opaque(nullptr, 2, 2, 12, 3));
    assert(!MacapkAlpha8Opaque(pixels.data(), 4, 2, 12, 3));
    assert(!MacapkAlpha8Opaque(pixels.data(), 2, 2, 12, 4));
}
