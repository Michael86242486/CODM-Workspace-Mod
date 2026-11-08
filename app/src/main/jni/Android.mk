LOCAL_PATH := $(call my-dir)

# Build the existing CODM module (nino) with fully integrated sandbox_core.

include $(CLEAR_VARS)

LOCAL_MODULE    := nino

LOCAL_CFLAGS += -std=c17 -Wno-format-security -fvisibility=hidden -ffunction-sections -fdata-sections -w
LOCAL_CPPFLAGS += -std=c++17 -Wno-format-security -fvisibility=hidden -ffunction-sections -fdata-sections -fexceptions -w -fms-extensions
LOCAL_LDFLAGS += -Wl,--gc-sections,--strip-all
LOCAL_ARM_MODE := arm

# Include sandbox_core directories for header search paths.
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/Foundation
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/Jni
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/Substrate
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/include
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/platforms/arch-arm
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/platforms/arch-arm64
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/platforms/arch-x86
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/platforms/backend-arm
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/platforms/backend-arm64
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/platforms/backend-linux
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/platforms/backend-posix
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/zzdeps/common
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/zzdeps/linux
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/HookZz/src/zzdeps/posix
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/fb/include
LOCAL_C_INCLUDES += $(LOCAL_PATH)/sandbox_core/fb/jni

LOCAL_SRC_FILES 		:= Main.cpp \
                               Tools.cpp \
                               fake_dlfcn.cpp \
                               Il2Cpp.cpp \
                               Engine/Paint.cpp \
                               Engine/Canvas.cpp \
                               Engine/Rect.cpp \
                               Engine/Typeface.cpp \
                               Engine/Path.cpp \
                               KittyMemory/KittyScanner.cpp \
                               RuntimeResolver.cpp

# --- sandbox_core Foundation sources ---
LOCAL_SRC_FILES += sandbox_core/Foundation/IOUniformer.cpp \
                   sandbox_core/Foundation/Path.cpp \
                   sandbox_core/Foundation/SandboxFs.cpp \
                   sandbox_core/Foundation/SymbolFinder.cpp \
                   sandbox_core/Foundation/VMPatch.cpp

# --- sandbox_core Jni sources ---
LOCAL_SRC_FILES += sandbox_core/Jni/VAJni.cpp

# --- sandbox_core Substrate sources (reuse nino local copies) ---
LOCAL_SRC_FILES += sandbox_core/Substrate/SubstrateDebug.cpp \
                   sandbox_core/Substrate/SubstrateHook.cpp \
                   sandbox_core/Substrate/SubstratePosixMemory.cpp \
                   sandbox_core/Substrate/hde64.c

# --- sandbox_core fb static library sources ---
LOCAL_SRC_FILES += sandbox_core/fb/assert.cpp \
                   sandbox_core/fb/jni/ByteBuffer.cpp \
                   sandbox_core/fb/jni/Countable.cpp \
                   sandbox_core/fb/jni/Environment.cpp \
                   sandbox_core/fb/jni/Exceptions.cpp \
                   sandbox_core/fb/jni/Hybrid.cpp \
                   sandbox_core/fb/jni/LocalString.cpp \
                   sandbox_core/fb/jni/OnLoad.cpp \
                   sandbox_core/fb/jni/References.cpp \
                   sandbox_core/fb/jni/WeakReference.cpp \
                   sandbox_core/fb/jni/android/CpuCapabilities.cpp \
                   sandbox_core/fb/jni/android/ReferenceChecking.cpp \
                   sandbox_core/fb/jni/fbjni.cpp \
                   sandbox_core/fb/jni/jni_helpers.cpp \
                   sandbox_core/fb/log.cpp \
                   sandbox_core/fb/lyra/lyra.cpp \
                   sandbox_core/fb/onload.cpp

# --- HookZz core sources (C files) ---
LOCAL_SRC_FILES += sandbox_core/HookZz/src/allocator.c \
                   sandbox_core/HookZz/src/interceptor.c \
                   sandbox_core/HookZz/src/memory.c \
                   sandbox_core/HookZz/src/stack.c \
                   sandbox_core/HookZz/src/trampoline.c \
                   sandbox_core/HookZz/src/zzinfo.c \
                   sandbox_core/HookZz/src/zzdeps/common/memory-utils-common.c \
                   sandbox_core/HookZz/src/zzdeps/linux/memory-utils-linux.c \
                   sandbox_core/HookZz/src/zzdeps/posix/memory-utils-posix.c \
                   sandbox_core/HookZz/src/zzdeps/posix/thread-utils-posix.c \
                   sandbox_core/HookZz/src/platforms/backend-linux/memory-linux.c \
                   sandbox_core/HookZz/src/platforms/backend-posix/thread-posix.c

# HookZz platform-specific sources (conditional on ABI)
ifeq ($(TARGET_ARCH_ABI), armeabi-v7a)
    LOCAL_SRC_FILES += sandbox_core/HookZz/src/platforms/arch-arm/instructions.c \
                       sandbox_core/HookZz/src/platforms/arch-arm/reader-arm.c \
                       sandbox_core/HookZz/src/platforms/arch-arm/reader-thumb.c \
                       sandbox_core/HookZz/src/platforms/arch-arm/regs-arm.c \
                       sandbox_core/HookZz/src/platforms/arch-arm/relocator-arm.c \
                       sandbox_core/HookZz/src/platforms/arch-arm/relocator-thumb.c \
                       sandbox_core/HookZz/src/platforms/arch-arm/writer-arm.c \
                       sandbox_core/HookZz/src/platforms/arch-arm/writer-thumb.c \
                       sandbox_core/HookZz/src/platforms/backend-arm/interceptor-arm.c \
                       sandbox_core/HookZz/src/platforms/backend-arm/thunker-arm.c
endif

ifeq ($(TARGET_ARCH_ABI), arm64-v8a)
    LOCAL_SRC_FILES += sandbox_core/HookZz/src/platforms/arch-arm64/instructions.c \
                       sandbox_core/HookZz/src/platforms/arch-arm64/reader-arm64.c \
                       sandbox_core/HookZz/src/platforms/arch-arm64/regs-arm64.c \
                       sandbox_core/HookZz/src/platforms/arch-arm64/relocator-arm64.c \
                       sandbox_core/HookZz/src/platforms/arch-arm64/writer-arm64.c \
                       sandbox_core/HookZz/src/platforms/backend-arm64/interceptor-arm64.c \
                       sandbox_core/HookZz/src/platforms/backend-arm64/thunker-arm64.c
endif

ifeq ($(TARGET_ARCH_ABI), x86)
    LOCAL_SRC_FILES += sandbox_core/HookZz/src/platforms/arch-x86/instructions.c \
                       sandbox_core/HookZz/src/platforms/arch-x86/reader-x86.c \
                       sandbox_core/HookZz/src/platforms/arch-x86/regs-x86.c \
                       sandbox_core/HookZz/src/platforms/arch-x86/relocator-x86.c \
                       sandbox_core/HookZz/src/platforms/arch-x86/writer-x86.c \
                       sandbox_core/HookZz/src/platforms/backend-x86/interceptor-x86.c \
                       sandbox_core/HookZz/src/platforms/backend-x86/thunker-x86.c
endif

LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog -landroid -lz -latomic

include $(BUILD_SHARED_LIBRARY)