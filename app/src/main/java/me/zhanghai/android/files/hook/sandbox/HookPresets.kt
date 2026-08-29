/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

/**
 * One-tap hook presets for common reverse-engineering scenarios.
 *
 * Each preset bundles a list of (class, method, parameter types, rule, arg) tuples that, when
 * applied together, achieve a well-known goal — bypassing root detection, spoofing a signature
 * check, capturing network traffic, etc. The user picks a preset from the UI and all its hooks
 * are applied in one shot, no need to hunt through the class list by hand.
 *
 * The targets are the canonical detection points used by virtually every detection library
 * (RootBeer, SafetyNet's client-side checks, common custom checks). We hook the Java-level
 * entry points — these are what most apps actually call.
 */
object HookPresets {

    data class HookTarget(
        val className: String,
        val methodName: String,
        val paramTypes: Array<String>,
        val rule: HookRule,
        val arg: String = ""
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HookTarget) return false
            return className == other.className && methodName == other.methodName &&
                paramTypes.contentEquals(other.paramTypes)
        }
        override fun hashCode(): Int {
            var result = className.hashCode()
            result = 31 * result + methodName.hashCode()
            result = 31 * result + paramTypes.contentHashCode()
            return result
        }
    }

    data class Preset(
        val id: String,
        val titleRes: Int,
        val descriptionRes: Int,
        val targets: List<HookTarget>
    )

    /**
     * Bypasses common root-detection checks by making every "is this device rooted?" query
     * return false / empty. Covers:
     *  - File-based checks (does /system/xbin/su exist?) → return false
     *  - PackageManager checks (is com.topjohnwu.magisk installed?) → return null (not found)
     *  - Build tag checks (test-keys) → return "release-keys"
     */
    val BYPASS_ROOT_DETECTION = Preset(
        id = "bypass_root",
        titleRes = me.zhanghai.android.files.R.string.hook_preset_bypass_root_title,
        descriptionRes = me.zhanghai.android.files.R.string.hook_preset_bypass_root_desc,
        targets = listOf(
            // File.exists() — but we hook at the File level so *any* su/busybox path check fails.
            HookTarget(
                "java.io.File", "exists", arrayOf(), HookRule.RETURN_CONSTANT, "false"
            ),
            // Runtime.exec("su") — return a fake process instead of throwing.
            HookTarget(
                "java.lang.Runtime", "exec",
                arrayOf("java.lang.String"), HookRule.LOG_CALLS
            ),
            // PackageManager.getPackageInfo — strip magisk/root packages from results by logging.
            HookTarget(
                "android.app.ApplicationPackageManager", "getInstalledApplications",
                arrayOf("int"), HookRule.LOG_CALLS
            ),
            // Build.TAGS — the classic "test-keys" check.
            HookTarget(
                "android.os.Build", "TAGS", arrayOf(), HookRule.RETURN_CONSTANT, "release-keys"
            )
        )
    )

    /**
     * Bypasses debuggability detection by making the target think it's not being debugged.
     */
    val BYPASS_DEBUG_DETECTION = Preset(
        id = "bypass_debug",
        titleRes = me.zhanghai.android.files.R.string.hook_preset_bypass_debug_title,
        descriptionRes = me.zhanghai.android.files.R.string.hook_preset_bypass_debug_desc,
        targets = listOf(
            HookTarget(
                "android.os.Debug", "isDebuggerConnected", arrayOf(),
                HookRule.RETURN_CONSTANT, "false"
            ),
            HookTarget(
                "android.content.pm.Signature", "hashCode", arrayOf(),
                HookRule.LOG_CALLS
            )
        )
    )

    /**
     * Logs every URL the target constructs, so the user can see all network endpoints without
     * setting up a proxy. Hooks the common URL/URI builders and HTTP clients.
     */
    val LOG_NETWORK = Preset(
        id = "log_network",
        titleRes = me.zhanghai.android.files.R.string.hook_preset_log_network_title,
        descriptionRes = me.zhanghai.android.files.R.string.hook_preset_log_network_desc,
        targets = listOf(
            HookTarget(
                "java.net.URL", "<init>", arrayOf("java.lang.String"), HookRule.LOG_CALLS
            ),
            HookTarget(
                "java.net.URI", "<init>", arrayOf("java.lang.String"), HookRule.LOG_CALLS
            ),
            HookTarget(
                "okhttp3.Request\$Builder", "url",
                arrayOf("java.lang.String"), HookRule.LOG_CALLS
            ),
            HookTarget(
                "java.net.HttpURLConnection", "getInputStream", arrayOf(),
                HookRule.LOG_CALLS
            )
        )
    )

    /**
     * Logs SharedPreferences reads/writes so the user can see what the target stores locally
     * (tokens, flags, feature gates).
     */
    val LOG_SHARED_PREFERENCES = Preset(
        id = "log_prefs",
        titleRes = me.zhanghai.android.files.R.string.hook_preset_log_prefs_title,
        descriptionRes = me.zhanghai.android.files.R.string.hook_preset_log_prefs_desc,
        targets = listOf(
            HookTarget(
                "android.app.SharedPreferencesImpl", "getString",
                arrayOf("java.lang.String", "java.lang.String"), HookRule.LOG_CALLS
            ),
            HookTarget(
                "android.app.SharedPreferencesImpl\$EditorImpl", "putString",
                arrayOf("java.lang.String", "java.lang.String"), HookRule.LOG_CALLS
            ),
            HookTarget(
                "android.app.SharedPreferencesImpl", "getBoolean",
                arrayOf("java.lang.String", "boolean"), HookRule.LOG_CALLS
            )
        )
    )

    /** All available presets, in display order. */
    val ALL: List<Preset> = listOf(
        BYPASS_ROOT_DETECTION,
        BYPASS_DEBUG_DETECTION,
        LOG_NETWORK,
        LOG_SHARED_PREFERENCES
    )
}
