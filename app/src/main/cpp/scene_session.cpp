#include <jni.h>
#include <android/bitmap.h>
#include <spektrafilm/spk_engine.h>
#include "native_pixels.hpp"
#include "core/json.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Matrix = std::array<double, 9>;
struct SceneSession {
    NativeRawPixels source;
    Matrix perspective{};
    spk::Json params;
    std::atomic<bool> cancelled{false};
    std::atomic<uint32_t> progress{0};
};
struct SceneResult {
    uint32_t width = 0, height = 0;
    std::vector<uint16_t> pixels;
};

void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck())
        return;
    const auto cls = env->FindClass("com/spektralab/android/engine/SpkException");
    if (cls)
        env->ThrowNew(cls, message);
}
void out_of_memory(JNIEnv* env) {
    if (env->ExceptionCheck())
        return;
    const auto cls = env->FindClass("java/lang/OutOfMemoryError");
    if (cls)
        env->ThrowNew(cls, "Insufficient scene pixel memory");
}
spk::Json parse(JNIEnv* env, jstring text) {
    const char* raw = env->GetStringUTFChars(text, nullptr);
    if (!raw)
        throw std::runtime_error("Scene parameters unavailable");
    const std::string value(raw);
    env->ReleaseStringUTFChars(text, raw);
    spk::Json result;
    std::string error;
    if (!spk::Json::parse(value, result, error) || !result.is_object())
        throw std::runtime_error("Invalid scene parameters");
    return result;
}
double number(const spk::Json& json, const char* key, double fallback, double low, double high) {
    const auto& value = json.at(key);
    if (value.is_null())
        return fallback;
    if (!value.is_number() || !std::isfinite(value.as_double()) || value.as_double() < low || value.as_double() > high)
        throw std::runtime_error(std::string("Invalid scene geometry: ") + key);
    return value.as_double();
}
std::array<double, 2> source_point(const Matrix& matrix, double x, double y) {
    const double z = matrix[6] * x + matrix[7] * y + matrix[8];
    if (!std::isfinite(z) || z <= 1e-12)
        throw std::runtime_error("Invalid perspective denominator");
    return {(matrix[0] * x + matrix[1] * y + matrix[2]) / z, (matrix[3] * x + matrix[4] * y + matrix[5]) / z};
}
float sample(const NativeRawPixels& source, double x, double y, unsigned channel) {
    x = std::clamp(x, 0.0, double(source.width - 1));
    y = std::clamp(y, 0.0, double(source.height - 1));
    const auto x0 = uint32_t(x), y0 = uint32_t(y);
    const auto x1 = std::min(x0 + 1, source.width - 1), y1 = std::min(y0 + 1, source.height - 1);
    const double fx = x - x0, fy = y - y0;
    const auto at = [&](uint32_t px, uint32_t py) {
        return source.rgb[(size_t(py) * source.width + px) * 3 + channel];
    };
    return float((at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy);
}
void check_ingress(JNIEnv* env, jobject cancellation) {
    const auto cls = env->GetObjectClass(cancellation);
    const auto get = cls ? env->GetMethodID(cls, "get", "()Z") : nullptr;
    const bool cancelled = get && env->CallBooleanMethod(cancellation, get);
    if (cls)
        env->DeleteLocalRef(cls);
    if (env->ExceptionCheck() || cancelled)
        throw std::runtime_error("Scene ingress cancelled");
}
NativeRawPixels warp(const NativeRawPixels& source, const Matrix& matrix, JNIEnv* env, jobject cancellation) {
    NativeRawPixels result;
    result.width = source.width;
    result.height = source.height;
    result.rgb.resize(source.rgb.size());
    for (uint32_t y = 0; y < source.height; ++y) {
        if (y % 16 == 0)
            check_ingress(env, cancellation);
        for (uint32_t x = 0; x < source.width; ++x) {
            const auto point = source_point(matrix, x + .5, y + .5);
            for (unsigned channel = 0; channel < 3; ++channel)
                result.rgb[(size_t(y) * source.width + x) * 3 + channel] =
                    sample(source, point[0] - .5, point[1] - .5, channel);
        }
    }
    return result;
}

NativeRawPixels bitmap_pixels(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height)
        throw std::runtime_error("Scene bitmap must be software RGBA_8888");
    NativeRawPixels result;
    result.width = info.width;
    result.height = info.height;
    result.rgb.resize(size_t(info.width) * info.height * 3);
    std::array<float, 256> table{};
    for (unsigned i = 0; i < table.size(); ++i) {
        const float s = i / 255.f;
        table[i] = s <= .04045f ? s / 12.92f : std::pow((s + .055f) / 1.055f, 2.4f);
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        throw std::runtime_error("Cannot read scene bitmap");
    for (uint32_t y = 0; y < info.height; ++y) {
        const auto* row = static_cast<const uint8_t*>(pixels) + size_t(y) * info.stride;
        for (uint32_t x = 0; x < info.width; ++x)
            for (unsigned c = 0; c < 3; ++c) {
                uint32_t value = row[x * 4 + c], alpha = row[x * 4 + 3];
                if ((info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL && alpha < 255)
                    value = alpha ? std::min(255u, (value * 255u + alpha / 2) / alpha) : 0;
                result.rgb[(size_t(y) * info.width + x) * 3 + c] = table[value];
            }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

std::unique_ptr<SceneResult> render(SceneSession& session, int limit, bool managed) {
    const auto& p = session.params;
    const double cx = number(p, "geometry_crop_x", 0, 0, 1), cy = number(p, "geometry_crop_y", 0, 0, 1);
    const double cw = number(p, "geometry_crop_w", 1, .0001, 1), ch = number(p, "geometry_crop_h", 1, .0001, 1);
    if (cx + cw > 1.000001 || cy + ch > 1.000001)
        throw std::runtime_error("Scene crop outside source");
    const auto& source = session.source;
    const uint32_t crop_w = std::max(1L, std::lround(cw * source.width)),
                   crop_h = std::max(1L, std::lround(ch * source.height));
    const unsigned turns = unsigned(number(p, "geometry_quarter_turns", 0, 0, 3));
    auto result = std::make_unique<SceneResult>();
    result->width = turns % 2 ? crop_h : crop_w;
    result->height = turns % 2 ? crop_w : crop_h;
    const uint32_t edge = std::max(result->width, result->height);
    if (limit > 0 && edge > uint32_t(limit)) {
        const double scale = double(limit) / edge;
        result->width = std::max(1L, std::lround(result->width * scale));
        result->height = std::max(1L, std::lround(result->height * scale));
    }
    const uint64_t samples = uint64_t(result->width) * result->height * 4;
    if (samples > uint64_t(SIZE_MAX / sizeof(uint16_t)) || (managed && samples > 64u * 1024u * 1024u))
        throw std::runtime_error("Scene output exceeds pixel budget");
    result->pixels.resize(size_t(samples));
    const double angle = number(p, "geometry_rotation_deg", 0, -45, 45) * 3.14159265358979323846 / 180;
    const double cs = std::cos(angle), sn = std::sin(angle);
    const bool prophoto = p.at("input_color_space").as_string() == "ProPhoto RGB";
    const bool p3 = p.at("output_color_space").as_string() == "Display P3";
    const auto encode = [](double v) {
        v = std::clamp(v, 0.0, 1.0);
        return v <= .0031308 ? v * 12.92 : 1.055 * std::pow(v, 1 / 2.4) - .055;
    };
    // Standard D50 ProPhoto -> D65 linear sRGB, same matrices as ColorPipeline's source preview.
    constexpr double m[9] = {2.034075761127914,    -.7273340535284178,  -.3067417508323348,
                             -.22881312503437068,  1.2317300675804077,  -.002916862954207667,
                             -.008569769855748609, -.15328662143133767, 1.161856414213668};
    for (uint32_t y = 0; y < result->height; ++y) {
        if (session.cancelled.load())
            throw std::runtime_error("Scene render cancelled");
        session.progress.store(uint32_t(uint64_t(y) * 100 / result->height));
        for (uint32_t x = 0; x < result->width; ++x) {
            double u = (x + .5) / result->width, v = (y + .5) / result->height;
            if (p.at("geometry_flip_h").as_bool())
                u = 1 - u;
            if (p.at("geometry_flip_v").as_bool())
                v = 1 - v;
            double a = u, b = v;
            if (turns == 1) {
                a = v;
                b = 1 - u;
            } else if (turns == 2) {
                a = 1 - u;
                b = 1 - v;
            } else if (turns == 3) {
                a = 1 - v;
                b = u;
            }
            const double dx = (a - .5) * cw * source.width, dy = (b - .5) * ch * source.height;
            const auto point = source_point(session.perspective, (cx + cw / 2) * source.width + dx * cs - dy * sn,
                                            (cy + ch / 2) * source.height + dx * sn + dy * cs);
            double rgb[3];
            for (unsigned c = 0; c < 3; ++c)
                rgb[c] = sample(source, point[0] - .5, point[1] - .5, c);
            if (prophoto) {
                double converted[3]{};
                for (unsigned r = 0; r < 3; ++r)
                    for (unsigned c = 0; c < 3; ++c)
                        converted[r] += m[r * 3 + c] * rgb[c];
                std::copy_n(converted, 3, rgb);
            }
            if (p3) {
                const double r = .82259287 * rgb[0] + .17753395 * rgb[1], g = .03319951 * rgb[0] + .96678350 * rgb[1],
                             b3 = .01708535 * rgb[0] + .07239572 * rgb[1] + .91030148 * rgb[2];
                rgb[0] = r;
                rgb[1] = g;
                rgb[2] = b3;
            }
            const size_t offset = (size_t(y) * result->width + x) * 4;
            for (unsigned c = 0; c < 3; ++c)
                result->pixels[offset + c] = uint16_t(std::lround(encode(rgb[c]) * 65535));
            result->pixels[offset + 3] = 65535;
        }
    }
    session.progress.store(100);
    return result;
}
} // namespace

extern "C" {
JNIEXPORT jlong JNICALL Java_com_spektralab_android_engine_NativeSceneSession_nativeOpen(
    JNIEnv* env, jobject, jlong engine, jfloatArray pixels, jint width, jint height, jlong raw, jobject bitmap,
    jdoubleArray inverse, jstring params, jboolean digital, jobject cancellation) {
    try {
        auto session = std::make_unique<SceneSession>();
        session->params = parse(env, params);
        if (env->GetArrayLength(inverse) != 9)
            throw std::runtime_error("Invalid perspective matrix");
        env->GetDoubleArrayRegion(inverse, 0, 9, session->perspective.data());
        if (env->ExceptionCheck())
            return 0;
        if (!std::all_of(session->perspective.begin(), session->perspective.end(),
                         [](double value) { return std::isfinite(value); }))
            throw std::runtime_error("Nonfinite perspective matrix");
        check_ingress(env, cancellation);
        // Borrow decoded RAW for perspective ingress; avoid a redundant full float32 copy.
        const NativeRawPixels* borrowed = raw && !digital ? reinterpret_cast<NativeRawPixels*>(raw) : nullptr;
        if (!borrowed) {
            if (bitmap)
                session->source = bitmap_pixels(env, bitmap);
            else if (raw)
                session->source = *reinterpret_cast<NativeRawPixels*>(raw);
            else {
                if (width <= 0 || height <= 0 || uint64_t(width) * height * 3 != uint64_t(env->GetArrayLength(pixels)))
                    throw std::runtime_error("Invalid scene dimensions");
                session->source.width = width;
                session->source.height = height;
                session->source.rgb.resize(size_t(width) * height * 3);
                env->GetFloatArrayRegion(pixels, 0, jsize(session->source.rgb.size()), session->source.rgb.data());
                if (env->ExceptionCheck())
                    return 0;
            }
        }
        check_ingress(env, cancellation);
        if (digital)
            return reinterpret_cast<jlong>(session.release());
        NativeRawPixels corrected =
            warp(borrowed ? *borrowed : session->source, session->perspective, env, cancellation);
        const auto json = session->params.dump();
        const spk_image image{corrected.rgb.data(), corrected.width, corrected.height, 3};
        auto* opened = spk_open(reinterpret_cast<spk_engine*>(engine), &image, json.c_str(), nullptr);
        if (!opened)
            throw std::runtime_error(spk_last_error(reinterpret_cast<spk_engine*>(engine)));
        return reinterpret_cast<jlong>(opened);
    } catch (const std::bad_alloc&) {
        out_of_memory(env);
        return 0;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}
JNIEXPORT void JNICALL Java_com_spektralab_android_engine_NativeSceneSession_nativeRelease(JNIEnv*, jobject,
                                                                                           jlong pointer) {
    delete reinterpret_cast<SceneSession*>(pointer);
}
JNIEXPORT void JNICALL Java_com_spektralab_android_engine_NativeSceneSession_nativeCancel(JNIEnv*, jobject,
                                                                                          jlong pointer) {
    reinterpret_cast<SceneSession*>(pointer)->cancelled.store(true);
}
JNIEXPORT jint JNICALL Java_com_spektralab_android_engine_NativeSceneSession_nativeProgress(JNIEnv*, jobject,
                                                                                            jlong pointer) {
    return jint(reinterpret_cast<SceneSession*>(pointer)->progress.load());
}
JNIEXPORT void JNICALL Java_com_spektralab_android_engine_NativeSceneSession_nativeUpdate(JNIEnv* env, jobject,
                                                                                          jlong pointer, jstring json) {
    try {
        auto& s = *reinterpret_cast<SceneSession*>(pointer);
        const auto delta = parse(env, json);
        for (const auto& field : delta.fields())
            s.params.set(field.first, field.second);
        s.cancelled.store(false);
    } catch (const std::bad_alloc&) {
        out_of_memory(env);
    } catch (const std::exception& e) {
        fail(env, e.what());
    }
}
JNIEXPORT jobject JNICALL Java_com_spektralab_android_engine_NativeSceneSession_nativeRender(JNIEnv* env, jobject,
                                                                                             jlong pointer, jint limit,
                                                                                             jboolean managed) {
    try {
        const auto start = std::chrono::steady_clock::now();
        auto result = render(*reinterpret_cast<SceneSession*>(pointer), limit, managed);
        if (!managed) {
            const auto cls = env->FindClass("com/spektralab/android/engine/NativeRenderResult");
            const auto ctor = cls ? env->GetMethodID(cls, "<init>", "(JIIZ)V") : nullptr;
            const auto object = ctor ? env->NewObject(cls, ctor, reinterpret_cast<jlong>(result.get()),
                                                      jint(result->width), jint(result->height), JNI_TRUE)
                                     : nullptr;
            if (object)
                result.release();
            return object;
        }
        const auto pixels = env->NewShortArray(jsize(result->pixels.size()));
        if (!pixels)
            return nullptr;
        env->SetShortArrayRegion(pixels, 0, jsize(result->pixels.size()),
                                 reinterpret_cast<const jshort*>(result->pixels.data()));
        const auto cls = env->FindClass("com/spektralab/android/engine/SpkResult");
        const auto ctor = cls ? env->GetMethodID(cls, "<init>", "([SIIIDZZLjava/lang/String;)V") : nullptr;
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        return ctor ? env->NewObject(cls, ctor, pixels, jint(result->width), jint(result->height), jint(result->width),
                                     ms, JNI_FALSE, JNI_FALSE, env->NewStringUTF("scene"))
                    : nullptr;
    } catch (const std::bad_alloc&) {
        out_of_memory(env);
        return nullptr;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}
JNIEXPORT void JNICALL Java_com_spektralab_android_engine_NativeRenderResult_nativeSceneReadRows(JNIEnv* env, jobject,
                                                                                                 jlong pointer, jint y,
                                                                                                 jint rows,
                                                                                                 jshortArray output) {
    const auto& r = *reinterpret_cast<SceneResult*>(pointer);
    if (y < 0 || rows <= 0 || uint64_t(y) + rows > r.height ||
        uint64_t(r.width) * rows * 4 > uint64_t(env->GetArrayLength(output))) {
        fail(env, "Invalid scene readback");
        return;
    }
    env->SetShortArrayRegion(output, 0, jsize(uint64_t(r.width) * rows * 4),
                             reinterpret_cast<const jshort*>(r.pixels.data() + size_t(y) * r.width * 4));
}
JNIEXPORT void JNICALL Java_com_spektralab_android_engine_NativeRenderResult_nativeSceneRelease(JNIEnv*, jobject,
                                                                                                jlong pointer) {
    delete reinterpret_cast<SceneResult*>(pointer);
}
}
