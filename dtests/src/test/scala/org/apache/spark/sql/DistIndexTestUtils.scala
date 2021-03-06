package org.apache.spark.sql

import java.io._
import java.util.TimeZone

import io.snappydata.benchmark.snappy.{TPCH_Snappy, SnappyAdapter, TPCH}
import org.apache.spark.sql.catalyst.plans.logical.Sort
import org.apache.spark.util.Benchmark

object DistIndexTestUtils {

  def benchmark(qNum: String, tableSizes: Map[String, Long], snc: SnappyContext, pw: PrintWriter,
                fos: FileOutputStream)
  = {

    val qryProvider = new TPCH with SnappyAdapter
    val query = qNum.toInt
    def executor(str: String) = snc.sql(str)

    val size = qryProvider.estimateSizes(query, tableSizes, executor)
    pw.println(s"$qNum size $size")
    val b = new Benchmark(s"JoinOrder optimization", size, minNumIters = 5, output = Some(fos))

    def case1(): Unit = snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name,
      "false")

    def case2(): Unit = snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name,
      "true")

    def case3(): Unit = {
      snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name,
        "true")
    }

    def evalSnappyMods(genPlan: Boolean) = TPCH_Snappy.queryExecution(qNum, snc, useIndex = false,
      genPlan = genPlan)._1.foreach(_ => ())

    def evalBaseTPCH = qryProvider.execute(query, executor)


    b.addCase(s"$qNum baseTPCH index = F", prepare = case1)(i => evalBaseTPCH)
    //    b.addCase(s"$qNum baseTPCH joinOrder = T", prepare = case2)(i => evalBaseTPCH)
    //    b.addCase(s"$qNum snappyMods joinOrder = F", prepare = case1)(i => evalSnappyMods(false))
    //    b.addCase(s"$qNum snappyMods joinOrder = T", prepare = case2)(i => evalSnappyMods(false))
    b.addCase(s"$qNum baseTPCH index = T", prepare = case3)(i =>
      evalBaseTPCH)
    b.run()
  }

  def executeQueriesWithResultValidation(snc: SnappyContext, pw: PrintWriter): Unit = {
    // scalastyle:off println
    val qryProvider = new TPCH with SnappyAdapter

    val queries = Array("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
      "12", "13", "14", "15", "16", "17", "18", "19",
      "20", "21", "22")

    //TPCHUtils.createAndLoadTables(snc, true)

    val existing = snc.getConf(io.snappydata.Property.EnableExperimentalFeatures.name)
    snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name, "true")

    for ((q, i) <- queries.zipWithIndex) {
      val qNum = i + 1
      val (expectedAnswer, _) = qryProvider.execute(qNum, str => {
        pw.println("Query String is : " + str)
        snc.sql(str)
      })
      val (newAnswer, df) = TPCH_Snappy.queryExecution(q, snc, false, false)
      newAnswer.foreach(pw.println)
      val isSorted = df.logicalPlan.collect { case s: Sort => s }.nonEmpty
      QueryTest.sameRows(expectedAnswer, newAnswer, isSorted).map { results =>
        s"""
           |Results do not match for query: $qNum
           |Timezone: ${TimeZone.getDefault}
           |Timezone Env: ${sys.env.getOrElse("TZ", "")}
           |
           |${df.queryExecution}
           |== Results ==
           |$results
       """.stripMargin
      }
      pw.println(s"Done $qNum")
    }
    snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name, existing)
  }

  def executeQueriesForBenchmarkResults(snc: SnappyContext, pw: PrintWriter, fos: FileOutputStream): Unit = {
    val queries = Array("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
      "12", "13", "14", "15", "16", "17", "18", "19",
      "20", "21", "22")
    /*
        val queries = Array("q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9", "q10", "q11",
          "q12", "q13", "q14", "q15", "q16", "q17", "q18", "q19",
          "q20", "q21", "q22")
    */

    //TPCHUtils.createAndLoadTables(snc, true)

    snc.sql(s"""CREATE INDEX idx_orders_cust ON orders(o_custkey)
             options (COLOCATE_WITH 'customer')
          """)

    snc.sql(
      s"""CREATE INDEX idx_lineitem_part ON lineitem(l_partkey)
             options (COLOCATE_WITH 'part')
          """)

    val tables = Seq("nation", "region", "supplier", "customer", "orders", "lineitem", "part",
      "partsupp")

    val tableSizes = tables.map { tableName =>
      (tableName, snc.table(tableName).count())
    }.toMap

    tableSizes.foreach(pw.println)
    queries.foreach(q => benchmark(q, tableSizes, snc, pw, fos))
    /*queries.foreach(q => {
      TPCH_Snappy.queryExecution(q, snc, false, false)
    pw.println("**** Executed query : " + q )
    })*/
    snc.sql(s"DROP INDEX idx_orders_cust")
    snc.sql(s"DROP INDEX idx_lineitem_part")
  }

}
