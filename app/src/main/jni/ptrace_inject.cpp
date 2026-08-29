// Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
// All Rights Reserved.

// ptrace-based remote library loader.
//
// This is the mechanism that turns "lsplant is linked into our app" into "lsplant runs inside
// the target app": we attach to the target with PTRACE_ATTACH, hijack one of its threads, and
// use remote-syscall injection to make it call dlopen() on libnexhook.so. Once dlopen returns,
// libnexhook's JNI_OnLoad has run and HookEngine.applyAll() can be driven through a second
// remote call (via JNI's CallStaticVoidMethod on a registered entry point).
//
// Status: this implements the well-established remote-dlopen technique used by virtually every
// Android injection tool (bibinjector, zhenxun-inject, etc.). It runs with root privileges
// (the root service is the only caller) and targets:
//   - debuggable apps on production Android (always allowed for shell/root uid), or
//   - any app on userdebug/eng builds, or
//   - any app when selinux is permissive.
// On fully-locked production Android with selinux enforcing, ptrace of non-debuggable apps is
// denied by SELinux; that is an OS-level boundary no pure-app solution can cross without
// Magisk/Zygisk, and we surface it as a clear error rather than silently failing.
//
// Architecture support: arm64-v8a primary (the dominant ABI); armeabi-v7a follows the same shape
// with 32-bit register conventions. x86_64 is structurally similar.

#include <jni.h>
#include <android/log.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <linux/elf.h>
#include <dlfcn.h>
#include <unistd.h>
#include <string>
#include <cstring>
#include <cstdint>

#define LOG_TAG "nexhook-ptrace"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(__aarch64__)
// arm64 GP register set, in the order getcontext/ptrace expect.
struct UserRegs {
    unsigned long long regs[31];
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};
#define PT_GETREGS_REQ NT_PRSTATUS
#elif defined(__arm__)
struct UserRegs {
    unsigned long regs[18];
};
// PTRACE_GETREGS works on 32-bit ARM via the legacy PTRACE_GETREGS request.
#endif

namespace {

// Write `len` bytes from `buf` into the remote process at `addr`.
bool remote_write(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    iovec local{const_cast<void*>(buf), len};
    iovec remote{reinterpret_cast<void*>(addr), len};
    ssize_t n = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    return n == static_cast<ssize_t>(len);
}

bool get_regs(pid_t pid, UserRegs &regs) {
#if defined(__aarch64__)
    iovec iov{&regs, sizeof(regs)};
    return ptrace(PTRACE_GETREGSET, pid, reinterpret_cast<void*>(NT_PRSTATUS), &iov) == 0;
#else
    return ptrace(PTRACE_GETREGS, pid, nullptr, &regs) == 0;
#endif
}

bool set_regs(pid_t pid, const UserRegs &regs) {
#if defined(__aarch64__)
    iovec iov{const_cast<UserRegs*>(&regs), sizeof(regs)};
    return ptrace(PTRACE_SETREGSET, pid, reinterpret_cast<void*>(NT_PRSTATUS), &iov) == 0;
#else
    return ptrace(PTRACE_SETREGS, pid, nullptr, const_cast<UserRegs*>(&regs)) == 0;
#endif
}

// Find the remote address of a libc/libdl symbol by resolving it in our own process (same
// libraries are mapped at the same addresses in every process on Android, modulo ASLR which
// only shifts the *load base* — the offset within the library is constant). We compute the
// remote base from /proc/<pid>/maps and add the local offset.
uintptr_t find_remote_symbol(pid_t pid, const char *lib_name, const char *symbol) {
    void *local_sym = dlsym(RTLD_DEFAULT, symbol);
    if (local_sym == nullptr) {
        // Try the specific library if the symbol isn't in the default namespace.
        void *lib = dlopen(lib_name, RTLD_NOW);
        if (lib != nullptr) local_sym = dlsym(lib, symbol);
    }
    if (local_sym == nullptr) return 0;

    // Walk /proc/self/maps and /proc/<pid>/maps to find the load bases of lib_name in each,
    // then translate the local symbol address to the remote one by applying the base delta.
    auto find_base = [](const char *maps_path, const char *lname) -> uintptr_t {
        FILE *f = fopen(maps_path, "r");
        if (f == nullptr) return 0;
        char line[512];
        uintptr_t base = 0;
        while (fgets(line, sizeof(line), f) != nullptr) {
            if (strstr(line, lname) != nullptr) {
                unsigned long start;
                if (sscanf(line, "%lx-", &start) == 1) {
                    base = start;
                    break;
                }
            }
        }
        fclose(f);
        return base;
    };

    char self_maps[32];
    char pid_maps[32];
    snprintf(self_maps, sizeof(self_maps), "/proc/self/maps");
    snprintf(pid_maps, sizeof(pid_maps), "/proc/%d/maps", pid);

    uintptr_t local_base = find_base(self_maps, lib_name);
    uintptr_t remote_base = find_base(pid_maps, lib_name);
    if (local_base == 0 || remote_base == 0) return 0;

    uintptr_t local_addr = reinterpret_cast<uintptr_t>(local_sym);
    uintptr_t offset = local_addr - local_base;
    return remote_base + offset;
}

// Resume the remote thread until it traps again (i.e. until the syscall we asked it to run
// completes). On arm64 we step over the syscall with PTRACE_CONT and wait for the next trap.
bool remote_continue(pid_t pid) {
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) != 0) return false;
    int status = 0;
    if (waitpid(pid, &status, __WALL) < 0) return false;
    return WIFSTOPPED(status);
}

// Call a remote function at `fn_addr` with up to 4 arguments, returning its result.
// We set x0-x3 (or r0-r3 on arm32) and the return address (lr) to a tiny invalid address so the
// call traps on return; we catch that trap and read x0.
uintptr_t remote_call(pid_t pid, uintptr_t fn_addr,
                      uintptr_t arg1 = 0, uintptr_t arg2 = 0,
                      uintptr_t arg3 = 0, uintptr_t arg4 = 0) {
    UserRegs regs{};
    if (!get_regs(pid, regs)) return 0;
    UserRegs saved = regs;
#if defined(__aarch64__)
    regs.regs[0] = arg1;
    regs.regs[1] = arg2;
    regs.regs[2] = arg3;
    regs.regs[3] = arg4;
    regs.regs[30] = 0;  // lr = 0 → return traps with SIGSEGV at address 0, which we catch.
    regs.pc = fn_addr;
#elif defined(__arm__)
    regs.regs[0] = arg1;
    regs.regs[1] = arg2;
    regs.regs[2] = arg3;
    regs.regs[3] = arg4;
    regs.regs[14] = 0;  // lr
    unsigned long *pc_slot = &regs.regs[15];
    *pc_slot = fn_addr;
#endif
    if (!set_regs(pid, regs)) return 0;
    if (!remote_continue(pid)) {
        set_regs(pid, saved);
        return 0;
    }
    if (!get_regs(pid, regs)) return 0;
#if defined(__aarch64__)
    uintptr_t result = static_cast<uintptr_t>(regs.regs[0]);
#else
    uintptr_t result = static_cast<uintptr_t>(regs.regs[0]);
#endif
    set_regs(pid, saved);
    return result;
}

}  // namespace

// Inject `library_path` into process `target_pid` by remote-calling dlopen.
// Returns the remote dlopen handle, or 0 on failure.
extern "C" uintptr_t nexhook_inject_dlopen(pid_t target_pid, const char *library_path) {
    LOGI("attaching to pid %d", target_pid);
    if (ptrace(PTRACE_ATTACH, target_pid, nullptr, nullptr) != 0) {
        LOGE("PTRACE_ATTACH failed: %s", strerror(errno));
        return 0;
    }
    int status = 0;
    if (waitpid(target_pid, &status, __WALL) < 0 || !WIFSTOPPED(status)) {
        LOGE("waitpid after attach failed");
        ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
        return 0;
    }

    uintptr_t remote_dlopen = find_remote_symbol(target_pid, "libdl.so", "dlopen");
    if (remote_dlopen == 0) {
        // On Android 8.0+ dlopen is in libc's loader namespace; try __loader_dlopen.
        remote_dlopen = find_remote_symbol(target_pid, "linker", "__loader_dlopen");
    }
    if (remote_dlopen == 0) {
        LOGE("could not locate remote dlopen");
        ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
        return 0;
    }

    // Allocate space in the target for the library path string by remote-calling mmap.
    uintptr_t remote_mmap = find_remote_symbol(target_pid, "libc.so", "mmap");
    if (remote_mmap == 0) {
        LOGE("could not locate remote mmap");
        ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
        return 0;
    }
    size_t path_len = strlen(library_path) + 1;
    uintptr_t remote_str = remote_call(target_pid, remote_mmap,
        /*addr*/0, path_len, /*prot*/0x3 /*PROT_READ|PROT_WRITE*/,
        /*flags*/0x22 /*MAP_PRIVATE|MAP_ANONYMOUS*/);
    if (remote_str == 0 || remote_str == static_cast<uintptr_t>(-1)) {
        LOGE("remote mmap for path string failed");
        ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
        return 0;
    }
    if (!remote_write(target_pid, remote_str, library_path, path_len)) {
        LOGE("remote_write of path string failed");
        ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
        return 0;
    }

    uintptr_t handle = remote_call(target_pid, remote_dlopen, remote_str, /*mode*/1 /*RTLD_NOW*/);

    ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
    LOGI("injection result: handle=%p", reinterpret_cast<void*>(handle));
    return handle;
}
