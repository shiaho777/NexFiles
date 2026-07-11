cmake_minimum_required(VERSION 3.6)

project(NexFiles C CXX)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})

# PTY layer for the built-in proot terminal. Links util-ftem (forkpty) and runtime (TIOCSWINSZ).
add_library(terminal SHARED
        src/main/jni/terminal.c)
target_compile_options(terminal
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(terminal
        PRIVATE
        ${LOG_LIBRARY})

# lsplant JNI bridge. C++17 (lsplant uses std::function/string_view); links the prefab-provided
# lsplant::lsplant (liblsplant.so) and ShadowHook (libshadowhook.so, the inline-hook backend).
# Together these close the full hook chain: lsplant decides what to hook, ShadowHook patches the
# native code that ART uses to dispatch methods.
add_library(nexhook SHARED
        src/main/jni/nexhook.cpp)
target_compile_features(nexhook PRIVATE cxx_std_17)
target_compile_options(nexhook
        PRIVATE
        -Wall
        -Werror
        -fvisibility=hidden
        -fexceptions)
find_package(lsplant REQUIRED CONFIG)
find_package(shadowhook REQUIRED CONFIG)
target_link_libraries(nexhook
        PRIVATE
        lsplant::lsplant
        shadowhook::shadowhook
        ${LOG_LIBRARY}
        dl)

# ptrace-based remote dlopen injector. Runs in the root service to load libnexhook.so into a
# target app process. Only the single C entry point nexhook_inject_dlopen is exported; everything
# else is static. Linked against log only.
add_library(nexhook_inject SHARED
        src/main/jni/ptrace_inject.cpp)
target_compile_features(nexhook_inject PRIVATE cxx_std_17)
target_compile_options(nexhook_inject
        PRIVATE
        -Wall
        -Werror
        -fvisibility=hidden)
target_link_libraries(nexhook_inject
        PRIVATE
        ${LOG_LIBRARY}
        dl)
