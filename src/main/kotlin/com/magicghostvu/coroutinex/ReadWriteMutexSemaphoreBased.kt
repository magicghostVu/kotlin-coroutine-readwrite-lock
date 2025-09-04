package com.magicghostvu.coroutinex

import com.magicghostvu.coroutinex.semaphore.FifoSemaphore

class ReadWriteMutexSemaphoreBased internal constructor() : ReadWriteMutex() {
    private val semaphore = FifoSemaphore(numPermitForSemaphore)

    override suspend fun <T> readImpl(action: suspend () -> T): T {
        semaphore.acquire(1)
        return try {
            action()
        } finally {
            semaphore.release(1)
        }
    }

    override suspend fun <T> writeImpl(action: suspend () -> T): T {
        semaphore.acquire(numPermitForSemaphore)
        return try {
            action()
        } finally {
            semaphore.release(numPermitForSemaphore)
        }
    }

    private companion object {
        //100M concurrent read task
        private val numPermitForSemaphore = 100_000_000
    }
}