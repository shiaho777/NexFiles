/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal;

import me.zhanghai.android.files.provider.remote.ParcelableException;

/**
 * One PTY session (a forked child + its master fd), living in the Shizuku-started process.
 * The app side holds a binder proxy to this; bytes flow via write() in and the output callback
 * registered at creation out.
 */
interface IRemotePty {
    /** Sends bytes to the PTY (user input). Returns bytes written, or throws via [exception]. */
    int write(in byte[] data, out ParcelableException exception);

    /** Resizes the terminal window; sends SIGWINCH to the child. */
    void setSize(int rows, int cols, out ParcelableException exception);

    /** Closes the master fd and stops the output pump thread. Safe to call multiple times. */
    void close(out ParcelableException exception);

    /**
     * Blocks until the child exits; returns the raw waitpid status (decode with WIFEXITED/
     * WIFSIGNALED on the caller side). Should be called after close() to reap the child.
     */
    int waitForExit(out ParcelableException exception);
}
