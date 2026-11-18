#include "windows/coupler_plan.hpp"

#include <array>
#include <iostream>
#include <stdexcept>
#include <vector>

static void require(bool value) {
    if (!value)
        throw std::runtime_error("Descriptor-local DIR plan contract failed");
}

int main() {
    using theia::export_windows::coupler_batch;
    size_t plans = 0;
    for (uint64_t alignment : {4, 16, 64, 96, 256, 4096}) {
        for (uint64_t limit : {262144, 8388608, 67108864}) {
            for (uint64_t invocations : {256, 65536, 16776960}) {
                for (uint64_t total : std::array<uint64_t, 7>{1, 17, 257, 65537, 1048577, 24498560, UINT32_MAX}) {
                    uint64_t first = 0;
                    while (first < total) {
                        const auto batch = coupler_batch(first, total, limit, alignment, invocations);
                        // A deliberately tiny launch cannot advance by a full
                        // 4096-byte alignment quantum. Fail closed, never loop.
                        if (!batch.pixels) {
                            require(alignment == 4096 && invocations == 256 && total > 256);
                            break;
                        }
                        require(batch.offset_bytes == first * 12 && batch.offset_bytes % alignment == 0);
                        require(batch.size_bytes == uint64_t(batch.pixels) * 12 && batch.size_bytes <= limit);
                        require(batch.pixels <= invocations && batch.pixels <= 1048576);
                        require(first + batch.pixels <= total);
                        first += batch.pixels;
                    }
                    ++plans;
                }
            }
        }
    }
    require(!coupler_batch(0, 0, 1024, 64, 256).pixels);
    require(!coupler_batch(1, 1000, 1024, 64, 256).pixels);
    require(!coupler_batch(0, 1000, 11, 64, 256).pixels);
    require(!coupler_batch(0, 1000, 1024, 0, 256).pixels);
    require(!coupler_batch(0, uint64_t(UINT32_MAX) + 1, 1024, 64, 256).pixels);

    // Simulate original shader indexing and its padded workgroup tail against
    // exact descriptors; odd totals exercise partial pixels and final batches.
    for (uint64_t alignment : {4, 64, 256}) {
        constexpr uint64_t count = 8193;
        std::vector<uint32_t> output(count * 3, UINT32_MAX);
        for (uint64_t first = 0; first < count;) {
            const auto batch = coupler_batch(first, count, 6144, alignment, 1024);
            require(batch.pixels > 0);
            const uint64_t padded = (uint64_t(batch.pixels) + 255) / 256 * 256;
            for (uint64_t local = 0; local < padded; ++local) {
                if (local >= batch.pixels)
                    continue;
                for (uint64_t channel = 0; channel < 3; ++channel) {
                    const auto at = batch.offset_bytes / 4 + 3 * local + channel;
                    require(at < output.size() && output[at] == UINT32_MAX);
                    output[at] = uint32_t(at);
                }
            }
            first += batch.pixels;
        }
        for (size_t i = 0; i < output.size(); ++i)
            require(output[i] == i);
    }
    std::cout << plans << " DIR plans, invalid contracts and exact once-only RGB tail coverage passed\n";
}
