cmake_minimum_required(VERSION 3.22.1)
project(clox_launcher)

# Axmol Engine — expected as git submodule at project root/axmol
# Build with: cd axmol && git checkout v2.x-stable
set(AX_ENABLE_LUA ON CACHE BOOL "" FORCE)
set(AX_ENABLE_PHYSICS OFF CACHE BOOL "" FORCE)
set(AX_ENABLE_AUDIO ON CACHE BOOL "" FORCE)

if(EXISTS "${CMAKE_SOURCE_DIR}/../axmol/CMakeLists.txt")
    add_subdirectory(${CMAKE_SOURCE_DIR}/../axmol axmol_build)
    set(AXMOL_AVAILABLE TRUE)
else()
    message(WARNING "Axmol submodule not found at ../axmol — building stub JNI only")
    set(AXMOL_AVAILABLE FALSE)
endif()

add_library(
    launcher_native
    SHARED
    app/src/main/cpp/launcher_native.cpp
)

find_library(log-lib log)
find_library(android-lib android)

if(AXMOL_AVAILABLE)
    target_link_libraries(
        launcher_native
        axmol
        lua
        ${log-lib}
        ${android-lib}
    )
else()
    target_link_libraries(
        launcher_native
        ${log-lib}
        ${android-lib}
    )
endif()
