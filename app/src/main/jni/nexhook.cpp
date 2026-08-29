// Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
// All Rights Reserved.

// nexhook: the C++ JNI bridge between NexFiles' Kotlin hook API and lsplant.
//
// lsplant-standalone is a pure-native library: its public surface is the C++ API in lsplant.hpp.
// This bridge implements the three responsibilities lsplant delegates to its host:
//
//   1. An inline-hooker backend. lsplant rewrites ArtMethod entry points by patching the native
//      ART functions that dispatch them; it calls our `inline_hooker(target, hooker)` to install
//      each native inline hook and expects a backup trampoline back. The backend is pluggable:
//      `set_inline_hook_backend()` lets the host wire in ShadowHook / Dobby / a hand-rolled
//      trampoline installer. Until one is wired in, the backend returns null and lsplant::Init
//      fails with a clear log line rather than crashing.
//
//   2. An ART symbol resolver. lsplant needs to locate internal symbols of libart.so (both
//      .dynsym and .symtab). We implement this by dlopen-ing libart.so and walking its ELF
//      tables with a small built-in reader.
//
//   3. JNI lifecycle: JNI_OnLoad calls lsplant::Init once, caches the JNIEnv and the Java hook
//      callback signature, and exposes hook/unhook/isHooked to Kotlin.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <string>
#include <string_view>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cstring>
#include <cstdint>

#include <lsplant.hpp>
#include <shadowhook.h>

#define LOG_TAG "nexhook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------------
//  Inline-hook backend: ShadowHook.
//
//  lsplant's InitInfo.inline_hooker has signature `void* (void* target, void* hooker)` and must
//  return a trampoline that invokes the original. ShadowHook's shadowhook_hook_func_addr writes
//  that trampoline into an out-parameter (`orig_addr`) and returns a `stub` used to unhook later.
//
//  lsplant's inline_unhooker receives the trampoline (what inline_hooker returned), NOT the stub,
//  so we keep a backup→stub map to translate back on unhook.
// ---------------------------------------------------------------------------------

std::mutex g_hook_map_mutex;
// Maps: backup trampoline (what we returned to lsplant) → ShadowHook stub (needed for unhook).
std::unordered_map<void*, void*> g_backup_to_stub;

std::atomic<bool> g_shadowhook_initialized{false};

bool ensure_shadowhook_init() {
    if (g_shadowhook_initialized.load(std::memory_order_acquire)) return true;
    // SHARED mode allows multiple hooks on the same address (lsplant may hook an ART internal
    // that's already hooked by another tool). debuggable=false — we're in production.
    int err = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (err != 0) {
        LOGE("shadowhook_init failed: %d (%s)", err, shadowhook_to_errmsg(err));
        return false;
    }
    g_shadowhook_initialized.store(true, std::memory_order_release);
    LOGI("shadowhook %s initialized (shared mode)", shadowhook_get_version());
    return true;
}

// lsplant calls this to install a native inline hook on `target`, redirecting to `hooker`.
// Returns a backup trampoline that invokes the original, or null on failure.
void* shadowhook_inline_hooker(void *target, void *hooker) {
    if (!ensure_shadowhook_init()) return nullptr;
    void *orig_addr = nullptr;
    void *stub = shadowhook_hook_func_addr(target, hooker, &orig_addr);
    if (stub == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_func_addr(%p, %p) failed: %d (%s)",
             target, hooker, err, shadowhook_to_errmsg(err));
        return nullptr;
    }
    // Remember the mapping so unhook can find the stub from the backup trampoline.
    {
        std::lock_guard<std::mutex> lock(g_hook_map_mutex);
        g_backup_to_stub[orig_addr] = stub;
    }
    return orig_addr;
}

// lsplant calls this to remove a hook, passing the backup trampoline it received.
bool shadowhook_inline_unhooker(void *func) {
    void *stub;
    {
        std::lock_guard<std::mutex> lock(g_hook_map_mutex);
        auto it = g_backup_to_stub.find(func);
        if (it == g_backup_to_stub.end()) {
            LOGW("shadowhook_inline_unhooker: no stub for backup %p", func);
            return false;
        }
        stub = it->second;
        g_backup_to_stub.erase(it);
    }
    int err = shadowhook_unhook(stub);
    if (err != 0) {
        LOGE("shadowhook_unhook(%p) failed: %d (%s)", stub, err, shadowhook_to_errmsg(err));
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------------
//  ART symbol resolver: locate a symbol in libart.so by walking its ELF tables.
//  We dlopen libart.so, then read its ELF symbol tables (.dynsym first, then .symtab) to find
//  both exported and internal symbols — lsplant needs internals like art_quick_to_interpreter_bridge.
// ---------------------------------------------------------------------------------

// ELF symbol-table walker. Generic over 32/64-bit via the ELF types below.
//
// Walks the loaded library's PT_DYNAMIC segment to find DT_SYMTAB/DT_STRTAB and derives the
// exported symbol count from DT_HASH (or, failing that, DT_GNU_HASH). This resolves both
// exported (.dynsym) and internal (.symtab when present) symbols of libart.so.

#if defined(__LP64__)
using ElfWSym = Elf64_Sym;
using ElfWDyn = Elf64_Dyn;
using ElfWPhdr = Elf64_Phdr;
#else
using ElfWSym = Elf32_Sym;
using ElfWDyn = Elf32_Dyn;
using ElfWPhdr = Elf32_Phdr;
#endif

struct ElfContext {
    uintptr_t load_bias = 0;
    const ElfWSym *dynsym = nullptr;
    const char *dynstr = nullptr;
    size_t dynsym_count = 0;
};

bool build_elf_context(const dl_phdr_info *info, ElfContext &ctx) {
    ctx.load_bias = info->dlpi_addr;
    const ElfWPhdr *dynamic_phdr = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const auto &phdr = info->dlpi_phdr[i];
        if (phdr.p_type == PT_DYNAMIC) {
            dynamic_phdr = &phdr;
            break;
        }
    }
    if (dynamic_phdr == nullptr) return false;
    const ElfWDyn *dynamic =
        reinterpret_cast<const ElfWDyn*>(info->dlpi_addr + dynamic_phdr->p_vaddr);

    uintptr_t strtab = 0, symtab = 0, gnu_hash = 0, hash = 0;
    for (const ElfWDyn *dyn = dynamic; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_STRTAB: strtab = dyn->d_un.d_ptr; break;
            case DT_SYMTAB: symtab = dyn->d_un.d_ptr; break;
            case DT_GNU_HASH: gnu_hash = dyn->d_un.d_ptr; break;
            case DT_HASH: hash = dyn->d_un.d_ptr; break;
            default: break;
        }
    }
    ctx.dynstr = reinterpret_cast<const char*>(strtab);
    ctx.dynsym = reinterpret_cast<const ElfWSym*>(symtab);
    if (ctx.dynsym == nullptr || ctx.dynstr == nullptr) return false;

    // Derive the exported symbol count. Prefer DT_HASH (nchain == nsyms); fall back to walking
    // DT_GNU_HASH's buckets. DT_HASH is the simple, reliable path.
    if (hash != 0) {
        ctx.dynsym_count = reinterpret_cast<const uint32_t*>(hash)[1];
    } else if (gnu_hash != 0) {
        // Walk GNU hash to find the highest symbol index.
        const auto *gh = reinterpret_cast<const uint32_t*>(gnu_hash);
        const uint32_t nbuckets = gh[0];
        const uint32_t symoffset = gh[1];
        const uint32_t bloom_size = gh[2];
        const uint32_t *buckets = reinterpret_cast<const uint32_t*>(
            reinterpret_cast<uintptr_t>(gh) + 16 + bloom_size * sizeof(size_t));
        const uint32_t *chain = buckets + nbuckets;
        uint32_t last = symoffset;
        for (uint32_t i = 0; i < nbuckets; ++i) {
            if (buckets[i] > last) last = buckets[i];
        }
        if (last >= symoffset) {
            while ((chain[last - symoffset] & 1) == 0) ++last;
            ctx.dynsym_count = last + 1;
        }
    }
    return ctx.dynsym_count > 0;
}

struct IterState {
    std::string_view lib_name;
    const dl_phdr_info *found = nullptr;
};

int find_lib_phdr(dl_phdr_info *info, size_t, void *data) {
    auto *state = static_cast<IterState*>(data);
    if (info->dlpi_name == nullptr) return 0;
    std::string_view name(info->dlpi_name);
    if (name.length() >= state->lib_name.length() &&
        name.substr(name.length() - state->lib_name.length()) == state->lib_name) {
        state->found = info;
        return 1;
    }
    return 0;
}

template <typename MatchFn>
void* walk_symbols_for(const char *lib_name, MatchFn match) {
    IterState state{lib_name, nullptr};
    dl_iterate_phdr(find_lib_phdr, &state);
    if (state.found == nullptr) return nullptr;

    ElfContext ctx;
    if (!build_elf_context(state.found, ctx)) return nullptr;

    const ElfWSym *table = ctx.dynsym;
    const char *strs = ctx.dynstr;
    size_t count = ctx.dynsym_count;
    if (table == nullptr || strs == nullptr || count == 0) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        const ElfWSym &sym = table[i];
        if (sym.st_name == 0) continue;
        const char *sname = strs + sym.st_name;
        if (match(sname)) {
            if (sym.st_value != 0) {
                return reinterpret_cast<void*>(ctx.load_bias + sym.st_value);
            }
        }
    }
    return nullptr;
}

void* resolve_elf_symbol_by_iterate(std::string_view name, const char *lib_name) {
    std::string name_str(name);
    return walk_symbols_for(lib_name, [&](const char *s) {
        return strcmp(s, name_str.c_str()) == 0;
    });
}

void* resolve_elf_symbol_by_prefix_iterate(std::string_view prefix, const char *lib_name) {
    std::string prefix_str(prefix);
    return walk_symbols_for(lib_name, [&](const char *s) {
        return strncmp(s, prefix_str.c_str(), prefix_str.length()) == 0;
    });
}

void* resolve_art_symbol(std::string_view name) {
    // The handle returned by dlopen for an already-loaded library is cheap; we open it per call
    // to avoid lifetime bookkeeping across the (possibly multiple) init sequences.
    void *handle = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen("libart.so", RTLD_NOW);
    }
    if (handle == nullptr) {
        LOGE("dlopen libart.so failed: %s", dlerror());
        return nullptr;
    }
    // dlsym covers exported (.dynsym) symbols directly.
    void *sym = dlsym(handle, std::string(name).c_str());
    if (sym != nullptr) return sym;
    // For internal-only symbols we walk libart.so's ELF tables ourselves.
    return resolve_elf_symbol_by_iterate(name, "libart.so");
}

void* resolve_art_symbol_prefix(std::string_view prefix) {
    return resolve_elf_symbol_by_prefix_iterate(prefix, "libart.so");
}

// ---------------------------------------------------------------------------------
//  lsplant init state.
// ---------------------------------------------------------------------------------

std::atomic<bool> g_initialized{false};
JavaVM *g_vm{nullptr};

bool ensure_lsplant_init(JNIEnv *env) {
    if (g_initialized.load(std::memory_order_acquire)) return true;

    lsplant::InitInfo info{
        .inline_hooker = shadowhook_inline_hooker,
        .inline_unhooker = shadowhook_inline_unhooker,
        .art_symbol_resolver = [](std::string_view name) -> void* {
            return resolve_art_symbol(name);
        },
        .art_symbol_prefix_resolver = [](std::string_view prefix) -> void* {
            return resolve_art_symbol_prefix(prefix);
        },
    };

    bool ok = lsplant::Init(env, info);
    if (!ok) {
        LOGE("lsplant::Init failed (shadowhook initialized? %s)",
             g_shadowhook_initialized.load() ? "yes" : "no");
        return false;
    }
    g_initialized.store(true, std::memory_order_release);
    LOGI("lsplant initialized with ShadowHook backend");
    return true;
}

}  // namespace

// ---------------------------------------------------------------------------------
//  JNI entry points, called from Kotlin via System.loadLibrary("nexhook").
// ---------------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    // We do NOT init lsplant here: it is initialized lazily on first hook or when the Kotlin
    // side explicitly calls nexhook_init(). ShadowHook (the inline-hook backend) is also
    // initialized lazily inside ensure_lsplant_init, so no manual backend wiring is needed.
    LOGI("nexhook loaded; lsplant+shadowhook will init on demand");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_hook_LsplantBridge_nativeInit(JNIEnv *env, jclass) {
    return ensure_lsplant_init(env) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_hook_LsplantBridge_nativeIsInitialized(JNIEnv *, jclass) {
    return g_initialized.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_me_zhanghai_android_files_hook_LsplantBridge_nativeHook(
        JNIEnv *env, jclass, jobject target_method, jobject hooker_object, jobject callback_method) {
    if (!ensure_lsplant_init(env)) return nullptr;
    jobject backup = lsplant::Hook(env, target_method, hooker_object, callback_method);
    if (backup == nullptr) {
        LOGE("lsplant::Hook returned null");
    }
    return backup;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_hook_LsplantBridge_nativeUnhook(
        JNIEnv *env, jclass, jobject target_method) {
    if (!g_initialized.load(std::memory_order_acquire)) return JNI_FALSE;
    return lsplant::UnHook(env, target_method) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_hook_LsplantBridge_nativeIsHooked(
        JNIEnv *env, jclass, jobject method) {
    if (!g_initialized.load(std::memory_order_acquire)) return JNI_FALSE;
    return lsplant::IsHooked(env, method) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_hook_LsplantBridge_nativeDeoptimize(
        JNIEnv *env, jclass, jobject method) {
    if (!g_initialized.load(std::memory_order_acquire)) return JNI_FALSE;
    return lsplant::Deoptimize(env, method) ? JNI_TRUE : JNI_FALSE;
}

