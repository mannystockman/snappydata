package org.apache.spark.sql.collection

import java.io.{IOException, ObjectOutputStream}

import scala.collection.mutable.ArrayBuffer

import _root_.io.snappydata.ToolsCallback

import scala.collection.generic.{CanBuildFrom, MutableMapFactory}
import scala.collection.{Map => SMap, Traversable, mutable}
import scala.reflect.ClassTag
import scala.util.Sorting

import org.apache.commons.math3.distribution.NormalDistribution

import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler.TaskLocation
import org.apache.spark.scheduler.local.LocalBackend
import org.apache.spark.sql.catalyst.CatalystTypeConverters
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.types._
import org.apache.spark.sql.{AnalysisException, Row, SQLContext}
import org.apache.spark.storage.BlockManagerId
import org.apache.spark._

object Utils extends MutableMapFactory[mutable.HashMap] {

  final val WEIGHTAGE_COLUMN_NAME = "__STRATIFIED_SAMPLER_WEIGHTAGE"

  // 1 - (1 - 0.95) / 2 = 0.975
  final val Z95Percent = new NormalDistribution().
      inverseCumulativeProbability(0.975)
  final val Z95Squared = Z95Percent * Z95Percent

  implicit class StringExtensions(val s: String) extends AnyVal {
    def normalize = normalizeId(s)
  }

  def fillArray[T](a: Array[_ >: T], v: T, start: Int, endP1: Int) = {
    var index = start
    while (index < endP1) {
      a(index) = v
      index += 1
    }
  }

  def columnIndex(col: String, cols: Array[String], module: String): Int = {
    val colT = normalizeId(col.trim)
    cols.indices.collectFirst {
      case index if colT == normalizeId(cols(index)) => index
    }.getOrElse {
      throw new AnalysisException(
        s"""$module: Cannot resolve column name "$col" among
            (${cols.mkString(", ")})""")
    }
  }

  def getFields(cols: Array[String], schema: StructType,
      module: String) = {
    cols.map { col =>
      val colT = normalizeId(col.trim)
      schema.fields.collectFirst {
        case field if colT == normalizeId(field.name) => field
      }.getOrElse {
        throw new AnalysisException(
          s"""$module: Cannot resolve column name "$col" among
            (${cols.mkString(", ")})""")
      }
    }
  }

  def getAllExecutorsMemoryStatus(
      sc: SparkContext): Map[BlockManagerId, (Long, Long)] = {
    val memoryStatus = sc.env.blockManager.master.getMemoryStatus
    // no filtering for local backend
    if (isLoner(sc)) memoryStatus else memoryStatus.filter(!_._1.isDriver)
  }

  def getHostExecutorId(blockId: BlockManagerId) =
    TaskLocation.executorLocationTag + blockId.host + '_' + blockId.executorId

  def ERROR_NO_QCS(module: String) = s"$module: QCS is empty"

  def qcsOf(qa: Array[String], cols: Array[String],
      module: String): Array[Int] = {
    val colIndexes = qa.map(columnIndex(_, cols, module))
    Sorting.quickSort(colIndexes)
    colIndexes
  }

  def resolveQCS(qcsV: Option[Any], fieldNames: Array[String],
      module: String): Array[Int] = {
    qcsV.map {
      case qi: Array[Int] => qi
      case qs: String =>
        if (qs.isEmpty) throw new AnalysisException(ERROR_NO_QCS(module))
        else qcsOf(qs.split(","), fieldNames, module)
      case qa: Array[String] => qcsOf(qa, fieldNames, module)
      case q => throw new AnalysisException(
        s"$module: Cannot parse 'qcs'='$q'")
    }.getOrElse(throw new AnalysisException(ERROR_NO_QCS(module)))
  }

  def matchOption(optName: String,
      options: SMap[String, Any]): Option[(String, Any)] = {
    val optionName = normalizeId(optName)
    options.get(optionName).map((optionName, _)).orElse {
      options.collectFirst { case (key, value)
        if normalizeId(key) == optionName => (key, value)
      }
    }
  }

  def resolveQCS(options: SMap[String, Any], fieldNames: Array[String],
      module: String): Array[Int] = {
    resolveQCS(matchOption("qcs", options).map(_._2), fieldNames, module)
  }

  def projectColumns(row: Row, columnIndices: Array[Int], schema: StructType,
      convertToScalaRow: Boolean) = {
    val ncols = columnIndices.length
    val newRow = new Array[Any](ncols)
    var index = 0
    if (convertToScalaRow) {
      while (index < ncols) {
        val colIndex = columnIndices(index)
        newRow(index) = CatalystTypeConverters.convertToScala(row(colIndex),
          schema(colIndex).dataType)
        index += 1
      }
    }
    else {
      while (index < ncols) {
        newRow(index) = row(columnIndices(index))
        index += 1
      }
    }
    new GenericRow(newRow)
  }

  def parseInteger(v: Any, module: String, option: String, min: Int = 1,
      max: Int = Int.MaxValue): Int = {
    val vl: Long = v match {
      case vi: Int => vi
      case vs: String =>
        try {
          vs.toLong
        } catch {
          case nfe: NumberFormatException => throw new AnalysisException(
            s"$module: Cannot parse int '$option' from string '$vs'")
        }
      case vl: Long => vl
      case vs: Short => vs
      case vb: Byte => vb
      case _ => throw new AnalysisException(
        s"$module: Cannot parse int '$option'=$v")
    }
    if (vl >= min && vl <= max) {
      vl.toInt
    } else {
      throw new AnalysisException(
        s"$module: Integer value outside of bounds [$min-$max] '$option'=$vl")
    }
  }

  def parseDouble(v: Any, module: String, option: String, min: Double,
      max: Double, exclusive: Boolean = true): Double = {
    val vd: Double = v match {
      case vd: Double => vd
      case vs: String =>
        try {
          vs.toDouble
        } catch {
          case nfe: NumberFormatException => throw new AnalysisException(
            s"$module: Cannot parse double '$option' from string '$vs'")
        }
      case vf: Float => vf.toDouble
      case vi: Int => vi.toDouble
      case vl: Long => vl.toDouble
      case vn: Number => vn.doubleValue()
      case _ => throw new AnalysisException(
        s"$module: Cannot parse double '$option'=$v")
    }
    if (exclusive) {
      if (vd > min && vd < max) {
        vd
      } else {
        throw new AnalysisException(
          s"$module: Double value outside of bounds ($min-$max) '$option'=$vd")
      }
    }
    else if (vd >= min && vd <= max) {
      vd
    } else {
      throw new AnalysisException(
        s"$module: Double value outside of bounds [$min-$max] '$option'=$vd")
    }
  }

  def parseColumn(cv: Any, cols: Array[String], module: String,
      option: String): Int = {
    val cl: Long = cv match {
      case cs: String => columnIndex(cs, cols, module)
      case ci: Int => ci
      case cl: Long => cl
      case cs: Short => cs
      case cb: Byte => cb
      case _ => throw new AnalysisException(
        s"$module: Cannot parse '$option'=$cv")
    }
    if (cl >= 0 && cl < cols.length) {
      cl.toInt
    } else {
      throw new AnalysisException(s"$module: Column index out of bounds " +
          s"for '$option'=$cl among ${cols.mkString(", ")}")
    }
  }

  /** string specification for time intervals */
  final val timeIntervalSpec = "([0-9]+)(ms|s|m|h)".r

  /**
   * Parse the given time interval value as long milliseconds.
   *
   * @see timeIntervalSpec for the allowed string specification
   */
  def parseTimeInterval(optV: Any, module: String): Long = {
    optV match {
      case tii: Int => tii.toLong
      case til: Long => til
      case tis: String => tis match {
        case timeIntervalSpec(interval, unit) =>
          unit match {
            case "ms" => interval.toLong
            case "s" => interval.toLong * 1000L
            case "m" => interval.toLong * 60000L
            case "h" => interval.toLong * 3600000L
            case _ => throw new AssertionError(
              s"unexpected regex match 'unit'=$unit")
          }
        case _ => throw new AnalysisException(
          s"$module: Cannot parse 'timeInterval'=$tis")
      }
      case _ => throw new AnalysisException(
        s"$module: Cannot parse 'timeInterval'=$optV")
    }
  }

  def mapExecutors[T: ClassTag](sqlContext: SQLContext,
      f: () => Iterator[T]): RDD[T] = {
    val sc = sqlContext.sparkContext
    val cleanedF = sc.clean(f)
    new ExecutorLocalRDD[T](sc,
      (context: TaskContext, part: ExecutorLocalPartition) => cleanedF())
  }

  def mapExecutors[T: ClassTag](sc: SparkContext,
      f: (TaskContext, ExecutorLocalPartition) => Iterator[T]): RDD[T] = {
    val cleanedF = sc.clean(f)
    new ExecutorLocalRDD[T](sc, cleanedF)
  }

  def getFixedPartitionRDD[T: ClassTag](sc: SparkContext,
      f: (TaskContext, Partition) => Iterator[T], partitioner: Partitioner,
      numPartitions: Int): RDD[T] = {
    val cleanedF = sc.clean(f)
    new FixedPartitionRDD[T](sc, cleanedF, numPartitions, Some(partitioner))
  }

  def getInternalType(dataType: DataType): Class[_] = {
    dataType match {
      case ByteType => classOf[scala.Byte]
      case IntegerType => classOf[scala.Int]
      case DoubleType => classOf[scala.Double]
      //case "numeric" => org.apache.spark.sql.types.DoubleType
      //case "character" => org.apache.spark.sql.types.StringType
      case StringType => classOf[String]
      //case "binary" => org.apache.spark.sql.types.BinaryType
      //case "raw" => org.apache.spark.sql.types.BinaryType
      //case "logical" => org.apache.spark.sql.types.BooleanType
      //case "boolean" => org.apache.spark.sql.types.BooleanType
      //case "timestamp" => org.apache.spark.sql.types.TimestampType
      //case "date" => org.apache.spark.sql.types.DateType
      case _ => throw new IllegalArgumentException(s"Invalid type $dataType")
    }
  }

  def getClientHostPort(netServer: String): String = {
    val addrIdx = netServer.indexOf('/')
    val portEndIndex = netServer.indexOf(']')
    if (addrIdx > 0) {
      val portIndex = netServer.indexOf('[')
      netServer.substring(0, addrIdx) +
          netServer.substring(portIndex, portEndIndex + 1)
    } else {
      netServer.substring(addrIdx + 1, portEndIndex + 1)
    }
  }

  def isLoner(sc: SparkContext): Boolean = sc.schedulerBackend match {
    case lb: LocalBackend => true
    case _ => false
  }

  def normalizeId(k: String): String = {
    var index = 0
    val len = k.length
    while (index < len) {
      if (Character.isUpperCase(k.charAt(index))) {
        return k.toLowerCase(java.util.Locale.ENGLISH)
      }
      index += 1
    }
    k
  }

  def normalizeIdUpperCase(k: String): String = {
    var index = 0
    val len = k.length
    while (index < len) {
      if (Character.isLowerCase(k.charAt(index))) {
        return k.toUpperCase(java.util.Locale.ENGLISH)
      }
      index += 1
    }
    k
  }

  def normalizeOptions[T](opts: Map[String, Any])
      (implicit bf: CanBuildFrom[Map[String, Any], (String, Any), T]): T =
    opts.map[(String, Any), T] {
      case (k, v) => (normalizeId(k), v)
    }(bf)

  // for mapping any tuple traversable to a mutable map

  def empty[A, B]: mutable.HashMap[A, B] = new mutable.HashMap[A, B]

  implicit def canBuildFrom[A, B] = new CanBuildFrom[Traversable[(A, B)],
      (A, B), mutable.HashMap[A, B]] {

    override def apply(from: Traversable[(A, B)]) = empty[A, B]

    override def apply() = empty
  }
}

class ExecutorLocalRDD[T: ClassTag](
    @transient private[this] val _sc: SparkContext,
    f: (TaskContext, ExecutorLocalPartition) => Iterator[T])
    extends RDD[T](_sc, Nil) {

  override def getPartitions: Array[Partition] = {
    val numberedPeers = Utils.getAllExecutorsMemoryStatus(sparkContext).
        keySet.zipWithIndex

    if (numberedPeers.nonEmpty) {
      numberedPeers.map {
        case (bid, idx) => createPartition(idx, bid)
      }.toArray[Partition]
    } else {
      Array.empty[Partition]
    }
  }

  def createPartition(index: Int,
      blockId: BlockManagerId): ExecutorLocalPartition =
    new ExecutorLocalPartition(index, blockId)

  override def getPreferredLocations(split: Partition): Seq[String] =
    Seq(split.asInstanceOf[ExecutorLocalPartition].hostExecutorId)

  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    val part = split.asInstanceOf[ExecutorLocalPartition]
    val thisBlockId = SparkEnv.get.blockManager.blockManagerId
    if (part.blockId != thisBlockId) {
      throw new IllegalStateException(
        s"Unexpected execution of $part on $thisBlockId")
    }

    f(context, part)
  }
}

class FixedPartitionRDD[T: ClassTag](@transient _sc: SparkContext,
    f: (TaskContext, Partition) => Iterator[T], numPartitions: Int,
    override val partitioner: Option[Partitioner])
    extends RDD[T](_sc, Nil) {

  override def getPartitions: Array[Partition] = {
    var i = 0

    Array.fill[Partition](numPartitions)({
      val x = i
      i += 1
      new Partition() {
        override def index: Int = x
      }
    })
  }

  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    f(context, split)
  }
}

class ExecutorLocalPartition(override val index: Int,
    val blockId: BlockManagerId) extends Partition {

  def hostExecutorId = Utils.getHostExecutorId(blockId)

  override def toString = s"ExecutorLocalPartition($index, $blockId)"
}

class MultiExecutorLocalPartition(override val index: Int,
    val blockIds: Seq[BlockManagerId]) extends Partition {

  def hostExecutorIds = blockIds.map(blockId => Utils.getHostExecutorId(blockId))

  override def toString = s"MultiExecutorLocalPartition($index, $blockIds)"
}


private[spark] case class NarrowExecutorLocalSplitDep(
    @transient rdd: RDD[_],
    @transient splitIndex: Int,
    var split: Partition
    ) extends Serializable {

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream): Unit = org.apache.spark.util.Utils.tryOrIOException {
    // Update the reference to parent split at the time of task serialization
    split = rdd.partitions(splitIndex)
    oos.defaultWriteObject()
  }
}

/**
 * Stores information about the narrow dependencies used by a StoreRDD.
 *
 * @param narrowDep maps to the dependencies variable in the parent RDD: for each one to one
 *                   dependency in dependencies, narrowDeps has a NarrowExecutorLocalSplitDep (describing
 *                   the partition for that dependency) at the corresponding index. The size of
 *                   narrowDeps should always be equal to the number of parents.
 */
private[spark] class CoGroupExecutorLocalPartition(
    idx: Int, val blockId: BlockManagerId, val narrowDep: Option[NarrowExecutorLocalSplitDep])
    extends Partition with Serializable {

  override val index: Int = idx

  def hostExecutorId = Utils.getHostExecutorId(blockId)

  override def toString = s"CoGroupExecutorLocalPartition($index, $blockId)"

  override def hashCode(): Int = idx
}

class ExecutorLocalShellPartition(override val index: Int,
    val hostList: ArrayBuffer[(String, String)]) extends Partition {
  override def toString = s"ExecutorLocalShellPartition($index, $hostList"
}

object ToolsCallbackInit extends Logging {
  final val toolsCallback = {
    try {
      val c = org.apache.spark.util.Utils.classForName(
        "io.snappydata.ToolsCallbackImpl$")
      val tc = c.getField("MODULE$").get(null).asInstanceOf[ToolsCallback]
      logInfo("toolsCallback initialized")
      tc
    } catch {
      case cnf: ClassNotFoundException =>
        logWarning("toolsCallback couldn't be INITIALIZED." +
            "DriverURL won't get published to others.")
        null
    }
  }
}
