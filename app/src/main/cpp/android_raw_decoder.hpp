#pragma once

#include <libraw/libraw.h>

#include <cstring>
#include <functional>
#include <string>
#include <utility>
#include <array>

namespace theia::raw {

// Upstream exposes HE tile bodies specifically for custom loop scheduling. Keep
// its three-pass arithmetic/order, with cancellation at each tile on this thread.
class Decoder final : public LibRaw {
public:
    std::array<float, 12> output_matrix{};
    explicit Decoder(std::function<bool()> cancelled = {})
        : LibRaw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK), cancelled_(std::move(cancelled)) {}

    bool supportedCompression(std::string& error) {
        // Converted DNGs may retain Nikon MakerNotes but use a DNG decoder.
        if (imgdata.idata.dng_version != 0) return true;
        const auto compression = imgdata.makernotes.nikon.NEFCompression;
        if (imgdata.idata.maker_index != LIBRAW_CAMERAMAKER_Nikon ||
            (compression != 13 && compression != 14)) return true;
        const char* decoder = unpack_function_name();
        if (decoder && std::strcmp(decoder, "nikon_he_load_raw()") == 0) return true;
        error = "RAW: 当前 NEF 高效率压缩未被正确识别，已停止解码以避免损坏画面";
        return false;
    }

protected:
    void convert_to_rgb_loop(float out_cam[3][4]) override {
        for (int row = 0; row < 3; ++row)
            for (int column = 0; column < 4; ++column)
                output_matrix[row * 4 + column] = out_cam[row][column];
        LibRaw::convert_to_rgb_loop(out_cam);
    }
    int nikonhe_decode_loop1(int tiles, int height, const void* data, void* decoder) override {
        for (int tile = 0; tile < tiles; ++tile) {
            checkRequested();
            const int status = nikonhe_decode_loop1_body(tile, tiles, height, data, decoder);
            if (status) return status;
        }
        checkRequested();
        return 0;
    }

    int nikonhe_decode_loop2(int tiles, int height, void* decoder) override {
        for (int tile = 0; tile < tiles; ++tile) {
            checkRequested();
            const int status = nikonhe_decode_loop2_body(tile, tiles, height, decoder);
            if (status) return status;
        }
        checkRequested();
        return 0;
    }

    int nikonhe_decode_loop3(int tiles, int height, void* decoder) override {
        for (int tile = 0; tile < tiles; ++tile) {
            checkRequested();
            const int status = nikonhe_decode_loop3_body(tile, tiles, height, decoder);
            if (status) return status;
        }
        checkRequested();
        return 0;
    }

private:
    void checkRequested() {
        if (cancelled_ && cancelled_()) throw LIBRAW_EXCEPTION_CANCELLED_BY_CALLBACK;
        checkCancel();
    }

    std::function<bool()> cancelled_;
};

}  // namespace theia::raw
