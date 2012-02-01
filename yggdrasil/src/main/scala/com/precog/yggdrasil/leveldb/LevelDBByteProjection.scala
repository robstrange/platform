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
package com.precog.yggdrasil
package leveldb

import com.precog.util.Bijection._
import java.nio.ByteBuffer
import scalaz.Order
import scalaz.Ordering
import scalaz.syntax.biFunctor._
import scalaz.std.AllInstances._

import scala.collection.immutable.ListSet
import scala.annotation.tailrec

object LevelDBByteProjection {
  val trueByte: Byte = 0x01
  val falseByte: Byte = 0x00
  val nullByte: Byte = 0xff.toByte
}

trait LevelDBByteProjection extends ByteProjection {
  import LevelDBByteProjection._

  private class LevelDBWriteBuffer(length: Int) {
    private final val buf = ByteBuffer.allocate(length)
    def writeIdentity(id: Identity): Unit = buf.putLong(id)

    def writeValue(colDesc: ColumnDescriptor, v: CValue) = v.fold[Unit](
      str     = s => {
        val sbytes = s.getBytes("UTF-8")
        colDesc.valueType.format match {
          case FixedWidth(w) => buf.put(sbytes, 0, w)
          case LengthEncoded => buf.putInt(sbytes.length).put(sbytes)
        }
      },
      bool    = b => buf.put(if (b) trueByte else falseByte), 
      int     = i => buf.putInt(i), 
      long    = l => buf.putLong(l),
      float   = f => buf.putFloat(f), 
      double  = d => buf.putDouble(d), 
      num     = d => {
        val dbytes = d.as[Array[Byte]]
        buf.putInt(dbytes.length).put(dbytes)
      },
      emptyObj = (), emptyArr = (), 
      nul = colDesc.valueType match {
        case SStringFixed(width)    => buf.put(Array.fill[Byte](width)(0x00))
        case SStringArbitrary       => buf.putInt(0)
        case SBoolean               => buf.put(nullByte)
        case SInt                   => buf.putInt(Int.MaxValue)
        case SLong                  => buf.putLong(Long.MaxValue)
        case SFloat                 => buf.putFloat(Float.MaxValue)
        case SDouble                => buf.putDouble(Double.MaxValue)
        case SDecimalArbitrary      => buf.putInt(0)
        case _                      => ()
      }
    )
    
    def toArray: Array[Byte] = buf.array
  }

  private val incompatible = (_: Any) => sys.error("Column values incompatible with projection descriptor.")

  private def listWidths(cvalues: Seq[CValue]): List[Int] = 
    descriptor.columns.map(_.valueType.format) zip cvalues map {
      case (FixedWidth(w), sv) => w
      case (LengthEncoded, sv) => 
        sv.fold[Int](
          str     = s => s.getBytes("UTF-8").length + 4,
          bool    = incompatible, int     = incompatible, long    = incompatible,
          float   = incompatible, double  = incompatible, 
          num     = _.as[Array[Byte]].length + 4,
          emptyObj = incompatible(()), emptyArr = incompatible(()), nul = incompatible(())
        )
    }

  private def allocateWidth(valueWidths: Seq[Int]): (Int) = 
    descriptor.sorting.foldLeft(0) { 
      case (width, (col, ById)) =>
        if (descriptor.indexedColumns.map(_._2).toList.indexOf(descriptor.indexedColumns(col)) == descriptor.columns.indexOf(col)) (width + 8)
        else width

      case (width, (col, ByValue)) => 
        val valueIndex = descriptor.columns.indexOf(col)
        (width + valueWidths(valueIndex))

      case (width, (col, ByValueThenId)) => 
        val valueIndex = descriptor.columns.indexOf(col)
        if (descriptor.indexedColumns.map(_._2).toList.indexOf(descriptor.indexedColumns(col)) == descriptor.columns.indexOf(col)) (width + valueWidths(valueIndex) + 8)
        else (width + valueWidths(valueIndex))
    }
  


  def project(identities: Identities, cvalues: Seq[CValue]): (Array[Byte], Array[Byte]) = {

    lazy val valueWidths = listWidths(cvalues)
    val indexWidth = allocateWidth(valueWidths) 

    val (usedIdentities, usedValues): (Set[Int], Set[Int]) = descriptor.sorting.foldLeft((Set.empty[Int], Set.empty[Int])) { 
      case ((ids, values), (col, ById)) => 
        (ids + descriptor.indexedColumns(col), values)

      case ((ids, values), (col, ByValue)) => 
        val valueIndex = descriptor.columns.indexOf(col)
        (ids, values + valueIndex)

      case ((ids, values), (col, ByValueThenId)) => 
        val valueIndex = descriptor.columns.indexOf(col)
        (ids + descriptor.indexedColumns(col), values + valueIndex)
    }

    // all of the identities must be included in the key; also, any values of columns that
    // use by-value ordering must be included in the key.
    val keyBuffer = new LevelDBWriteBuffer(indexWidth + ((identities.size - usedIdentities.size) * 8))
    descriptor.sorting.foreach {
      case (col, ById)          =>
        //checks that the identity has not been written already
        if (descriptor.indexedColumns.map(_._2).toList.indexOf(descriptor.indexedColumns(col)) == descriptor.columns.indexOf(col))
          keyBuffer.writeIdentity(identities(descriptor.indexedColumns(col)))
      case (col, ByValue)       => keyBuffer.writeValue(col, cvalues(descriptor.columns.indexOf(col)))        
      case (col, ByValueThenId) =>
        keyBuffer.writeValue(col, cvalues(descriptor.columns.indexOf(col)))
        if (descriptor.indexedColumns.map(_._2).toList.indexOf(descriptor.indexedColumns(col)) == descriptor.columns.indexOf(col))
          keyBuffer.writeIdentity(identities(descriptor.indexedColumns(col)))
    }

    identities.zipWithIndex.foreach {
      case (id, i) => if (!usedIdentities.contains(i)) keyBuffer.writeIdentity(id)
      case _ =>
    }

    val valuesBuffer = new LevelDBWriteBuffer(valueWidths.zipWithIndex collect { case (w, i) if !usedValues.contains(i) => w } sum)
    (cvalues zip descriptor.columns).zipWithIndex.foreach { //by zip we lose the extra cvalues not in bijection with descriptor - is this correct?
      case ((v, col), i) if !usedValues.contains(i) => valuesBuffer.writeValue(col, v)
      case _ => 
    }

    (keyBuffer.toArray, valuesBuffer.toArray)
  }

  /** Mutable wrapper around a byte array to allow direct extraction of CValues */
  private class LevelDBReadBuffer(arr: Array[Byte]) {
    private final val buf = ByteBuffer.wrap(arr)

    def readIdentity(): Long = buf.getLong

    def readValue(valueType: ColumnType): CValue = {
      valueType match {
        case SStringFixed(width)    => 
            val sstringbuffer: Array[Byte] = new Array(width)
            buf.get(sstringbuffer)
            CString(new String(sstringbuffer, "UTF-8"))
        
        case SStringArbitrary       => 
          val length: Int = buf.getInt
          val sstringarb: Array[Byte] = new Array(length)
          buf.get(sstringarb)
          CString(new String(sstringarb, "UTF-8"))

        case SBoolean               => 
          val b: Byte = buf.get
          if (b == trueByte)       CBoolean(true)
          else if (b == falseByte) CBoolean(false)
          else                     sys.error("Boolean byte value was not true or false")

        case SInt                   => CInt(buf.getInt)
        case SLong                  => CLong(buf.getLong)
        case SFloat                 => CFloat(buf.getFloat)
        case SDouble                => CDouble(buf.getDouble)
        case SDecimalArbitrary      => 
          val length: Int = buf.getInt
          val sdecimalarb: Array[Byte] = new Array(length)
          buf.get(sdecimalarb)
          CNum(sdecimalarb.as[BigDecimal])
      }
    }
  }

  private type RBuf = LevelDBReadBuffer
  private type IdentityRead = LevelDBReadBuffer => Long
  private type ValueRead    = LevelDBReadBuffer => CValue
  private lazy val keyParsers: Seq[Either[IdentityRead, ValueRead]] = { 
    val (initial, unused) = descriptor.sorting.foldLeft((Vector.empty[Either[IdentityRead, ValueRead]], ListSet(0.until(descriptor.identities): _*))) {
      case ((acc, ids), (col, ById))          => (acc :+ Left((_:RBuf).readIdentity()),                                           ids - descriptor.indexedColumns(col))
      case ((acc, ids), (col, ByValue))       => (acc :+ Right((_:RBuf).readValue(col.valueType)),                                ids)
      case ((acc, ids), (col, ByValueThenId)) => (acc :+ Right((_:RBuf).readValue(col.valueType)) :+ Left((_:RBuf).readIdentity), ids - descriptor.indexedColumns(col))
    }

    unused.foldLeft(initial) {
      case (acc, _) => acc :+ Left((_:RBuf).readIdentity())
    }
  }

  private lazy val valueParsers: List[ValueRead] = {
    descriptor.columns filter { col => 
      !(descriptor.sorting exists { 
        case (`col`, sortBy)  => sortBy == ByValue || sortBy == ByValueThenId
        case _                => false
      })
    } map { col => 
      (_: RBuf).readValue(col.valueType)
    }
  }

  private lazy val mergeDirectives: List[Boolean] = {
    descriptor.columns.map(c => descriptor.sorting exists { 
      case (`c`, sortBy)  => sortBy == ByValue || sortBy == ByValueThenId 
      case _              => false
    }) 
  }


  private def mergeValues(keyMembers: List[CValue], valueMembers: List[CValue]): List[CValue] = {  
    @tailrec def merge(mergeDirectives: List[Boolean], keyMembers: List[CValue], valueMembers: List[CValue], result: List[CValue]): List[CValue] = {
      //println(keyMembers)
      //println(valueMembers)
      //println(mergeDirectives)
      //println(result)
      mergeDirectives match {
        case true  :: ms => merge(ms, keyMembers.tail, valueMembers, keyMembers.head :: result)
        case false :: ms => merge(ms, keyMembers, valueMembers.tail, valueMembers.head :: result)
        case Nil => result
      }
    }

    merge(mergeDirectives.reverse, keyMembers, valueMembers, Nil)
  }


  def unproject[E](keyBytes: Array[Byte], valueBytes: Array[Byte])(f: (Identities, Seq[CValue]) => E): E = {
    val (_, (identities, valuesInKey)) = keyParsers.foldLeft((new LevelDBReadBuffer(keyBytes), (List.empty[Long], List.empty[CValue]))) {
      case ((buf, (ids, values)), Left(f)) => (buf, (f(buf) :: ids, values))
      case ((buf, (ids, values)), Right(f)) => (buf, (ids, f(buf) :: values))
    }

    val (_, valueMembers) = valueParsers.foldLeft((new LevelDBReadBuffer(valueBytes), List.empty[CValue])) {
      case ((buf, acc), f) => (buf, f(buf) :: acc)
    }

    val values = mergeValues(valuesInKey, valueMembers)
   
    
    f(identities.reverse, values)
  }



  def keyOrder: Order[Array[Byte]] = new Order[Array[Byte]] {
    def order(k1: Array[Byte], k2: Array[Byte]) = {
      val (_, elements1) = keyParsers.foldRight((new LevelDBReadBuffer(k1), List.empty[Either[Long, CValue]])) {
        case (f, (buf, acc)) => (buf, f.bimap(_(buf), _(buf)) :: acc)
      }

      val (_, elements2) = keyParsers.foldRight((new LevelDBReadBuffer(k2), List.empty[Either[Long, CValue]])) {
        case (f, (buf, acc)) => (buf, f.bimap(_(buf), _(buf)) :: acc)
      }

      (elements1 zip elements2).foldLeft[Ordering](Ordering.EQ) {
        case (Ordering.EQ, (Left(e1),  Left(e2)))  => Order[Long].order(e1, e2)
        case (Ordering.EQ, (Right(e1), Right(e2))) => Order[CValue].order(e1, e2)
        case (other, _) => other
      }
    }
  }

}

// vim: set ts=4 sw=4 et: