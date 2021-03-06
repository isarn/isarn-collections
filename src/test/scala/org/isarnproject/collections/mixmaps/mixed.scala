/*
Copyright 2016 Erik Erlandson

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.isarnproject.collections.mixmaps

import org.scalatest._

import com.twitter.algebird.{ Monoid, Aggregator, MonoidAggregator }
import org.isarnproject.algebraAPI.{ MonoidAPI, AggregatorAPI }

import org.isarnproject.scalatest.matchers.seq._
import org.isarnproject.algebirdAlgebraAPI.implicits._

object mixed {
  import math.Numeric
  import scala.collection.SortedMap

  import org.isarnproject.collections.mixmaps.increment._
  import org.isarnproject.collections.mixmaps.prefixsum._
  import org.isarnproject.collections.mixmaps.nearest._

  object tree {
    import org.isarnproject.collections.mixmaps.increment.tree._
    import org.isarnproject.collections.mixmaps.prefixsum.tree._
    import org.isarnproject.collections.mixmaps.nearest.tree._

    trait NodeMix[K, V, P] extends NodePS[K, V, P] with NodeInc[K, V] with NodeNearMap[K, V]

    trait LNodeMix[K, V, P] extends NodeMix[K, V, P]
      with LNodePS[K, V, P] with LNodeInc[K, V] with LNodeNearMap[K, V]

    trait INodeMix[K, V, P] extends NodeMix[K, V, P]
      with INodePS[K, V, P] with INodeInc[K, V] with INodeNearMap[K, V] {
      def lsub: NodeMix[K, V, P]
      def rsub: NodeMix[K, V, P]
    }
  }

  import tree._

  object infra {
    import org.isarnproject.collections.mixmaps.redblack.tree._
    import org.isarnproject.collections.mixmaps.ordered.tree.DataMap

    class Inject[K, V, P](
      val keyOrdering: Numeric[K],
      val valueMonoid: MonoidAPI[V],
      val prefixAggregator: AggregatorAPI[P, V]) extends Serializable {

      def iNode(clr: Color, dat: Data[K], ls: Node[K], rs: Node[K]) =
        new Inject[K, V, P](keyOrdering, valueMonoid, prefixAggregator) with INodeMix[K, V, P] with MixedMap[K, V, P] {

          // INode[K]
          val color = clr
          val lsub = ls.asInstanceOf[NodeMix[K, V, P]]
          val rsub = rs.asInstanceOf[NodeMix[K, V, P]]
          val data = dat.asInstanceOf[DataMap[K, V]]
          // INodePS[K, V, P]
          val prefix = prefixAggregator.lff(
            prefixAggregator.monoid.combine(lsub.pfs, rsub.pfs), data.value)
          // INodeNear[K, V]
          val kmin = lsub match {
            case n: INodeMix[K, V, P] => n.kmin
            case _ => data.key
          }
          val kmax = rsub match {
            case n: INodeMix[K, V, P] => n.kmax
            case _ => data.key
          }
        }
    }

  }

  import infra._

  sealed trait MixedMap[K, V, P] extends SortedMap[K, V]
    with IncrementMapLike[K, V, INodeMix[K, V, P], MixedMap[K, V, P]]
    with PrefixSumMapLike[K, V, P, INodeMix[K, V, P], MixedMap[K, V, P]]
    with NearestMapLike[K, V, INodeMix[K, V, P], MixedMap[K, V, P]] {

    override def empty = MixedMap.key(keyOrdering).value(valueMonoid).prefix(prefixAggregator)

    override def toString =
      "MixedMap(" +
        iterator.zip(prefixSumsIterator())
        .map(x => s"${x._1._1} -> (${x._1._2}, ${x._2})").mkString(", ") +
        ")"
  }

  object MixedMap {
    def key[K](implicit num: Numeric[K]) = infra.GetValue(num)

    object infra {
      case class GetValue[K](num: Numeric[K]) {
        def value[V](implicit vm: MonoidAPI[V]) = GetPrefix(num, vm)
      }

      case class GetPrefix[K, V](num: Numeric[K], vm: MonoidAPI[V]) {
        def prefix[P](implicit agg: AggregatorAPI[P, V]): MixedMap[K, V, P] =
          new Inject[K, V, P](num, vm, agg) with LNodeMix[K, V, P] with MixedMap[K, V, P]
      }
    }
  }
}

class MixedMapSpec extends FlatSpec with Matchers {

  import org.isarnproject.collections.mixmaps.ordered.RBProperties._
  import org.isarnproject.collections.mixmaps.ordered.OrderedMapProperties._
  import org.isarnproject.collections.mixmaps.prefixsum.PrefixSumMapProperties._
  import org.isarnproject.collections.mixmaps.increment.IncrementMapProperties._
  import org.isarnproject.collections.mixmaps.nearest.NearestMapProperties._

  import mixed.MixedMap

  def mapType1 =
    MixedMap.key[Double].value[Int]
      .prefix(Aggregator.appendMonoid((ps: Int, v: Int) => ps + v))

  it should "pass randomized tree patterns" in {
    val data = Vector.tabulate(50)(j => (j.toDouble, j))
    (1 to 1000).foreach { u =>
      val shuffled = scala.util.Random.shuffle(data)
      val map = shuffled.foldLeft(mapType1)((m, e) => m + e)

      testRB(map)
      testKV(data, map)
      testDel(data, map)
      testEq(data, mapType1)
      testPrefix(data, map)
      testIncrement(data, map)
      testNearest(data, map)
    }
  }

  it should "serialize and deserialize" in {
    import org.isarnproject.scalatest.serde.roundTripSerDe

    val data = Vector.tabulate(50)(j => (j.toDouble, j))
    val omap = data.foldLeft(mapType1)((m, e) => m + e)
    val imap = roundTripSerDe(omap)

    (imap == omap) should be (true)
    testRB(imap)
    testKV(data, imap)
    testDel(data, imap)
    testPrefix(data, imap)
    testIncrement(data, imap)
    testNearest(data, imap)
  }
}
