package com.magicghostvu.coroutinex


import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.contracts.ExperimentalContracts



class ReadWriteMutexFSMBased : ReadWriteMutex() {
    private var state: ReadWriteMutexStateData = Empty

    private val logger: Logger = LoggerFactory.getLogger("rw-mutex")

    @OptIn(ExperimentalContracts::class)
    override suspend fun <T> readImpl(action: suspend () -> T): T {
        // loop to re-check
        while (true) {
            val ticketOrAllowedAction: Either<CompletableDeferred<Unit>, Unit> = synchronized(this) {
                when (val tState = state) {
                    Empty -> {
                        logger.debug("empty to reading")
                        state = Reading(
                            linkedSetOf(),
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
                        val ticket = tState.addReadReq()
                        Either.left(ticket)
                    }

                    is WaitingCurrentReadDone -> {
                        logger.debug("read enqueued because of priority write")
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
                        onCancelTicketRead(ticketRetry)
                        throw e
                    }
                    continue
                }

                is Right -> {
                    return try {
                        action()
                    } finally {
                        // lock time is very short and no problem
                        synchronized(this) {
                            //logger.debug("current state is {}", state)
                            when (val tState = state) {
                                is Reading -> {
                                    logger.debug("reading count is {}", tState.readingCount)
                                    tState.readingCount--
                                    // không còn ai đang read
                                    // thử check write
                                    if (tState.readingCount == 0) {
                                        // todo: notify for all write req
                                        tState.reqWrite.forEach {
                                            it.complete(Unit)
                                        }
                                        logger.debug("comeback to empty from read")
                                        state = Empty
                                    }
                                }

                                is WaitingCurrentReadDone -> {
                                    logger.debug(
                                        "after read action at waiting current read done num read is {}",
                                        tState.numCurrentRead
                                    )
                                    tState.numCurrentRead--
                                    if (tState.numCurrentRead == 0) {
                                        //dispatch tất cả các write nếu có các write
                                        if (tState.writeQueue.isNotEmpty()) {
                                            logger.debug("dispatch {} write req", tState.writeQueue.size)
                                            tState.writeQueue.forEach {
                                                it.complete(Unit)
                                            }
                                            state = EmptyDelayRead(
                                                tState.readQueue
                                            )
                                        } else {
                                            // về empty và dispatch all read
                                            tState.readQueue.forEach {
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
    override suspend fun <T> writeImpl(action: suspend () -> T): T {
        /*contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }*/
        // temp var here to save req data for write???
        while (true) {
            val ticketOrAllowedWrite: Either<CompletableDeferred<Unit>, Unit> = synchronized(this) {
                when (val tState = state) {
                    Empty -> {
                        //allow to write
                        state = Writing(linkedSetOf(), mutableListOf())
                        Either.right(Unit)
                    }

                    is Reading -> {
                        val ticket = tState.addWriteReq()
                        state = WaitingCurrentReadDone(
                            tState.readingCount,
                            writeQueue = tState.reqWrite
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
                        state = Writing(linkedSetOf(), tState.readQueue)
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
                            when (val tState = state) {
                                Empty,
                                is EmptyDelayRead ,
                                is WaitingCurrentReadDone,
                                is Reading -> {
                                    throw IllegalArgumentException("impossible, review code")
                                }
                                is Writing -> {

                                    // báo hiệu cho tất cả các read và write req re-check
                                    logger.debug(
                                        "after write: read req is {}, write req is {}",
                                        tState.readQueue.size,
                                        tState.writeQueue.size
                                    )
                                    if (tState.writeQueue.isNotEmpty()) {
                                        tState.writeQueue.forEach {
                                            it.complete(Unit)
                                        }
                                        state = EmptyDelayRead(
                                            tState.readQueue
                                        )
                                        logger.debug("writing comeback to empty delay read")
                                    } else {
                                        logger.debug("writing comeback to empty")
                                        tState.readQueue.forEach {
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
    // cancel sẽ có thể xảy ra ở trạng thái nào
    // khi có một ticket phải đợi -> reading, writing, empty delay read, waiting current read done
    // reading, writing,
    private fun onCancelTicketWrite(ticket: CompletableDeferred<Unit>) = synchronized(this) {
        logger.debug("state at cancel ticket write {}", state.javaClass.simpleName)

        // todo: check all states possible here
        when (val tState = state) {
            Empty -> {}
            is Reading -> {
                tState.reqWrite.remove(ticket)
            }

            is Writing -> {
                // todo: remove ticket
                // có khi nào nó không remove được??
                // sẽ không remove được khi ticket này thuộc về wait current read done trước đó
                val removed = tState.writeQueue.remove(ticket)
                logger.debug("remove ticket at cancel ticket write {}", removed)
            }

            // nếu bị hủy trong trạng thái này thì
            // khả năng là tất cả các write được wake đã bị hủy hết
            // nên chuyển về empty và wake tất cả các read
            // todo: có thể ghi lại các ticket write đã được dispatch ở phase WaitingCurrentReadDone
            //  để nếu ticket đó có thể xóa được ở đây thì check và có thể quay lại empty
            is EmptyDelayRead -> {
                //
                //logger.debug("cancel write at empty delay read")
                tState.readQueue.forEach {
                    it.complete(Unit)
                }
                state = Empty
            }

            // chờ các read hiện tại xong và chuyển sang write
            // phải test trường hợp mà tất cả các write req đã được dispatch nhưng không có action write nào được gọi
            is WaitingCurrentReadDone -> {
                val removed = tState.writeQueue.remove(ticket)
                logger.debug("remove write ticket at wait current read done {}", removed)
                if (removed) {
                    // check write queue empty
                    logger.debug("write queue size after remove is {}", tState.writeQueue.size)
                    if (tState.writeQueue.isEmpty()) {
                        //đánh thức tất cả các read và chuyển sang reading???
                        tState.readQueue.forEach {
                            it.complete(Unit)
                        }
                        state = Reading(readingCount = tState.numCurrentRead)
                    }
                }
                Unit
            }
        }
    }

    // chỉ xảy ra khi đang writing hoặc WaitingCurrentReadDone
    private fun onCancelTicketRead(ticket: CompletableDeferred<Unit>): Unit = synchronized(this) {
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
            is Writing -> {}
            Empty -> {}
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
) : ReadWriteMutexStateData() {
    fun addReadTicket(): CompletableDeferred<Unit> {
        val ticket = CompletableDeferred<Unit>()
        readQueue.add(ticket)
        return ticket
    }
}

internal class Reading(
    val reqWrite: MutableSet<CompletableDeferred<Unit>> = linkedSetOf(),
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
    val writeQueue: MutableSet<CompletableDeferred<Unit>>,
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