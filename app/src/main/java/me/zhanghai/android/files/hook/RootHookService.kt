/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import android.content.Context

/**
 * The root-based injection path — a fallback for when the sandbox can't host the target.
 *
 * The rootless sandbox (see [me.zhanghai.android.files.hook.sandbox]) is the primary hook
 * mechanism: it loads the target's code into our process, where lsplant has unrestricted
 * access. But the sandbox can't host every target:
 *  - Targets whose Application onCreate calls into native code that assumes it's running as a
 *    specific uid, or that checks its own signature, may misbehave in-sandbox.
 *  - Targets that spawn multiple processes or bind to their own services won't see those
 *    children hooked.
 *
 * For those cases, when the device has root (the existing SuiFileService / libsu path), we fall
 * back to ptrace injection into the target's *real* process. This is the legacy path used by
 * MT/LSPosed; it's strictly more capable (it hooks the live app) but strictly more demanding
 * (root + SELinux policy).
 *
 * The two paths share the same lsplant core (LsplantBridge / HookEngine) and the same hook
 * DSL (MethodHook); only the delivery mechanism differs.
 */
object RootHookService {
    /**
     * Requests ptrace injection into the live process of [packageName], available only when the
     * device is rooted. Returns the installed-hook count, or -1 on failure / when root is
     * unavailable.
     *
     * @see me.zhanghai.android.files.hook.PtraceRemoteInvoker
     */
    fun launchInjection(
        context: Context,
        packageName: String,
        callback: (Int) -> Unit
    ) {
        // The ptrace path requires the root service, which holds the HookInjector. Until that
        // AIDL is wired into the existing root service (SuiFileServiceLauncher), this reports
        // unsupported. The sandbox path covers the common case without root.
        callback(-1)
    }
}
