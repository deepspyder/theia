# Keep the compiler compatibility policy in Android, without changing the
# shared shader modules, specialization constants or desktop backend.
function(theia_android_vulkan_pipelines input output)
    set(source "${input}")
    theia_f14_replace(source "#include \"volk.h\""
        "#include \"volk.h\"\n#include \"vulkan_pipeline_compat.hpp\"" "pipeline compatibility include")
    set(before "        const std::string path = shader_dir_ + \"/\" + key + \".spv\";")
    set(template "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/templates/VulkanPipelineContext.cpp.in")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${template}")
    file(READ "${template}" context)
    set(after "${before}\n${context}")
    theia_f14_replace(source "${before}" "${after}" "pipeline failure context")
    theia_f14_replace(source "error = vk_error(\"vkCreateShaderModule\", result);"
        "error = pipeline_error(result);" "shader module failure context")
    set(before "        result = vkCreateDescriptorSetLayout(device_, &desc_create, nullptr, &p.descriptors);")
    theia_f14_replace(source "${before}" "        creation_stage = \"vkCreateDescriptorSetLayout\";\n${before}" "descriptor failure stage")
    set(before "            result = vkCreatePipelineLayout(device_, &layout_create, nullptr, &p.layout);")
    theia_f14_replace(source "${before}" "            creation_stage = \"vkCreatePipelineLayout\";\n${before}" "layout failure stage")
    set(before "            result = vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &pipeline_create,\n                                              nullptr, &p.pipeline);")
    set(after "            creation_stage = \"vkCreateComputePipelines\";\n            compilation = theia::vulkan_pipeline::create(device_, pipeline_create, key.c_str(), properties_, p.pipeline);\n            result = compilation.result;")
    theia_f14_replace(source "${before}" "${after}" "exact compiler retry")
    theia_f14_replace(source "error = vk_error(\"Vulkan compute pipeline\", result);"
        "error = pipeline_error(result);" "pipeline failure details")
    set(${output} "${source}" PARENT_SCOPE)
endfunction()
