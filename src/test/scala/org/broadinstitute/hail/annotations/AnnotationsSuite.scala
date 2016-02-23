package org.broadinstitute.hail.annotations

import org.apache.spark.sql.Row
import org.broadinstitute.hail.SparkSuite
import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.driver._
import org.broadinstitute.hail.variant.{Genotype, IntervalList, Variant}
import org.testng.annotations.Test
import org.broadinstitute.hail.methods._
import scala.language.implicitConversions

/**
  * This testing suite evaluates the functionality of the [[org.broadinstitute.hail.annotations]] package
  */
class AnnotationsSuite extends SparkSuite {
  @Test def test() {

    /*
      The below tests are designed to check for a subset of variants and info fields, that:
          1. the types, emitConversionIdentifier strings, and description strings agree with the VCF
          2. the strings stored in the AnnotationData classes agree with the VCF
          3. the strings stored in the AnnotationData classes convert correctly to the proper type
    */

    val vds = LoadVCF(sc, "src/test/resources/sample.vcf")

    val state = State(sc, sqlContext, vds)
    val vas = vds.metadata.variantAnnotationSignatures
    val variantAnnotationMap = vds.variantsAndAnnotations.collect().toMap

    val firstVariant = Variant("20", 10019093, "A", "G")
    val anotherVariant = Variant("20", 10036107, "T", "G")
    assert(variantAnnotationMap.contains(firstVariant))
    assert(variantAnnotationMap.contains(anotherVariant))

    // type Int - INFO.DP
    assert(vas.get[Annotations]("info").attrs.get("DP").contains(VCFSignature("Int", "Integer", "1",
      "Approximate read depth; some reads may have been filtered")))
    assert(variantAnnotationMap(firstVariant)
      .get[Annotations]("info").attrs.get("DP")
      .get.asInstanceOf[Int] == 77560)
    assert(variantAnnotationMap(anotherVariant)
      .get[Annotations]("info").attrs.get("DP").get.asInstanceOf[Int] == 20271)

    // type Double - INFO.HWP
    assert(vas.get[Annotations]("info").attrs.get("HWP").contains(new VCFSignature("Double", "Float", "1",
      "P value from test of Hardy Weinberg Equilibrium")))
    assert(
      D_==(variantAnnotationMap(firstVariant)
        .get[Annotations]("info").attrs.get("HWP").get.asInstanceOf[Double], 0.0001))
    assert(D_==(variantAnnotationMap(anotherVariant)
      .get[Annotations]("info").attrs.get("HWP").get.asInstanceOf[Double], 0.8286))

    // type String - INFO.culprit
    assert(vas.get[Annotations]("info").attrs.get("culprit").contains(VCFSignature("String", "String", "1",
      "The annotation which was the worst performing in the Gaussian mixture model, " +
        "likely the reason why the variant was filtered out")))
    assert(variantAnnotationMap(firstVariant)
      .get[Annotations]("info").attrs.get("culprit")
      .contains("FS"))
    assert(variantAnnotationMap(anotherVariant)
      .get[Annotations]("info").attrs.get("culprit")
      .contains("FS"))

    // type Array - INFO.AC (allele count)
    assert(vas.get[Annotations]("info").attrs.get("AC").contains(VCFSignature("Array[Int]", "Integer", "A",
      "Allele count in genotypes, for each ALT allele, in the same order as listed")))
    assert(variantAnnotationMap(firstVariant)
      .get[Annotations]("info").attrs.get("AC")
      .map(_.asInstanceOf[IndexedSeq[Int]])
      .forall(_.equals(IndexedSeq(89))))
    assert(variantAnnotationMap(anotherVariant)
      .get[Annotations]("info").attrs.get("AC")
      .map(_.asInstanceOf[IndexedSeq[Int]])
      .forall(_.equals(IndexedSeq(13))))

    // type Boolean/flag - INFO.DB (dbSNP membership)
    assert(vas.get[Annotations]("info").attrs.get("DB").contains(new VCFSignature("Boolean", "Flag", "0",
      "dbSNP Membership")))
    assert(variantAnnotationMap(firstVariant)
      .get[Annotations]("info").attrs.get("DB")
      .contains(true))
    assert(!variantAnnotationMap(anotherVariant)
      .get[Annotations]("info").attrs.contains("DB"))

    //type Set[String]
    assert(vas.attrs.get("filters").contains(new SimpleSignature("Set[String]")))
    assert(variantAnnotationMap(firstVariant)
      .attrs.get("filters").contains(Set[String]("PASS")))
    assert(variantAnnotationMap(anotherVariant)
      .attrs.get("filters").contains(Set("VQSRTrancheSNP99.95to100.00")))

    // GATK PASS
    assert(vas.attrs.get("pass").contains(new SimpleSignature("Boolean")))
    assert(variantAnnotationMap(firstVariant)
      .attrs.get("pass").contains(true))
    assert(variantAnnotationMap(anotherVariant)
      .attrs.get("pass").contains(false))

    val vds2 = LoadVCF(sc, "src/test/resources/sample2.vcf")


    // Check that VDS can be written to disk and retrieved while staying the same
    hadoopDelete("/tmp/sample.vds", sc.hadoopConfiguration, recursive = true)
    vds2.write(sqlContext, "/tmp/sample.vds")
    val readBack = Read.run(state, Array("-i", "/tmp/sample.vds"))

    assert(readBack.vds.same(vds2))
  }

  @Test def testMergeAnnotations() {
    val map1 = Map[String, Any]("a" -> 1, "b" -> 2, "c" -> 3)
    val map2 = Map[String, Any]("a" -> 4, "b" -> 5)
    val map3 = Map[String, Any]("a" -> 6)

    val anno1 = Annotations(Map("a" -> 1))
    val anno2 = Annotations(Map("a" -> Annotations(map2), "b" -> 2, "c" -> 3))
    val anno3 = Annotations(Map("a" -> Annotations(Map("a" -> 1))))

    // make sure that adding one deep annotation does the right thing
    // make sure that overwriting one high annotation does the right thing
    assert(anno2 ++ anno1 == Annotations(map1))
    assert(anno2 ++ anno3 == Annotations(Map("a" -> Annotations(Map("a" -> 1, "b" -> 5)), "b" -> 2, "c" -> 3)))
  }

  @Test def testRewrite() {


    val inner = Row.fromSeq(Array(4, 5, 6))
    val middle = Row.fromSeq(Array(2, 3, inner))
    val outer = Row.fromSeq(Array(1, middle))

    val inner2 = Row.fromSeq(Array(100))
    val middle2 = Row.fromSeq(Array(inner2))
    val outer2 = Row.fromSeq(Array(middle2, 55))

    val outer3 = Row.fromSeq(Array(1000))

    val ad1 = AnnotationData(outer)
    val ad2 = AnnotationData(outer2)
    val ad3 = AnnotationData(outer3)

    println("ad1:")
    AnnotationData.printData(ad1)
    println("ad2:")
    AnnotationData.printData(ad2)
    println("ad3:")
    AnnotationData.printData(ad3)


    val innerSigs: AnnotationSignatures = AnnotationSignatures(Map(
      "d" -> SimpleSignature("Int", IndexPath(Array(1, 2), 0)),
      "e" -> SimpleSignature("Int", IndexPath(Array(1, 2), 1)),
      "f" -> SimpleSignature("Int", IndexPath(Array(1, 2), 2))
    ), IndexPath(Array(1), 2))

    val middleSigs = AnnotationSignatures(Map(
      "b" -> SimpleSignature("Int", IndexPath(Array(1), 0)),
      "c" -> SimpleSignature("Int", IndexPath(Array(1), 1)),
      "inner" -> innerSigs
    ), IndexPath(Array[Int](), 2))

    val outerSigs = AnnotationSignatures(Map(
      "a" -> SimpleSignature("Int", IndexPath(Array[Int](), 0)),
      "middle" -> middleSigs
    ))

    println("here")
    val signatures = outerSigs
    val sigsToAdd = AnnotationSignatures(Map(
      "middle" -> AnnotationSignatures(Map(
        "inner" -> AnnotationSignatures(Map(
          "g" -> SimpleSignature("Int", IndexPath(Array(0, 0), 0))
        ), IndexPath(Array(0), 0))
      ), IndexPath(Array[Int](), 0)),
      "anotherthing" -> SimpleSignature("Int", IndexPath(Array[Int](), 1)))
    )

    val sigsToAdd2 = AnnotationSignatures(Map(
      "middle" -> SimpleSignature("Int", IndexPath(Array[Int](), 0))
    ))


    println("sigs1")
    val sb1 = new StringBuilder
    ShowAnnotations.printSignatures(sb1, signatures, 0, "va")
    println(sb1.result())

    println("sigs2")
    val sb2 = new StringBuilder
    ShowAnnotations.printSignatures(sb2, sigsToAdd, 0, "va")
    println(sb2.result())


    println("new sigs")
    val (newS, f1) = AnnotationData.mergeSignatures(signatures, sigsToAdd)
    val sb = new StringBuilder
    ShowAnnotations.printSignatures(sb, newS, 0, "va")
    println(sb.result())
    val newRow = f1(ad1, ad2)
    AnnotationData.printRow(newRow.row)

    println("new sigs2")'
    val (newS2, f2) = AnnotationData.mergeSignatures(signatures, sigsToAdd2)
    val sbNew = new StringBuilder
    ShowAnnotations.printSignatures(sbNew, newS2, 0, "va")
    println(sbNew.result())
    println()
//    AnnotationData.printData(f(ad1, ad2))
//    AnnotationData.printData(f2(ad1, ad3))
  }
}
