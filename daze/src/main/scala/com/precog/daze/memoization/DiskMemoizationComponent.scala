/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.daze
package memoization

import com.precog.yggdrasil._
import com.precog.yggdrasil.serialization._
import com.precog.util._

import akka.dispatch.Future
import akka.dispatch.Promise
import akka.dispatch.ExecutionContext
import java.io._
import java.util.zip._

import scala.annotation.tailrec
import scalaz._
import scalaz.effect._
import scalaz.iteratee._
import scalaz.syntax.monad._
import scalaz.syntax.semigroup._
import scalaz.syntax.std._
import Iteratee._

trait DiskMemoizationConfig {
  def memoizationBufferSize: Int
  def memoizationWorkDir: File
}

trait DiskIterableDatasetMemoizationComponent extends YggConfigComponent with MemoizationEnvironment { self =>
  type YggConfig <: DiskMemoizationConfig
  type Dataset[α] = IterableDataset[α]
  type MemoId = Int

  implicit val asyncContext: ExecutionContext

  def withMemoizationContext[A](f: MemoContext => A) = f(new MemoContext { })

  sealed trait MemoContext extends MemoizationContext[IterableDataset] { ctx => 
    type CacheKey[α] = MemoId
    class CacheValue[A](val value: Promise[(Int, Either[Vector[A], File])]) {
      @volatile private var references = 1
      def reserve = synchronized {
        if (references > 0) references += 1
        else sys.error("Attempt to reserve usage for an expired memo")
      }

      def release = synchronized {
        references -= 1
        if (references == 0) for ((_, Right(file)) <- value) file.delete
      }

      def expire = synchronized {
        references -= 1
      }
    }

    @volatile private var memoCache: KMap[CacheKey, CacheValue] = KMap.empty[CacheKey, CacheValue]
    @volatile private var files: List[File] = Nil

    private def datasetFor[A](cv: CacheValue[(Identities, A)])(implicit serialization: IncrementalSerialization[(Identities, A)]): Future[IterableDataset[A]] = {
      type IA = (Identities, A)
      def wrap(inner: Iterator[IA]): Iterator[IA] = new Iterator[IA] { self =>
        @volatile private var cacheValue = cv
        def hasNext = {
          if (!inner.hasNext) {
            self.synchronized { 
              if (cacheValue != null) {
                cacheValue.release
                // ensure that we don't release the same reference twice
                cacheValue = null.asInstanceOf[CacheValue[IA]]
              }
            }
          }

          inner.hasNext
        }

        def next = inner.next
      }

      // first, update the reference count
      cv.reserve

      // return a dataset that wraps an iterator which will decrement the reference count
      // on exhaustion
      cv.value map {
        case (idCount, Left(vector)) =>
          IterableDataset(idCount, new Iterable[IA] { 
            def iterator = wrap(vector.iterator)
          })

        case (idCount, Right(file)) => 
          IterableDataset(idCount, new Iterable[IA] { 
            def iterator = wrap(serialization.reader.read(serialization.iStream(file), true))
          })
      }
    }

    def memoizing[A](memoId: MemoId)(implicit serialization: IncrementalSerialization[(Identities, A)]): Either[IterableDataset[A] => Future[IterableDataset[A]], Future[IterableDataset[A]]] = {
      type IA = (Identities, A)
      def store(iter: Iterator[IA]): Either[Vector[IA], File] = {
        @tailrec def buffer(i: Int, acc: Vector[IA]): Vector[IA] = {
          if (i < yggConfig.memoizationBufferSize && iter.hasNext) buffer(i+1, acc :+ iter.next)
          else acc
        }

        @tailrec def spill(writer: serialization.IncrementalWriter, out: DataOutputStream, iter: Iterator[IA]): serialization.IncrementalWriter = {
          if (iter.hasNext) spill(writer.write(out, iter.next), out, iter)
          else writer
        }

        val initial = buffer(0, Vector()) 
        if (initial.length < yggConfig.memoizationBufferSize) {
          Left(initial)
        } else {
          val memoFile = new File(yggConfig.memoizationWorkDir, "memo." + memoId)
          using(serialization.oStream(memoFile)) { out =>
            spill(spill(serialization.writer, out, initial.iterator), out, iter).finish(out)
          }

          ctx.synchronized { files = memoFile :: files }

          Right(memoFile)
        }
      }


      ctx.synchronized {
        memoCache.get[IA](memoId) match {
          case Some(cacheValue) => 
            Right(datasetFor(cacheValue))

          case None => 
            val promise = Promise[(Int, Either[Vector[IA], File])]
            val cacheValue: CacheValue[IA] = new CacheValue[IA](promise)
            memoCache += (memoId -> cacheValue)

            Left((toMemoize: IterableDataset[A]) => {
              promise.completeWith(Future((toMemoize.idCount, store(toMemoize.iterable.iterator))))
              datasetFor(cacheValue)
            })
        }
      }
    }

    object cache extends MemoCache {
      def expire(memoId: Int) = {
        ctx.synchronized { 
          memoCache.get[Any](memoId) foreach { cacheValue =>
            memoCache -= memoId
            cacheValue.expire // this final release will decrement the reference counter so that it will be freed by the last iterator
          }
        }
      }

      def purge = {
        ctx.synchronized {
          for (f <- files) f.delete
          files = Nil
          memoCache = KMap.empty[CacheKey, CacheValue]
        }
      }
    }
  }
}

/*
trait DiskIterateeMemoizationComponent extends YggConfigComponent with MemoizationEnvironment { self =>
  type YggConfig <: DiskMemoizationConfig

  def withMemoizationContext[A](f: MemoContext => A) = f(new MemoContext { })

  sealed trait MemoContext extends IterateeMemoizationContext with BufferingContext { ctx => 
    type CacheKey[α] = Int
    type CacheValue[α] = Either[Vector[α], File]
    @volatile private var memoCache: KMap[CacheKey, CacheValue] = KMap.empty[CacheKey, CacheValue]
    @volatile private var files: List[File] = Nil

    private def memoizer[X, E, F[_]](memoId: Int)(implicit fs: IterateeFileSerialization[E], MO: F |>=| IO): IterateeT[X, E, F, Either[Vector[E], File]] = {
      import MO._

      def consume(i: Int, acc: Vector[E]): IterateeT[X, E, F, Either[Vector[E], File]] = {
        if (i < yggConfig.memoizationBufferSize) 
          cont(
            (_: Input[E]).fold(
              el    = el => consume(i + 1, acc :+ el),
              empty = consume(i, acc),
              eof   = done(Left(acc), eofInput)))
        else 
          (fs.writer(new File(yggConfig.memoizationWorkDir, "memo" + memoId)) &= enumStream[X, E, F](acc.toStream)) map (f => Right(f))
      }

      consume(0, Vector.empty[E])
    }

    def buffering[X, E, F[_]](memoId: Int)(implicit fs: IterateeFileSerialization[E], MO: F |>=| IO): IterateeT[X, E, F, EnumeratorP[X, E, IO]] = ctx.synchronized {
      import MO._
      memoCache.get(memoId) match {
        case Some(Left(vector)) => 
          done[X, E, F, EnumeratorP[X, E, IO]](EnumeratorP.enumPStream[X, E, IO](vector.toStream), eofInput)
          
        case Some(Right(file)) => 
          done[X, E, F, EnumeratorP[X, E, IO]](fs.reader[X](file), eofInput)

        case None =>
          import MO._
          memoizer[X, E, F](memoId) map { memo =>
            EnumeratorP.perform[X, E, IO, Unit](IO(ctx.synchronized { if (!memoCache.isDefinedAt(memoId)) memoCache += (memoId -> memo) })) |+|
            memo.fold(
              vector => EnumeratorP.enumPStream[X, E, IO](vector.toStream),
              file   => fs.reader[X](file)
            )
          }
      }
    }

    def memoizing[X, E](memoId: Int)(implicit fs: IterateeFileSerialization[E], asyncContext: ExecutionContext): Either[Memoizer[X, E], EnumeratorP[X, E, IO]] = ctx.synchronized {
      memoCache.get(memoId) match {
        case Some(Left(vector)) => 
          Right(EnumeratorP.enumPStream[X, E, IO](vector.toStream))
          
        case Some(Right(file)) => 
          Right(fs.reader[X](file))

        case None => Left(
          new Memoizer[X, E] {
            def apply[F[_], A](iter: IterateeT[X, E, F, A])(implicit MO: F |>=| IO) = {
              import MO._
              (iter zip memoizer[X, E, F](memoId)) map {
                case (result, memo) => 
                  ctx.synchronized { if (!memoCache.isDefinedAt(memoId)) {
                    for (f <- memo.right) files = f :: files
                    memoCache += (memoId -> memo) 
                  }}
                  result
              }
            }
          }
        )
      }
    }

    object cache extends MemoCache {
      def expire(memoId: Int) = IO {
        ctx.synchronized { memoCache -= memoId }
      }

      def purge = IO {
        ctx.synchronized {
          for (f <- files) f.delete
          files = Nil
          memoCache = KMap.empty[CacheKey, CacheValue]
        }
      }
    }
  }
}

*/
