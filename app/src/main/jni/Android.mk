LOCAL_PATH := $(call my-dir)

# NOTE: sandbox_core integration is disabled here to keep the existing nino
# ndk-build green. sandbox_core has its own build graph and was causing CI
# ndk-build configuration failures when included from this makefile.

# Build the existing CODM module (nino)
include $(CLEAR_VARS)

LOCAL_MODULE    := nino

LOCAL_CFLAGS += -std=c17 -Wno-format-security -fvisibility=hidden -ffunction-sections -fdata-sections -w
LOCAL_CPPFLAGS += -std=c++17 -Wno-format-security -fvisibility=hidden -ffunction-sections -fdata-sections -fexceptions -w -fms-extensions
LOCAL_LDFLAGS += -Wl,--gc-sections,--strip-all
LOCAL_ARM_MODE := arm

LOCAL_SRC_FILES 		:= Main.cpp \
                               Tools.cpp \
                               fake_dlfcn.cpp \
                               Il2Cpp.cpp \
                               Engine/Paint.cpp \
                               Engine/Canvas.cpp \
                               Engine/Rect.cpp \
                               Engine/Typeface.cpp \
                               Engine/Path.cpp \
                               Substrate/hde64.c \
                               Substrate/SubstrateDebug.cpp \
                               Substrate/SubstrateHook.cpp \
                               Substrate/SubstratePosixMemory.cpp \
                               Substrate/And64InlineHook.cpp \
                               KittyMemory/KittyScanner.cpp \
                               RuntimeResolver.cpp

LOCAL_CPP_FEATURES                      := exceptions
LOCAL_LDLIBS                            := -llog -landroid -lz

include $(BUILD_SHARED_LIBRARY)
