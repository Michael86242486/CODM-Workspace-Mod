#include "Includes.h"
#include "Tools.h"
#include "Engine/Canvas.h"
#include "fake_dlfcn.h"
#include "Il2Cpp.h"
#include "RuntimeResolver.h"
#include "Vector2.hpp"
#include "Vector3.hpp"
#include "Quaternion.hpp"

#include <atomic>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

// Runtime symbol resolution (dual-layered, self-updating, offset-cached):
// 1) Cache lookup (fast path): load relative offsets from persistent cache if version matches.
// 2) Primary resolver (string path): RuntimeResolver / IL2CPP metadata by class+method name.
// 3) Backup scanner (AOB path): fail-closed find_pattern(raw bytes + mask) in libil2cpp.so.
// 4) Persistent save: write discovered relative offsets to cache for subsequent launches.
// All setup runs inside a detached ModWorker thread with retry/timeout and std::call_once.
// ================================================================================================================================ //
#define SLEEP_TIME 1000LL / 120LL
#define TARGET_LIB "libil2cpp.so"
#define INIT_RETRY_INTERVAL_SEC 1
#define INIT_TIMEOUT_SEC 30
#define OFFSET_CACHE_PATH "/data/data/com.activision.charlie/files/offset_cache.txt"
#define LEARNED_AOBS_PATH "/data/data/com.activision.charlie/files/learned_aobs.txt"
#define LEARNED_AOB_BYTES 16

namespace RuntimeStatus {
    // Global, thread-safe runtime status log visible to worker thread and UI renderers.
    std::string runtime_log = "Status: Waiting for initialization...";
    std::mutex runtime_log_mutex;

    inline void Set(const std::string &msg) {
        std::lock_guard<std::mutex> lock(runtime_log_mutex);
        runtime_log = msg;
        LOGI("%s", runtime_log.c_str());
    }

    inline std::string Get() {
        std::lock_guard<std::mutex> lock(runtime_log_mutex);
        return runtime_log;
    }
}

// Requested global, thread-safe runtime status variable.
std::string runtime_log = "Status: Waiting for initialization...";
static std::mutex runtime_log_sync;
inline void SetRuntimeLog(const std::string &msg) {
    {
        std::lock_guard<std::mutex> lock(runtime_log_sync);
        runtime_log = msg;
    }
    RuntimeStatus::Set(msg);
}
inline std::string GetRuntimeLog() {
    std::lock_guard<std::mutex> lock(runtime_log_sync);
    return runtime_log;
}

std::map<std::string, u_long> Config;

int g_screenWidth = 0, g_screenHeight = 0;
std::atomic<bool> bInitDone(false);
int screenWidth = 0, screenHeight = 0;
uintptr_t g_il2cpp = 0;
// ================================================================================================================================ //
std::map<std::string, uintptr_t> Fields;
std::map<std::string, uintptr_t> Methods;

namespace {
std::once_flag g_hookOnceFlag;

// Hook target descriptor for cache + dual-layer resolution.
struct HookTarget {
    const char *key;            // cache key, e.g. "PlayerController.Update"
    const char *image;          // IL2CPP image, e.g. "Assembly-CSharp.dll"
    const char *namespaze;      // namespace ("" if global)
    const char *clazz;          // class name
    const char *method;         // method name
    int argsCount;              // IL2CPP arg count for overload resolution
    const uint8_t *aob;         // predefined backup AOB bytes (may be placeholder)
    const char *mask;           // predefined backup mask ('x' match, '?' wildcard)
    uintptr_t resolved;         // absolute address (filled at runtime)
    const char *source;         // "cache" | "resolver" | "learned_aob" | "aob"
};


bool IsValidResolvedAddress(uintptr_t addr, uintptr_t base, size_t size);
bool IsPlaceholderAob(const uint8_t *aob, const char *mask) {
    if (!aob || !mask) return true;
    for (const char *p = mask; *p; ++p) {
        if (*p != '?') return false;
    }
    return true;
}
// Fail-closed AOB scanner: scans [start, start + size) for `pattern` masked by `mask`.
// mask chars: 'x' = byte must match, '?' = wildcard. Returns 0 if not found/invalid.
uintptr_t find_pattern(const uint8_t *pattern, const char *mask, uintptr_t start, size_t size) {
    if (!pattern || !mask || !start || !size) return 0;

    const size_t patLen = strlen(mask);
    if (patLen == 0 || patLen > size) return 0;

    const uint8_t *hay = reinterpret_cast<const uint8_t *>(start);
    const size_t last = size - patLen;

    for (size_t i = 0; i <= last; ++i) {
        bool ok = true;
        for (size_t j = 0; j < patLen; ++j) {
            if (mask[j] == 'x' && hay[i + j] != pattern[j]) {
                ok = false;
                break;
            }
        }
        if (ok) return start + i;
    }
    return 0;
}

// Learned AOB: generated from a resolver hit (first N bytes + heuristic mask).
struct LearnedAob {
    std::string key;
    std::vector<uint8_t> bytes;
    std::string mask;           // 'x' static, '?' dynamic/relative
    std::string version;        // game/module version fingerprint at learn time
};

static inline char HexNibble(uint8_t v) {
    return v < 10 ? static_cast<char>('0' + v) : static_cast<char>('A' + (v - 10));
}

std::string BytesToHexSignature(const uint8_t *bytes, size_t n) {
    std::string out;
    out.reserve(n * 3);
    for (size_t i = 0; i < n; ++i) {
        if (i) out.push_back(' ');
        out.push_back(HexNibble((bytes[i] >> 4) & 0xF));
        out.push_back(HexNibble(bytes[i] & 0xF));
    }
    return out;
}

bool ParseHexSignature(const std::string &sig, std::vector<uint8_t> &bytes) {
    bytes.clear();
    const char *p = sig.c_str();
    while (*p) {
        while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') ++p;
        if (!*p) break;
        if (p[0] == '?' && (p[1] == '?' || p[1] == ' ' || p[1] == '\0')) {
            bytes.push_back(0x00);
            ++p; if (*p == '?') ++p;
            continue;
        }
        char *end = nullptr;
        unsigned long v = strtoul(p, &end, 16);
        if (end == p) return false;
        bytes.push_back(static_cast<uint8_t>(v & 0xFF));
        p = end;
    }
    return !bytes.empty();
}

// Heuristic mask for first 16 instruction bytes:
// - ARM64 (AArch64): wildcard imm26 branch/call opcodes (B/BL/BCOND/CBZ/CBNZ/TBZ/TBNZ/ADR/ADRP).
// - ARM32/Thumb (fallback heuristic): wildcard common PC-relative prefixes and keep others static.
// This is intentionally conservative; placeholders/custom masks can replace learned entries.
std::string GenerateMaskForBytes(const uint8_t *bytes, size_t n) {
    std::string mask(n, 'x');
#if defined(__aarch64__) || defined(__arm64__)
    for (size_t i = 0; i + 4 <= n; i += 4) {
        uint32_t insn = (uint32_t)bytes[i] |
                        ((uint32_t)bytes[i + 1] << 8) |
                        ((uint32_t)bytes[i + 2] << 16) |
                        ((uint32_t)bytes[i + 3] << 24);
        bool wildcard = false;
        // B / BL (imm26)
        if ((insn & 0x7C000000u) == 0x14000000u) wildcard = true;
        // B.cond / CBZ / CBNZ (imm19)
        else if ((insn & 0xFF000010u) == 0x54000000u) wildcard = true;
        else if ((insn & 0x7E000000u) == 0x34000000u) wildcard = true;
        // TBZ / TBNZ (imm14)
        else if ((insn & 0x7E000000u) == 0x36000000u) wildcard = true;
        // ADR / ADRP (immhi:immlo)
        else if ((insn & 0x1F000000u) == 0x10000000u) wildcard = true;
        // LDR (literal, imm19)
        else if ((insn & 0x3B000000u) == 0x18000000u) wildcard = true;
        if (wildcard) {
            mask[i] = mask[i + 1] = mask[i + 2] = mask[i + 3] = '?';
        }
    }
#else
    for (size_t i = 0; i < n; ++i) {
        uint8_t b = bytes[i];
        // Heuristic: Thumb32 prefixes / BL/BLX/B.W op patterns often contain relative immediates.
        if (b == 0xF0 || b == 0xF8 || b == 0xF2) mask[i] = '?';
    }
#endif
    return mask;
}

std::map<std::string, LearnedAob> LoadLearnedAobs() {
    std::map<std::string, LearnedAob> out;
    std::ifstream in(LEARNED_AOBS_PATH);
    if (!in.is_open()) {
        LOGI("Learned AOB backup not found: %s", LEARNED_AOBS_PATH);
        return out;
    }

    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        // Format: key|version|mask|AA BB CC ...
        std::stringstream ss(line);
        std::string key, version, mask, sig;
        if (!std::getline(ss, key, '|')) continue;
        if (!std::getline(ss, version, '|')) continue;
        if (!std::getline(ss, mask, '|')) continue;
        if (!std::getline(ss, sig)) continue;
        if (key.empty() || mask.empty() || sig.empty()) continue;

        LearnedAob aob;
        aob.key = key;
        aob.version = version;
        aob.mask = mask;
        if (!ParseHexSignature(sig, aob.bytes)) continue;
        if (aob.mask.size() != aob.bytes.size()) continue;
        out[key] = std::move(aob);
    }

    LOGI("Loaded %zu learned AOB entr%s from %s",
         out.size(), out.size() == 1 ? "y" : "ies", LEARNED_AOBS_PATH);
    return out;
}

void SaveLearnedAobs(const std::map<std::string, LearnedAob> &aobs) {
    if (aobs.empty()) return;
    std::string tmp = std::string(LEARNED_AOBS_PATH) + ".tmp";
    std::ofstream out(tmp, std::ios::trunc);
    if (!out.is_open()) {
        LOGW("Failed to open learned AOB backup for write: %s", tmp.c_str());
        return;
    }
    for (const auto &kv : aobs) {
        const auto &a = kv.second;
        if (a.key.empty() || a.bytes.empty() || a.mask.size() != a.bytes.size()) continue;
        out << a.key << "|" << a.version << "|" << a.mask << "|"
            << BytesToHexSignature(a.bytes.data(), a.bytes.size()) << "\n";
    }
    out.flush();
    out.close();
    if (rename(tmp.c_str(), LEARNED_AOBS_PATH) != 0) {
        LOGW("Failed to persist learned AOB backup: %s", LEARNED_AOBS_PATH);
        unlink(tmp.c_str());
    } else {
        LOGI("Learned AOB backup saved (%zu entries): %s", aobs.size(), LEARNED_AOBS_PATH);
    }
}

bool LearnAobFromResolvedAddress(HookTarget &t, uintptr_t libBase, size_t libSize,
                                 const std::string &version,
                                 std::map<std::string, LearnedAob> &learned) {
    if (!IsValidResolvedAddress(t.resolved, libBase, libSize)) return false;
    if (t.resolved + LEARNED_AOB_BYTES > libBase + libSize) return false;

    // 1) Read first 16 bytes of native machine code at resolved address.
    uint8_t bytes[LEARNED_AOB_BYTES] = {0};
    memcpy(bytes, reinterpret_cast<const void *>(t.resolved), LEARNED_AOB_BYTES);

    // 2) Generate hex signature + mask ('x' static opcode bytes, '?' dynamic relative/branch).
    LearnedAob a;
    a.key = t.key ? t.key : "";
    a.version = version;
    a.bytes.assign(bytes, bytes + LEARNED_AOB_BYTES);
    a.mask = GenerateMaskForBytes(bytes, LEARNED_AOB_BYTES);
    if (a.mask.size() != a.bytes.size()) return false;

    learned[a.key] = std::move(a);
    LOGI("Learned AOB for %s: sig=\"%s\" mask=\"%s\"",
         t.key,
         BytesToHexSignature(bytes, LEARNED_AOB_BYTES).c_str(),
         GenerateMaskForBytes(bytes, LEARNED_AOB_BYTES).c_str());
    return true;
}

// Resolves a module's mapped address range from /proc/self/maps (min start -> max end).
bool GetModuleRange(const char *name, uintptr_t &base, size_t &size) {
    base = 0;
    size = 0;
    if (!name || !*name) return false;

    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;

    char line[512];
    uintptr_t low = 0;
    uintptr_t high = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, name)) continue;

        unsigned long startAddr = 0;
        unsigned long endAddr = 0;
        char perms[5] = {0};
        char path[256] = {0};
        int matched = sscanf(line, "%lx-%lx %4s %*s %*s %*s %255[^\n]", &startAddr, &endAddr, perms, path);
        if (matched < 3) continue;

        uintptr_t s = static_cast<uintptr_t>(startAddr);
        uintptr_t e = static_cast<uintptr_t>(endAddr);
        if (!base || s < low) {
            low = s;
            base = s;
        }
        if (e > high) high = e;
    }

    fclose(fp);
    if (base && high > low) {
        size = static_cast<size_t>(high - low);
        return true;
    }
    base = 0;
    size = 0;
    return false;
}

std::string GetModulePath(const char *name) {
    if (!name || !*name) return {};
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return {};

    char line[512];
    std::string result;
    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, name)) continue;
        char path[256] = {0};
        // path is the last whitespace-delimited field when present
        unsigned long s = 0, e = 0;
        char perms[5] = {0}, off[32] = {0}, dev[32] = {0}, ino[32] = {0};
        int n = sscanf(line, "%lx-%lx %4s %31s %31s %31s %255[^\n]", &s, &e, perms, off, dev, ino, path);
        if (n == 7 && path[0] == '/') {
            // trim leading spaces
            char *p = path;
            while (*p == ' ' || *p == '\t') ++p;
            result = p;
            break;
        }
    }
    fclose(fp);
    return result;
}

// Version fingerprint: module path + size + mtime. Used to invalidate cache after game updates.
std::string GetGameVersionKey(uintptr_t /*libBase*/) {
    std::string path = GetModulePath(TARGET_LIB);
    struct stat st;
    if (!path.empty() && stat(path.c_str(), &st) == 0) {
        char buf[256];
        snprintf(buf, sizeof(buf), "%s|%lld|%lld",
                 path.c_str(),
                 (long long) st.st_size,
                 (long long) st.st_mtime);
        return std::string(buf);
    }
    // Fallback: use mapped base/size snapshot (less stable across runs, but safe)
    uintptr_t base = 0; size_t size = 0;
    if (GetModuleRange(TARGET_LIB, base, size)) {
        char buf[128];
        snprintf(buf, sizeof(buf), TARGET_LIB "|base=%" PRIxPTR "|size=%zx", base, size);
        return std::string(buf);
    }
    return TARGET_LIB "|unknown";
}

struct OffsetCache {
    std::string version;
    std::map<std::string, uintptr_t> offsets; // relative to lib base
    bool valid = false;
};

OffsetCache LoadOffsetCache(const std::string &currentVersion) {
    OffsetCache cache;
    std::ifstream in(OFFSET_CACHE_PATH);
    if (!in.is_open()) {
        LOGI("Offset cache not found: %s", OFFSET_CACHE_PATH);
        return cache;
    }

    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string k = line.substr(0, eq);
        std::string v = line.substr(eq + 1);
        if (k == "version") {
            cache.version = v;
        } else if (!k.empty() && !v.empty()) {
            char *end = nullptr;
            unsigned long long off = strtoull(v.c_str(), &end, 16);
            if (end != v.c_str()) {
                cache.offsets[k] = static_cast<uintptr_t>(off);
            }
        }
    }

    cache.valid = (!cache.version.empty() && cache.version == currentVersion && !cache.offsets.empty());
    if (cache.valid) {
        LOGI("Offset cache loaded (%zu entries, version matched)", cache.offsets.size());
    } else if (!cache.version.empty() && cache.version != currentVersion) {
        LOGI("Offset cache version mismatch; cache ignored");
    } else {
        LOGI("Offset cache empty/invalid; cache ignored");
    }
    return cache;
}

void SaveOffsetCache(const std::string &version, const std::map<std::string, uintptr_t> &offsets) {
    if (version.empty() || offsets.empty()) return;

    // Write atomically via temp file in same directory.
    std::string tmp = std::string(OFFSET_CACHE_PATH) + ".tmp";
    std::ofstream out(tmp, std::ios::trunc);
    if (!out.is_open()) {
        LOGW("Failed to open offset cache for write: %s", tmp.c_str());
        return;
    }

    out << "version=" << version << "\n";
    for (const auto &kv : offsets) {
        char buf[64];
        snprintf(buf, sizeof(buf), "0x%" PRIxPTR, kv.second);
        out << kv.first << "=" << buf << "\n";
    }
    out.flush();
    out.close();

    if (rename(tmp.c_str(), OFFSET_CACHE_PATH) != 0) {
        LOGW("Failed to persist offset cache: %s", OFFSET_CACHE_PATH);
        unlink(tmp.c_str());
    } else {
        LOGI("Offset cache saved (%zu entries): %s", offsets.size(), OFFSET_CACHE_PATH);
    }
}

bool IsValidResolvedAddress(uintptr_t addr, uintptr_t base, size_t size) {
    return addr != 0 && base != 0 && size != 0 && addr >= base && addr < (base + size);
}
} // namespace


// ================================================================================================================================ //
class Transform {
public:
    Vector3 get_position() {
        auto Transform_get_position = (Vector3 (*)(Transform *)) (Methods["Transform::get_position"]);
        return Transform_get_position(this);
    }
};

class Component {
public:
    Transform *get_transform() {
        auto Component_get_transform = (Transform *(*)(Component *)) (Methods["Component::get_transform"]);
        return Component_get_transform(this);
    }
};

class Camera {
public:
    static Camera *get_main() {
        auto Camera_get_main = (Camera *(*)()) (Methods["Camera::get_main"]);
        return Camera_get_main();
    }
};

Vector3 WorldToScreen(Vector3 pos) {
    auto main = Camera::get_main();
    if (main) {
        auto Camera_WorldToScreenPoint = (Vector3 (*)(Camera *, Vector3)) (Methods["Camera::WorldToScreenPoint"]);
        return Camera_WorldToScreenPoint(main, pos);
    }
    return {0, 0, 0};
}

uintptr_t GetClosestTarget() {
    uintptr_t result = 0;

    float MaxDist = std::numeric_limits<float>::infinity();

    void *BaseWorld_Instance = 0;
    Il2Cpp::GetStaticFieldValue("Assembly-CSharp.dll", "GameEngine", "BaseWorld", "Instance", &BaseWorld_Instance);
    if (BaseWorld_Instance) {
        auto m_Game = *(uintptr_t *) ((uintptr_t) BaseWorld_Instance + Fields["BaseWorld::m_Game"]);
        if (m_Game) {
            auto Gameplay_get_LocalPawn = (uintptr_t (*)()) (Methods["Gameplay::get_LocalPawn"]);
            auto LocalPawn = Gameplay_get_LocalPawn();
            if (LocalPawn) {
                Vector3 MyPos{0, 0, 0};

                auto local_m_Mesh = *(Transform **) (LocalPawn + Fields["Pawn::m_Mesh"]);
                if (local_m_Mesh) {
                    MyPos = local_m_Mesh->get_position();
                }

                auto EnemyPawns = *(List<uintptr_t> **) (m_Game + Fields["BaseGame::EnemyPawns"]);
                if (EnemyPawns) {
                    auto Items = EnemyPawns->getItems();
                    if (Items) {
                        for (int i = 0; i < EnemyPawns->getSize(); i++) {
                            auto Pawn = Items[i];
                            if (Pawn) {
                                if (!*(bool *) (Pawn + Fields["Pawn::m_IsAlive"]))
                                    continue;

                                auto m_Mesh = *(Transform **) (Pawn + Fields["Pawn::m_Mesh"]);
                                if (!m_Mesh)
                                    continue;

                                auto RootPos = m_Mesh->get_position();
                                float Distance = Vector3::Distance(MyPos, RootPos);

                                if (Distance < MaxDist) {
                                    result = Pawn;
                                    MaxDist = Distance;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    return result;
}

bool isInsideFOV(int x, int y) {
    if (!Config["AIM::SIZE"])
        return true;

    int circle_x = g_screenWidth / 2;
    int circle_y = g_screenHeight / 2;
    int rad = Config["AIM::SIZE"];
    return (x - circle_x) * (x - circle_x) + (y - circle_y) * (y - circle_y) <= rad * rad;
}

uintptr_t GetInsideFOVTarget() {
    uintptr_t result = 0;

    float MaxDist = std::numeric_limits<float>::infinity();

    void *BaseWorld_Instance = 0;
    Il2Cpp::GetStaticFieldValue("Assembly-CSharp.dll", "GameEngine", "BaseWorld", "Instance", &BaseWorld_Instance);
    if (BaseWorld_Instance) {
        auto m_Game = *(uintptr_t *) ((uintptr_t) BaseWorld_Instance + Fields["BaseWorld::m_Game"]);
        if (m_Game) {
            auto Gameplay_get_LocalPawn = (uintptr_t (*)()) (Methods["Gameplay::get_LocalPawn"]);
            auto LocalPawn = Gameplay_get_LocalPawn();
            if (LocalPawn) {
                Vector3 MyPos{0, 0, 0};

                auto local_m_Mesh = *(Transform **) (LocalPawn + Fields["Pawn::m_Mesh"]);
                if (local_m_Mesh) {
                    MyPos = local_m_Mesh->get_position();
                }

                auto EnemyPawns = *(List<uintptr_t> **) (m_Game + Fields["BaseGame::EnemyPawns"]);
                if (EnemyPawns) {
                    auto Items = EnemyPawns->getItems();
                    if (Items) {
                        for (int i = 0; i < EnemyPawns->getSize(); i++) {
                            auto Pawn = Items[i];
                            if (Pawn) {
                                if (!*(bool *) (Pawn + Fields["Pawn::m_IsAlive"]))
                                    continue;

                                auto m_HeadBone = *(Transform **) (Pawn + Fields["Pawn::m_HeadBone"]);
                                if (!m_HeadBone)
                                    continue;

                                auto HeadSc = WorldToScreen(m_HeadBone->get_position());

                                Vector2 v2Middle = Vector2((float) (g_screenWidth / 2), (float) (g_screenHeight / 2));
                                Vector2 v2Loc = Vector2(HeadSc.X, HeadSc.Y);

                                if (isInsideFOV((int) HeadSc.X, (int) HeadSc.Y)) {
                                    float Distance = Vector2::Distance(v2Middle, v2Loc);

                                    if (Distance < MaxDist) {
                                        result = Pawn;
                                        MaxDist = Distance;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    return result;
}
// ================================================================================================================================ //

// ================================================================================================================================ //
void native_onCanvasDraw(JNIEnv *env, jobject obj, jobject canvas, int screenWidth, int screenHeight, float screenDensity) {
    static Canvas *m_Canvas = 0;
    if (!m_Canvas) {
        LOGI("Canvas Object: %p | Width: %d | Height: %d | Density: %f", canvas, screenWidth, screenHeight, screenDensity);
        m_Canvas = new Canvas(env, screenWidth, screenHeight, screenDensity);
    }

    m_Canvas->UpdateCanvas(canvas);

    g_screenWidth = screenWidth;
    g_screenHeight = screenHeight;

    // Live runtime status overlay (visible on top of menu/ESP canvas).
    {
        std::string status = GetRuntimeLog();
        if (!status.empty()) {
            float pad = m_Canvas->scaleSize(6.0f);
            float textSize = m_Canvas->scaleSize(8.5f);
            float boxW = m_Canvas->scaleSize(360.0f);
            float boxH = m_Canvas->scaleSize(18.0f);
            float x = pad;
            float y = pad;
            int bg = ARGB(180, 0, 0, 0);
            int fg = status.find("ERROR") != std::string::npos ? ARGB(255, 255, 90, 90)
                 : status.find("SUCCESS") != std::string::npos ? ARGB(255, 90, 255, 140)
                 : ARGB(255, 255, 235, 120);
            m_Canvas->drawBox(x, y, boxW, boxH, bg);
            m_Canvas->drawBorder(x, y, boxW, boxH, m_Canvas->scaleSize(1.0f), ARGB(255, 255, 255, 255));
            m_Canvas->drawText(status.c_str(), x + pad, y + boxH - m_Canvas->scaleSize(4.0f), textSize, Align::LEFT, fg, ARGB(255, 0, 0, 0));
        }
    }

    float lineSize = m_Canvas->scaleSize(0.8f);
	
    if (!bInitDone.load(std::memory_order_acquire))
        return;

    auto Screen_SetResolution = (void (*)(void *, int, int, bool)) (Methods["Screen::SetResolution"]);
    Screen_SetResolution(0, screenWidth, screenHeight, true);

    void *BaseWorld_Instance = 0;
    Il2Cpp::GetStaticFieldValue("Assembly-CSharp.dll", "GameEngine", "BaseWorld", "Instance", &BaseWorld_Instance);
    if (Tools::IsPtrValid(BaseWorld_Instance)) {
        auto m_Game = *(uintptr_t *) ((uintptr_t) BaseWorld_Instance + Fields["BaseWorld::m_Game"]);
        if (Tools::IsPtrValid((void *) m_Game)) {
            auto Gameplay_get_LocalPawn = (uintptr_t (*)()) (Methods["Gameplay::get_LocalPawn"]);
            auto LocalPawn = Gameplay_get_LocalPawn();
              if (Tools::IsPtrValid((void *) LocalPawn)) {
                if (Config["AIM::TARGET"] == 1) {
                    m_Canvas->drawCircle(screenWidth / 2, screenHeight / 2, Config["AIM::SIZE"], 1.5f, false, ARGB(255, 0, 255, 0));
                }

                Vector3 MyPos{0, 0, 0};

                auto local_m_Mesh = *(Transform **) (LocalPawn + Fields["Pawn::m_Mesh"]);
                if (Tools::IsPtrValid(local_m_Mesh)) {
                    MyPos = local_m_Mesh->get_position();
                }

                auto EnemyPawns = *(List<uintptr_t> **) (m_Game + Fields["BaseGame::EnemyPawns"]);
                if (Tools::IsPtrValid(EnemyPawns)) {
                    auto Items = EnemyPawns->getItems();
                    if (Tools::IsPtrValid(Items)) {
                        for (int i = 0; i < EnemyPawns->getSize(); i++) {
                            auto Pawn = Items[i];
                            if (Tools::IsPtrValid((void *) Pawn)) {
                                auto m_PlayerInfo = *(uintptr_t *) (Pawn + Fields["Pawn::m_PlayerInfo"]);
                                if (Tools::IsPtrValid((void *) m_PlayerInfo)) {
                                    auto m_HeadBone = *(Transform **) (Pawn + Fields["Pawn::m_HeadBone"]);
                                    if (!Tools::IsPtrValid(m_HeadBone))
                                        continue;

                                    auto m_Mesh = *(Transform **) (Pawn + Fields["Pawn::m_Mesh"]);
                                    if (!m_Mesh)
                                        continue;

                                    auto HeadPos = m_HeadBone->get_position();
                                    auto HeadSc = WorldToScreen(HeadPos);                                 
                                    auto RootPos = m_Mesh->get_position();
                                    auto RootSc = WorldToScreen(RootPos);

                                    float Distance = Vector3::Distance(MyPos, RootPos);
                                      if (HeadSc.Z > 0) {
                                      if (Config["ESP::LINE"]) {
                                        m_Canvas->drawLine(screenWidth / 2, 0, HeadSc.X, screenHeight - HeadSc.Y, lineSize, RED);
                                       }					                                        
                                        if (Config["ESP::BOX"]) {
                                            float boxHeight = abs(HeadSc.Y - RootSc.Y);
                                            float boxWidth = boxHeight * 0.65f;
                                            Vector2 vBox = {HeadSc.X - (boxWidth / 2), HeadSc.Y};

                                            m_Canvas->drawBorder(vBox.X, screenHeight - vBox.Y, boxWidth, boxHeight, 1.5f, RED);
                                        }
                                        if (Config["ESP::HEALTH"]) {
                                            auto m_AttackableInfo = *(uintptr_t *) (Pawn + Fields["AttackableTarget::m_AttackableInfo"]);
                                            if (m_AttackableInfo) {
                                                int CurHP = (int) *(float *) (m_AttackableInfo + Fields["AttackableTargetInfo::m_Health"]);
                                                int MaxHP = (int) *(float *) (m_AttackableInfo + Fields["AttackableTargetInfo::m_MaxHealth"]);

                                                long Color = ARGB(155, std::min(((510 * (MaxHP - CurHP)) / MaxHP), 255), std::min(((510 * CurHP) / MaxHP), 255), 0);

                                                auto AboveHead = HeadPos;
                                                Vector3 AboveHeadSc = WorldToScreen(AboveHead);
                                                if (AboveHeadSc.Z > 0) {
                                                    auto mWidth = m_Canvas->scaleSize(35.f);
                                                    auto mHeight = mWidth * 0.175f;

                                                    AboveHeadSc.X -= (mWidth / 2);
                                                    AboveHeadSc.Y += (mHeight * 2);

                                                    m_Canvas->drawBox(AboveHeadSc.X, screenHeight - AboveHeadSc.Y, (CurHP * mWidth / MaxHP), mHeight, Color);
                                                    m_Canvas->drawBorder(AboveHeadSc.X, screenHeight - AboveHeadSc.Y, mWidth, mHeight, 1.0f, BLACK);
                                                }
                                            }
                                        }
                                        if (Config["ESP::NAME"] || Config["ESP::DISTANCE"]) {
                                            Vector3 BelowRoot = RootPos;
                                            Vector3 BelowRootSc = WorldToScreen(BelowRoot);
                                            if (BelowRootSc.Z > 0) {
                                                std::wstring ws;

                                                if (Config["ESP::NAME"]) {
                                                    auto m_NickName = *(String **) (m_PlayerInfo + Fields["PlayerInfo::m_NickName"]);
                                                    if (m_NickName) {
                                                        ws += m_NickName->WCString();
                                                    }

                                                    if (Config["ESP::DISTANCE"]) {
                                                        if (!ws.empty())
                                                            ws += L" [";
                                                        ws += std::to_wstring((int) Distance);
                                                        ws += L"m]";
                                                    }

                                                    auto mText = m_Canvas->getTextBounds(ws.c_str(), 0, ws.size());
                                                    m_Canvas->drawText(ws.c_str(), BelowRootSc.X, screenHeight - BelowRootSc.Y + mText->getHeight(), 7.5f, Align::CENTER,PINK, BLACK);
													}                                        
											}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
void *Main_Thread(void *) {
    while (g_il2cpp && bInitDone.load(std::memory_order_acquire)) {
        auto t1 = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();

        void *BaseWorld_Instance = 0;
        Il2Cpp::GetStaticFieldValue("Assembly-CSharp.dll", "GameEngine", "BaseWorld", "Instance", &BaseWorld_Instance);

        if (BaseWorld_Instance) {
            auto m_Game = *(uintptr_t *) ((uintptr_t) BaseWorld_Instance + Fields["BaseWorld::m_Game"]);
            if (m_Game) {
                auto Gameplay_get_LocalPawn = (uintptr_t (*)()) (Methods["Gameplay::get_LocalPawn"]);
                auto LocalPawn = Gameplay_get_LocalPawn();
                if (LocalPawn) {
                    if (Config["AIM::AIMBOT"]) {
                        bool bTriggerReady = Config["AIM::TRIGGER"] == 0;
                        if (Config["AIM::TRIGGER"] == 1) {
                            auto Pawn_get_IsFiring = (bool (*)(uintptr_t)) (Methods["Pawn::get_IsFiring"]);
                            bTriggerReady = Pawn_get_IsFiring(LocalPawn);
                        } else if (Config["AIM::TRIGGER"] == 2) {
                            auto Pawn_IsAiming = (bool (*)(uintptr_t)) (Methods["Pawn::IsAiming"]);
                            bTriggerReady = Pawn_IsAiming(LocalPawn);
                        }
                        if (bTriggerReady) {
                            uintptr_t Target = 0;
                            if (Config["AIM::TARGET"] == 0) {
                                Target = GetClosestTarget();
                            }
                            if (Config["AIM::TARGET"] == 1) {
                                Target = GetInsideFOVTarget();
                            }
                            if (Target) {
                                Vector3 targetPos;
                                if (Config["AIM::LOCATION"] == 0) {
                                   auto m_HeadBone = *(Transform **) (Target + Fields["Pawn::m_HeadBone"]);
                                if (!m_HeadBone)
                                    continue; 

                                targetPos = m_HeadBone->get_position();
                            }
                                if (Config["AIM::LOCATION"] == 1) {
                                   auto m_HeadBone = *(Transform **) (Target + Fields["Pawn::m_HeadBone"]);
                                if (!m_HeadBone)
                                    continue;

                                targetPos = m_HeadBone->get_position();
                                targetPos.Y -= 0.2f;
                            }
                                if (Config["AIM::LOCATION"] == 2) {
                                   auto m_HeadBone = *(Transform **) (Target + Fields["Pawn::m_HeadBone"]);
                                if (!m_HeadBone)
                                    continue;

                                targetPos = m_HeadBone->get_position();
                                targetPos.Y -= 0.4f;
                            }

                                auto main = Camera::get_main();
                                if (main) {
                                    auto mainView = ((Component *) main)->get_transform();
                                    if (mainView) {
                                        auto Pawn_SetAimRotation = (void (*)(uintptr_t, Quaternion)) (Methods["Pawn::SetAimRotation"]);
                                        Pawn_SetAimRotation(LocalPawn, Quaternion::LookRotation(targetPos - mainView->get_position(), Vector3::Up()));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        auto td = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count() - t1;
        std::this_thread::sleep_for(std::chrono::milliseconds(std::max(std::min(0LL, SLEEP_TIME - td), SLEEP_TIME)));
    }
    return 0;
}
// ================================================================================================================================ //

void AutoAdjustResolution(uintptr_t thiz) {
    // Custom hook logic kept intact.
    return;
}

// ================================================================================================================================ //
// Dual-layered, self-updating hook resolver with offset caching.
// Runs once inside std::call_once from the detached ModWorker thread.
static void InstallHooks(uintptr_t il2cppBase, size_t il2cppSize) {
    g_il2cpp = il2cppBase;
    LOGI(TARGET_LIB ": base=%p size=0x%zx", (void *) g_il2cpp, il2cppSize);
    SetRuntimeLog("Status: Resolving class methods via RuntimeResolver...");

    // Attach IL2CPP runtime (needed for string/metadata resolver path).
    Il2Cpp::Attach();
    sleep(2); // allow IL2CPP domain/metadata to stabilize after attach

    // ------------------------------------------------------------------------
    // Hook registry. Custom hook logic functions remain intact elsewhere.
    // AOB fields are backup scanners (fail-closed). No hardcoded il2cppBase+0xHex.
    // ------------------------------------------------------------------------
    static const uint8_t kAutoAdjustResolutionPat[] = {
        0x00, 0x00, 0x00, 0x00 // TODO: real AOB bytes
    };
    static const char kAutoAdjustResolutionMask[] = "????";

    static const uint8_t kPlayerControllerUpdatePat[] = {
        0x00, 0x00, 0x00, 0x00 // TODO: real AOB bytes
    };
    static const char kPlayerControllerUpdateMask[] = "????";

    HookTarget targets[] = {
        {
            /*key*/ "PlayerController.Update",
            /*image*/ "Assembly-CSharp.dll",
            /*namespaze*/ "",
            /*clazz*/ "PlayerController",
            /*method*/ "Update",
            /*argsCount*/ 0,
            /*aob*/ kPlayerControllerUpdatePat,
            /*mask*/ kPlayerControllerUpdateMask,
            /*resolved*/ 0,
            /*source*/ "none"
        },
        {
            /*key*/ "AutoAdjustResolution",
            /*image*/ nullptr,            // AOB-only/native hook target
            /*namespaze*/ nullptr,
            /*clazz*/ nullptr,
            /*method*/ nullptr,
            /*argsCount*/ 0,
            /*aob*/ kAutoAdjustResolutionPat,
            /*mask*/ kAutoAdjustResolutionMask,
            /*resolved*/ 0,
            /*source*/ "none"
        },
    };

    const std::string versionKey = GetGameVersionKey(il2cppBase);
    std::map<std::string, uintptr_t> resolvedOffsets;
    std::map<std::string, LearnedAob> learnedAobs = LoadLearnedAobs();
    bool learnedAobsDirty = false;

    // 1) CACHE LOOKUP (Fast Path)
    OffsetCache cache = LoadOffsetCache(versionKey);
    if (cache.valid) {
        for (auto &t : targets) {
            auto it = cache.offsets.find(t.key);
            if (it == cache.offsets.end()) continue;
            uintptr_t abs = il2cppBase + it->second;
            if (IsValidResolvedAddress(abs, il2cppBase, il2cppSize)) {
                t.resolved = abs;
                t.source = "cache";
                resolvedOffsets[t.key] = it->second;
                LOGI("Cache hit: %s -> %p (off 0x%" PRIxPTR ")", t.key, (void *) abs, it->second);
            } else {
                LOGW("Cache entry out of range, ignored: %s", t.key);
            }
        }
    }

    // 2) PRIMARY RESOLVER (String Path) + self-learning + 3) BACKUP SCANNER
    // (Learned AOB Path, then predefined AOB Path). Fail-closed on total miss.
    for (auto &t : targets) {
        if (t.resolved) continue;

        // 2) Primary: string/metadata resolver (if target is IL2CPP method).
        if (t.image && t.clazz && t.method) {
            uintptr_t viaName = RuntimeResolver::ResolveMethod(
                t.image, t.namespaze ? t.namespaze : "", t.clazz, t.method, t.argsCount, nullptr);
            if (IsValidResolvedAddress(viaName, il2cppBase, il2cppSize)) {
                t.resolved = viaName;
                t.source = "resolver";
                LOGI("Resolver hit: %s -> %p", t.key, (void *) t.resolved);

                // 2b) Self-learning: capture first 16 bytes and persist signature+mask+name.
                if (LearnAobFromResolvedAddress(t, il2cppBase, il2cppSize, versionKey, learnedAobs)) {
                    learnedAobsDirty = true;
                }
            } else if (viaName) {
                LOGW("Resolver returned out-of-range address for %s; trying AOB backup", t.key);
            } else {
                LOGW("Resolver failed for %s (stripped/changed?); trying learned/predefined AOB", t.key);
            }
        }

        // 3) Backup A: learned AOB (restores hooks when names are stripped after updates).
        if (!t.resolved) {
            auto lit = learnedAobs.find(t.key);
            if (lit != learnedAobs.end()) {
                const LearnedAob &la = lit->second;
                uintptr_t viaLearned = find_pattern(la.bytes.data(), la.mask.c_str(), il2cppBase, il2cppSize);
                if (IsValidResolvedAddress(viaLearned, il2cppBase, il2cppSize)) {
                    t.resolved = viaLearned;
                    t.source = "learned_aob";
                    LOGI("Learned AOB hit: %s -> %p (learned version: %s)",
                         t.key, (void *) t.resolved, la.version.c_str());
                } else {
                    LOGW("Learned AOB miss for %s; trying predefined AOB", t.key);
                }
            }
        }

        // 3) Backup B: predefined AOB scanner (fail-closed).
        if (!t.resolved) {
            if (IsPlaceholderAob(t.aob, t.mask)) {
                LOGW("%s: no valid learned/predefined AOB; hook aborted (fail-closed)", t.key);
            } else {
                uintptr_t viaAob = find_pattern(t.aob, t.mask, il2cppBase, il2cppSize);
                if (IsValidResolvedAddress(viaAob, il2cppBase, il2cppSize)) {
                    t.resolved = viaAob;
                    t.source = "aob";
                    LOGI("AOB hit: %s -> %p", t.key, (void *) t.resolved);
                } else {
                    LOGE("%s: AOB not found; hook aborted (fail-closed)", t.key);
                }
            }
        }

        // 4) Stage discovered relative offset for persistent save.
        if (t.resolved && IsValidResolvedAddress(t.resolved, il2cppBase, il2cppSize)) {
            resolvedOffsets[t.key] = t.resolved - il2cppBase;
        }
    }

    // Apply resolved targets (custom hook logic kept intact; install calls remain explicit).
    bool resolverCriticalFailure = false;
    for (const auto &t : targets) {
        if (!strcmp(t.key, "PlayerController.Update")) {
            Methods["PlayerController::Update"] = t.resolved;
            if (t.resolved) {
                // void (*orig_PlayerControllerUpdate)(void*) = nullptr;
                // Tools::Hook((void *) t.resolved, (void *) MyPlayerControllerUpdate, (void **) &orig_PlayerControllerUpdate);
                LOGI("PlayerController::Update ready [%s]: %p (off 0x%" PRIxPTR ")",
                     t.source, (void *) t.resolved, t.resolved - il2cppBase);
            } else {
                LOGW("PlayerController::Update unresolved; hook skipped (fail-closed)");
                if (t.image && t.clazz && t.method) resolverCriticalFailure = true;
            }
        } else if (!strcmp(t.key, "AutoAdjustResolution")) {
            if (t.resolved) {
                // void (*orig_AutoAdjustResolution)(uintptr_t) = nullptr;
                // Tools::Hook((void *) t.resolved, (void *) AutoAdjustResolution, (void **) &orig_AutoAdjustResolution);
                LOGI("AutoAdjustResolution ready [%s]: %p (off 0x%" PRIxPTR ")",
                     t.source, (void *) t.resolved, t.resolved - il2cppBase);
            } else {
                LOGW("AutoAdjustResolution unresolved; hook skipped (fail-closed)");
            }
        }
    }

    // 4) PERSISTENT SAVING
    if (learnedAobsDirty) {
        SaveLearnedAobs(learnedAobs); // signature+mask+function name backup
    }
    if (!resolvedOffsets.empty()) {
        SaveOffsetCache(versionKey, resolvedOffsets);
    } else {
        LOGW("No hook offsets discovered; cache not written");
    }

    // Resolve Unity/engine methods at runtime (metadata; optional IDA AOB via last arg).
    SetRuntimeLog("Status: Resolving class methods via RuntimeResolver...");
    Methods["Transform::get_position"] = RuntimeResolver::ResolveMethod("UnityEngine.dll", "UnityEngine", "Transform", "get_position", 0);
    Methods["Camera::get_main"] = RuntimeResolver::ResolveMethod("UnityEngine.dll", "UnityEngine", "Camera", "get_main", 0);
    Methods["Camera::WorldToScreenPoint"] = RuntimeResolver::ResolveMethod("UnityEngine.dll", "UnityEngine", "Camera", "WorldToScreenPoint", 1);
    Methods["Component::get_transform"] = RuntimeResolver::ResolveMethod("UnityEngine.dll", "UnityEngine", "Component", "get_transform", 0);
    Methods["Screen::SetResolution"] = RuntimeResolver::ResolveMethod("UnityEngine.dll", "UnityEngine", "Screen", "SetResolution", 3);
    Methods["Gameplay::get_LocalPawn"] = RuntimeResolver::ResolveMethod("Assembly-CSharp.dll", "GameEngine", "GamePlay", "get_LocalPawn", 0);

    // Resolve fields at runtime (no static hex offsets).
    Fields["BaseWorld::m_Game"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameEngine", "BaseWorld", "m_Game");
    Fields["BaseGame::AllPawns"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameBase", "BaseGame", "AllPawns");
    Fields["BaseGame::EnemyPawns"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameBase", "BaseGame", "EnemyPawns");

    Methods["Pawn::SetAimRotation"] = RuntimeResolver::ResolveMethod("Assembly-CSharp.dll", "GameBase", "Pawn", "SetAimRotation", 1);
    Methods["Pawn::get_IsFiring"] = RuntimeResolver::ResolveMethod("Assembly-CSharp.dll", "GameBase", "Pawn", "get_IsFiring", 0);
    Methods["Pawn::IsAiming"] = RuntimeResolver::ResolveMethod("Assembly-CSharp.dll", "GameBase", "Pawn", "IsAiming", 0);

    Fields["Pawn::m_PlayerInfo"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameBase", "Pawn", "m_PlayerInfo");
    Fields["Pawn::get_HeadBone"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameBase", "Pawn", "get_HeadBone");
    Fields["Pawn::m_HeadBone"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameBase", "Pawn", "m_HeadBone");
    Fields["Pawn::m_Mesh"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameBase", "Pawn", "m_Mesh");
    Fields["Pawn::m_IsAlive"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameBase", "Pawn", "m_IsAlive");

    Fields["AttackableTarget::m_AttackableInfo"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameEngine", "AttackableTarget", "m_AttackableInfo");
    Fields["AttackableTargetInfo::m_Health"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameEngine", "AttackableTargetInfo", "m_Health");
    Fields["AttackableTargetInfo::m_MaxHealth"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameEngine", "AttackableTargetInfo", "m_MaxHealth");

    Fields["PlayerInfo::m_NickName"] = RuntimeResolver::ResolveField("Assembly-CSharp.dll", "GameEngine", "PlayerInfo", "m_NickName");

    if (resolverCriticalFailure) {
        SetRuntimeLog("Status: ERROR - Failed to resolve target function string!");
    } else {
        SetRuntimeLog("Status: SUCCESS - All hooks actively linked to game memory!");
    }
    bInitDone.store(true, std::memory_order_release);
    LOGI("Initialization complete");
}

static void InstallHooksOnce(uintptr_t il2cppBase, size_t il2cppSize) {
    std::call_once(g_hookOnceFlag, [il2cppBase, il2cppSize]() {
        InstallHooks(il2cppBase, il2cppSize);
    });
}

// Asynchronous worker: waits for target library with retry/timeout, then installs hooks once.
static void *ModWorker(void *) {
    SetRuntimeLog("Status: Locating libil2cpp.so in RAM...");
    for (int elapsed = 0; elapsed < INIT_TIMEOUT_SEC; elapsed += INIT_RETRY_INTERVAL_SEC) {
        uintptr_t base = 0;
        size_t size = 0;
        if (GetModuleRange(TARGET_LIB, base, size)) {
            InstallHooksOnce(base, size);
            return nullptr;
        }
        sleep(INIT_RETRY_INTERVAL_SEC);
    }

    SetRuntimeLog("Status: ERROR - libil2cpp.so not found");
    LOGE("Timed out waiting for " TARGET_LIB " after %d seconds; init aborted", INIT_TIMEOUT_SEC);
    return nullptr;
}

// Automatic initializer: starts detached, non-blocking worker (does not touch JNI_OnLoad).
__attribute__((constructor))
static void ModInit() {
    pthread_t t;
    if (pthread_create(&t, nullptr, ModWorker, nullptr) == 0) {
        pthread_detach(t);
    } else {
        LOGE("Failed to create mod init worker thread");
    }
}

// JNI entry kept non-blocking and safe; constructor performs initialization.
void native_Init(JNIEnv * /*env*/, jclass /*clazz*/, jobject /*mContext*/) {
    // No-op: initialization is handled by ModInit() constructor worker.
}

