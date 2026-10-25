#pragma once
//
// logging.h — minimal logging macros for the vendored SandHook ElfImg
// (elf_util.cpp includes this; the Zygisk module core uses its own macros
// defined in phantom.cpp, so the two never clash).
//
#ifndef PHANTOM_ELF_LOGGING_H
#define PHANTOM_ELF_LOGGING_H

#include <android/log.h>

#ifndef ELF_LOG_TAG
#define ELF_LOG_TAG "Phantom-ElfImg"
#endif

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, ELF_LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, ELF_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, ELF_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ELF_LOG_TAG, __VA_ARGS__)

#endif // PHANTOM_ELF_LOGGING_H
