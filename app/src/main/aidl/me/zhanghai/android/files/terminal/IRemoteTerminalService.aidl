/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal;

import android.os.Bundle;
import me.zhanghai.android.files.provider.remote.ParcelableException;
import me.zhanghai.android.files.provider.remote.ParcelableObject;
import me.zhanghai.android.files.util.RemoteCallback;
import me.zhanghai.android.files.terminal.IRemotePty;

/**
 * Factory for PTY sessions, running inside the Shizuku-started (shell-uid) process. The app side
 * binds this via Shizuku.bindUserService and never instantiates it directly.
 *
 * Output from the PTY is pushed back through [outputCallback] (a Bundle carrying a byte[] under
 * key "output"), mirroring the RemotePathObservable push pattern. Input flows the other way via
 * [IRemotePty.write].
 */
interface IRemoteTerminalService {
    /**
     * Creates a new PTY session running the program described in [config]. [config] wraps a
     * TerminalConfig Parcelable carrying argv, envp, and initial rows/cols. [outputCallback] is
     * invoked repeatedly with PTY output; callers register it once per session.
     */
    IRemotePty createPty(
        in ParcelableObject config,
        in RemoteCallback outputCallback,
        out ParcelableException exception
    );

    /** Releases the service binding; sessions created before this remain until closed. */
    void release(out ParcelableException exception);
}
