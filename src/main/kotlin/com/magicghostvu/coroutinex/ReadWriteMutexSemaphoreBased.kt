package com.magicghostvu.coroutinex

import com.magicghostvu.coroutinex.semaphore.FifoSemaphore
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class ReadWriteMutexSemaphoreBased() {
    private val semaphore = FifoSemaphore(numPermitForSemaphore)

    @OptIn(ExperimentalContracts::class)
    suspend fun <T> read(action: suspend () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        semaphore.acquire(1)
        return try {
            action()
        } finally {
            semaphore.release(1)
        }
    }

    @OptIn(ExperimentalContracts::class)
    suspend fun <T> write(action: suspend () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        semaphore.acquire(numPermitForSemaphore)
        return try {
            action()
        } finally {
            semaphore.release(numPermitForSemaphore)
        }
    }

    private companion object {
        //1M concurrent read task
        private val numPermitForSemaphore = 1_000_000
    }
}