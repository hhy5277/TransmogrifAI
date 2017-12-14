/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op

import com.salesforce.op.features.FeatureJsonHelper
import com.salesforce.op.stages.OpPipelineStageBase
import com.salesforce.op.stages.sparkwrappers.generic.SparkWrapperParams
import enumeratum._
import org.apache.hadoop.fs.Path
import org.apache.spark.ml.OpPipelineStageWriter
import org.apache.spark.ml.util.MLWriter
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{DefaultFormats, Formats}

/**
 * Writes the [[OpWorkflowModel]] to json format.
 * For now we will not serialize the parent of the model
 *
 * @note The features/stages must be sorted in topological order
 *
 * @param model workflow model to save
 */
class OpWorkflowModelWriter(val model: OpWorkflowModel) extends MLWriter {

  implicit val jsonFormats: Formats = DefaultFormats

  override protected def saveImpl(path: String): Unit = {
    sc.parallelize(Seq(toJsonString(path)), 1)
      .saveAsTextFile(OpWorkflowModelReadWriteShared.jsonPath(path))
  }

  /**
   * Json serialize model instance
   *
   * @param path to save the model and its stages
   * @return model json string
   */
  def toJsonString(path: String): String = pretty(render(toJson(path)))

  /**
   * Json serialize model instance
   *
   * @param path to save the model and its stages
   * @return model json
   */
  def toJson(path: String): JObject = {
    val FN = OpWorkflowModelReadWriteShared.FieldNames
    (FN.Uid.entryName -> model.uid) ~
      (FN.ResultFeaturesUids.entryName -> resultFeaturesJArray) ~
      (FN.Stages.entryName -> stagesJArray(path)) ~
      (FN.AllFeatures.entryName -> allFeaturesJArray) ~
      (FN.Parameters.entryName -> model.parameters.toJson(pretty = false))
  }

  private def resultFeaturesJArray(): JArray =
    JArray(model.resultFeatures.map(_.uid).map(JString).toList)

  /**
   * Serialize all the workflow model stages
   *
   * @param path path to store the spark params for stages
   * @return array of serialized stages
   */
  private def stagesJArray(path: String): JArray = {
    val stages: Seq[OpPipelineStageBase] = model.stages
    val stagesJson: Seq[JObject] = stages.map {
      // Set save path for all Spark wrapped stages
      case s: SparkWrapperParams[_] => s.setSavePath(path)
      case s => s
    }.map(_.write.asInstanceOf[OpPipelineStageWriter].writeToJson)

    JArray(stagesJson.toList)
  }

  /**
   * Gets all features to be serialized.
   *
   * @note Features should be topologically sorted
   * @return all features to be serialized
   */
  private def allFeaturesJArray: JArray = {
    val features = model.rawFeatures ++ model.stages.flatMap(s => s.getInputFeatures()) ++ model.resultFeatures
    JArray(features.distinct.map(FeatureJsonHelper.toJson).toList)
  }

}

/**
 * Shared functionality between [[OpWorkflowModelWriter]] and [[OpWorkflowModelReader]]
 */
private[op] object OpWorkflowModelReadWriteShared {
  def jsonPath(path: String): String = new Path(path, "op-model.json").toString

  /**
   * Model json field names
   */
  sealed abstract class FieldNames(override val entryName: String) extends EnumEntry

  /**
   * Model json field names
   */
  object FieldNames extends Enum[FieldNames] {
    val values = findValues
    case object Uid extends FieldNames("uid")
    case object ResultFeaturesUids extends FieldNames("resultFeaturesUids")
    case object Stages extends FieldNames("stages")
    case object AllFeatures extends FieldNames("allFeatures")
    case object Parameters extends FieldNames("parameters")
  }

}


/**
 * Writes the OpWorkflowModel into a specified path
 */
object OpWorkflowModelWriter {

  /**
   * Save [[OpWorkflowModel]] to path
   *
   * @param model     workflow model instance
   * @param path      path to save the model and its stages
   * @param overwrite should overwrite the destination
   */
  def save(model: OpWorkflowModel, path: String, overwrite: Boolean = true): Unit = {
    val w = new OpWorkflowModelWriter(model)
    val writer = if (overwrite) w.overwrite() else w
    writer.save(path)
  }

  /**
   * Serialize [[OpWorkflowModel]] to json
   *
   * @param model workflow model instance
   * @param path  path to save the model and its stages
   */
  def toJson(model: OpWorkflowModel, path: String): String = {
    new OpWorkflowModelWriter(model).toJsonString(path)
  }
}