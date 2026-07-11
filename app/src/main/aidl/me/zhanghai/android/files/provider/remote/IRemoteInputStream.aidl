package me.zhanghai.android.files.provider.remote;

import android.os.ParcelFileDescriptor;
import me.zhanghai.android.files.provider.remote.ParcelableException;

interface IRemoteInputStream {
    int read(out ParcelableException exception);

    int read2(out byte[] buffer, out ParcelableException exception);

    long skip(long size, out ParcelableException exception);

    int available(out ParcelableException exception);

    void close(out ParcelableException exception);

    // Returns a duplicated file descriptor when the backing stream is fd-backed (e.g. a
    // FileInputStream). The caller reads through Os.read() with no further IPC. Returns null
    // for non-fd-backed streams (sockets, decrypted pipes), in which case the caller falls
    // back to the per-call read2()/skip() methods above.
    ParcelFileDescriptor openFd(out ParcelableException exception);
}
