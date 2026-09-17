/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.util.concurrent.Executor

/**
 * Android-free, demand-driven scheduler. Each pane owns its demand, results and generation.
 * Only [parallelism] jobs are submitted at once; the remaining queue is the current viewport,
 * not a backlog of every directory ever listed. [postDelayed] must enqueue, never run inline.
 *
 * Cancellation is cooperative as well as interrupt-based. An uninterruptible provider retains
 * its worker slot until it returns, but can never publish into a newer owner generation.
 */
internal class DirectorySizeScheduler<K : Any, V : Any>(
    private val executor: Executor,
    private val postDelayed: (Long, () -> Unit) -> Unit,
    private val compute: (K, Cancellation) -> V,
    private val parallelism: Int = 2,
    private val cacheCapacity: Int = 512,
    private val maxDemand: Int = 128,
    private val batchDelayMillis: Long = 100
) {
    init {
        require(parallelism > 0 && cacheCapacity > 0 && maxDemand > 0)
    }

    class Cancellation {
        @Volatile var isCancelled = false
            private set
        private var thread: Thread? = null

        @Synchronized internal fun start() {
            thread = Thread.currentThread()
            if (isCancelled) thread?.interrupt()
        }

        @Synchronized internal fun cancel() {
            isCancelled = true
            thread?.interrupt()
        }

        @Synchronized internal fun finish() {
            thread = null
            // Do not leak cancellation to the next job on the pooled thread.
            Thread.interrupted()
        }
    }

    private inner class Owner(val publish: (Map<K, V>) -> Unit) {
        var generation = 0L
        var demand = emptyList<K>()
        val results = mutableMapOf<K, V>()
        val attempted = mutableSetOf<K>()
        var dirty = false
    }

    private inner class Job(val owner: Any, val generation: Long, val key: K) {
        val cancellation = Cancellation()
        val cacheGeneration = this@DirectorySizeScheduler.cacheGeneration
    }

    private val owners = linkedMapOf<Any, Owner>()
    private val running = mutableListOf<Job>()
    private val cache = LinkedHashMap<K, V>(16, 0.75f, true)
    private var cacheGeneration = 0L
    private var publicationPending = false

    @Synchronized fun register(owner: Any, publish: (Map<K, V>) -> Unit) {
        release(owner)
        owners[owner] = Owner(publish)
    }

    /** Paths are in viewport priority order. Empty demand releases all work for this owner. */
    @Synchronized fun request(owner: Any, keys: List<K>) {
        val state = owners[owner] ?: return
        val demand = keys.distinct().take(maxDemand)
        if (state.demand == demand) return
        state.demand = demand
        val wanted = demand.toSet()
        state.results.keys.retainAll(wanted)
        state.attempted.retainAll(wanted)
        running.filter { it.owner == owner && it.key !in wanted }.forEach {
            it.cancellation.cancel()
        }
        for (key in demand) {
            cache[key]?.let { state.results[key] = it }
        }
        markDirty(state)
        pump()
    }

    /** Reset this pane, without interrupting another pane even when it views the same path. */
    @Synchronized fun reset(owner: Any, invalidate: ((K) -> Boolean)? = null) {
        if (invalidate != null) {
            cache.keys.removeAll(invalidate)
            // Other panes may finish their walks, but pre-refresh results cannot refill the cache.
            ++cacheGeneration
        }
        val state = owners[owner] ?: return
        ++state.generation
        state.demand = emptyList()
        state.results.clear()
        state.attempted.clear()
        running.filter { it.owner == owner }.forEach { it.cancellation.cancel() }
        markDirty(state)
        pump()
    }

    @Synchronized fun release(owner: Any) {
        owners.remove(owner)
        running.filter { it.owner == owner }.forEach { it.cancellation.cancel() }
        pump()
    }

    // Round-robin by owner: a viewport with many folders cannot consume the entire pending queue.
    private fun pump() {
        while (running.size < parallelism) {
            val entry = owners.entries.firstOrNull { (owner, state) ->
                state.demand.any { key ->
                    key !in state.results && key !in state.attempted &&
                        running.none { it.owner == owner && it.key == key &&
                            !it.cancellation.isCancelled }
                }
            } ?: return
            val owner = entry.key
            val state = entry.value
            val key = state.demand.first { key ->
                key !in state.results && key !in state.attempted &&
                    running.none { it.owner == owner && it.key == key &&
                        !it.cancellation.isCancelled }
            }
            owners.remove(owner)
            owners[owner] = state
            val job = Job(owner, state.generation, key)
            running.add(job)
            executor.execute {
                job.cancellation.start()
                val value = try {
                    if (job.cancellation.isCancelled) null else compute(key, job.cancellation)
                } catch (_: Exception) {
                    null
                } finally {
                    job.cancellation.finish()
                }
                complete(job, state, value)
            }
        }
    }

    @Synchronized private fun complete(job: Job, original: Owner, value: V?) {
        running.remove(job)
        val state = owners[job.owner]
        if (state === original && state.generation == job.generation &&
            job.key in state.demand && !job.cancellation.isCancelled
        ) {
            state.attempted.add(job.key)
            if (value != null) {
                state.results[job.key] = value
                if (job.cacheGeneration == cacheGeneration) {
                    cache[job.key] = value
                    while (cache.size > cacheCapacity) {
                        cache.remove(cache.keys.first())
                    }
                }
                markDirty(state)
            }
        }
        pump()
    }

    private fun markDirty(state: Owner) {
        state.dirty = true
        if (publicationPending) return
        publicationPending = true
        postDelayed(batchDelayMillis) { publishBatch() }
    }

    @Synchronized private fun publishBatch() {
        publicationPending = false
        // Construct snapshots only once per batch and only for the owner's current viewport.
        // Read current state here, not when posting: reset/release cannot deliver an old snapshot.
        for (state in owners.values.toList()) {
            if (!state.dirty) continue
            state.dirty = false
            state.publish(state.results.toMap())
        }
    }
}
