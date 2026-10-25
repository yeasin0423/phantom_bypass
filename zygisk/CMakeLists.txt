cmake_minimum_required(VERSION 3.18.1)
project(phantom LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# ---------------------------------------------------------------------------
# Dobby — inline hook engine (Apache-2.0)
# ---------------------------------------------------------------------------
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)

FetchContent_Declare(dobby
    GIT_REPOSITORY https://github.com/jmpews/Dobby.git
    GIT_TAG        latest
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(dobby)
if(NOT dobby_POPULATED)
    FetchContent_Populate(dobby)
    add_subdirectory(${dobby_SOURCE_DIR} dobby-build)
endif()

# ---------------------------------------------------------------------------
# LSPlant — Java method hooking (Apache-2.0), pinned to the stable v1.0 API
# ---------------------------------------------------------------------------
FetchContent_Declare(lsplant
    GIT_REPOSITORY https://github.com/LSPosed/LSPlant.git
    GIT_TAG        v1.0
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(lsplant)
if(NOT lsplant_POPULATED)
    FetchContent_Populate(lsplant)
    add_subdirectory(${lsplant_SOURCE_DIR}/lsplant/src/main/jni lsplant-build)
endif()

# ---------------------------------------------------------------------------
# The Zygisk module
# ---------------------------------------------------------------------------
add_library(phantom SHARED
    src/phantom.cpp
    src/elf_util.cpp
)

target_include_directories(phantom PRIVATE
    src
    ${lsplant_SOURCE_DIR}/lsplant/src/main/jni/include
    ${dobby_SOURCE_DIR}/include
)

find_library(log-lib log)

target_link_libraries(phantom PRIVATE
    lsplant_static
    dobby
    ${log-lib}
)
