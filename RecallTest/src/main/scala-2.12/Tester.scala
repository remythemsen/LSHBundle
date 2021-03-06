import java.io.File

import actors._
import akka.actor.{ActorRef, ActorSystem, AddressFromURIString, Deploy, Props}
import akka.remote.RemoteScope
import io.Parser.{DisaParserBinary, DisaParserNumeric}
import lsh._
import measures._
import tools.CandSet
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Random
import com.googlecode.javaewah.datastructure.BitSet

trait Tester[Descriptor, Query, FileSet] {
  type Result = ((Double, Double, Double, Double), (Double,Double,Double, Double), Double, Double)
  var lsh:LSHStructure[Descriptor, Query, FileSet] = _
  var queries:Array[(Int, Query)] = _
  var knnStructure:mutable.HashMap[Int, Array[(Int, Double)]] = _
  var rnd:Random = _
  var testCase:TestCase = _
  var annSet:CandSet = _

  def run(testCase:TestCase, warmUpIterations:Int, invocationCount:Int) : Result

  def warmUp(warmUpiterations:Int) : Unit = {
    println("Running warmup...")
    var i = 0
    while(i < warmUpiterations) {
      val index = rnd.nextInt(this.queries.length)
      val qRes = this.lsh.query(this.queries(index)._2, this.testCase.knn)
      val p = qRes.ids
      i+=1
    }
  }

  def runQueries(invocationCount:Int):((Double,Double,Double,Double),(Double,Double,Double,Double), Double, Double) = {
    println("Running queries...")
    // Test result containers
    val queryTimes:ArrayBuffer[Double] = new ArrayBuffer()
    val queryRecalls = Tuple4(new ArrayBuffer[Double](), new ArrayBuffer[Double](), new ArrayBuffer[Double](), new ArrayBuffer[Double]())

    // Run queries
    var j = 0
    while(j < this.queries.length) {

      val index = rnd.nextInt(this.queries.length)

      val qp:(Int, Query) = this.queries(index)
      //println("Query id: " + qp._1 + "Query vc: " + qp._2)


      // Recall Test
      val optSet = knnStructure(qp._1).take(this.testCase.knn)

      // Time Test, every query is made 5 times
      var invocationTimes:Array[Double] = new Array(invocationCount)
      var l = 0

      while(l < invocationCount) {
        invocationTimes(l) = timer {
          annSet = lsh.query(qp._2,this.testCase.knn)
        }
        l+=1
      }

      // Adding avg time of that query to set of times
      queryTimes += invocationTimes.sum / invocationCount

      // Sqrt here since it's not in euclidean measure
      if(this.testCase.measure.toLowerCase == "euclidean" || this.testCase.measure.toLowerCase == "hamming") {
        var j = 0
        while(j < annSet.size) {
          this.annSet.dists.set(j, Math.sqrt(annSet.dists.getDouble(j)))
          j+=1
        }

      }

      // Adding the current q's recall to the set of recalls
      // eps = 0 means no approx
      queryRecalls._1 += recallFarthestPointApprox(optSet, annSet, this.testCase.knn, 0.0)
      queryRecalls._2 += recallFarthestPointApprox(optSet, annSet, this.testCase.knn, 0.001)
      queryRecalls._3 += recallFarthestPointApprox(optSet, annSet, this.testCase.knn, 0.01)
      queryRecalls._4 += recallFarthestPointApprox(optSet, annSet, this.testCase.knn, 0.05)

      j += 1
    }

    // Accumulate
    ((average(queryRecalls._1), average(queryRecalls._2), average(queryRecalls._3), average(queryRecalls._4)),
     (stdDeviation(queryRecalls._1),stdDeviation(queryRecalls._2),stdDeviation(queryRecalls._3), stdDeviation(queryRecalls._4)),
      average(queryTimes), stdDeviation(queryTimes))
  }

  def recallFarthestPointApprox(optSet:Array[(Int, Double)], annSet:CandSet, k:Int, eps:Double) : Double = {
    // We assume here that the optSet is sorted, and have equalto or more points than specified k
    // We also assume that annSet.size <= k
    require(optSet.length >= k)
    require(annSet.size <= k)

    val kthFarthestDistOpt = optSet(k-1)._2

    var r = 0.0
    var c = 0


    while(c < annSet.size) {
      //println(" ids " + annSet.ids.getInt(c))
      //println("chck: " + annSet.dists.getDouble(c) + " vs: " + kthFarthestDistOpt)
      if(annSet.dists.getDouble(c) <= (1 + eps) * kthFarthestDistOpt ) r+=1
      c+=1
    }

    r / k
  }

  def average(sum:Double, length:Int) : Double = {
    sum / length
  }

  def average(seq:Seq[Double]) : Double = {
    var i = 0
    var res:Double = 0.0
    while(i < seq.size) {
      res+=seq(i)
      i+=1
    }
    res / seq.size
  }

  def stdDeviation(seq:ArrayBuffer[Double]) : Double = {
    Math.sqrt(variance(seq))
  }

  def variance(seq: ArrayBuffer[Double]) : Double = {
    var tmpArray:Array[Double] = new Array(seq.size)
    require(seq.size == queries.length)
    val avg = average(seq)
    var i = 0
    var vari = 0.0
    while(i < seq.size) {
      vari = seq(i) - avg
      tmpArray(i) = vari * vari
      i+=1
    }
    average(tmpArray)
  }

  def timer[R](r: => R): Double = {
    val now = System.nanoTime
    r
    val time = System.nanoTime - now
    time
  }

  def loadKNNSets(file:File): mutable.HashMap[Int, Array[(Int, Double)]] = {
    println("Loading knn sets...")
    val map = new mutable.HashMap[Int, Array[(Int, Double)]]
    val fileLines = Source.fromFile(file).getLines()
    // format is: key,id dist,id dist, ....  for each line
    while(fileLines.hasNext) {
      val pair = fileLines.next.split(",")
      val key:String = pair.head
      val nearestNeighbors:Array[(Int, Double)] = pair.tail.map(x => {
        val set = x.split(" ")
        (set(0).toInt, set(1).toDouble)
      })
      map.put(key.toInt, nearestNeighbors)
    }

    map
  }
}
trait DistributedTester[Descriptor, Query, FileSet] extends Tester[Descriptor, Query, FileSet] {
  val system = ActorSystem("RecallTestSystem")
  println("System started")
  def getNodes(nodes:File):Array[ActorRef] = {
    val nodesAddresses = Source.fromFile(nodes).getLines.map(x => {
      val y = x.split(":")
      "akka.tcp://RepetitionSystem@"+y(0)+":"+y(1)+"/user/Repetition"
    }).toArray

    var repetitions:Array[ActorRef] = new Array(nodesAddresses.length)

    for(i <- nodesAddresses.indices) {
      println("init'ing rephandler "+i+"!")
      repetitions(i) = system.actorOf(Props[RepetitionHandler[Array[Float]]].withDeploy(Deploy(scope = RemoteScope(AddressFromURIString(nodesAddresses(i))))))
    }
    repetitions
  }
}

class NumericDistributed(data:String, dataSize:Int, dimensions:Int, seed:Long, nodes:File) extends DistributedTester[Array[Float], Array[Float], String] {
  type Descriptor = Array[Float]

  this.rnd = new Random(seed)
  this.lsh = new LSHNumericDistributed(this.getNodes(nodes))
  var lastQueriesDir = " "
  var lastKnnDir = " "

  override def run(testCase: TestCase, warmUpIterations: Int, invocationCount: Int): Result = {
    this.testCase = testCase
    if(lastKnnDir != testCase.knnSetsPath) {
      this.knnStructure = loadKNNSets(new File(testCase.knnSetsPath))
      lastKnnDir = testCase.knnSetsPath
    }

    // Get queries, keep last set if fileDir is the same
    if(!testCase.queriesDir.equals(lastQueriesDir)) {
      println("queries has not been loaded. Loading queries...")
      this.queries = DisaParserNumeric(Source.fromFile(new File(testCase.queriesDir)).getLines(), dimensions).toArray
      this.lastQueriesDir = testCase.queriesDir
    }

    // Call lsh build
    val distance = testCase.measure.toLowerCase match {
      case "cosineunit" => CosineUnit
      case "cosine" => Cosine
      case "euclidean" => EuclideanFast
      case _ => throw new Exception("Unknown distance measure!")
    }

    // Initializing LSH Structure
    this.lsh.build(data, dataSize, DisaParserFacNumeric, testCase.repsPrNode, HyperplaneFactory, testCase.probeScheme, testCase.queryMaxCands, testCase.functions, dimensions, distance, this.rnd.nextLong)

    // Run warmup
    this.warmUp(warmUpIterations)

    // Gets accumulated average results
    runQueries(invocationCount)
  }
}

class NumericSingle(data:String, dataSize:Int, dimensions:Int, seed:Long) extends Tester[Array[Float], Array[Float], String] {
  type Descriptor = Array[Float]

  this.rnd = new Random(seed)
  this.lsh = new LSHNumericSingle
  var lastQueriesDir = " "
  var lastKnnDir = " "

  override def run(testCase: TestCase, warmUpIterations: Int, invocationCount: Int): Result = {
    this.testCase = testCase

    if(lastKnnDir != testCase.knnSetsPath) {
      this.knnStructure = loadKNNSets(new File(testCase.knnSetsPath))
      lastKnnDir = testCase.knnSetsPath
    }

    // Get queries, keep last set if fileDir is the same
    if(!testCase.queriesDir.equals(lastQueriesDir)) {
      println("queries has not been loaded. Loading queries...")
      this.queries = DisaParserNumeric(Source.fromFile(new File(testCase.queriesDir)).getLines(), dimensions).toArray
      this.lastQueriesDir = testCase.queriesDir
    }

    // Call lsh build
    val distance = testCase.measure.toLowerCase match {
      case "cosineunit" => CosineUnit
      case "cosine" => Cosine
      case "euclidean" => EuclideanFast
      case _ => throw new Exception("Unknown distance measure!")
    }

    // Initializing LSH Structure
    this.lsh.build(data, dataSize, DisaParserFacNumeric, testCase.repsPrNode, HyperplaneFactory, testCase.probeScheme, testCase.queryMaxCands, testCase.functions, dimensions, distance, this.rnd.nextLong)

    // Run warmup
    this.warmUp(warmUpIterations)

    // Gets accumulated average results
    runQueries(invocationCount)
  }
}

class BinaryDistributed(data:String, dataeuc:String, dataSize:Int, dimensions:Int, seed:Long, nodes:File) extends DistributedTester[BitSet, (BitSet, Array[Float], Int), (String,String)] {

  type Descriptor = BitSet

  this.rnd = new Random(seed)
  this.lsh = new LSHBinaryDistributed(this.getNodes(nodes))
  var lastQueriesDir = " "
  var lastKnnDir = " "
  var lastKnnMax = -1


  override def run(testCase: TestCase, warmUpIterations: Int, invocationCount: Int): Result = {
    this.testCase = testCase
    if(lastKnnDir != testCase.knnSetsPath) {
      this.knnStructure = loadKNNSets(new File(testCase.knnSetsPath))
      lastKnnDir = testCase.knnSetsPath
    }

    // Get queries, keep last set if fileDir is the same
    if(!testCase.queriesDir.equals(lastQueriesDir) || !testCase.knnMax.equals(lastKnnMax)) {
      println("Loading queries...")
      val binQueries = DisaParserBinary(Source.fromFile(new File(testCase.queriesDir)).getLines(), dimensions).toArray
      val eucQueries = DisaParserNumeric(Source.fromFile(new File(testCase.eucQueriesDir)).getLines(), 128).toArray // TODO dont hardcode 128
      this.queries = new Array(binQueries.length)
      var i = 0
      while(i < binQueries.length) {
        this.queries(i) = (binQueries(i)._1, (binQueries(i)._2, eucQueries(i)._2, testCase.knnMax))
        i+=1
      }
      this.lastQueriesDir = testCase.queriesDir
      this.lastKnnMax = testCase.knnMax
    }


    // Call lsh build
    val distance = testCase.measure.toLowerCase match {
      case "hamming" => Hamming
      case _ => throw new Exception("Unknown Distance measure specified...")
    }

    val ps = testCase.probeScheme.toLowerCase match {
      case "pq" => throw new Exception("PQ cannot be used with bitsets")
      case "pq2" => throw new Exception("PQ2 cannot be used with bitsets")
      case "twostep" => "twostep"
      case _ => throw new Exception("Unknown probescheme!")
    }

    // Initializing LSH Structure
    this.lsh.build((data, dataeuc), dataSize, DisaParserFacBitSet, testCase.repsPrNode, BitHashFactory, ps, testCase.queryMaxCands, testCase.functions, dimensions, distance, this.rnd.nextLong)

    // Run warmup
    this.warmUp(warmUpIterations)

    // Gets accumulated average results
    runQueries(invocationCount)
  }
}

class BinarySingle(data:String, dataeuc:String, dataSize:Int, dimensions:Int, seed:Long) extends Tester[BitSet, (BitSet, Array[Float], Int), (String,String)] {
  type Descriptor = BitSet

  this.rnd = new Random(seed)
  this.lsh = new LSHBinarySingle
  var lastQueriesDir = " "
  var lastKnnDir = " "
  var lastKnnMax:Int = -1

  override def run(testCase: TestCase, warmUpIterations: Int, invocationCount: Int): Result = {
    this.testCase = testCase
    if(lastKnnDir != testCase.knnSetsPath) {
      this.knnStructure = loadKNNSets(new File(testCase.knnSetsPath))
      lastKnnDir = testCase.knnSetsPath
    }

    // Get queries, keep last set if fileDir is the same
    if(!testCase.queriesDir.equals(lastQueriesDir) || !testCase.knnMax.equals(lastKnnMax)) {
      println("Loading queries...")
      val binQueries = DisaParserBinary(Source.fromFile(new File(testCase.queriesDir)).getLines(), dimensions).toArray // dimensions are not needed in disaparser bin and num
      val eucQueries = DisaParserNumeric(Source.fromFile(new File(testCase.eucQueriesDir)).getLines(), 0).toArray
      this.queries = new Array(binQueries.length)
      var i = 0
      while(i < binQueries.length) {
        this.queries(i) = (binQueries(i)._1, (binQueries(i)._2, eucQueries(i)._2, testCase.knnMax))
        i+=1
      }

      this.lastQueriesDir = testCase.queriesDir
      this.lastKnnMax = testCase.knnMax
    }

    // Call lsh build
    val distance = testCase.measure.toLowerCase match {
      case "hamming" => Hamming
      case _ => throw new Exception("Unkown Distance measure specified...")
    }

    val ps = testCase.probeScheme.toLowerCase match {

      case "pq" => throw new Exception("PQ cannot be used with bitsets")
      case "pq2" => throw new Exception("PQ2 cannot be used with bitsets")
      case "twostep" => "twostep"
      case _ => throw new Exception("Unknown probescheme!")
    }

    // Initializing LSH Structure
    this.lsh.build((data, dataeuc), dataSize, DisaParserFacBitSet, testCase.repsPrNode, BitHashFactory, ps, testCase.queryMaxCands, testCase.functions, dimensions, distance, this.rnd.nextLong)

    // Run warmup
    this.warmUp(warmUpIterations)

    // Gets accumulated average results
    runQueries(invocationCount)
  }
}
