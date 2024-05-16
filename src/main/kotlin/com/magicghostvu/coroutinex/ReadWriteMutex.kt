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

                    is WaitingCurrentReadDone -> {
                        val ticket = tState.addReadReq()
                        Either.left(ticket)
                    }

                    is EmptyDelayRead -> {
                        logger.debug("add read to empty delay read")
                        val ticket = tState.addReadTicket()
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
                                        // todo: notify for all write req
                                        tTState.reqWrite.forEach {
                                            it.complete(Unit)
                                        }
                                        logger.debug("comeback to empty from read")
                                        state = Empty
                                    }
                                }

                                is WaitingCurrentReadDone -> {
                                    logger.debug("after read action at waiting current read done")
                                    tTState.numCurrentRead--
                                    if (tTState.numCurrentRead == 0) {
                                        //dispatch tất cả các write nếu có các write
                                        if (tTState.writeQueue.isNotEmpty()) {
                                            tTState.writeQueue.forEach {
                                                it.complete(Unit)
                                            }
                                            state = EmptyDelayRead(
                                                tTState.readQueue
                                            )
                                        } else {
                                            // về empty và dispatch all read
                                            tTState.readQueue.forEach {
                                                it.complete(Unit)
                                            }
                                            state = Empty
                                        }
                                    }
                                }

                                is EmptyDelayRead -> {
                                    logger.warn("wrong logic, review code")
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
                        val writeQueue = linkedSetOf<CompletableDeferred<Unit>>()
                        writeQueue.addAll(tState.reqWrite)
                        state = WaitingCurrentReadDone(
                            tState.readingCount,
                            writeQueue = writeQueue
                        )
                        Either.left(ticket)
                    }

                    is Writing -> {
                        //logger.debug("add write request")
                        Either.left(tState.addWriteReq())
                    }

                    is WaitingCurrentReadDone -> {
                        val ticket = tState.addWriteReq()
                        Either.left(ticket)
                    }

                    is EmptyDelayRead -> {
                        val writeQueue = LinkedList<CompletableDeferred<Unit>>()
                        writeQueue.addAll(tState.writeQueue)
                        state = Writing(writeQueue, tState.readQueue)
                        Either.right(Unit)
                    }
                }
            }
            when (ticketOrAllowedWrite) {
                is Left -> {
                    val ticket = ticketOrAllowedWrite.value
                    try {
                        ticket.await()
                    } catch (e: CancellationException) {
                        // todo: post process like action success
                        //logger.info("canceled write at waiting ticket", e)
                        onCancelTicketWrite(ticket)
                        throw e
                    }
                    continue
                }

                is Right -> {
                    return try {
                        action()
                    } finally {
                        synchronized(this) {
                            logger.debug("state after write is {}", state.javaClass.simpleName)
                            when (val tTState = state) {
                                Empty,
                                is EmptyDelayRead -> {}
                                is WaitingCurrentReadDone -> {}
                                is Reading -> {
                                    throw IllegalArgumentException("impossible, review code")
                                }


                                is Writing -> {

                                    // báo hiệu cho tất cả các read và write req re-check
                                    logger.debug(
                                        "read req is {}, write req is {}",
                                        tTState.readQueue.size,
                                        tTState.writeQueue.size
                                    )
                                    if (tTState.writeQueue.isNotEmpty()) {
                                        tTState.writeQueue.forEach {
                                            it.complete(Unit)
                                        }
                                        state = EmptyDelayRead(
                                            tTState.readQueue,
                                            linkedSetOf()
                                        )
                                    } else {
                                        logger.debug("writing comeback to empty")
                                        tTState.readQueue.forEach {
                                            it.complete(Unit)
                                        }
                                        state = Empty
                                    }

                                }


                            }
                        }
                    }
                }
            }
        }
    }

    // do nothing ??
    private fun onCancelTicketWrite(ticket: CompletableDeferred<Unit>) = synchronized(this) {
        logger.debug("state at cancel ticket write {}", state.javaClass.simpleName)

        // todo: check state possible here
        when (val tState = state) {
            Empty -> {}
            is Reading -> {

            }

            is Writing -> {
                // todo: remove ticket
            }

            is EmptyDelayRead -> {}
            is WaitingCurrentReadDone -> {}
        }
    }

    // chỉ xảy ra khi đang writing hoặc WaitingCurrentReadDone
    private fun onCancelTicketRead(): Unit = synchronized(this) {
        logger.debug("on cancel ticket read state is {}", state.javaClass.simpleName)
        when (val tState = state) {
            is Reading -> {// it is possible???
                //todo: trừ số reading count đi???
                // và check xem có về 0 chưa để chuyển state??
                //tState.readingCount--
            }

            is WaitingCurrentReadDone -> {
                //todo: trừ số reading count đi???
                // và check xem có về 0 chưa để chuyển trạng thái??
                //tState.numCurrentRead--
            }

            is EmptyDelayRead -> {}
            Empty -> {}
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

internal class EmptyDelayRead(
    val readQueue: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
    val writeQueue: MutableSet<CompletableDeferred<Unit>> = linkedSetOf()
) : ReadWriteMutexStateData() {
    fun addReadTicket(): CompletableDeferred<Unit> {
        val ticket = CompletableDeferred<Unit>()
        readQueue.add(ticket)
        return ticket
    }
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

// nếu đang read mà có yêu cầu write thì chuyển sang cái này
internal class WaitingCurrentReadDone(
    var numCurrentRead: Int,
    val readQueue: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
    val writeQueue: MutableSet<CompletableDeferred<Unit>> = linkedSetOf(),
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