/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.os.Parcel
import android.os.Parcelable

/**
 * Cross-process exception carrier for the sandbox IPC. We serialize only the message and the
 * class name (not the stack or cause graph), since those are all the UI needs to surface a
 * failure and a full Throwable isn't reliably Parcelable across processes.
 */
class ParcelableException() : Parcelable {
    var className: String? = null
        internal set
    var message: String? = null
        internal set

    constructor(throwable: Throwable) : this() {
        className = throwable.javaClass.name
        message = throwable.message
    }

    constructor(source: Parcel) : this() {
        className = source.readString()
        message = source.readString()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(className)
        dest.writeString(message)
    }

    /** Human-readable summary suitable for logging or UI display. */
    fun toDisplayString(): String =
        if (className != null) "$className: ${message ?: ""}" else (message ?: "")

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ParcelableException> {
            override fun createFromParcel(source: Parcel): ParcelableException =
                ParcelableException(source)

            override fun newArray(size: Int): Array<ParcelableException?> = arrayOfNulls(size)
        }
    }
}
