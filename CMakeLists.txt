cmake_minimum_required(VERSION 3.22)

set(APP_NAME clox)
project(${APP_NAME})

# ── Locate Axmol engine ──────────────────────────────────────────────────────
set(_AX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/axmol")

if(EXISTS "${_AX_ROOT}/CMakeLists.txt")
    message(STATUS "Axmol found at ${_AX_ROOT}")

    # axslcc — check repo tools/ first, then Axmol's own tools dir
    set(_AXSLCC_REPO_BIN "${CMAKE_CURRENT_SOURCE_DIR}/tools/axslcc/bin/axslcc")
    set(_AXSLCC_AX_BIN   "${_AX_ROOT}/tools/external/axslcc/bin/axslcc")
    if(EXISTS "${_AXSLCC_REPO_BIN}")
        set(AXSLCC_EXE "${_AXSLCC_REPO_BIN}" CACHE FILEPATH "axslcc shader compiler" FORCE)
    elseif(EXISTS "${_AXSLCC_AX_BIN}")
        set(AXSLCC_EXE "${_AXSLCC_AX_BIN}"   CACHE FILEPATH "axslcc shader compiler" FORCE)
    endif()

    # Android project dir — AXBuildSet.cmake uses this to set AXSLCC_OUT_DIR
    set(_AX_ANDROID_PROJECT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/app"
        CACHE PATH "Android app module directory" FORCE)

    set(AX_ENABLE_EXT_LUA    ON  CACHE BOOL "" FORCE)
    set(AX_ENABLE_PHYSICS     OFF CACHE BOOL "" FORCE)
    set(AX_ENABLE_AUDIO       ON  CACHE BOOL "" FORCE)
    set(AX_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
    set(AX_ENABLE_EXT_SPINE   OFF CACHE BOOL "" FORCE)
    set(AX_ENABLE_EXT_DRAGONBONES OFF CACHE BOOL "" FORCE)
    set(AX_ENABLE_EXT_COCOSTUDIO  OFF CACHE BOOL "" FORCE)

    set(BUILD_ENGINE_DONE TRUE)
    add_subdirectory(${_AX_ROOT} axmol_build)

    # Collect native sources
    file(GLOB_RECURSE GAME_SOURCE
        app/src/main/cpp/*.cpp
        app/src/main/cpp/*.h
    )

    add_library(${APP_NAME} SHARED ${GAME_SOURCE})

    target_include_directories(${APP_NAME} PRIVATE
        app/src/main/cpp
    )

    target_link_libraries(${APP_NAME} axmol)

    # Axmol helper: copies Lua + assets into the correct place for Android packaging
    ax_setup_app_config(${APP_NAME})

else()
    # ── Axmol submodule absent — build JNI stub only ─────────────────────────
    message(WARNING "Axmol not found at ${_AX_ROOT} — building JNI stub only (screen will be black)")

    add_library(launcher_native SHARED
        app/src/main/cpp/launcher_native.cpp
    )

    find_library(log-lib log)
    find_library(android-lib android)
    target_link_libraries(launcher_native ${log-lib} ${android-lib})
endif()
