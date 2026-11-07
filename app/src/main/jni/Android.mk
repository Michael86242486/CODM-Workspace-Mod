LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# Keep the existing module name stable (Java loads "nino").
LOCAL_MODULE    := nino

# Modernized flags while keeping ndk-build setup stable.
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



