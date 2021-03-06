/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.tuning

import org.apache.hadoop.fs.Path
import org.json4s.DefaultFormats

import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.internal.Logging
import org.apache.spark.ml.{Estimator, Model}
import org.apache.spark.ml.evaluation.Evaluator
import org.apache.spark.ml.param.{DoubleParam, ParamMap, ParamValidators}
import org.apache.spark.ml.util._
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.StructType

/**
 * Params for [[TrainValidationSplit]] and [[TrainValidationSplitModel]].
 */
private[ml] trait TrainValidationSplitParams extends ValidatorParams {
  /**
   * Param for ratio between train and validation data. Must be between 0 and 1.
   * Default: 0.75
   *
   * @group param
   */
  val trainRatio: DoubleParam = new DoubleParam(this, "trainRatio",
    "ratio between training set and validation set (>= 0 && <= 1)", ParamValidators.inRange(0, 1))

  /** @group getParam */
  def getTrainRatio: Double = $(trainRatio)

  setDefault(trainRatio -> 0.75)
}

/**
 * :: Experimental ::
 * Validation for hyper-parameter tuning.
 * Randomly splits the input dataset into train and validation sets,
 * and uses evaluation metric on the validation set to select the best model.
 * Similar to [[CrossValidator]], but only splits the set once.
 */
@Since("1.5.0")
@Experimental
class TrainValidationSplit @Since("1.5.0") (@Since("1.5.0") override val uid: String)
  extends Estimator[TrainValidationSplitModel]
  with TrainValidationSplitParams with MLWritable with Logging {

  @Since("1.5.0")
  def this() = this(Identifiable.randomUID("tvs"))

  /** @group setParam */
  @Since("1.5.0")
  def setEstimator(value: Estimator[_]): this.type = set(estimator, value)

  /** @group setParam */
  @Since("1.5.0")
  def setEstimatorParamMaps(value: Array[ParamMap]): this.type = set(estimatorParamMaps, value)

  /** @group setParam */
  @Since("1.5.0")
  def setEvaluator(value: Evaluator): this.type = set(evaluator, value)

  /** @group setParam */
  @Since("1.5.0")
  def setTrainRatio(value: Double): this.type = set(trainRatio, value)

  @Since("1.5.0")
  override def fit(dataset: DataFrame): TrainValidationSplitModel = {
    val schema = dataset.schema
    transformSchema(schema, logging = true)
    val sqlCtx = dataset.sqlContext
    val est = $(estimator)
    val eval = $(evaluator)
    val epm = $(estimatorParamMaps)
    val numModels = epm.length
    val metrics = new Array[Double](epm.length)

    val Array(training, validation) =
      dataset.rdd.randomSplit(Array($(trainRatio), 1 - $(trainRatio)))
    val trainingDataset = sqlCtx.createDataFrame(training, schema).cache()
    val validationDataset = sqlCtx.createDataFrame(validation, schema).cache()

    // multi-model training
    logDebug(s"Train split with multiple sets of parameters.")
    val models = est.fit(trainingDataset, epm).asInstanceOf[Seq[Model[_]]]
    trainingDataset.unpersist()
    var i = 0
    while (i < numModels) {
      // TODO: duplicate evaluator to take extra params from input
      val metric = eval.evaluate(models(i).transform(validationDataset, epm(i)))
      logDebug(s"Got metric $metric for model trained with ${epm(i)}.")
      metrics(i) += metric
      i += 1
    }
    validationDataset.unpersist()

    logInfo(s"Train validation split metrics: ${metrics.toSeq}")
    val (bestMetric, bestIndex) =
      if (eval.isLargerBetter) metrics.zipWithIndex.maxBy(_._1)
      else metrics.zipWithIndex.minBy(_._1)
    logInfo(s"Best set of parameters:\n${epm(bestIndex)}")
    logInfo(s"Best train validation split metric: $bestMetric.")
    val bestModel = est.fit(dataset, epm(bestIndex)).asInstanceOf[Model[_]]
    copyValues(new TrainValidationSplitModel(uid, bestModel, metrics).setParent(this))
  }

  @Since("1.5.0")
  override def transformSchema(schema: StructType): StructType = transformSchemaImpl(schema)

  @Since("1.5.0")
  override def copy(extra: ParamMap): TrainValidationSplit = {
    val copied = defaultCopy(extra).asInstanceOf[TrainValidationSplit]
    if (copied.isDefined(estimator)) {
      copied.setEstimator(copied.getEstimator.copy(extra))
    }
    if (copied.isDefined(evaluator)) {
      copied.setEvaluator(copied.getEvaluator.copy(extra))
    }
    copied
  }

  @Since("2.0.0")
  override def write: MLWriter = new TrainValidationSplit.TrainValidationSplitWriter(this)
}

@Since("2.0.0")
object TrainValidationSplit extends MLReadable[TrainValidationSplit] {

  @Since("2.0.0")
  override def read: MLReader[TrainValidationSplit] = new TrainValidationSplitReader

  @Since("2.0.0")
  override def load(path: String): TrainValidationSplit = super.load(path)

  private[TrainValidationSplit] class TrainValidationSplitWriter(instance: TrainValidationSplit)
    extends MLWriter {

    ValidatorParams.validateParams(instance)

    override protected def saveImpl(path: String): Unit =
      ValidatorParams.saveImpl(path, instance, sc)
  }

  private class TrainValidationSplitReader extends MLReader[TrainValidationSplit] {

    /** Checked against metadata when loading model */
    private val className = classOf[TrainValidationSplit].getName

    override def load(path: String): TrainValidationSplit = {
      implicit val format = DefaultFormats

      val (metadata, estimator, evaluator, estimatorParamMaps) =
        ValidatorParams.loadImpl(path, sc, className)
      val trainRatio = (metadata.params \ "trainRatio").extract[Double]
      new TrainValidationSplit(metadata.uid)
        .setEstimator(estimator)
        .setEvaluator(evaluator)
        .setEstimatorParamMaps(estimatorParamMaps)
        .setTrainRatio(trainRatio)
    }
  }
}

/**
 * :: Experimental ::
 * Model from train validation split.
 *
 * @param uid Id.
 * @param bestModel Estimator determined best model.
 * @param validationMetrics Evaluated validation metrics.
 */
@Since("1.5.0")
@Experimental
class TrainValidationSplitModel private[ml] (
    @Since("1.5.0") override val uid: String,
    @Since("1.5.0") val bestModel: Model[_],
    @Since("1.5.0") val validationMetrics: Array[Double])
  extends Model[TrainValidationSplitModel] with TrainValidationSplitParams with MLWritable {

  @Since("1.5.0")
  override def transform(dataset: DataFrame): DataFrame = {
    transformSchema(dataset.schema, logging = true)
    bestModel.transform(dataset)
  }

  @Since("1.5.0")
  override def transformSchema(schema: StructType): StructType = {
    bestModel.transformSchema(schema)
  }

  @Since("1.5.0")
  override def copy(extra: ParamMap): TrainValidationSplitModel = {
    val copied = new TrainValidationSplitModel (
      uid,
      bestModel.copy(extra).asInstanceOf[Model[_]],
      validationMetrics.clone())
    copyValues(copied, extra)
  }

  @Since("2.0.0")
  override def write: MLWriter = new TrainValidationSplitModel.TrainValidationSplitModelWriter(this)
}

@Since("2.0.0")
object TrainValidationSplitModel extends MLReadable[TrainValidationSplitModel] {

  @Since("2.0.0")
  override def read: MLReader[TrainValidationSplitModel] = new TrainValidationSplitModelReader

  @Since("2.0.0")
  override def load(path: String): TrainValidationSplitModel = super.load(path)

  private[TrainValidationSplitModel]
  class TrainValidationSplitModelWriter(instance: TrainValidationSplitModel) extends MLWriter {

    ValidatorParams.validateParams(instance)

    override protected def saveImpl(path: String): Unit = {
      import org.json4s.JsonDSL._
      val extraMetadata = "validationMetrics" -> instance.validationMetrics.toSeq
      ValidatorParams.saveImpl(path, instance, sc, Some(extraMetadata))
      val bestModelPath = new Path(path, "bestModel").toString
      instance.bestModel.asInstanceOf[MLWritable].save(bestModelPath)
    }
  }

  private class TrainValidationSplitModelReader extends MLReader[TrainValidationSplitModel] {

    /** Checked against metadata when loading model */
    private val className = classOf[TrainValidationSplitModel].getName

    override def load(path: String): TrainValidationSplitModel = {
      implicit val format = DefaultFormats

      val (metadata, estimator, evaluator, estimatorParamMaps) =
        ValidatorParams.loadImpl(path, sc, className)
      val trainRatio = (metadata.params \ "trainRatio").extract[Double]
      val bestModelPath = new Path(path, "bestModel").toString
      val bestModel = DefaultParamsReader.loadParamsInstance[Model[_]](bestModelPath, sc)
      val validationMetrics = (metadata.metadata \ "validationMetrics").extract[Seq[Double]].toArray
      val tvs = new TrainValidationSplitModel(metadata.uid, bestModel, validationMetrics)
      tvs.set(tvs.estimator, estimator)
        .set(tvs.evaluator, evaluator)
        .set(tvs.estimatorParamMaps, estimatorParamMaps)
        .set(tvs.trainRatio, trainRatio)
    }
  }
}
