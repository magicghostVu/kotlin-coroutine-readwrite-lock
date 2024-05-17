package com.magicghostvu.coroutinex.runr

import com.magicghostvu.coroutinex.ReadWriteMutex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory


suspend fun main() {
    val logger: Logger = LoggerFactory.getLogger("common")
    logger.debug("log ok")

    //testWritePriority()
    testDeadLockCancelTicketWrite()

    logger.debug("done main")
}

suspend fun test1() {
    val logger = LoggerFactory.getLogger("common")
    delay(1000)
    coroutineScope {
        val readWriteMutex = ReadWriteMutex()
        var t = 0;
        launch {
            logger.debug("c1 try to write")
            readWriteMutex.write {
                logger.debug("c1 write success ,t plus {}", ++t)
                delay(1500)
                logger.debug("done c1")
            }
        }

        launch {
            delay(100)
            logger.debug("c2 try to read")
            readWriteMutex.read {
                logger.debug("c2 read success t is {}", t)
            }
        }

        val j3 = launch {
            delay(500)
            logger.debug("c3 try to write")
            readWriteMutex.write {
                logger.debug("maybe never happen")
            }
        }

        val j4 = launch {
            delay(200)
            logger.debug("c4 is trying to write")
            readWriteMutex.write {
                logger.debug("c4 will never happen")
            }
        }

        launch {
            logger.debug("start c5")
            delay(1000)
            j3.cancel()
            logger.debug("j3 canceled")
            delay(300)
            /*j4.cancel()
            logger.debug("j4 canceled")*/
        }

    }
}

// test case này phải được thiết kế sao cho cancel ticket write sau khi
// wait read done -> empty delay read để cho không cái write nào được dispatch -> dead-lock
suspend fun testDeadLockCancelTicketWrite() {
    val logger = LoggerFactory.getLogger("common")
    val mutex = ReadWriteMutex()
    coroutineScope {
        launch {
            logger.debug("start c1")
            mutex.read {
                logger.debug("c2 read")
                delay(2000)
            }
            logger.debug("done c1")
        }

        val j2 = launch {
            delay(1)
            logger.debug("start c2")
            mutex.write {
                logger.debug("c2 write")
            }
        }
        val j3 = launch {
            delay(2)
            logger.debug("start c3")
            mutex.write {
                logger.debug("c3 write")
            }
        }

        val j4 = launch {
            delay(3)
            logger.debug("start c4")
            mutex.write {
                logger.debug("c4 write")
            }
        }

        launch {
            delay(1000)// after read done
            logger.debug("start c5")
            mutex.read {
                logger.debug("c5 read")
            }
        }

        launch {
            logger.debug("start c6 to control other jobs")
            delay(2010)
            logger.debug("start cancel write request")
            j2.cancel()
            j3.cancel()
            j4.cancel()
            logger.debug("cancel all write req")
        }
    }
}

suspend fun testWritePriority() {
    val logger = LoggerFactory.getLogger("common")
    coroutineScope {
        val mutex = ReadWriteMutex()
        launch {
            mutex.read {
                logger.debug("c1 read")
                delay(1500)
                logger.debug("c1 read done")
            }
        }

        launch {
            delay(100)
            logger.debug("c2 trying to write")
            mutex.write {
                logger.debug("c2 write success")
                delay(1000)
            }
        }

        launch {
            delay(150)
            logger.debug("c3 try to read")
            mutex.read {
                logger.debug("c3 read success")
            }
        }
    }
}