package com.magicghostvu.coroutinex.semaphore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class FifoSemaphore(private val originPermits: Int) {
    private var numPermitsRemain: Int = originPermits
    private var permitWait = -1
    private val lockAcquire = Mutex()
    private val lockPermit = Mutex()
    private val lockWait = Mutex(true)

    private val logger: Logger = LoggerFactory.getLogger("fifo-semaphore")

    suspend fun acquire(n: Int) {
        if (n > originPermits) {
            throw IllegalArgumentException("can not acquire $n permits, max is {} $originPermits")
        }
        lockAcquire.withLock {
            lockPermit.lock()
            if (numPermitsRemain < n) {
                permitWait = n
                lockPermit.unlock()
                try {
                    lockWait.lock()
                } catch (ce: CancellationException) {
                    withContext(NonCancellable) {
                        logger.debug("canceled {}", permitWait)
                        lockPermit.withLock {
                            logger.debug("canceled 2 {}", permitWait)
                            if (permitWait < 0)
                                lockWait.lock()
                            else
                                permitWait = -1

                            logger.debug("canceled 3 {}", lockWait.isLocked)
                        }
                    }
                    throw ce
                }
                lockPermit.lock()
            }
            numPermitsRemain -= n
            logger.debug("{} acquired, remain {}", n, numPermitsRemain)
            lockPermit.unlock()
        }
    }

    suspend fun release(n: Int) {
        withContext(NonCancellable) {
            lockPermit.withLock {
                val newValue = numPermitsRemain + n
                if (newValue > originPermits) {
                    throw IllegalArgumentException("release to much $newValue, max is $originPermits")
                }
                numPermitsRemain = newValue
                if (permitWait in 0..numPermitsRemain) {
                    permitWait = -1;
                    lockWait.unlock()
                }
            }
        }
    }
}
