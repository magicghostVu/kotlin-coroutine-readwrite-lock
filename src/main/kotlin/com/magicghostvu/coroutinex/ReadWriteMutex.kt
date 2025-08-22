package com.magicghostvu.coroutinex

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
sealed class ReadWriteMutex {

    suspend fun <T> read(action: suspend () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        return readImpl(action)
    }


    suspend fun <T> write(action: suspend () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        return writeImpl(action)
    }


    protected abstract suspend fun <T> readImpl(action: suspend () -> T): T
    protected abstract suspend fun <T> writeImpl(action: suspend () -> T): T

    companion object {
        fun ReadWriteMutex(typeImpl: UnderlyingImplType = UnderlyingImplType.FSM): ReadWriteMutex {
            return when (typeImpl) {
                UnderlyingImplType.FSM -> ReadWriteMutexFSMBased()
                UnderlyingImplType.SEMAPHORE -> ReadWriteMutexSemaphoreBased()
            }
        }
    }
}

enum class UnderlyingImplType {
    FSM,
    SEMAPHORE
}