package com.magicghostvu.coroutinex


import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.LinkedList
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


class ReadWriteMutex {
    private var state: ReadWriteMutexStateData = Empty

    private val logger: Logger = LoggerFactory.getLogger("mutex")

    @OptIn(ExperimentalContracts::class)
    suspend fun <T> read(action: suspend () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        // loop to re-check
        while (true) {
            val ticketOrAllowedAction: Either<CompletableDeferred<Unit>, Unit> = synchronized(this) {
                when (val tState = state) {
                    Empty -> {
                        logger.debug("empty to reading")
                        state = Reading(
                            LinkedList(),
                            1
                        )
                        Either.right(Unit)
                    }

                    is Reading -> {
                        logger.debug("continue reading")
                        tState.readingCount++
                        Either.right(Unit)
                    }

                    is Writing -> {
                        //todo: add read req to the queue to notify later
                        val ticket = tState.addReadReq()
                        Either.left(ticket)
                    }
                }
            }

            when (ticketOrAllowedAction) {
                is Left -> {
                    val ticketRetry = ticketOrAllowedAction.value
                    try {
                        ticketRetry.await()
                    } catch (e: CancellationException) {
                        //todo: post process like action success
                        logger.debug("cancel read at waiting ticket")
                        onCancelTicketRead()
                        throw e
                    }
                    continue
                }

                is Right -> {
                    return try {
                        action()
                    } finally {
                        // lock time is very short and it is acceptable
                        synchronized(this) {
                            //logger.debug("current state is {}", state)
                            when (val tTState = state) {
                                is Reading -> {
                                    logger.debug("reading count is {}", tTState.readingCount)
                                    tTState.readingCount--
                                    // không còn ai đang read
                                    // thử check write

                                    if (tTState.readingCount == 0) {
                                        if (tTState.reqWrite.isNotEmpty()) {
                                            // todo: notify for all write req
                                            tTState.reqWrite.forEach {
                                                it.complete(Unit)
                                            }
                                        }
                                        logger.debug("comeback to empty from read")
                                        state = Empty
                                    }
                                }

                                Empty -> {}
                                is Writing -> {
                                    throw IllegalArgumentException("impossible")
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    @OptIn(ExperimentalContracts::class)
    suspend fun <T> write(action: suspend () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        // temp var here to save req data for write???
        while (true) {
            val ticketOrAllowedWrite: Either<CompletableDeferred<Unit>, Unit> = synchronized(this) {
                when (val tState = state) {
                    Empty -> {
                        //allow to write
                        state = Writing(LinkedList(), mutableListOf())
                        Either.right(Unit)
                    }

                    is Reading -> {
                        val ticket = tState.addWriteReq()
                        Either.left(ticket)
                    }

                    is Writing -> {
                        //logger.debug("add write request")
                        Either.left(tState.addWriteReq())
                    }
                }
            }
            when (ticketOrAllowedWrite) {
                is Left -> {
                    try {
                        ticketOrAllowedWrite.value.await()
                    } catch (e: CancellationException) {
                        // todo: post process like action success
                        //logger.info("canceled write at waiting ticket", e)
                        onCancelTicketWrite()
                        throw e
                    }
                    continue
                }

                is Right -> {
                    return try {
                        action()
                    } finally {
                        synchronized(this) {
                            when (val tTState = state) {
                                Empty,
                                is Reading -> {
                                    throw IllegalArgumentException("impossible, review code")
                                }

                                // todo: consider optimize this
                                is Writing -> {

                                    // báo hiệu cho tất cả các read và write req re-check
                                    logger.debug(
                                        "read req is {}, write req is {}",
                                        tTState.readQueue.size,
                                        tTState.writeQueue.size
                                    )

                                    tTState.writeQueue.forEach {
                                        it.complete(Unit)
                                    }
                                    tTState.readQueue.forEach {
                                        it.complete(Unit)
                                    }
                                    logger.debug("writing comeback to empty")
                                    state = Empty
                                }
                            }
                        }
                    }
                }
            }
        }

    }


    // do nothing ??
    private fun onCancelTicketWrite() = synchronized(this) {
        when (val tState = state) {
            Empty -> {}
            is Reading -> {

            }

            is Writing -> {

            }
        }
    }

    private fun onCancelTicketRead(): Unit = synchronized(this) {
        when (val tState = state) {
            Empty -> {

            }

            is Reading -> {}
            is Writing -> {}
        }
    }

}


// hiện tại khi đang read thì write sẽ không thể vào
// như vậy có thể dẫn đến sẽ không bao giờ được write nếu các read cứ chồng chéo lên nhau mãi
// nên thêm một state để khi có req write thì sẽ chỉ đợi xong hết các read hiện tại, các read req
// được add vào sau cái write sẽ được thực thi sau khi write

internal sealed class ReadWriteMutexStateData() {

}

internal object Empty : ReadWriteMutexStateData() {

}

internal class Reading(
    val reqWrite: LinkedList<CompletableDeferred<Unit>> = LinkedList(),
    var readingCount: Int = 0,
) : ReadWriteMutexStateData() {


    fun addWriteReq(): CompletableDeferred<Unit> {
        val res = CompletableDeferred<Unit>()
        reqWrite.add(res)
        return res
    }
}

internal class Writing(
    val writeQueue: LinkedList<CompletableDeferred<Unit>>,
    val readQueue: MutableList<CompletableDeferred<Unit>>
) : ReadWriteMutexStateData() {


    fun addReadReq(): CompletableDeferred<Unit> {
        val res = CompletableDeferred<Unit>()
        readQueue.add(res)
        return res
    }

    fun addWriteReq(): CompletableDeferred<Unit> {
        val res = CompletableDeferred<Unit>()
        writeQueue.add(res)
        return res
    }
}