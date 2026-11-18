#pragma once

#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <string>
#include <vector>

// Android-owned bounded wire contract. Five rows: RGB, luma, R, G, B.
namespace theia::scene_curve {
constexpr size_t rows = 5;
constexpr size_t max_samples = 1025;
constexpr size_t max_text_bytes = 131072;
constexpr float stops = 8.0f;

inline bool parse(const std::string& text, std::vector<float>& table, size_t& samples, std::string& error) {
    table.clear();
    samples = 0;
    if (text.empty())
        return true;
    const auto invalid = [&]() {
        table.clear();
        error = "scene_curve requires five rows of 2..1025 finite samples in 0..1";
        return false;
    };
    if (text.size() > max_text_bytes)
        return invalid();
    const char* cursor = text.c_str();
    while (*cursor) {
        if (table.size() == rows * max_samples)
            return invalid();
        const char first = *cursor;
        if (!((first >= '0' && first <= '9') || first == '-' || first == '.'))
            return invalid();
        char* end = nullptr;
        errno = 0;
        const double value = std::strtod(cursor, &end);
        if (end == cursor || errno == ERANGE || !std::isfinite(value) || value < 0 || value > 1)
            return invalid();
        for (const char* token = cursor; token != end; ++token) {
            const char c = *token;
            if (!((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'))
                return invalid();
        }
        table.push_back(float(value));
        if (*end == '\0')
            break;
        if (*end != ',' || end[1] == '\0')
            return invalid();
        cursor = end + 1;
    }
    if (table.size() % rows != 0 || table.size() / rows < 2)
        return invalid();
    samples = table.size() / rows;
    return true;
}
} // namespace theia::scene_curve
