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
            readWriteMutex.write {
                logger.debug("t plus c1 is {}", ++t)
                delay(1500)
                logger.debug("done c1")
            }
        }

        val j = launch {
            delay(100)
            logger.debug("req access write")
            readWriteMutex.write {
                logger.debug("never happen")
            }
        }

        val j2 = launch {
            readWriteMutex.read {
                logger.debug("enter read success")
            }
        }

        launch {
            delay(1000)
            j.cancel()
            logger.debug("j canceled")
            delay(500)
            j2.cancel()
            logger.debug("j2 canceled")
        }
    }

    logger.debug("done main")
}