package epic.srl

import epic.sequences.{Gazetteer, Segmentation, SemiCRFModel, SemiCRF}
import breeze.util._
import epic.framework.{StandardExpectedCounts, Model, Feature}
import breeze.linalg.{SparseVector, VectorBuilder, Counter, DenseVector}
import collection.mutable.ArrayBuffer
import epic.parser.features.{SpanShapeGenerator, PairFeature, WordShapeFeaturizer}
import breeze.text.analyze.{WordShapeGenerator, EnglishWordClassGenerator}
import collection.immutable
import epic.trees.Span
import epic.parser.features.StandardSpanFeatures.WordEdges
import epic.parser.features.PairFeature
import epic.trees.Span
import epic.parser.features.StandardSpanFeatures.WordEdges
import breeze.collection.mutable.TriangularArray
import breeze.util

/**
 *
 * @author dlwh
@SerialVersionUID(1L)
class SemiSRLModel(val featureIndex: Index[Feature],
                   val featurizer: SemiSRLModel.Featurizer,
                   maxSegmentLength: Int=>Int,
                   initialWeights: Feature=>Double = {(_: Feature) => 0.0}) extends Model[SrlInstance] with StandardExpectedCounts.Model with Serializable {

  def extractCRF(weights: DenseVector[Double]) = {
    val grammar = inferenceFromWeights(weights)
    new SemiCRF(grammar)
  }

  type Marginal = SemiCRF.Marginal[String, String]

  def initialValueForFeature(f: Feature): Double = initialWeights(f)

  def inferenceFromWeights(weights: DenseVector[Double]): Inference = new SemiSRLInference(weights, featureIndex, featurizer, maxSegmentLength)

}

object SemiSRLModelFactory {
  case class SFeature(w: Any, kind: Symbol) extends Feature
  case class BeginFeature(w: Feature, cur: String) extends Feature
  case class EndFeature(w: Feature, cur: String) extends Feature
  case class TrigramFeature(a: Any, b: Any, c: Any) extends Feature
  case class SpanFeature(distance: Feature, cur: Any) extends Feature
  case class UnigramFeature(w: Feature, cur: String) extends Feature
  case class CFeature(component: Int, f: Feature) extends Feature
  case class DistanceFeature(distanceBin: Int) extends Feature
  case object TransitionFeature extends Feature
  case object SpanStartsSentence extends Feature
  case object SpansWholeSentence extends Feature

  /**
   * Computes basic features from word counts
   * @param wordCounts
   */
  @SerialVersionUID(1L)
  class StandardFeaturizer(gazetteer: Gazetteer[Any, String], wordCounts: Counter[String, Double] ) extends Serializable {
    val inner = new WordShapeFeaturizer(wordCounts)

    def localize(words: IndexedSeq[String], lemma: String, pos: Int)= new Localization(words)

    val interner = new Interner[Feature]

    class Localization(words: IndexedSeq[String]) {
      val classes = words.map(EnglishWordClassGenerator)
      val shapes = words.map(WordShapeGenerator)

      val basicFeatures = (0 until words.length) map { i =>
        val w = words(i)
        if(wordCounts(w) > 10) IndexedSeq(w)
        else if (wordCounts(w) > 5) IndexedSeq(w, classes(i), shapes(i))
        else IndexedSeq(classes(i), shapes(i))
      } map {_.map(_.intern)}

      def basicFeature(pos: Int) = {
        if(pos < 0 || pos >= words.length) IndexedSeq("#")
        else basicFeatures(pos)
      }

      val featuresForWord: immutable.IndexedSeq[Array[Feature]] = 0 until words.length map { pos =>
        val feats = new ArrayBuffer[Feature]()
        val basic = basicFeature(pos).map(SFeature(_, 'Cur))
        val basicLeft = basicFeature(pos - 1).map(SFeature(_, 'Prev))
        val basicRight = basicFeature(pos + 1).map(SFeature(_, 'Next))
        feats ++= basicLeft
        feats ++= basicRight
        feats ++= inner.featuresFor(words, pos)
        for (a <- basicLeft; b <- basic) feats += PairFeature(a,b)
        for (a <- basic; b <- basicRight) feats += PairFeature(a,b)
        //        for (a <- basicLeft; b <- basicRight) feats += PairFeature(a,b)
        feats += TrigramFeature(basicLeft(0), basic(0), basicRight(0))
        if(pos > 0 && pos < words.length - 1) {
          feats += TrigramFeature(shapes(pos-1), shapes(pos), shapes(pos+1))
          feats += TrigramFeature(classes(pos-1), classes(pos), classes(pos+1))
        }
        feats ++= gazetteer.lookupWord(words(pos)).map(SFeature(_, 'WordSeenInSegment))
        feats.map(interner.intern _).toArray
      }

      def featuresForTransition(beg: Int, end: Int) = {
        val feats = ArrayBuffer[Feature](DistanceFeature(binDistance(end - beg)), TransitionFeature)
        feats.toArray
      }

      def featuresForSpan(start: Int, end: Int):Array[Feature] = {
        val feats = ArrayBuffer[Feature]()
        feats ++= gazetteer.lookupSpan(Span(start, end).map(words).toIndexedSeq).map(SFeature(_, 'SegmentKnown))
        if (start < end - 1) {
          feats += WordEdges('Inside, basicFeature(start)(0), basicFeature(end-1)(0))
          feats += WordEdges('Outside, basicFeature(start-1)(0), basicFeature(end)(0))
          feats += WordEdges('Begin, basicFeature(start-1)(0), basicFeature(start)(0))
          feats += WordEdges('End, basicFeature(end-1)(0), basicFeature(end)(0))
          feats += SFeature(SpanShapeGenerator.apply(words, Span(start,end)), 'SpanShape)
        }
        if(start == 0)
          feats += SpanStartsSentence
        if(start == 0 && end == words.length)
          feats += SpansWholeSentence

        feats.toArray
      }

      private def binDistance(dist2:Int) = {
        val dist = dist2.abs - 1
        if (dist >= 20) 8
        else if (dist > 10) 7
        else if (dist > 5) 6
        else dist
      }
    }

  }

  @SerialVersionUID(1L)
  class IndexedStandardFeaturizer(f: StandardFeaturizer,
                                  val startSymbol: String,
                                  val labelIndex: Index[String],
                                  val basicFeatureIndex: Index[Feature],
                                  val basicSpanFeatureIndex: Index[Feature],
                                  val basicTransFeatureIndex: Index[Feature],
                                  val pruningModel: Option[SemiCRF.ConstraintGrammar[String, String]] = None) extends Serializable {
    // feature mappings... sigh
    // basically we want to build a big index for all features
    // (beginFeatures ++ endFeatures ++ unigramFeatures ++ spanFeatures ++ transitionFeatures)
    private val label2Index = new PairIndex(labelIndex, labelIndex)
    private val labeledFeatureIndex = new PairIndex(labelIndex, basicFeatureIndex)
    private implicit val beginIso = Isomorphism[(String, Feature), BeginFeature](
      tu={pair => BeginFeature(pair._2, pair._1)},
      ut={f => (f.cur, f.w) }
    )
    private implicit val endIso = Isomorphism[(String, Feature), EndFeature](
      tu={pair => EndFeature(pair._2, pair._1)},
      ut={f => (f.cur, f.w) }
    )

    private implicit val uniIso = Isomorphism[(String, Feature), UnigramFeature](
      tu={pair => UnigramFeature(pair._2, pair._1)},
      ut={f => (f.cur, f.w) }
    )

    private implicit val spanIso = Isomorphism[(String,  Feature), SpanFeature](
      tu={pair => SpanFeature(pair._2, pair._1)},
      ut={f => (f.cur.asInstanceOf[String], f.distance) }
    )


    private implicit val transIso = Isomorphism[((String,String),  Feature), SpanFeature](
      tu={pair => SpanFeature(pair._2, pair._1)},
      ut={f => (f.cur.asInstanceOf[(String, String)], f.distance) }
    )

    private val spanFeatureIndex = new PairIndex(labelIndex, basicSpanFeatureIndex)
    private val transFeatureIndex = new PairIndex(label2Index, basicTransFeatureIndex)

    val compositeIndex = new CompositeIndex[Feature](new IsomorphismIndex(labeledFeatureIndex)(beginIso),
      //      new IsomorphismIndex(labeledFeatureIndex)(endIso),
      new IsomorphismIndex(labeledFeatureIndex)(uniIso),
      new IsomorphismIndex(spanFeatureIndex)(spanIso),
      new IsomorphismIndex(transFeatureIndex)(transIso)
    )

    val BEGIN_COMP = 0
    //    val END_COMP = 1
    val UNI_COMP = 1
    val SPAN_COMP = 2
    val TRANS_COMP = 3

    private implicit val featureIso = util.Isomorphism[(Int,Feature), Feature](
      tu={pair => CFeature(pair._1, pair._2)},
      ut={f => f.asInstanceOf[CFeature].component -> f.asInstanceOf[CFeature].f}
    )

    val featureIndex: IsomorphismIndex[(Int, Feature), Feature] = new IsomorphismIndex(compositeIndex)(featureIso)
    println("Number of features: " + featureIndex.size)

    def anchor(w: IndexedSeq[String], lemma: String, pos: Int): SemiCRFModel.BIEOAnchoredFeaturizer[String, String] = new SemiCRFModel.BIEOAnchoredFeaturizer[String, String] {
      val constraints = pruningModel.map(_.constraints(w))
      val loc = f.localize(w, lemma, pos)

      val basicFeatureCache = Array.tabulate(w.length){ pos =>
        val feats =  loc.featuresForWord(pos)
        feats.map(basicFeatureIndex).filter(_ >= 0)
      }


      val beginCache = Array.tabulate(labelIndex.size, labelIndex.size, w.length){ (p,c,w) =>
        val ok = constraints.forall(_.allowedStarts(w)(c))
        if (!ok) {
          null
        }  else {
          val builder = new VectorBuilder[Double](featureIndex.size)
          val feats = basicFeatureCache(w)
          builder.reserve(feats.length)
          var i = 0
          while(i < feats.length) {
            val index = compositeIndex.mapIndex(BEGIN_COMP, labeledFeatureIndex.mapIndex(c, feats(i)))
            if(index != -1) {
              builder.add(index, 1.0)
            }

            i += 1
          }
          builder.toSparseVector
        }
      }
      val endCache = Array.tabulate(labelIndex.size, w.length){ (l, w) =>
        val builder = new VectorBuilder[Double](featureIndex.size)
        /*
        val feats = basicFeatureCache(w)
        builder.sizeHint(feats.length)
        var i = 0
        while(i < feats.length) {
          val index = compositeIndex.mapIndex(END_COMP, labeledFeatureIndex.mapIndex(l, feats(i)))
          if(index != -1) {
            builder.add(index, 1.0)
          }

          i += 1
        }
        */
        builder.toSparseVector
      }
      val wordCache = Array.tabulate(labelIndex.size, w.length){ (l, w) =>
        val builder = new VectorBuilder[Double](featureIndex.size)
        val feats = basicFeatureCache(w)
        builder.reserve(feats.length)
        var i = 0
        while(i < feats.length) {
          val index = compositeIndex.mapIndex(UNI_COMP, labeledFeatureIndex.mapIndex(l, feats(i)))
          if(index != -1) {
            builder.add(index, 1.0)
          }

          i += 1
        }
        builder.toSparseVector
      }

      def featureIndex: Index[Feature] = IndexedStandardFeaturizer.this.featureIndex

      def featuresForBegin(prev: Int, cur: Int, pos: Int): SparseVector[Double] = {
        beginCache(prev)(cur)(pos)
      }

      def featuresForEnd(cur: Int, pos: Int): SparseVector[Double] = {
        endCache(cur)(pos-1)
      }

      def featuresForInterior(cur: Int, pos: Int): SparseVector[Double] = {
        wordCache(cur)(pos)
      }

      private val spanCache = TriangularArray.tabulate(w.length+1){ (beg,end) =>
        if(beg < end)
          loc.featuresForSpan(beg, end).map(basicSpanFeatureIndex).filter(_ >= 0)
        else null
      }
      private val spanFeatures = Array.tabulate(labelIndex.size, labelIndex.size){ (prev, cur) =>
        TriangularArray.tabulate(w.length+1) { (beg, end) =>
          if(!(constraints == None || constraints.get.allowedLabels(beg, end).contains(cur))) {
            null
          } else {
            val builder = new VectorBuilder[Double](featureIndex.size)
            if(beg < end) {
              val feats = spanCache(beg, end)
              val tfeats = loc.featuresForTransition(beg, end).map(basicTransFeatureIndex)
              builder.reserve(feats.length + tfeats.length)
              var i = 0
              while(i < feats.length) {
                val index = compositeIndex.mapIndex(SPAN_COMP, spanFeatureIndex.mapIndex(cur, feats(i)))
                if(index != -1) {
                  builder.add(index, 1.0)
                }
                i += 1
              }
              i = 0
              while(i < tfeats.length) {
                val index = compositeIndex.mapIndex(TRANS_COMP, transFeatureIndex.mapIndex(label2Index.mapIndex(prev, cur), tfeats(i)))
                if(index != -1) {
                  builder.add(index, 1.0)
                }
                i += 1
              }
            }
            builder.toSparseVector
          }
        }
      }


      def featuresForSpan(prev: Int, cur: Int, beg: Int, end: Int): SparseVector[Double] = {
        spanFeatures(prev)(cur)(beg,end)
      }

    }
  }


}
 */