/*
 *  Copyright 2018 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.twosigma.flint.timeseries

import java.util.concurrent.TimeUnit

import com.twosigma.flint.rdd.{ KeyPartitioningType, OrderedRDD }
import com.twosigma.flint.timeseries.row.Schema
import com.twosigma.flint.timeseries.time.types.TimeType
import org.apache.spark.sql.functions._
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types._

class DFConversionSpec extends TimeSeriesSuite with FlintTestData {
  override val defaultPartitionParallelism = 5
  val SEED = 12345L
  val clockSchema: StructType = Schema("time" -> LongType)
  val clockData = (0L to 100L).map(ts => (ts, new GenericRowWithSchema(Array(ts), clockSchema).asInstanceOf[Row]))
  var clockTSRdd: TimeSeriesRDD = _

  private lazy val init: Unit = {
    clockTSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(clockData, defaultPartitionParallelism), KeyPartitioningType.Sorted),
      clockSchema
    )
  }

  def genRandomDataFrame(sqlContext: SQLContext, numRows: Int, max: Int, numPartitions: Int): DataFrame = {
    val idDf = sqlContext.range(0, numRows.toLong, 1, numPartitions)
    val timeColumn = functions.rand(SEED) * max
    idDf.select(timeColumn.as(TimeSeriesRDD.timeColumnName).cast(LongType), functions.randn(SEED).as("value"))
  }

  it should "convert time unit correctly" in {
    init
    val df = clockTSRdd.toDF
    val convertedDf = TimeSeriesRDD.canonizeTime(df, TimeUnit.MICROSECONDS)

    val dfRows = df.collect()
    val convertedRows = convertedDf.collect()

    val zipped = dfRows.zip(convertedRows)
    assert(zipped.forall {
      case (row, convertedRow) => row.getAs[Long]("time") * 1000 == convertedRow.getAs[Long]("time")
    })
  }

  it should "convert timestamp correctly" in {
    init
    val df = clockTSRdd.toDF.withColumn("time", col("time") * (123456 * 10e8).toLong)
    val convertedDf = df.withColumn("time", (df("time") / 10e8).cast(TimestampType))

    // These should all be the same
    val result1 = TimeSeriesRDD.fromDF(convertedDf)(isSorted = true, null)
    val result2 = TimeSeriesRDD.fromDF(convertedDf)(isSorted = true, TimeUnit.NANOSECONDS)
    val result3 = TimeSeriesRDD.fromDF(convertedDf)(isSorted = true, TimeUnit.MICROSECONDS)

    val expected = TimeSeriesRDD.fromDF(df)(isSorted = true, TimeUnit.NANOSECONDS)

    assertEquals(expected, result1)
    assertEquals(expected, result2)
    assertEquals(expected, result3)
  }

  it should "sort dataframes correctly" in {
    init
    val df = clockTSRdd.toDF
    val revertedDf = df.withColumn("time", -col("time") + 100)

    val tsRdd = TimeSeriesRDD.fromDF(revertedDf)(isSorted = false, TimeUnit.NANOSECONDS)
    val tsRows = tsRdd.collect()

    val zipped = tsRows.map(row => row.getAs[Long]("time")).zipWithIndex
    assert(zipped.forall {
      case (ts, index) => ts == index
    })
  }

  it should "convert to physical rdd" in {
    init
    val unsortedDF = testData
    val sortedDF = testData.orderBy("time")
    var tsRdd = TimeSeriesRDD.fromDF(unsortedDF)(isSorted = false, TimeUnit.NANOSECONDS)
    assert(PartitionPreservingOperation.isPartitionPreservingDataFrame(tsRdd.toDF))
    tsRdd = TimeSeriesRDD.fromDF(sortedDF)(isSorted = true, TimeUnit.NANOSECONDS)
    assert(PartitionPreservingOperation.isPartitionPreservingDataFrame(tsRdd.toDF))
  }

  it should "use timeColumn correctly" in {
    init
    val df1 = testData
    val df2 = testData.withColumnRenamed("time", "time2")
    val tsRdd1 = TimeSeriesRDD.fromDF(df1)(isSorted = true, TimeUnit.NANOSECONDS)
    val tsRdd2 = TimeSeriesRDD.fromDF(df2)(isSorted = true, TimeUnit.NANOSECONDS, timeColumn = "time2")
    assertEquals(tsRdd1, tsRdd2.deleteColumns("time2"))
  }

  it should "throw exception if `time` column exists but is not time column" in {
    init
    intercept[IllegalArgumentException] {
      val df = testData.withColumn("time2", testData("time"))
      TimeSeriesRDD.fromDF(df)(isSorted = true, TimeUnit.NANOSECONDS, timeColumn = "time2")
    }
  }

  it should "throw exception if timeColumn doesn't exist" in {
    init
    intercept[Exception] {
      TimeSeriesRDD.fromDF(testData.withColumnRenamed("time", "time2"))(isSorted = true, TimeUnit.NANOSECONDS)
    }

    intercept[Exception] {
      TimeSeriesRDD.fromDF(testData)(isSorted = true, TimeUnit.NANOSECONDS, timeColumn = "time2")
    }
  }

  it should "build from unsorted DataFrames correctly" in {
    val maxValues = List(100, 1000, 10000)
    maxValues.foreach { maxValue =>
      val df = genRandomDataFrame(sqlContext, numRows = 1000, max = maxValue, numPartitions = 10)
      intercept[Exception] {
        TimeSeriesRDD.fromDF(df)(isSorted = true, TimeUnit.NANOSECONDS).keepColumns("time")
      }
      TimeSeriesRDD.fromDF(df)(isSorted = false, TimeUnit.NANOSECONDS).keepColumns("time").validate()
    }
  }

  it should "correctly use Catalyst partitioning information" in {
    val df = clockTSRdd.toDF.withColumn("data", col("time") * 100)
    assert(!TimeSeriesStore.isClustered(df.queryExecution.executedPlan))

    assert(TimeSeriesStore.isClustered(df.sort("time").queryExecution.executedPlan))

    assert(TimeSeriesStore.isClustered(df.sort(col("time").desc).queryExecution.executedPlan))
    assert(!TimeSeriesStore.isSorted(df.sort(col("time").desc).queryExecution.executedPlan))

    assert(TimeSeriesStore.isClustered(df.repartition(col("time")).queryExecution.executedPlan))
    assert(!TimeSeriesStore.isSorted(df.repartition(col("time")).queryExecution.executedPlan))

    assert(!TimeSeriesStore.isClustered(df.sort("data").queryExecution.executedPlan))

    assert(!TimeSeriesStore.isSorted(df.sort("data", "time").queryExecution.executedPlan))
  }

  it should "correctly canonize normalized DataFrame" in {
    val df = clockTSRdd.toDF.withColumn("data", col("time") * 100)
    val withWrongOrder = df.select("data", "time").sort("time")

    val canonized = TimeSeriesRDD.canonizeDF(withWrongOrder, isSorted = true, TimeUnit.NANOSECONDS, "time")
    // columns should be reordered, but it shouldn't break normalization
    assert(canonized.schema.fieldNames.head == "time")
    assert(TimeSeriesStore.isClustered(canonized.queryExecution.executedPlan))
  }

  it should "preserve partitions of a sorted DF" in {
    val sortedDf = clockTSRdd.toDF.sort("time")
    val dfPartitions = sortedDf.rdd.mapPartitions {
      iter => if (iter.isEmpty) Iterator.empty else Iterator(iter.next())
    }.collect().map(_.getLong(0))

    val tsrdd = TimeSeriesRDD.fromDF(sortedDf)(isSorted = true, TimeUnit.NANOSECONDS)
    val tsrddPartitions = tsrdd.partInfo.get.splits.map(_.range.begin)
    assert(dfPartitions.toSeq == tsrddPartitions)

    tsrdd.validate()
  }

  it should "correctly convert a DataFrame sorted by time,id" in {
    val df = clockTSRdd.toDF.withColumn("ids", array(lit(2), lit(1), lit(0)))
      .withColumn("id", explode(col("ids")))
      .drop("ids")
      .sort("time", "id")
    val tsrdd = TimeSeriesRDD.fromDF(df)(isSorted = true, TimeUnit.NANOSECONDS)
    tsrdd.validate()
  }
}
