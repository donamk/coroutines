package tech.pronghorn.coroutines.service

import tech.pronghorn.coroutines.awaitable.ServiceCoroutineContext
import tech.pronghorn.coroutines.core.CoroutineWorker
import tech.pronghorn.coroutines.core.myRun
import mu.KLogger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.RestrictsSuspension
import kotlin.coroutines.experimental.suspendCoroutine

@RestrictsSuspension
abstract class Service {
    companion object {
        private val serviceID = AtomicLong(0)
    }

    abstract protected val logger: KLogger
    protected val serviceID = Service.serviceID.incrementAndGet()
    protected var isRunning = true
    abstract val worker: CoroutineWorker
    private var wakeValue: Any? = null

    var isQueued = false

    var continuation: Continuation<Any>? = null
        private set

    abstract protected suspend fun run()

    /**
     * Offers a hook for a function to be called each time this service suspends, useful for counters or stats tracking.
     * Called when suspending regardless of reason, whether due to shouldYield() or any async call.
     */
    open fun onSuspend() = Unit

    fun shutdown(): Unit {
        isRunning = false
    }

    fun start() {
        myRun(ServiceCoroutineContext(this)) { runWrapper() }
    }

    private suspend fun runWrapper(): Unit {
        try {
            run()
        }
        catch (ex: Exception) {
            ex.printStackTrace()
        }
        catch (ex: Error) {
            ex.printStackTrace()
            throw ex
        }
    }

    fun resume() {
        val wakeValue = this.wakeValue
        val continuation = this.continuation
        if(wakeValue == null || continuation == null){
            println("Unexpected null wake value ($wakeValue) ($continuation).")
            System.exit(1)
            throw IllegalStateException("Unexpected null wake value.")
        }
        else {
            this.wakeValue = null
            this.continuation = null
            continuation.resume(wakeValue)
        }
    }

    fun <T> wake(value: T) {
        logger.debug("Waking service $serviceID")
        if(wakeValue != null){
            throw IllegalStateException("Unexpected overwrite of wake value.")
        }
        else if(continuation == null){
            throw IllegalStateException("Unable to wake service without a continuation.")
        }
        wakeValue = value
        worker.offerReady(this)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> yield(continuation: Continuation<T>) {
        this.continuation = continuation as Continuation<Any>
        onSuspend()
    }

    protected suspend fun yieldAsync() {
        logger.debug("Yielding to worker...")
        suspendCoroutine { continuation: Continuation<Any> ->
            yield(continuation)
            wake(Unit)
        }
    }
}
