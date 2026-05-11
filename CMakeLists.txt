cmake_minimum_required(VERSION 3.22)

set(APP_NAME clox)
project(${APP_NAME})

set(_AX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/axmol")

if(EXISTS "${_AX_ROOT}/CMakeLists.txt")
    message(STATUS "Axmol found at ${_AX_ROOT}")

    # Use repo-tracked axslcc binary; fall back to Axmol's bundled copy
    set(_AXSLCC_REPO_BIN "${CMAKE_CURRENT_SOURCE_DIR}/tools/axslcc/bin/axslcc")
    if(EXISTS "${_AXSLCC_REPO_BIN}")
        set(AXSLCC_EXE "${_AXSLCC_REPO_BIN}" CACHE FILEPATH "axslcc" FORCE)
    endif()

    # Required so AXBuildSet.cmake can derive the shader output path
    set(_AX_ANDROID_PROJECT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/app" CACHE PATH "" FORCE)

    set(BUILD_ENGINE_DONE TRUE)
    add_subdirectory(${_AX_ROOT} axmol_build)

    file(GLOB_RECURSE GAME_SOURCE
        app/src/main/cpp/*.cpp
        app/src/main/cpp/*.h)

    add_library(${APP_NAME} SHARED ${GAME_SOURCE})

    target_include_directories(${APP_NAME} PRIVATE
        app/src/main/cpp
        $<TARGET_PROPERTY:axmol,INTERFACE_INCLUDE_DIRECTORIES>
        $<TARGET_PROPERTY:${AX_LUA_ENGINE},INTERFACE_INCLUDE_DIRECTORIES>
        ${_AX_ROOT}/extensions/scripting
        ${_AX_ROOT}/3rdparty/lua/plainlua
        ${_AX_ROOT}/3rdparty/lua/tolua
    )

    if(ANDROID)
        # Exactly mirrors axmol/tests/lua-tests/CMakeLists.txt Android section
        target_link_libraries(${APP_NAME}
            -Wl,--whole-archive cpp_android_spec -Wl,--no-whole-archive)

        # Build and link lua_android_spec (LuaJavaBridge JNI)
        set(_ENGINE_BINARY_PATH "${CMAKE_BINARY_DIR}/axmol_build/engine")
        add_subdirectory(
            ${_AX_ROOT}/extensions/scripting/lua-bindings/proj.android
            ${_ENGINE_BINARY_PATH}/extensions/lua-android)
        target_link_libraries(${APP_NAME}
            -Wl,--whole-archive lua_android_spec -Wl,--no-whole-archive)
    endif()

    # ax_setup_app_config appends _AX_EXTENSION_LIBS (cocostudio, GUI, etc.)
    ax_setup_app_config(${APP_NAME})

    # Link Lua bindings last; use --start-group so the linker re-scans static libs
    # to resolve mutual dependencies between axlua, cocostudio, and other extensions.
    target_link_libraries(${APP_NAME}
        -Wl,--start-group ${_AX_LUA_LIB} ${_AX_EXTENSION_LIBS} -Wl,--end-group)

else()
    message(WARNING "Axmol submodule not found — building JNI stub only")

    add_library(launcher_native SHARED app/src/main/cpp/launcher_native.cpp)
    find_library(log-lib log)
    find_library(android-lib android)
    target_link_libraries(launcher_native ${log-lib} ${android-lib})
endif()
