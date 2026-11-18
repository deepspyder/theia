#include "vulkan_pipeline_compat.hpp"

#include <array>
#include <iostream>
#include <stdexcept>

static void require(bool ok) {
    if (!ok)
        throw std::runtime_error("Vulkan compiler retry contract failed");
}

int main() {
    using namespace theia::vulkan_pipeline;
    constexpr VkPipelineCreateFlags extra = VK_PIPELINE_CREATE_ALLOW_DERIVATIVES_BIT;
    const auto partial = reinterpret_cast<VkPipeline>(uintptr_t(1));
    const auto valid = reinterpret_cast<VkPipeline>(uintptr_t(2));
    for (const VkResult initial : {VK_SUCCESS, VK_ERROR_UNKNOWN, VK_ERROR_DEVICE_LOST, VK_ERROR_OUT_OF_HOST_MEMORY,
                                   VK_ERROR_OUT_OF_DEVICE_MEMORY}) {
        for (const VkResult secondary : {VK_SUCCESS, VK_ERROR_UNKNOWN, VK_ERROR_DEVICE_LOST}) {
            for (const bool already_disabled : {false, true}) {
                VkPipeline handle = VK_NULL_HANDLE;
                std::array<VkPipelineCreateFlags, 2> seen{};
                size_t calls = 0, destroyed = 0;
                const auto flags = extra | (already_disabled ? VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT : 0);
                const auto result = compile(
                    flags, handle,
                    [&](VkPipelineCreateFlags attempt_flags, VkPipeline& destination) {
                        require(calls < seen.size());
                        seen[calls] = attempt_flags;
                        const auto status = calls++ == 0 ? initial : secondary;
                        require(destination == VK_NULL_HANDLE);
                        destination = status == VK_SUCCESS ? valid : partial;
                        return status;
                    },
                    [&](VkPipeline old) {
                        require(old == partial);
                        ++destroyed;
                    });
                const bool retry = initial == VK_ERROR_UNKNOWN && !already_disabled;
                require(result.retried == retry && result.initial == initial);
                require(result.result == (retry ? secondary : initial));
                require(calls == (retry ? 2u : 1u) && destroyed == (retry ? 1u : 0u));
                require(seen[0] == flags);
                if (retry)
                    require(seen[1] == (flags | VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT));
            }
        }
    }
    VkPhysicalDeviceProperties properties{};
    std::strcpy(properties.deviceName, "audit GPU");
    const auto error = failure("vkCreateComputePipelines", "spk_overscan_add_mask.windowed", properties,
                               VK_ERROR_UNKNOWN, {VK_ERROR_UNKNOWN, VK_ERROR_UNKNOWN, true});
    require(error.find("kernel=spk_overscan_add_mask.windowed") != std::string::npos);
    require(error.find("compiler_retry=-13->-13") != std::string::npos);
    require(error.find("gpu=audit GPU") != std::string::npos);
    std::cout << "30 compiler outcomes and failure context passed\n";
}
