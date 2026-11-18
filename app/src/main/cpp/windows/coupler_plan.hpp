#pragma once

#include <algorithm>
#include <cstdint>
#include <numeric>

namespace theia::export_windows {

struct CouplerBatch {
    uint64_t offset_bytes = 0;
    uint64_t size_bytes = 0;
    uint32_t pixels = 0;
};

// Only this pointwise kernel is eligible: no neighbours, pixel coordinates or
// random identity. Both RGB32 planes advance together; all constants stay whole.
inline CouplerBatch coupler_batch(uint64_t first, uint64_t total, uint64_t range_bytes, uint64_t alignment,
                                  uint64_t max_invocations) {
    constexpr uint64_t pixel_bytes = 3 * sizeof(float);
    if (first >= total || total > UINT32_MAX || !alignment || (first * pixel_bytes) % alignment)
        return {};
    const uint64_t quantum = alignment / std::gcd(alignment, pixel_bytes);
    const uint64_t remaining = total - first;
    uint64_t count = std::min({remaining, range_bytes / pixel_bytes, max_invocations, uint64_t(1048576)});
    // Every non-final batch must leave the next descriptor exactly aligned.
    // Rounding the start down would make original shader pixel zero incorrect.
    if (count < remaining)
        count -= count % quantum;
    if (!count)
        return {};
    return {first * pixel_bytes, count * pixel_bytes, uint32_t(count)};
}

} // namespace theia::export_windows
