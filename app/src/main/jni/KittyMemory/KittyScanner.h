#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace KittyScanner {

// Simple AOB/IDA pattern scanner utilities (KittyMemory-style usage) for libil2cpp.so.
// Pattern format: "AA BB ?? CC ? DD"
struct Pattern {
    std::vector<uint8_t> bytes;
    std::vector<uint8_t> mask; // 1 = match byte, 0 = wildcard

    bool empty() const { return bytes.empty() || bytes.size() != mask.size(); }
    size_t size() const { return bytes.size(); }
    static Pattern Parse(const std::string &idaSig);
};

uintptr_t findBytes(uintptr_t start, uintptr_t end, const Pattern &pattern);
uintptr_t findIdaPatternFirst(const char *lib, const std::string &pattern);
uintptr_t findIdaPatternFirst(const char *lib, const char *pattern);

} // namespace KittyScanner
