package com.wing.folderplayer.data.waveform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One keyed job at a time that outlives its watchers: [watch] of the key whose job is running joins it, any other key
 * cancels it and starts its own. The job runs in [scope], so a watcher that goes away does not stop it. A finished job
 * is not kept: the next watch of its key runs its block again (the caller keeps what should last, e.g. on disk).
 */
class LatestJob<K, T : Any>(private val scope: CoroutineScope) {
    private sealed interface State<out V> {
        /** [latest]: the last progress value, null before the first. */
        class Running<V>(val latest: V?) : State<V>
        /** [result]: null when the block returned none or was cancelled. */
        class Done<V>(val result: V?) : State<V>
    }

    private class Run<K, V>(val key: K, val job: Job, val state: StateFlow<State<V>>)

    private var current: Run<K, T>? = null

    /**
     * The progress values of the job of [key] (when joining, the latest one first), then its result. Completes after
     * the result, or without one when the block returned null or was cancelled by a watch of another key. [block] runs
     * only when no job of [key] is running; it reports progress through its argument and must not throw anything but
     * a cancellation (it runs outside the watcher, which could not catch it).
     */
    @OptIn(ExperimentalCoroutinesApi::class) // transformWhile, in older versions
    fun watch(key: K, block: suspend (progress: suspend (T) -> Unit) -> T?): Flow<T> = flow {
        emitAll(join(key, block).transformWhile { s ->
            when (s) {
                is State.Running -> { s.latest?.let { emit(it) }; true }
                is State.Done -> { s.result?.let { emit(it) }; false }
            }
        })
    }

    private fun join(key: K, block: suspend (progress: suspend (T) -> Unit) -> T?): StateFlow<State<T>> {
        synchronized(this) {
            current?.takeIf { it.key == key && it.job.isActive }?.let { return it.state }
            current?.job?.cancel()
            val state = MutableStateFlow<State<T>>(State.Running(null))
            val job = scope.launch { state.value = State.Done(block { state.value = State.Running(it) }) }
            // Also when cancelled before it started: its watchers must not wait for a result that never comes.
            job.invokeOnCompletion { state.update { s -> if (s is State.Done) s else State.Done<T>(null) } }
            current = Run(key, job, state)
            return state
        }
    }
}
