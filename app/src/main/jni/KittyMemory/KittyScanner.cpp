#include "KittyScanner.h"

#include <sstream>

#include "../Tools.h"
#include "../Includes.h"

namespace KittyScanner {

static int hexNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

Pattern Pattern::Parse(const std::string &idaSig) {
    Pattern p;
    std::istringstream iss(idaSig);
    std::string token;
    while (iss >> token) {
        if (token == "?" || token == "??") {
            p.bytes.push_back(0);
            p.mask.push_back(0);
            continue;
        }
        if (token.size() == 2) {
            int hi = hexNibble(token[0]);
            int lo = hexNibble(token[1]);
            if (hi >= 0 && lo >= 0) {
                p.bytes.push_back(static_cast<uint8_t>((hi << 4) | lo));
                p.mask.push_back(1);
            }
        }
    }
    return p;
}

uintptr_t findBytes(uintptr_t start, uintptr_t end, const Pattern &pattern) {
    if (pattern.empty() || start >= end || end - start < pattern.size()) return 0;

    const size_t plen = pattern.size();
    const uint8_t *pbytes = pattern.bytes.data();
    const uint8_t *pmask = pattern.mask.data();

    for (uintptr_t addr = start; addr + plen <= end; ++addr) {
        bool ok = true;
        for (size_t i = 0; i < plen; ++i) {
            if (!pmask[i]) continue;
            uint8_t b = 0;
            if (!Tools::Read(reinterpret_cast<void *>(addr + i), &b, 1)) {
                ok = false;
                break;
            }
            if (b != pbytes[i]) {
                ok = false;
                break;
            }
        }
        if (ok) return addr;
    }
    return 0;
}

uintptr_t findIdaPatternFirst(const char *lib, const std::string &pattern) {
    return findIdaPatternFirst(lib, pattern.c_str());
}

uintptr_t findIdaPatternFirst(const char *lib, const char *pattern) {
    if (!lib || !pattern) return 0;
    Pattern p = Pattern::Parse(pattern);
    if (p.empty()) return 0;

    uintptr_t start = Tools::GetBaseAddress(lib);
    uintptr_t end = Tools::GetEndAddress(lib);
    if (!start || !end || end <= start) return 0;

    uintptr_t result = findBytes(start, end, p);
    if (result) {
        LOGI("KittyScanner: %s pattern hit @ %p", lib, reinterpret_cast<void *>(result));
    } else {
        LOGW("KittyScanner: %s pattern miss", lib);
    }
    return result;
}

} // namespace KittyScanner
