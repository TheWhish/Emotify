package me.whish.emotify.network

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal object NetworkMainThreadDispatcher {
    fun submit(
        enqueue: (Runnable) -> CompletableFuture<Void>,
        task: () -> Unit,
        onTaskFailure: (RuntimeException) -> Unit,
        onEnqueueFailure: (Throwable) -> Unit,
        onCompletion: () -> Unit = {},
    ) {
        val taskStarted = AtomicBoolean()
        val future = try {
            enqueue(
                Runnable {
                    taskStarted.set(true)
                    try {
                        task()
                    } catch (exception: RuntimeException) {
                        onTaskFailure(exception)
                    }
                },
            )
        } catch (exception: RuntimeException) {
            try {
                onEnqueueFailure(exception)
            } finally {
                onCompletion()
            }
            return
        } catch (error: Error) {
            onCompletion()
            throw error
        }

        future.whenComplete { _, failure ->
            try {
                val cause = failure?.completionCause()
                if (cause != null && cause !is CancellationException && !taskStarted.get()) {
                    onEnqueueFailure(cause)
                }
            } finally {
                onCompletion()
            }
        }
    }

    private fun Throwable.completionCause(): Throwable = when (this) {
        is CompletionException,
        is ExecutionException,
        -> cause?.completionCause() ?: this
        else -> this
    }
}
