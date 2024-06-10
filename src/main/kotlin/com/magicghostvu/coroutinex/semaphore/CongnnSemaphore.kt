package com.magicghostvu.coroutinex.semaphore

import kotlinx.coroutines.sync.Mutex

class CongnnSemaphore(val numPermits: Int) {
    private var permitWait: Int = -1
    private var permitRemain: Int = numPermits;
    private val lockAcquire: Mutex = Mutex(false)
    private val lockPermit: Mutex = Mutex(false)
    private val lockWait: Mutex = Mutex(true)

    suspend fun acquire(n: Int) {
        //validate n value
        lockAcquire.lock()
        lockPermit.lock()
        if (permitRemain < n) {
            permitWait = n
            lockPermit.unlock()
            lockWait.lock()
            lockPermit.lock()
        }
        permitRemain -= n;
        lockPermit.unlock()
        lockAcquire.unlock()
    }

    suspend fun release(n: Int) {
        lockPermit.lock()
        permitRemain += n;
        // todo: convert to range check
        if (permitWait >= 0 && permitRemain >= permitWait) {
            permitWait = -1;
            lockWait.unlock()
        }
        lockPermit.unlock()
    }
}