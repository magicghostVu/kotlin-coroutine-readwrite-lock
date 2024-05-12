package com.magicghostvu.coroutinex

internal sealed class Either<out L, out R> {
    companion object {
        fun <T> left(value: T): Left<T> {
            return Left(value)
        }

        fun <T> right(value: T): Right<T> {
            return Right(value)
        }
    }
}

internal class Left<T> internal constructor(val value: T) : Either<T, Nothing>()
internal class Right<T> internal constructor(val value: T) : Either<Nothing, T>()