/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op

import com.salesforce.op.features.Feature
import com.salesforce.op.features.types._
import com.salesforce.op.stages.base.unary.UnaryLambdaTransformer
import com.salesforce.op.stages.impl.feature.NumericBucketizer
import com.salesforce.op.stages.impl.preparators.{SanityChecker, SanityCheckerSummary}
import com.salesforce.op.test._
import com.salesforce.op.testkit._
import com.salesforce.op.utils.spark.RichMetadata._
import org.apache.spark.sql.types.Metadata
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BadFeatureZooTest extends FlatSpec with TestSparkContext {

  Spec[SanityChecker] should "correctly identify label leakage in PickList features using the Cramer's V criteria" +
    "when the label corresponds to a binary classification problem" in {
    // First set up the raw features:
    val cityData: Seq[City] = RandomText.cities.withProbabilityOfEmpty(0.2).take(1000).toList
    val countryData: Seq[Country] = RandomText.countries.withProbabilityOfEmpty(0.2).take(1000).toList
    val pickListData: Seq[PickList] = RandomText.pickLists(domain = List("A", "B", "C", "D", "E", "F", "G", "H", "I"))
      .withProbabilityOfEmpty(0.2).limit(1000)
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0)
      .withProbabilityOfEmpty(0.2).limit(1000)

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(City, Country, PickList, Currency)] =
      cityData.zip(countryData).zip(pickListData).zip(currencyData).map {
        case (((ci, co), pi), cu) => (ci, co, pi, cu)
      }
    val (rawDF, rawCity, rawCountry, rawPickList, rawCurrency) =
      TestFeatureBuilder("city", "country", "picklist", "currency", generatedData)

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[PickList, RealNN](operationName = "labelFunc",
      transformFn = p => p.value match {
        case Some("A") | Some("B") | Some("C") => RealNN(1.0)
        case _ => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawPickList).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawCity, rawCountry, rawPickList, rawCurrency).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // Check that all the PickList features are dropped and they are the only ones dropped
    // (9 choices + "other" + empty = 11 total dropped columns)
    retrieved.dropped.forall(_.startsWith("picklist")) shouldBe true
    retrieved.dropped.length shouldBe 11
    retrieved.categoricalStats.categoricalFeatures.length shouldBe retrieved.names.length - 2
  }

  it should "not fail to run or serialize when passed empty features" in {
    // First set up the raw features:
    val cityData: Seq[City] = RandomText.cities.withProbabilityOfEmpty(1.0).take(1000).toList
    val countryData: Seq[Country] = RandomText.countries.withProbabilityOfEmpty(1.0).take(1000).toList
    val pickListData: Seq[PickList] = RandomText.pickLists(domain = List("A", "B", "C", "D", "E", "F", "G", "H", "I"))
      .withProbabilityOfEmpty(0.5).limit(1000)
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0)
      .withProbabilityOfEmpty(0.5).limit(1000)

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(City, Country, PickList, Currency)] =
      cityData.zip(countryData).zip(pickListData).zip(currencyData).map {
        case (((ci, co), pi), cu) => (ci, co, pi, cu)
      }
    val (rawDF, rawCity, rawCountry, rawPickList, rawCurrency) =
      TestFeatureBuilder("city", "country", "picklist", "currency", generatedData)

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[PickList, RealNN](operationName = "labelFunc",
      transformFn = p => p.value match {
        case Some(_) => RealNN(1.0)
        case _ => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawPickList).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawCity, rawCountry, rawPickList, rawCurrency).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    val asJson = retrieved.toMetadata().wrapped.prettyJson
    Metadata.fromJson(asJson) shouldEqual retrieved.toMetadata()
    retrieved.dropped.length shouldBe 15
  }


  it should "correctly identify label leakage in PickList features using the Cramer's V criteria when the label " +
    "corresponds to a multiclass classification problem" in {
    // First set up the raw features:
    val cityData: Seq[City] = RandomText.cities.withProbabilityOfEmpty(0.2).take(1000).toList
    val countryData: Seq[Country] = RandomText.countries.withProbabilityOfEmpty(0.2).take(1000).toList
    val pickListData: Seq[PickList] = RandomText.pickLists(domain = List("A", "B", "C", "D", "E", "F", "G", "H", "I"))
      .withProbabilityOfEmpty(0.2).limit(1000)
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0)
      .withProbabilityOfEmpty(0.2).limit(1000)

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(City, Country, PickList, Currency)] =
      cityData.zip(countryData).zip(pickListData).zip(currencyData).map {
        case (((ci, co), pi), cu) => (ci, co, pi, cu)
      }
    val (rawDF, rawCity, rawCountry, rawPickList, rawCurrency) =
      TestFeatureBuilder("city", "country", "picklist", "currency", generatedData)

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[PickList, RealNN](operationName = "labelFunc",
      transformFn = p => p.value match {
        case Some("A") | Some("B") => RealNN(1.0)
        case Some("C") | Some("D") => RealNN(2.0)
        case Some("E") => RealNN(3.0)
        case _ => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawPickList).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawCity, rawCountry, rawPickList, rawCurrency).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // Check that all the PickList features are dropped and they are the only ones dropped
    // (9 choices + "other" + empty = 11 total dropped columns)
    retrieved.dropped.forall(_.startsWith("picklist")) shouldBe true
    retrieved.dropped.length shouldBe 11
  }

  it should "not calculate Cramer's V if the label is flagged as not categorical" in {
    // First set up the raw features:
    val cityData: Seq[City] = RandomText.cities.withProbabilityOfEmpty(0.2).take(1000).toList
    val countryData: Seq[Country] = RandomText.countries.withProbabilityOfEmpty(0.2).take(1000).toList
    val pickListData: Seq[PickList] = RandomText.pickLists(domain = List("A", "B", "C", "D", "E", "F", "G", "H", "I"))
      .withProbabilityOfEmpty(0.2).limit(1000)
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0)
      .withProbabilityOfEmpty(0.2).limit(1000)

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(City, Country, PickList, Currency)] =
      cityData.zip(countryData).zip(pickListData).zip(currencyData).map {
        case (((ci, co), pi), cu) => (ci, co, pi, cu)
      }
    val (rawDF, rawCity, rawCountry, rawPickList, rawCurrency) =
      TestFeatureBuilder("city", "country", "picklist", "currency", generatedData)

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[PickList, RealNN](operationName = "labelFunc",
      transformFn = p => p.value match {
        case Some("A") | Some("B") | Some("C") => RealNN(1.0)
        case _ => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawPickList).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawCity, rawCountry, rawPickList, rawCurrency).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .setCategoricalLabel(false)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // Should only remove
    retrieved.dropped.length shouldBe 1
  }

  it should "correctly identify label leakage in PickList features using the Cramer's V criteria, ignoring " +
    "indicator groups/values coming from numeric MapVectorizers" in {
    // First set up the raw features:
    val cityData: Seq[City] = RandomText.cities.withProbabilityOfEmpty(0.2).limit(1000)
    val pickListData: Seq[PickList] = RandomText.pickLists(domain = List("A", "B", "C", "D", "E", "F", "G", "H", "I"))
      .withProbabilityOfEmpty(0.2).limit(1000)
    val integralMapData: Seq[IntegralMap] = RandomMap.of(RandomIntegral.integrals(-100, 100), 0, 4).limit(1000)
    val multiPickListData: Seq[MultiPickList] = RandomMultiPickList.of(RandomText.countries, maxLen = 5)
      .limit(1000)

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(City, MultiPickList, PickList, IntegralMap)] =
      cityData.zip(multiPickListData).zip(pickListData).zip(integralMapData).map {
        case (((ci, mp), pi), im) => (ci, mp, pi, im)
      }
    val (rawDF, rawCity, rawCountry, rawPickList, rawIntegralMap) =
      TestFeatureBuilder("city", "multipicklist", "picklist", "integralMap", generatedData)

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[PickList, RealNN](operationName = "labelFunc",
      transformFn = p => p.value match {
        case Some("A") | Some("B") | Some("C") => RealNN(1.0)
        case _ => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawPickList).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawCity, rawCountry, rawPickList, rawIntegralMap).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // Check that all the PickList features are dropped and they are the only ones dropped
    // (9 choices + "other" + empty = 11 total dropped columns)
    retrieved.dropped.forall(_.startsWith("picklist")) shouldBe true
    retrieved.dropped.length shouldBe 11
  }

  it should "correctly identify label leakage due to null indicators and throw away corresponding parent features" in {
    // First set up the raw features:
    val cityData: Seq[City] = RandomText.cities.withProbabilityOfEmpty(0.4).take(1000).toList
    val realData: Seq[Real] = RandomReal.uniform[Real](minValue = 0.0, maxValue = 1.0)
      .withProbabilityOfEmpty(0.5).limit(1000)
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0).limit(1000)
    val expectedRevenueData: Seq[Currency] = currencyData.zip(realData).map{ case (cur, re) =>
      re.value match {
        case Some(v) => (v * cur.value.get).toCurrency
        case None => Currency.empty
      }
    }

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(City, Real, Currency, Currency)] =
      cityData.zip(realData).zip(currencyData).zip(expectedRevenueData).map {
        case (((ci, re), cu), er) => (ci, re, cu, er)
      }
    val (rawDF, rawCity, rawReal, rawCurrency, rawER) =
      TestFeatureBuilder("city", "real", "currency", "expectedRevenue", generatedData)

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[Real, RealNN](operationName = "labelFunc",
      transformFn = r => r.value match {
        case Some(v) => RealNN(1.0)
        case _ => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawReal).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawCity, rawReal, rawCurrency, rawER).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    retrieved.dropped.count(_.startsWith("expectedRevenue")) shouldBe 2
    retrieved.dropped.count(_.startsWith("real")) shouldBe 2
  }

  it should "correctly identify label leakage from binned numerics" in {
    // First set up the raw features:
    val binaryData: Seq[Binary] = RandomBinary(probabilityOfSuccess = 0.5).withProbabilityOfEmpty(0.3).take(1000).toList
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0).limit(1000)
    val expectedRevenueData: Seq[Currency] = currencyData.zip(binaryData).map{ case (cur, bi) =>
      bi.value match {
        case Some(v) => ((if (v) 1.0 else 0.0) * cur.value.get).toCurrency
        case None => Currency.empty
      }
    }

    // Generate the raw features and corresponding dataframe
    val generatedRawData: Seq[(Binary, Currency, Currency)] =
      binaryData.zip(currencyData).zip(expectedRevenueData).map {
        case ((bi, cu), er) => (bi, cu, er)
      }
    val (rawDF, rawBinary, rawCurrency, rawER) = TestFeatureBuilder("binary", "currency", "expectedRevenue",
      generatedRawData)

    // Construct a binned value of expected revenue into three bins: null, 0, or neither to perform a Cramer's V test
    val erTransformer = new UnaryLambdaTransformer[Currency, PickList](operationName = "erBinned",
      transformFn = c => c.value match {
        case Some(v) if v != 0 => "nonZero".toPickList
        case Some(_) => "zero".toPickList
        case None => "null".toPickList
      }
    )
    val erBinned = erTransformer.setInput(rawER).getOutput()

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[Binary, RealNN](operationName = "labelFunc",
      transformFn = r => r.value match {
        case Some(true) => RealNN(1.0)
        case Some(false) => RealNN(0.0)
        case None => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawBinary).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawBinary, rawCurrency, rawER, erBinned).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // Should throw out the five Picklist features resulting from binning: three bins + other + null
    retrieved.dropped.count(_.startsWith("expectedRevenue_1-stagesApplied_PickList")) shouldBe 5
    // TODO: figure out that original expectedRevenue columns should be dropped since a unary transformation links them
  }

  it should "correctly identify label leakage from binned numerics, and throw away immediate parent features when " +
    "the binning transformer outputs an OPVector" in {
    // First set up the raw features:
    val binaryData: Seq[Binary] = RandomBinary(probabilityOfSuccess = 0.5).withProbabilityOfEmpty(0.3).limit(1000)
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0).limit(1000)
    val expectedRevenueData: Seq[Currency] = currencyData.zip(binaryData).map{ case (cur, bi) =>
      bi.value match {
        case Some(v) => ((if (v) 1.0 else 0.0) * cur.value.get).toCurrency
        case None => Currency.empty
      }
    }

    val generatedRawData: Seq[(Binary, Currency, Currency)] =
      binaryData.zip(currencyData).zip(expectedRevenueData).map {
        case ((bi, cu), er) => (bi, cu, er)
      }
    val (rawDF, rawBinary, rawCurrency, rawER) = TestFeatureBuilder("binary", "currency", "expectedRevenue",
      generatedRawData)

    // Construct a binned value of expected revenue into three bins: null, 0, or neither to perform a Cramer's V test
    val erBucketizer = new NumericBucketizer[Double, Currency]()
      .setBuckets(splitPoints = Array(-1.0, 0.0, 1.0, Double.PositiveInfinity))
    val erBinned = erBucketizer.setInput(rawER).getOutput()

    // Construct a label that we know is highly biased from the pickList data to check if SanityChecker detects it
    val labelTransformer = new UnaryLambdaTransformer[Binary, RealNN](operationName = "labelFunc",
      transformFn = r => r.value match {
        case Some(true) => RealNN(1.0)
        case Some(false) => RealNN(0.0)
        case None => RealNN(0.0)
      }
    )
    val labelData = labelTransformer.setInput(rawBinary).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    // Construct the feature vector and the
    val genFeatureVector = Seq(rawBinary, rawCurrency, rawER, erBinned).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // Should throw out the original two expectedRevenue features, along with the three that come from binning
    retrieved.dropped.count(_.startsWith("expectedRevenue")) shouldBe 5
  }

  it should "not try to compute Cramer's V between categorical features and numeric labels (eg. for regression)" in {
    // First set up the raw features:
    val cityData: Seq[City] = RandomText.cities.withProbabilityOfEmpty(0.2).take(1000).toList
    val countryData: Seq[Country] = RandomText.countries.withProbabilityOfEmpty(0.2).take(1000).toList
    val pickListData: Seq[PickList] = RandomText.pickLists(domain = List("A", "B", "C", "D", "E", "F", "G", "H", "I"))
      .withProbabilityOfEmpty(0.2).limit(1000)
    val labelData: Seq[RealNN] = RandomReal.logNormal[RealNN](mean = 10.0, sigma = 1.0).limit(1000)

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(City, Country, PickList, RealNN)] =
      cityData.zip(countryData).zip(pickListData).zip(labelData).map {
        case (((ci, co), pi), la) => (ci, co, pi, la)
      }
    val (rawDF, rawCity, rawCountry, rawPickList, rawLabel) =
      TestFeatureBuilder("city", "country", "picklist", "label", generatedData)
    val labels = rawLabel.copy(isResponse = true)

    val genFeatureVector = Seq(rawCity, rawCountry, rawPickList).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labels, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // Should just drop "other" column from the picklist since there are no "other"s, and the variance is 0
    retrieved.dropped.count(_.startsWith("picklist")) shouldBe 1
    retrieved.dropped.length shouldBe 1
  }

  // TODO: Check for throwing out features from maps once vectorizers have been fixed to track null values
  it should "not calculate Cramer's V on features coming from maps and multipicklists" in {
    // First set up the raw features:
    val currencyData: Seq[Currency] = RandomReal.logNormal[Currency](mean = 10.0, sigma = 1.0).limit(1000)
    val domain = List("Strawberry Milk", "Chocolate Milk", "Soy Milk", "Almond Milk")
    val picklistMapData: Seq[PickListMap] = RandomMap.of[PickList, PickListMap](RandomText.pickLists(domain), 1, 3)
      .limit(1000)
    val multiPickListData: Seq[MultiPickList] = RandomMultiPickList.of(RandomText.countries, maxLen = 5)
      .limit(1000)

    // Generate the raw features and corresponding dataframe
    val generatedData: Seq[(Currency, MultiPickList, PickListMap)] =
      currencyData.zip(multiPickListData).zip(picklistMapData).map {
        case ((cu, mp), pm) => (cu, mp, pm)
      }
    val (rawDF, rawCurrency, rawMultiPickList, rawPicklistMap) =
      TestFeatureBuilder("currency", "multipicklist", "picklistMap", generatedData)

    val labelTransformer2 = new UnaryLambdaTransformer[PickListMap, RealNN](operationName = "labelFunc",
      transformFn = p => p.value match {
        case j if j.contains("k1") => RealNN(1.0)
        case _ => RealNN(0.0)
      }
    )
    val labelData = labelTransformer2.setInput(rawPicklistMap).getOutput().asInstanceOf[Feature[RealNN]]
      .copy(isResponse = true)

    val genFeatureVector = Seq(rawCurrency, rawMultiPickList, rawPicklistMap).transmogrify()
    val checkedFeatures = new SanityChecker()
      .setCheckSample(1.0)
      .setInput(labelData, genFeatureVector)
      .setRemoveBadFeatures(true)
      .getOutput()

    val transformed = new OpWorkflow().setResultFeatures(checkedFeatures).transform(rawDF)
    val summary = transformed.schema(checkedFeatures.name).metadata
    val retrieved = SanityCheckerSummary.fromMetadata(summary.getSummaryMetadata())

    // should only have a single categorical feature to compute Cramer's V from (null indicator for currency)
    retrieved.categoricalStats.categoricalFeatures.length shouldBe 1
    retrieved.categoricalStats.categoricalFeatures.count(_.startsWith("currency")) shouldBe 1
    retrieved.categoricalStats.cramersVs.length shouldBe 1
  }

}