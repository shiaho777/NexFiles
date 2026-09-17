package me.zhanghai.android.files.filejob

internal class WriteCompletion(private val listener: (Boolean) -> Unit) {
    private enum class State { READY, RUNNING, FINISHED }
    private var state = State.READY

    fun run(write: () -> Boolean) {
        synchronized(this) {
            if (state != State.READY) return
            state = State.RUNNING
        }
        var successful = false
        try {
            successful = write()
        } finally {
            synchronized(this) { state = State.FINISHED }
            listener(successful)
        }
    }

    // A running write must close its output before the editor can retry.
    fun cancelBeforeStart() {
        synchronized(this) {
            if (state != State.READY) return
            state = State.FINISHED
        }
        listener(false)
    }
}
