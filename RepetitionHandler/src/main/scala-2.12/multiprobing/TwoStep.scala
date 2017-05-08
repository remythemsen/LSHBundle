package multiprobing

import hashfunctions.HashFunction

/**
  * Created by remeeh on 07-04-2017.
  */
class TwoStep[A](k:Int, hfs:Array[HashFunction[A]]) extends ProbeScheme[A] {
  // Set of resulting probes for all keys
  var probes:Array[(Int, Long)] = new Array(hfs.length*(1+(k*(k+1)/2)))
  var probesSpent:Int = _
  var hashFunctions:Array[HashFunction[A]] = hfs
  var keys:Array[(Int, Long)] = new Array(hfs.length)

  override def generate(qp:A): Unit = {

    // Get keys of qp
    var i = 0
    while(i < this.hfs.length) {
      this.keys(i) = (i,this.hashFunctions(i)(qp))
      i += 1
    }

    var kIndex, c = 0
    while (kIndex < hfs.length) {
      var i, j = 0
      var oneStepProbe: Long = 0

      // Adding the key itself
      val key = (kIndex, keys(i)._2)
      probes(c) = key
      c += 1

      while (i < k) {
        probes(c) = (kIndex, checkAndFlip(key._2, i))
        oneStepProbe = probes(c)._2

        c = c + 1
        j = i + 1
        while (j < k) {
          probes(c) = (kIndex, checkAndFlip(oneStepProbe, j))
          c = c + 1
          j = j + 1
        }
        i += 1
      }

      kIndex += 1
    }

    this.probesSpent = 0

  }

  def checkAndFlip(key:Long, i:Int): Long = {
    var res:Long = key
    if((res & (1 << i)) != 0) {
      res -= (1 << i)
    } else {
      res += (1 << i)
    }
    res
  }

  override def hasNext():Boolean = probesSpent < probes.length

  override def next(): (Int, Long) = {
    val r = probes(probesSpent)
    this.probesSpent += 1
    r
  }
}
