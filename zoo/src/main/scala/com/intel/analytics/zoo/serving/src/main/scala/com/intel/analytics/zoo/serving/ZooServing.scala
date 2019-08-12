/*
 * Copyright 2018 Analytics Zoo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.zoo.serving

import com.intel.analytics.bigdl.numeric.NumericFloat
import com.intel.analytics.bigdl.tensor.Tensor
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import com.intel.analytics.zoo.pipeline.api.keras.layers.utils.EngineRef
import org.apache.spark.rdd.ZippedPartitionsWithLocalityRDD
import com.intel.analytics.zoo.utils._


object ZooServing {
  case class Result(id: String, value: String)

  Logger.getLogger("org").setLevel(Level.ERROR)
  Logger.getLogger("akka").setLevel(Level.ERROR)
  Logger.getLogger("breeze").setLevel(Level.ERROR)
  Logger.getLogger("com.intel.analytics.zoo.feature.image").setLevel(Level.ERROR)
  Logger.getLogger("com.intel.analytics.zoo").setLevel(Level.INFO)

  val logger: Logger = Logger.getLogger(getClass)

  def main(args: Array[String]): Unit = {

    val loader = new Loader()
    loader.init(args)
    val cachedModel = loader.loadModel[Float]()

    val coreNumber = EngineRef.getCoreNumber()

    val spark = loader.loadSparkSession(args)

    logger.info(s"connected to redis ${spark.conf.get("spark.redis.host")}:${spark.conf.get("spark.redis.port")}")
    val batchSize = loader.batchSize
    val topN = loader.topN

    val images = spark
      .readStream
      .format("redis")
      .option("stream.keys", "image_stream")
      .option("stream.read.batch.size", batchSize.toString)
      .option("stream.parallelism", EngineRef.getNodeNumber())
      .schema(StructType(Array(
        StructField("id", StringType),
        StructField("path", StringType),
        StructField("image", StringType)
      )))
      .load().limit(5)

    import org.apache.spark.storage.StorageLevel._
    val query = images
      .writeStream
      .foreachBatch { (batchDF: DataFrame, batchId: Long) => {
        // uncomment this line if you want action on DataFrame
        // otherwise you should not do any action on batchDF
        // if you do action on it, it would become empty
        // batchDF.persist(MEMORY_ONLY)

        logger.info(s"Get batch $batchId")

        logger.info(s"num of partition: ${batchDF.rdd.partitions.size}")
        logger.info(s"${batchDF.rdd.partitions.map(_.index).mkString("  ")}")

        val batchImage = batchDF.rdd.map{image =>
          (image.getAs[String]("path"), java.util
            .Base64.getDecoder.decode(image.getAs[String]("image")))
        }.mapPartitions{bytes =>


          bytes.grouped(coreNumber).flatMap{
            batchPath =>
              batchPath.indices.toParArray.map{i =>
                (batchPath(i)._1, ImageProcessing.bytesToBGRTensor(batchPath(i)._2))
              }
          }
        }
        logger.info("Preprocess end")
        val result = ZippedPartitionsWithLocalityRDD(batchImage, cachedModel){ (imageTensor, modelIter) =>
          val localModel = modelIter.next()
          val inputTensor = Tensor[Float](batchSize, 3, 224, 224)
          imageTensor.grouped(batchSize).flatMap { batch =>
            val size = batch.size
            val startCopy = System.nanoTime()
            (0 until size).toParArray.foreach { i =>
              inputTensor.select(1, i + 1).copy(batch(i)._2)
            }

            val start = System.nanoTime()
            val output = localModel.forward(inputTensor).toTensor[Float]
            val end = System.nanoTime()

            (0 until size).map { i => {
              var value: String = ""
              for (j <- 0 to topN) {
                value = value + output.valueAt(i + 1, j + 1) + "|"
              }

              Result(batch(i)._1, value)

            }}
          }
        }

        val resDf = spark.createDataFrame(result)
        resDf.write
          .format("org.apache.spark.sql.redis")
          .option("table", "result")
          .mode(SaveMode.Append).save()

        logger.info("Predict end")
      }
    }.start()
    query.awaitTermination()
  }
}
