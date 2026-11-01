#ifndef PORTAL_SAMPLE_LLM_UTF8_H
#define PORTAL_SAMPLE_LLM_UTF8_H

#include <algorithm>
#include <cstddef>

namespace portal_sample_llm {
// Standard UTF-8 only: excludes overlong encodings, surrogate scalars, invalid
// continuations and values above U+10FFFF. complete excludes an unfinished tail.
inline bool utf8_prefix(const char* data, size_t length, size_t& complete) noexcept {
    size_t i = 0;
    while (i < length) {
        const auto first = static_cast<unsigned char>(data[i]);
        if (first < 0x80) {
            ++i;
            continue;
        }
        size_t count;
        if (first >= 0xc2 && first <= 0xdf) count = 2;
        else if (first >= 0xe0 && first <= 0xef) count = 3;
        else if (first >= 0xf0 && first <= 0xf4) count = 4;
        else return false;
        const size_t available = std::min(count, length - i);
        for (size_t j = 1; j < available; ++j) {
            const auto next = static_cast<unsigned char>(data[i + j]);
            if ((next & 0xc0) != 0x80 ||
                (j == 1 && ((first == 0xe0 && next < 0xa0) ||
                            (first == 0xed && next >= 0xa0) ||
                            (first == 0xf0 && next < 0x90) ||
                            (first == 0xf4 && next >= 0x90)))) return false;
        }
        if (available < count) {
            complete = i;
            return true;
        }
        i += count;
    }
    complete = i;
    return true;
}
} // namespace portal_sample_llm

#endif
