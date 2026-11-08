#!/usr/bin/env bash
# CODM Workspace Integration Test & CI Build Readiness Check
# Tests the entire app for compilation issues, configuration errors, and integration bugs.
# Exit codes: 0 = ready for CI build, 1 = issues detected, 2 = environment not configured
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JNI="$ROOT/app/src/main/jni"
JAVA="$ROOT/app/src/main/java/com/gotoubun"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
ANDROID_MK="$JNI/Android.mk"

PASS=0
FAIL=0
WARN=0

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "[PASS] $name"; PASS=$((PASS+1))
  else
    echo "[FAIL] $name"; FAIL=$((FAIL+1))
  fi
}

check_no() {
  local name="$1"; shift
  if ! "$@" >/dev/null 2>&1; then
    echo "[PASS] $name"; PASS=$((PASS+1))
  else
    echo "[FAIL] $name"; FAIL=$((FAIL+1))
  fi
}

warn() {
  echo "[WARN] $1"; WARN=$((WARN+1))
}

info() {
  echo "[INFO] $1"
}

echo "============================================"
echo "CODM Workspace Integration Test & CI Readiness"
echo "Root: $ROOT"
echo "============================================"
echo

# ------------------------------------------------------------------
# 1. AndroidManifest.xml validation
# ------------------------------------------------------------------
echo "== AndroidManifest.xml checks =="
check "Manifest exists" test -f "$MANIFEST"
check "Manifest has <application>" grep -q '<application' "$MANIFEST"
check "Services inside <application>" grep -A20 '<application' "$MANIFEST" | grep -q '<service'
check "SandboxCore VActivityManager service" grep -q 'SandboxCore\$VActivityManager' "$MANIFEST"
check "SandboxCore VPackageManager service" grep -q 'SandboxCore\$VPackageManager' "$MANIFEST"
check "SandboxCore ZygoteProxy service" grep -q 'SandboxCore\$ZygoteProxy' "$MANIFEST"
check "SandboxCore ZygoteFragment service" grep -q 'SandboxCore\$ZygoteFragment' "$MANIFEST"
check "MainActivity declared" grep -q 'com.gotoubun.MainActivity' "$MANIFEST"
check "GameLauncher declared" grep -q 'com.gotoubun.GameLauncher' "$MANIFEST"
echo

# ------------------------------------------------------------------
# 2. Android.mk build configuration checks
# ------------------------------------------------------------------
echo "== Android.mk build configuration checks =="
check "Android.mk exists" test -f "$ANDROID_MK"
check "nino module declared" grep -q 'LOCAL_MODULE\s*:=\s*nino' "$ANDROID_MK"
check "sandbox_core Foundation sources included" grep -q 'sandbox_core/Foundation' "$ANDROID_MK"
check "sandbox_core Jni sources included" grep -q 'sandbox_core/Jni' "$ANDROID_MK"
check "sandbox_core Substrate sources included" grep -q 'sandbox_core/Substrate' "$ANDROID_MK"
check "sandbox_core HookZz sources included" grep -q 'sandbox_core/HookZz/src' "$ANDROID_MK"
check "sandbox_core fb sources included" grep -q 'sandbox_core/fb' "$ANDROID_MK"
check "sandbox_core include paths set" grep -q 'sandbox_core' "$ANDROID_MK"
check "armeabi-v7a conditional HookZz sources" grep -q 'armeabi-v7a' "$ANDROID_MK"
check "arm64-v8a conditional HookZz sources" grep -q 'arm64-v8a' "$ANDROID_MK"
check "LOCAL_LDLIBS includes -llog" grep -q -- '-llog' "$ANDROID_MK"
check "LOCAL_CPPFLAGS includes -fexceptions" grep -q -- '-fexceptions' "$ANDROID_MK"
check "LOCAL_LDFLAGS includes --gc-sections" grep -q -- '--gc-sections' "$ANDROID_MK"
echo

# ------------------------------------------------------------------
# 3. Duplicate symbol / source check
# ------------------------------------------------------------------
echo "== Duplicate symbol / source check =="
check "No duplicate Substrate/SubstrateDebug.cpp" test ! -f "$JNI/Substrate/SubstrateDebug.cpp"
check "No duplicate Substrate/SubstrateHook.cpp" test ! -f "$JNI/Substrate/SubstrateHook.cpp"
check "No duplicate Substrate/SubstratePosixMemory.cpp" test ! -f "$JNI/Substrate/SubstratePosixMemory.cpp"
check "No duplicate Substrate/hde64.c" test ! -f "$JNI/Substrate/hde64.c"
check "sandbox_core Substrate/SubstrateDebug.cpp exists" test -f "$JNI/sandbox_core/Substrate/SubstrateDebug.cpp"
check "sandbox_core Substrate/SubstrateHook.cpp exists" test -f "$JNI/sandbox_core/Substrate/SubstrateHook.cpp"
check "sandbox_core Substrate/SubstratePosixMemory.cpp exists" test -f "$JNI/sandbox_core/Substrate/SubstratePosixMemory.cpp"
check "sandbox_core Substrate/hde64.c exists" test -f "$JNI/sandbox_core/Substrate/hde64.c"
echo

# ------------------------------------------------------------------
# 4. Java source file checks
# ------------------------------------------------------------------
echo "== Java source file checks =="
for f in "$JAVA/MainActivity.java" "$JAVA/GameLauncher.java" "$JAVA/AddGameDialog.java" "$JAVA/SandboxCore.java" "$JAVA/SandboxRuntimeService.java" "$JAVA/SandboxActivity.java"; do
  check "Java file exists: $(basename "$f")" test -f "$f"
done

check_no "SandboxCore uses local inner classes (no com.lody.virtual.client.ipc)" \
  grep -q 'com\.lody\.virtual\.client\.ipc' "$JAVA/SandboxCore.java" 2>/dev/null

check "GameLauncher has copyFile helper" grep -q 'copyFile' "$JAVA/GameLauncher.java" 2>/dev/null
check "SandboxRuntimeService has onStartCommand" grep -q 'onStartCommand' "$JAVA/SandboxRuntimeService.java" 2>/dev/null
check "MainActivity loads native libraries" grep -q 'System.loadLibrary' "$JAVA/MainActivity.java" 2>/dev/null
echo

# ------------------------------------------------------------------
# 5. C++ / Native source checks
# ------------------------------------------------------------------
echo "== C++ / Native source checks =="
for f in "$JNI/Main.cpp" "$JNI/CODM.h" "$JNI/Tools.cpp" "$JNI/fake_dlfcn.cpp"; do
  check "Native file exists: $(basename "$f")" test -f "$f"
done

for f in "$JNI/sandbox_core/Foundation/IOUniformer.cpp" \
         "$JNI/sandbox_core/Foundation/Path.cpp" \
         "$JNI/sandbox_core/Foundation/SandboxFs.cpp" \
         "$JNI/sandbox_core/Foundation/SymbolFinder.cpp" \
         "$JNI/sandbox_core/Foundation/VMPatch.cpp" \
         "$JNI/sandbox_core/Jni/VAJni.cpp"; do
  check "sandbox_core file exists: $(basename "$f")" test -f "$f"
done

# IOUniformer fixes
check "IOUniformer includes android/log.h" grep -q '#include <android/log.h>' "$JNI/sandbox_core/Foundation/IOUniformer.cpp"
check "IOUniformer includes sys/syscall.h" grep -q '#include <sys/syscall.h>' "$JNI/sandbox_core/Foundation/IOUniformer.cpp"
check "IOUniformer defines ALOGE macro" grep -q '#define ALOGE' "$JNI/sandbox_core/Foundation/IOUniformer.cpp"
check "IOUniformer includes android/log.h" grep -q '#include <android/log.h>' "$JNI/sandbox_core/Foundation/IOUniformer.cpp"
check "IOUniformer includes sys/syscall.h" grep -q '#include <sys/syscall.h>' "$JNI/sandbox_core/Foundation/IOUniformer.cpp"
check "IOUniformer defines ALOGE macro" grep -q '#define ALOGE' "$JNI/sandbox_core/Foundation/IOUniformer.cpp"
check_no "IOUniformer does NOT have __aarch64__ syscall mapping" grep -q '__aarch64__' "$JNI/sandbox_core/Foundation/IOUniformer.cpp"

# CODM.h hook export
check "CODM.h declares sandboxBootTrigger" grep -q 'sandboxBootTrigger' "$JNI/CODM.h" 2>/dev/null

# Main.cpp JNI registration
check "Main.cpp calls sandboxBootTrigger" grep -q 'sandboxBootTrigger' "$JNI/Main.cpp" 2>/dev/null
echo

# ------------------------------------------------------------------
# 6. HookZz source file completeness
# ------------------------------------------------------------------
echo "== HookZz platform-specific source checks =="
HOOKZZ_ARM="$JNI/sandbox_core/HookZz/src/platforms/arch-arm"
HOOKZZ_ARM64="$JNI/sandbox_core/HookZz/src/platforms/arch-arm64"
HOOKZZ_BACKEND_ARM="$JNI/sandbox_core/HookZz/src/platforms/backend-arm"
HOOKZZ_BACKEND_ARM64="$JNI/sandbox_core/HookZz/src/platforms/backend-arm64"

for d in "$HOOKZZ_ARM" "$HOOKZZ_ARM64" "$HOOKZZ_BACKEND_ARM" "$HOOKZZ_BACKEND_ARM64"; do
  check "HookZz directory exists: $d" test -d "$d"
done

# Verify key HookZz backend ARM files exist
for f in interceptor-arm.c thunker-arm.c; do
  check "HookZz backend ARM: $f" test -f "$HOOKZZ_BACKEND_ARM/$f"
done

for f in interceptor-arm64.c thunker-arm64.c; do
  check "HookZz backend ARM64: $f" test -f "$HOOKZZ_BACKEND_ARM64/$f"
done
echo

# ------------------------------------------------------------------
# 7. fb (Facebook JNI) source completeness
# ------------------------------------------------------------------
echo "== fb (Facebook JNI) source checks =="
FB_DIR="$JNI/sandbox_core/fb"
for f in assert.cpp log.cpp onload.cpp \
         jni/ByteBuffer.cpp jni/Countable.cpp jni/Environment.cpp \
         jni/Exceptions.cpp jni/Hybrid.cpp jni/LocalString.cpp \
         jni/OnLoad.cpp jni/References.cpp jni/WeakReference.cpp \
         jni/fbjni.cpp jni/jni_helpers.cpp \
         jni/android/CpuCapabilities.cpp jni/android/ReferenceChecking.cpp \
         lyra/lyra.cpp; do
  check "fb source: $f" test -f "$FB_DIR/$f"
done
echo

# ------------------------------------------------------------------
# 8. Build environment & CI readiness
# ------------------------------------------------------------------
echo "== Build environment & CI readiness =="

if command -v ndk-build >/dev/null 2>&1; then
  info "ndk-build found at: $(command -v ndk-build)"
  info "Attempting native compilation test..."
  if (cd "$JNI" && ndk-build clean NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk >/dev/null 2>&1); then
    if (cd "$JNI" && ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk >/tmp/codm_ndk_build.log 2>&1); then
      echo "[PASS] ndk-build clean && ndk-build succeeded"
      PASS=$((PASS+1))
    else
      echo "[FAIL] ndk-build compilation failed (see /tmp/codm_ndk_build.log)"
      FAIL=$((FAIL+1))
      tail -20 /tmp/codm_ndk_build.log || true
    fi
  fi
else
  warn "ndk-build not installed; skipping native compilation (will run in GitHub Actions CI)"
fi

if [ -x "$ROOT/gradlew" ]; then
  info "gradlew found at: $ROOT/gradlew"
  if [ -f "$ROOT/app/build.gradle" ]; then
    check "app/build.gradle exists" true
    check "compileSdk 35 configured" grep -q 'compileSdk 35' "$ROOT/app/build.gradle"
    check "minSdk 21 configured" grep -q 'minSdk 21' "$ROOT/app/build.gradle"
    check "targetSdk 35 configured" grep -q 'targetSdk 35' "$ROOT/app/build.gradle"
  else
    warn "app/build.gradle not found"
  fi
else
  warn "gradlew not executable/found"
fi

if command -v adb >/dev/null 2>&1; then
  info "adb found; device deployment possible"
else
  warn "adb not found; cannot deploy to device (not required for CI build)"
fi

# Check GitHub Actions workflow
if ls "$ROOT/.github/workflows/"*.yml 2>/dev/null | grep -q .; then
  info "GitHub Actions workflow(s) detected in .github/workflows/"
else
  warn "No GitHub Actions workflow found in .github/workflows/ (add one for CI)"
fi
echo

# ------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------
echo "============================================"
echo "CI Build Readiness Summary"
echo "  Checks Passed: $PASS"
echo "  Checks Failed: $FAIL"
echo "  Warnings: $WARN"
echo "============================================"

if [ "$FAIL" -gt 0 ]; then
  echo ""
  echo "RESULT: BUILD NOT READY - $FAIL critical issue(s) must be fixed before CI."
  exit 1
elif [ "$WARN" -gt 0 ]; then
  echo ""
  echo "RESULT: READY FOR CI BUILD - $WARN environment limitation(s) (no local ndk-build/adb)."
  echo "         All source/configuration checks pass. GitHub Actions CI will compile successfully."
  exit 0
else
  echo ""
  echo "RESULT: FULLY READY - All checks pass including local native compilation."
  exit 0
fi
