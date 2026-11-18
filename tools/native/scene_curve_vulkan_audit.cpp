// Device-only contract audit. Not linked into either production APK.
#include "gpu/gpu.hpp"
#include "scene_curve/scene_curve_wire.hpp"
#include "export_execution.hpp"
#include "bounded_dispatch_stats.hpp"
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

int main(int argc, char** argv) {
    try {
        if (argc != 6)
            throw std::runtime_error("shader-dir input.bin table.txt output.bin windowed");
        std::ifstream binary(argv[2], std::ios::binary);
        uint32_t pixels = 0;
        binary.read(reinterpret_cast<char*>(&pixels), sizeof pixels);
        if (!pixels || pixels > 1024u * 1024u)
            throw std::runtime_error("Invalid audit dimensions");
        float parameters[7]{};
        binary.read(reinterpret_cast<char*>(parameters), sizeof parameters);
        std::vector<float> input(size_t(pixels) * 3), output(input.size());
        binary.read(reinterpret_cast<char*>(input.data()), input.size() * sizeof(float));
        if (!binary)
            throw std::runtime_error("Truncated audit input");
        std::ifstream text_file(argv[3]);
        std::string text((std::istreambuf_iterator<char>(text_file)), {}), error;
        size_t samples = 0;
        std::vector<float> values;
        if (!theia::scene_curve::parse(text, values, samples, error) || !samples)
            throw std::runtime_error(error);
        parameters[0] = float(samples);
        std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1], error));
        if (!gpu)
            throw std::runtime_error(error);
        gpu->begin_frame();
        {
            auto source = gpu->upload(input.data(), input.size() * sizeof(float), error);
            auto settings = gpu->upload(parameters, sizeof parameters, error);
            auto table = gpu->upload(values.data(), values.size() * sizeof(float), error);
            auto result = gpu->alloc(output.size() * sizeof(float), error);
            if (!source || !settings || !table || !result)
                throw std::runtime_error(error);
            const auto dispatch = [&]() {
                const uint32_t count[] = {pixels};
                using spk::gpu::Arg;
                if (!gpu->dispatch("spk_scene_curve",
                                   {Arg::buf(source), Arg::buf(settings), Arg::buf(table), Arg::inline_bytes(count, 1),
                                    Arg::buf(result)},
                                   pixels, error) ||
                    !gpu->flush(error) ||
                    !gpu->read(result.get(), 0, output.data(), output.size() * sizeof(float), error))
                    throw std::runtime_error(error);
            };
            if (std::string(argv[5]) == "1") {
                theia::export_execution::Scope scope;
                dispatch();
                const auto stats = theia::export_execution::dispatch_stats;
                std::cout << "windowed submissions=" << stats.submissions
                          << " max_binding_bytes=" << stats.largest_binding_bytes << '\n';
                if (pixels > 32768 && stats.submissions < 2)
                    throw std::runtime_error("Audit did not cross a descriptor window");
            } else
                dispatch();
        }
        gpu->end_frame();
        std::ofstream destination(argv[4], std::ios::binary);
        destination.write(reinterpret_cast<const char*>(output.data()), output.size() * sizeof(float));
        if (!destination)
            throw std::runtime_error("Cannot write audit result");
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
