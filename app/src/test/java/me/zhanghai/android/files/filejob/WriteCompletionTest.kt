package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class WriteCompletionTest {
    @Test fun successAndFailureCompleteOnce() {
        for (result in listOf(true, false)) {
            val results = mutableListOf<Boolean>()
            val completion = WriteCompletion { results.add(it) }
            completion.run { result }
            completion.run { error("Must not write twice") }
            completion.cancelBeforeStart()
            assertEquals(listOf(result), results)
        }
    }

    @Test fun exceptionsCompleteWithFailureAndPropagate() {
        for (exception in listOf(IOException(), InterruptedIOException(), IllegalStateException())) {
            val results = mutableListOf<Boolean>()
            val completion = WriteCompletion { results.add(it) }
            try {
                completion.run { throw exception }
                fail("Exception must propagate")
            } catch (actual: Exception) {
                assertSame(exception, actual)
            }
            completion.cancelBeforeStart()
            assertEquals(listOf(false), results)
        }
    }

    @Test fun cancellationBeforeExecutionPreventsWrite() {
        val results = mutableListOf<Boolean>()
        val completion = WriteCompletion { results.add(it) }
        completion.cancelBeforeStart()
        completion.cancelBeforeStart()
        completion.run { error("Cancelled job must not open output") }
        assertEquals(listOf(false), results)
    }

    @Test fun cancellationDuringWriteWaitsForOutputCleanup() {
        val results = mutableListOf<Boolean>()
        var closed = false
        val completion = WriteCompletion {
            assertTrue(closed)
            results.add(it)
        }
        try {
            completion.run {
                try {
                    completion.cancelBeforeStart()
                    assertTrue(results.isEmpty())
                    throw InterruptedIOException()
                } finally {
                    closed = true
                }
            }
            fail("Expected interruption")
        } catch (_: InterruptedIOException) {
        }
        assertEquals(listOf(false), results)
    }
}
