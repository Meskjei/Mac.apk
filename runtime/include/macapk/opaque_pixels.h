#pragma once
#include <cstddef>
#include <cstdint>

// Padding is not pixel data; one transparent pixel prevents occlusion culling.
inline bool MacapkAlpha8Opaque(const uint8_t* pixels, size_t width, size_t height,
                               size_t stride, size_t alphaOffset) {
    if (!pixels || !width || !height || alphaOffset > 3 || width > stride / 4) return false;
    for (size_t y = 0; y < height; ++y)
        for (size_t x = 0; x < width; ++x)
            if (pixels[y * stride + x * 4 + alphaOffset] != 255) return false;
    return true;
}
