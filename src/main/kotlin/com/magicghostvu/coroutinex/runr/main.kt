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

    testWritePriority()

    logger.debug("done main")
}

suspend fun test1(){
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
                logger.debug("c2 read success t is {}",t)
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
suspend fun testWritePriority(){
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