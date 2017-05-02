package datastructures

import java.io.File

import hashfunctions.Hyperplane
import io.Parser.DisaParserNumeric
import multiprobing.{PQ, TwoStep}
import org.scalatest.{FlatSpec, Matchers}

import scala.io.Source
import scala.util.Random

class ProbeTableSpec extends FlatSpec with Matchers {

  val arr = new Array[Float](128)
  val rnd = new Random(System.currentTimeMillis())


  "Query " should "return results given a valid query" in {
    val k = 6
    val rnd = new Random(System.currentTimeMillis())
    val hp = Hyperplane(k, rnd.nextLong(), 128)
    val pt = new Table(hp)

    var i = 0

    val vec = ((1,Array.fill[Float](128)(rnd.nextFloat)),1)
    pt+=vec
    while(i < 100) {
      pt+=((rnd.nextInt,Array.fill[Float](128)(rnd.nextFloat)),1)
      i+=1
    }

    assert(pt.query(hp(vec._1._2)).contains(vec._2)) // same object

  }
  "Query " should "not throw exception given invalid query" in {
    val k = 6
    val rnd = new Random(System.currentTimeMillis())
    val hp = Hyperplane(k, rnd.nextLong(), 128)
    val vec = ((1,Array.fill[Float](128)(rnd.nextFloat)),1)
    val vec2 = ((3,Array.fill[Float](128)(rnd.nextFloat)),2)
    val pt = new Table(hp)
    pt+=vec
    assert(!pt.query(hp(vec2._1._2)).contains(vec2._2)) // same object
  }

}
