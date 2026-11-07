#pragma once

#include "Includes.h"
#include "Tools.h"
#include "Il2Cpp.h"
#include "KittyMemory/KittyScanner.h"

// Runtime resolver: uses KittyScanner AOB patterns against libil2cpp.so when provided,
// falling back to Il2Cpp metadata resolution. This avoids hardcoded/static memory offsets.
class RuntimeResolver {
public:
    static uintptr_t ResolveIl2CppPattern(const char *aobPattern);
    static uintptr_t ResolveMethod(const char *image, const char *namespaze, const char *clazz, const char *name, int argsCount = 0, const char *aobPattern = nullptr);
    static uintptr_t ResolveField(const char *image, const char *namespaze, const char *clazz, const char *name, const char *aobPattern = nullptr);
};
