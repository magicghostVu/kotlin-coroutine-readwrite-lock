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

        launch {
            logger.debug("start c4")
            delay(1000)
            j3.cancel()
            logger.debug("j3 canceled")
        }

    }

    logger.debug("done main")
}