#include "RuntimeResolver.h"

uintptr_t RuntimeResolver::ResolveIl2CppPattern(const char *aobPattern) {
    if (!aobPattern || !*aobPattern) return 0;
    uintptr_t hit = KittyScanner::findIdaPatternFirst("libil2cpp.so", aobPattern);
    if (hit) {
        uintptr_t base = Tools::GetBaseAddress("libil2cpp.so");
        if (base && hit >= base) {
            LOGI("RuntimeResolver: AOB resolved libil2cpp.so+0x%" PRIXPTR, hit - base);
        }
    }
    return hit;
}

uintptr_t RuntimeResolver::ResolveMethod(const char *image, const char *namespaze, const char *clazz, const char *name, int argsCount, const char *aobPattern) {
    uintptr_t aob = ResolveIl2CppPattern(aobPattern);
    if (aob) return aob;

    void *m = Il2Cpp::GetMethodOffset(image, namespaze, clazz, name, argsCount);
    uintptr_t result = reinterpret_cast<uintptr_t>(m);
    if (result) {
        LOGI("RuntimeResolver: method %s::%s -> %p", clazz ? clazz : "?", name ? name : "?", m);
    } else {
        LOGW("RuntimeResolver: failed method %s::%s", clazz ? clazz : "?", name ? name : "?");
    }
    return result;
}

uintptr_t RuntimeResolver::ResolveField(const char *image, const char *namespaze, const char *clazz, const char *name, const char *aobPattern) {
    uintptr_t aob = ResolveIl2CppPattern(aobPattern);
    if (aob) {
        LOGI("RuntimeResolver: field AOB %s::%s -> 0x%" PRIXPTR, clazz ? clazz : "?", name ? name : "?", aob);
        return aob;
    }

    uintptr_t off = Il2Cpp::GetFieldOffset(image, namespaze, clazz, name);
    if (off) {
        LOGI("RuntimeResolver: field %s::%s -> 0x%" PRIXPTR, clazz ? clazz : "?", name ? name : "?", off);
    } else {
        LOGW("RuntimeResolver: failed field %s::%s", clazz ? clazz : "?", name ? name : "?");
    }
    return off;
}
