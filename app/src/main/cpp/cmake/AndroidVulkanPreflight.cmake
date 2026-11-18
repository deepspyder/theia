# ADB-only audit: compile the complete kernel set without reading a photograph.
# Temporary pipelines are destroyed before ordinary engine startup continues.
function(theia_android_vulkan_preflight input output)
    set(source "${input}")
    set(before "        if (result != VK_SUCCESS) { error = vk_error(\"vkCreateCommandPool\", result); return false; }\n        return true;")
    set(template "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/templates/VulkanPreflight.cpp.in")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${template}")
    file(READ "${template}" preflight)
    set(after "        if (result != VK_SUCCESS) { error = vk_error(\"vkCreateCommandPool\", result); return false; }\n${preflight}        return true;")
    theia_f14_replace(source "${before}" "${after}" "diagnostic complete pipeline preflight")
    set(${output} "${source}" PARENT_SCOPE)
endfunction()
