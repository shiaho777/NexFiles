package me.zhanghai.android.files.provider.remote;

import android.os.ParcelFileDescriptor;
import me.zhanghai.android.files.provider.remote.ParcelableException;

interface IRemoteSeekableByteChannel {
    int read(out byte[] destination, out ParcelableException exception);

    int write(in byte[] source, out ParcelableException exception);

    long position(out ParcelableException exception);

    void position2(long newPosition, out ParcelableException exception);

    long size(out ParcelableException exception);

    void truncate(long size, out ParcelableException exception);

    void force(boolean metaData, out ParcelableException exception);

    void close(out ParcelableException exception);

    // Returns a duplicated file descriptor backing this channel, when the underlying channel
    // is fd-backed (e.g. a local FileChannel). The caller owns the returned descriptor and is
    // responsible for closing it. Returns null when the channel has no fd backing, in which
    // case the caller must fall back to the per-call read()/write() methods above.
    ParcelFileDescriptor openFd(out ParcelableException exception);
}
