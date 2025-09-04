package com.magicghostvu.coroutinex.semaphore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class FifoSemaphore(private val originPermits: Int) {
    private val logger: Logger = LoggerFactory.getLogger("fifo-semaphore")

    // Remaining available permits
    private var available: Int = originPermits

    // Single mutex protecting all state
    private val lock = Mutex()

    // FIFO queue of waiters; once any waiter exists, no one bypasses the queue
    // maybe use linked hashset for better removal performance
    private val waiters: LinkedHashSet<Waiter> = LinkedHashSet()

    //use value class when valhalla ready
    private data class Waiter(val permits: Int, val gate: CompletableDeferred<Unit> = CompletableDeferred())

    suspend fun acquire(n: Int) {
        require(n > 0) { "permits must be positive" }
        if (n > originPermits) {
            throw IllegalArgumentException("can not acquire $n permits, max is $originPermits")
        }

        // Fast-path if no queue and enough permits; otherwise enqueue and wait
        val waiter = lock.withLock {
            if (waiters.isEmpty() && available >= n) {
                available -= n
                logger.debug("{} acquired immediately, remain {}", n, available)
                null
            } else {
                val w = Waiter(n)
                waiters.add(w)
                logger.debug("enqueue acquire {} (queue size {}), available {}", n, waiters.size, available)
                w
            }
        }

        // acquire success
        if (waiter == null) return

        try {
            waiter.gate.await()
        } catch (ce: CancellationException) {
            // Remove from queue on cancellation and try to dispatch others
            withContext(NonCancellable) {
                lock.withLock {
                    if (waiters.remove(waiter)) {
                        logger.debug("canceled waiter for {} permits (removed from queue)", waiter.permits)
                        dispatchLocked()
                    }
                }
            }
            throw ce
        }
    }

    suspend fun release(n: Int) {
        require(n > 0) { "permits must be positive" }
        withContext(NonCancellable) {
            lock.withLock {
                val newValue = available + n
                if (newValue > originPermits) {
                    throw IllegalArgumentException("release too much $newValue, max is $originPermits")
                }
                available = newValue
                logger.debug("released {}, now available {}", n, available)
                dispatchLocked()
            }
        }
    }

    private fun dispatchLocked() {
        // Grant permits to the head(s) of the queue in FIFO order.
        // New acquirers do not bypass queued waiters.
        while (waiters.isNotEmpty()) {
            val head = waiters.first()
            if (available < head.permits) break
            waiters.remove(head)
            available -= head.permits
            head.gate.complete(Unit)
            logger.debug("granted {}, remain {}, queue {}", head.permits, available, waiters.size)
        }
    }
}
