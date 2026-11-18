#pragma once

#include <android/log.h>
#include <sys/system_properties.h>
#include <vulkan/vulkan.h>

#include <cstring>
#include <string>

namespace theia::vulkan_pipeline {

struct Compilation {
    VkResult result;
    VkResult initial;
    bool retried;
};

// Retry only an unknown compiler failure, before any GPU work is submitted.
// The module, entry point, specialization and layout must remain identical.
template <typename Create, typename Destroy>
Compilation compile(VkPipelineCreateFlags flags, VkPipeline& pipeline, Create&& create, Destroy&& destroy) {
    const VkResult initial = create(flags, pipeline);
    if (initial != VK_ERROR_UNKNOWN || (flags & VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT))
        return {initial, initial, false};
    // Vulkan may return a partial handle on failure. Never leak it or pass it
    // back as an input to the second compilation attempt.
    if (pipeline != VK_NULL_HANDLE) {
        destroy(pipeline);
        pipeline = VK_NULL_HANDLE;
    }
    return {create(flags | VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT, pipeline), initial, true};
}

inline Compilation create(VkDevice device, const VkComputePipelineCreateInfo& info, const char* kernel,
                          const VkPhysicalDeviceProperties& properties, VkPipeline& pipeline) {
    char setting[PROP_VALUE_MAX]{};
    // ADB-only fault injection for the exact Release-native compatibility audit.
    // Unset in normal operation; it never changes shader code or arithmetic.
    const bool injected = __system_property_get("debug.theia.pipeline_retry_test", setting) > 0 &&
                          std::strcmp(setting, "1") == 0 && std::strstr(kernel, ".windowed");
    bool first = true;
    const auto result = compile(
        info.flags, pipeline,
        [&](VkPipelineCreateFlags flags, VkPipeline& handle) {
            if (first && injected) {
                first = false;
                return VK_ERROR_UNKNOWN;
            }
            first = false;
            auto attempt = info;
            attempt.flags = flags;
            return vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &attempt, nullptr, &handle);
        },
        [&](VkPipeline handle) { vkDestroyPipeline(device, handle, nullptr); });
    if (result.retried)
        __android_log_print(result.result == VK_SUCCESS ? ANDROID_LOG_WARN : ANDROID_LOG_ERROR, "SpkPipeline",
                            "compiler_retry kernel=%s gpu=%s driver=%u initial=%d result=%d injected=%d", kernel,
                            properties.deviceName, properties.driverVersion, int(result.initial), int(result.result),
                            int(injected));
    return result;
}

inline std::string failure(const char* stage, const std::string& kernel, const VkPhysicalDeviceProperties& properties,
                           VkResult result, const Compilation& compilation) {
    std::string message =
        std::string("Vulkan ") + stage + " failed (VkResult " + std::to_string(int(result)) + "; kernel=" + kernel +
        "; gpu=" + properties.deviceName + "; vendor=" + std::to_string(properties.vendorID) +
        "; device=" + std::to_string(properties.deviceID) + "; driver=" + std::to_string(properties.driverVersion);
    if (compilation.retried)
        message += "; compiler_retry=" + std::to_string(int(compilation.initial)) + "->" +
                   std::to_string(int(compilation.result));
    return message + ")";
}

} // namespace theia::vulkan_pipeline
