/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.os.Parcel
import android.os.Parcelable

/**
 * Result of a hook-method IPC call.
 *
 * @property success whether the hook was installed.
 * @property targetMethod a display string identifying what was hooked (e.g. "com.foo.Bar.qux").
 * @property message optional detail on success or failure cause on failure.
 */
class HookResult() : Parcelable {
    var success: Boolean = false
        private set
    var targetMethod: String? = null
        private set
    var message: String? = null
        private set

    constructor(success: Boolean, targetMethod: String?, message: String?) : this() {
        this.success = success
        this.targetMethod = targetMethod
        this.message = message
    }

    constructor(source: Parcel) : this() {
        success = source.readByte().toInt() != 0
        targetMethod = source.readString()
        message = source.readString()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByte(if (success) 1 else 0)
        dest.writeString(targetMethod)
        dest.writeString(message)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<HookResult> {
            override fun createFromParcel(source: Parcel): HookResult = HookResult(source)

            override fun newArray(size: Int): Array<HookResult?> = arrayOfNulls(size)
        }
    }
}
