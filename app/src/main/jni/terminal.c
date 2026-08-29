/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 *
 * Native PTY layer for the built-in proot terminal. Provides the primitives that the remote
 * (Shizuku shell-uid) process needs to:
 *   - fork a child attached to a pseudo-terminal (forkpty),
 *   - shuttle bytes between the master fd and the caller (write/read),
 *   - resize the terminal window (TIOCSWINSZ),
 *   - reap the child and report its exit status.
 *
 * Runs inside the Shizuku-started process, so it is free of the app's W^X exec restriction —
 * that is what lets proot itself be exec'd here. All functions convert failures into Java
 * IOExceptions carrying a strerror message, so the Kotlin side never sees a raw errno.
 */

#include <errno.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <fcntl.h>
#include <pty.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

#include <jni.h>

#include <android/log.h>

#define LOG_TAG "terminal"

#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// bionic retries interrupted syscalls for us in most cases, but we keep an explicit guard for
// read/write/poll where partial/EINTR semantics still surface.
#undef TEMP_FAILURE_RETRY
#define TEMP_FAILURE_RETRY(exp) ({ \
    __typeof__(exp) _rc; \
    do { \
        errno = 0; \
        _rc = (exp); \
    } while (_rc == -1 && errno == EINTR); \
    _rc; })

static jclass findClass(JNIEnv *env, const char *name) {
    jclass localClass = (*env)->FindClass(env, name);
    if (!localClass) {
        ALOGE("Failed to find class '%s'", name);
        abort();
    }
    jclass globalClass = (*env)->NewGlobalRef(env, localClass);
    (*env)->DeleteLocalRef(env, localClass);
    if (!globalClass) {
        ALOGE("Failed to create a global reference for '%s'", name);
        abort();
    }
    return globalClass;
}

static jclass getIoExceptionClass(JNIEnv *env) {
    static jclass ioExceptionClass;
    if (!ioExceptionClass) {
        ioExceptionClass = findClass(env, "java/io/IOException");
    }
    return ioExceptionClass;
}

// Throws a java.io.IOException carrying the current errno's strerror, and returns NULL so it can
// be used as `return throwIoException(env, "forkpty");`.
static jobject throwIoException(JNIEnv *env, const char *prefix) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", prefix, strerror(errno));
    jclass clazz = getIoExceptionClass(env);
    // If an exception is already pending, ClearException before throwing a new one, otherwise the
    // new throw is a no-op and the caller gets a confusing stale exception.
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    (*env)->ThrowNew(env, clazz, message);
    return NULL;
}

// TerminalNative companion object in Kotlin; methods are static so we can call them without a
// bound instance. The companion lives in the terminal package and is loaded via System.loadLibrary.
//
//   Java_me_zhanghai_android_files_terminal_TerminalNative_createPty(
//       env, clazz, argv, envp, rows, cols)
//
// Returns the master fd (>= 0) on success, or throws IOException. argv is a NULL-terminated
// UTF-8 argv array built from the Java String[]; envp likewise (may be NULL for inherit).
JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_terminal_TerminalNative_createPty(
        JNIEnv *env, jclass clazz, jobjectArray argvArray, jobjectArray envpArray,
        jint rows, jint cols) {
    int argc = (*env)->GetArrayLength(env, argvArray);
    if (argc <= 0) {
        (*env)->ThrowNew(env, getIoExceptionClass(env), "argv is empty");
        return -1;
    }

    // Build C argv[] from the Java String[]. We keep the original Java strings live by pinning
    // them as local refs and freeing at the end; GetStringUTFChars returns a stable pointer for
    // the duration of the call.
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    jstring *argvRefs = calloc((size_t) argc, sizeof(jstring));
    if (!argv || !argvRefs) {
        free(argv);
        free(argvRefs);
        (*env)->ThrowNew(env, getIoExceptionClass(env), "out of memory (argv)");
        return -1;
    }
    bool failed = false;
    for (int i = 0; i < argc; ++i) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, argvArray, i);
        argvRefs[i] = s;
        if (!s) {
            argv[i] = strdup("");
        } else {
            const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
            if (!utf) {
                failed = true;
                break;
            }
            argv[i] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, s, utf);
        }
    }
    argv[argc] = NULL;

    // Optional envp[] (NULL means inherit the caller's environment).
    char **envp = NULL;
    jstring *envpRefs = NULL;
    int envc = 0;
    if (envpArray != NULL) {
        envc = (*env)->GetArrayLength(env, envpArray);
        envp = calloc((size_t) envc + 1, sizeof(char *));
        envpRefs = calloc((size_t) envc, sizeof(jstring));
        if (!envp || !envpRefs) {
            failed = true;
        } else {
            for (int i = 0; i < envc; ++i) {
                jstring s = (jstring) (*env)->GetObjectArrayElement(env, envpArray, i);
                envpRefs[i] = s;
                if (!s) {
                    envp[i] = strdup("");
                } else {
                    const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
                    if (!utf) {
                        failed = true;
                        break;
                    }
                    envp[i] = strdup(utf);
                    (*env)->ReleaseStringUTFChars(env, s, utf);
                }
            }
            envp[envc] = NULL;
        }
    }

    if (failed) {
        // Free what we managed to allocate before bailing.
        for (int i = 0; i < argc; ++i) { free(argv[i]); }
        free(argv);
        for (int i = 0; i < argc; ++i) { (*env)->DeleteLocalRef(env, argvRefs[i]); }
        free(argvRefs);
        if (envp) {
            for (int i = 0; i < envc; ++i) { free(envp[i]); }
            free(envp);
        }
        if (envpRefs) {
            for (int i = 0; i < envc; ++i) { (*env)->DeleteLocalRef(env, envpRefs[i]); }
            free(envpRefs);
        }
        return (jint) (intptr_t) throwIoException(env, "argv/envp construction");
    }

    // Set the initial window size on the master fd that forkpty gives us. We set it via the
    // returned master fd after the fork; doing it pre-fork via a struct winsize through the
    // pty avoids a race where the child reads a 0x0 size before we resize.
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, &ws);
    if (pid == -1) {
        // Fork failed — clean up and surface the errno.
        for (int i = 0; i < argc; ++i) { free(argv[i]); }
        free(argv);
        for (int i = 0; i < argc; ++i) { (*env)->DeleteLocalRef(env, argvRefs[i]); }
        free(argvRefs);
        if (envp) {
            for (int i = 0; i < envc; ++i) { free(envp[i]); }
            free(envp);
        }
        if (envpRefs) {
            for (int i = 0; i < envc; ++i) { (*env)->DeleteLocalRef(env, envpRefs[i]); }
            free(envpRefs);
        }
        return (jint) (intptr_t) throwIoException(env, "forkpty");
    }

    if (pid == 0) {
        // Child. Exec the requested program; envp via execvpe when provided, else execvp for
        // environment inheritance.
        if (envp) {
            execvpe(argv[0], argv, envp);
        } else {
            execvp(argv[0], argv);
        }
        // If we get here, exec failed. There is no safe way to report this back to Java from a
        // forked child, so write a marker to stderr and exit with a distinctive code. The parent
        // detects this via waitpid's exit status and the Kotlin side maps 126/127 accordingly.
        const char *msg = "terminal: exec failed\n";
        TEMP_FAILURE_RETRY(write(STDERR_FILENO, msg, strlen(msg)));
        _exit(errno == ENOENT ? 127 : 126);
    }

    // Parent. Free the C copies of argv/envp; the Java strings are still referenced until we
    // release the local refs below.
    for (int i = 0; i < argc; ++i) { free(argv[i]); }
    free(argv);
    for (int i = 0; i < argc; ++i) { (*env)->DeleteLocalRef(env, argvRefs[i]); }
    free(argvRefs);
    if (envp) {
        for (int i = 0; i < envc; ++i) { free(envp[i]); }
        free(envp);
    }
    if (envpRefs) {
        for (int i = 0; i < envc; ++i) { (*env)->DeleteLocalRef(env, envpRefs[i]); }
        free(envpRefs);
    }

    ALOGI("forkpty: pid=%d masterFd=%d rows=%d cols=%d", pid, masterFd, ws.ws_row, ws.ws_col);
    return (jint) masterFd;
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_terminal_TerminalNative_write(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray bytes, jint offset, jint length) {
    if (length <= 0) {
        return 0;
    }
    jbyte *buf = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!buf) {
        (*env)->ThrowNew(env, getIoExceptionClass(env), "GetByteArrayElements failed");
        return -1;
    }
    ssize_t n = TEMP_FAILURE_RETRY(write((int) fd, buf + offset, (size_t) length));
    jthrowable pending = (*env)->ExceptionOccurred(env);
    (*env)->ReleaseByteArrayElements(env, bytes, buf, JNI_ABORT);
    if (pending) {
        (*env)->DeleteLocalRef(env, pending);
    }
    if (n == -1) {
        return (jint) (intptr_t) throwIoException(env, "write");
    }
    return (jint) n;
}

// Reads up to buffer.length bytes from the master fd. Blocks with poll() for up to timeoutMillis
// so an IPC thread can't wedge forever; returns 0 on timeout, -1 on EOF/error (error thrown).
JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_terminal_TerminalNative_read(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length,
        jint timeoutMillis) {
    if (length <= 0) {
        return 0;
    }
    struct pollfd pfd;
    pfd.fd = (int) fd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int pr = TEMP_FAILURE_RETRY(poll(&pfd, 1, timeoutMillis));
    if (pr == 0) {
        // Timeout, no data yet — caller treats 0 as "try again later".
        return 0;
    }
    if (pr == -1) {
        return (jint) (intptr_t) throwIoException(env, "poll");
    }
    if (pfd.revents & (POLLERR | POLLNVAL)) {
        (*env)->ThrowNew(env, getIoExceptionClass(env), "poll: fd error");
        return -1;
    }
    if (!(pfd.revents & POLLIN)) {
        return 0;
    }
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        (*env)->ThrowNew(env, getIoExceptionClass(env), "GetByteArrayElements failed");
        return -1;
    }
    ssize_t n = TEMP_FAILURE_RETRY(read((int) fd, buf + offset, (size_t) length));
    jthrowable pending = (*env)->ExceptionOccurred(env);
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    if (pending) {
        (*env)->DeleteLocalRef(env, pending);
    }
    if (n == -1) {
        return (jint) (intptr_t) throwIoException(env, "read");
    }
    return (jint) n;
}

JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_terminal_TerminalNative_setSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    if (ioctl((int) fd, TIOCSWINSZ, &ws) == -1) {
        throwIoException(env, "TIOCSWINSZ");
    }
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_terminal_TerminalNative_close(
        JNIEnv *env, jclass clazz, jint fd) {
    int rc = TEMP_FAILURE_RETRY(close((int) fd));
    if (rc == -1) {
        // Don't throw on close failures — the caller is tearing down anyway and a stale EBADF
        // shouldn't abort session cleanup. Log instead.
        ALOGW("close(%d) failed: %s", fd, strerror(errno));
    }
    return (jint) rc;
}

// Blocks until the child exits and returns its exit status encoded like waitpid's WEXITSTATUS:
//   - normal exit: returns the (code & 0xFF) << 8 | status
//   - signal: returns the signal number in the low byte
// We expose the raw waitpid status so Kotlin can decode both cases unambiguously.
JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_terminal_TerminalNative_waitForExit(
        JNIEnv *env, jclass clazz, jint fd) {
    // The master fd isn't the pid; we don't have the pid here, so this entrypoint reaps any
    // child of this process that has exited. Callers are expected to have closed the master fd
    // first (which delivers SIGHUP to the child via the controlling terminal), then call this.
    int status = 0;
    pid_t pid = TEMP_FAILURE_RETRY(waitpid(-1, &status, 0));
    if (pid == -1) {
        return (jint) (intptr_t) throwIoException(env, "waitpid");
    }
    ALOGI("waitpid: pid=%d status=%d", pid, status);
    return (jint) status;
}
