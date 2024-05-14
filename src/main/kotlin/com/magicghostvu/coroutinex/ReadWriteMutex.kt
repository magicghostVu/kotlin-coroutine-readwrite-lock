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
                        state = Reading()
                        Either.right(Unit)
                    }

                    is Reading -> {
                        logger.debug("continue reading")
                        tState.readingCount++
                        Either.right(Unit)
                    }

                    is WaitCurrentReadDone -> {
                        logger.debug("add read req at waiting all current read done")
                        Either.left(tState.addReadReq())
                    }

                    is Writing -> {
                        val ticket = tState.addReadReq()
                        Either.left(ticket)
                    }

                    is ContinueWrite -> {
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
                                        val writeQueue = tTState.writeQueue
                                        if (writeQueue.isNotEmpty()) {
                                            val firstTicket = writeQueue.first()
                                            writeQueue.remove(firstTicket)
                                            if (writeQueue.isNotEmpty()) {
                                                state = ContinueWrite(
                                                    writeQueue,
                                                    mutableListOf()
                                                )
                                                // maybe first ticket đã bị hủy
                                                firstTicket.complete(WriteReqWithToken())
                                            } else {
                                                firstTicket.complete(EmptyWriteReq)
                                                state = Empty
                                            }
                                        } else {
                                            logger.debug("comeback to empty from read")
                                            state = Empty
                                        }
                                    }
                                }

                                is WaitCurrentReadDone -> {
                                    tTState.numCurrentRead--
                                    // all read done
                                    // move to write
                                    if (tTState.numCurrentRead == 0) { //về writing nhưng phải có token mới vào được???
                                        val writeQueue = tTState.writeQueue
                                        if (writeQueue.isNotEmpty()) {
                                            val firstTicket = writeQueue.first()
                                            writeQueue.remove(firstTicket)
                                            firstTicket.complete(WriteReqWithToken())
                                            state = ContinueWrite(
                                                writeQueue,
                                                tTState.readQueue
                                            )
                                        }
                                    }
                                }

                                Empty -> {}
                                is ContinueWrite,
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

        var ticketDataReq: WriteReqData = EmptyWriteReq

        while (true) {
            val ticketOrAllowedWrite: Either<CompletableDeferred<WriteReqData>, Unit> = synchronized(this) {
                when (val tState = state) {
                    Empty -> {
                        //allow to write
                        state = Writing(mutableListOf())
                        Either.right(Unit)
                    }

                    is Reading -> {
                        // todo: chuyển sang wait read done
                        val t = WaitCurrentReadDone(
                            tState.readingCount,
                            tState.writeQueue,
                            mutableListOf()
                        )
                        val ticket = t.addWriteReq()
                        state = t
                        Either.left(ticket)
                    }

                    is Writing -> {
                        // todo: move to continue write
                        val t = ContinueWrite(
                            linkedSetOf(),
                            tState.readQueue
                        )
                        val ticket = t.addWriteReq()
                        state = t
                        Either.left(ticket)
                    }

                    is WaitCurrentReadDone -> {
                        val ticket = tState.addWriteReq()
                        Either.left(ticket)
                    }

                    // bị loop ở đây do không bao giờ vào được write khi đang continue write
                    is ContinueWrite -> {
                        when (ticketDataReq) {
                            EmptyWriteReq -> {
                                val ticket = tState.addWriteReq()
                                Either.left(ticket)
                            }

                            is WriteReqWithToken -> {
                                Either.right(Unit)
                            }
                        }
                    }
                }
            }
            when (ticketOrAllowedWrite) {
                is Left -> {
                    val ticket = ticketOrAllowedWrite.value
                    try {
                        ticketDataReq = ticket.await()
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
                            when (val tTState = state) {
                                Empty,
                                is WaitCurrentReadDone,
                                is Reading -> {
                                    logger.warn(
                                        "wrong logic happen somewhere, review code, state is {}",
                                        tTState.javaClass.simpleName
                                    )
                                }

                                is Writing -> {
                                    tTState.readQueue.forEach {
                                        it.complete(Unit)
                                    }
                                    logger.debug("writing comeback to empty read queue is {}", tTState.readQueue.size)
                                    state = Empty
                                }

                                is ContinueWrite -> {
                                    val writeQueue = tTState.writeQueue
                                    if (writeQueue.isNotEmpty()) {
                                        val ticket = tTState.writeQueue.removeFirst()
                                        ticket.complete(WriteReqWithToken())
                                    } else {
                                        // báo cho tất cả các read biết để vào read
                                        tTState.readQueue.forEach {
                                            it.complete(Unit)
                                        }
                                        logger.debug("continue write comeback to empty")
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

    // writing maybe in operation
    private fun onCancelTicketWrite(ticket: CompletableDeferred<WriteReqData>) = synchronized(this) {
        logger.debug("cancel ticket write at {}", state.javaClass.simpleName)
        when (val tState = state) {
            Empty -> {}
            is Reading -> {}
            is Writing -> {}
            is WaitCurrentReadDone -> {}
            is ContinueWrite -> {
                // gọi req tiếp theo vào
                val writeQueue = tState.writeQueue
                val removed = writeQueue.remove(ticket)

                // không còn ticket này
                if (removed) {
                    logger.debug("ticket write removed")
                } else {
                    // todo: do somethings
                    logger.debug("this ticket already dispatched,but will not run next action write")
                }
            }
        }
    }

    private fun <T> MutableSet<T>.removeFirst(): T {
        if (this.isEmpty()) throw IllegalArgumentException("empty set")
        val first = this.first()
        this.remove(first)
        return first
    }

    private fun onCancelTicketRead(): Unit = synchronized(this) {
        when (val tState = state) {
            Empty -> {
            }

            is Reading -> {
            }

            is WaitCurrentReadDone -> {

            }

            is Writing -> {

            }

            is ContinueWrite -> {

            }

        }
    }


}

internal sealed class WriteReqData()

internal object EmptyWriteReq : WriteReqData()

internal class WriteReqWithToken(val token: Int = 0) : WriteReqData()


// hiện tại khi đang read thì write sẽ không thể vào
// như vậy có thể dẫn đến sẽ không bao giờ được write nếu các read cứ chồng chéo lên nhau mãi
// nên thêm một state để khi có req write thì sẽ chỉ đợi xong hết các read hiện tại, các read req
// được add vào sau cái write sẽ được thực thi sau khi write

internal sealed class ReadWriteMutexStateData() {

}

internal object Empty : ReadWriteMutexStateData() {

}


internal sealed class HadWriteQueue() : ReadWriteMutexStateData() {
    abstract val writeQueue: MutableSet<CompletableDeferred<WriteReqData>>
}

internal class Reading(
    override val writeQueue: MutableSet<CompletableDeferred<WriteReqData>> = linkedSetOf(),
    var readingCount: Int = 0,
) : HadWriteQueue() {


    fun addWriteReq(): CompletableDeferred<WriteReqData> {
        val res = CompletableDeferred<WriteReqData>()
        writeQueue.add(res)
        return res
    }
}

internal class Writing(
    val readQueue: MutableList<CompletableDeferred<Unit>>,
) : ReadWriteMutexStateData() {

    fun addReadReq(): CompletableDeferred<Unit> {
        val res = CompletableDeferred<Unit>()
        readQueue.add(res)
        return res
    }

}

internal class ContinueWrite(
    override val writeQueue: MutableSet<CompletableDeferred<WriteReqData>>,
    val readQueue: MutableList<CompletableDeferred<Unit>>,
) : HadWriteQueue() {


    fun addReadReq(): CompletableDeferred<Unit> {
        val res = CompletableDeferred<Unit>()
        readQueue.add(res)
        return res
    }

    fun addWriteReq(): CompletableDeferred<WriteReqData> {
        val res = CompletableDeferred<WriteReqData>()
        writeQueue.add(res)
        return res
    }
}

internal class WaitCurrentReadDone(
    var numCurrentRead: Int,
    override val writeQueue: MutableSet<CompletableDeferred<WriteReqData>>,
    val readQueue: MutableList<CompletableDeferred<Unit>>,
) : HadWriteQueue() {
    fun addReadReq(): CompletableDeferred<Unit> {
        val res = CompletableDeferred<Unit>()
        readQueue.add(res)
        return res
    }

    fun addWriteReq(): CompletableDeferred<WriteReqData> {
        val res = CompletableDeferred<WriteReqData>()
        writeQueue.add(res)
        return res
    }
}

// khi ở trạng thái này thì các read mới sẽ không được vào read nữa mà chỉ được add ticket
// khi reading count về 0 thì sẽ chuyển về empty???
// nhưng như thế thì các read lại có thể chiếm quyền read????
