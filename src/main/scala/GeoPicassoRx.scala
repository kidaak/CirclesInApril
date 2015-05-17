import java.io.{FileOutputStream, ByteArrayInputStream, File, PrintWriter}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import org.apache.batik.transcoder.{TranscoderOutput, TranscoderInput}
import org.apache.batik.transcoder.image.PNGTranscoder
import org.jsoup.Jsoup
import org.jsoup.nodes.{Element, Document}
import org.jsoup.parser.Parser
import rx.lang.scala._
import scala.collection.JavaConversions.asScalaIterator
import scala.collection.immutable.Range
import scala.util.matching.Regex
import org.apache.batik.transcoder

/**
 *
 * Something like
 *   countdown (numberOfCircles) -> map (generateCircle) -> map (svgElement) -> do (appendDoc)
 *
 */

class GeoPicassoRx() {

  // Our model classes

  case class CircleStyle(fill: FillModel, stroke: StrokeModel)
  case class CircleModel(index: Int, cx: Float, cy: Float, r: Float, scaleFactor: Int)
  case class FillModel(color: String, opacity: Float)
  case class StrokeModel(color: String, opacity: Float, strokeWidth: Float)

  object circleTransformer {

    var cxPlus: Float = 0
    var cyPlus: Float = 0
    var scalar: Float = 0

    def cxTransform(cx: Float): Float  = {
      return scalar * cx + cxPlus
    }

    def cyTransform(cy: Float): Float  = {
      return scalar * cy + cyPlus
    }

    def rTransform(r: Float): Float  = {
      return scalar * r
    }

    def transform(whichCircle: CircleModel): CircleModel = {
      return new CircleModel(whichCircle.index, this.cxTransform(whichCircle.cx), this.cyTransform(whichCircle.cy),this.rTransform(whichCircle.r), whichCircle.scaleFactor)
    }

  }


  var numOfCirclesInDiameter = 1000
//  var numOfCirclesInDiameter = 10
  var docFilename = "render"
  var docTag = "5"
  var baseDocFilename = "baseTemplate.svg"

  var doc: Document = null
  var circlesContainer: Element = null

  var fillModels: List[FillModel] = null
  var strokeModels: List[StrokeModel] = null

  var startingCircle: CircleModel = null
  var lastCircle: CircleModel = null

  val circleStream = Observable.apply[CircleModel]((observer: Observer[CircleModel]) => {
    var lastCircleCreated: CircleModel = null
    var nc = this.nextCircle(lastCircleCreated)
    while (nc != null) {
      observer.onNext(nc)
      lastCircleCreated = nc
      nc = this.nextCircle(lastCircleCreated)
    }
    observer.onCompleted()
  }).onBackpressureBuffer


  val mainStream = circleStream
    .map[CircleModel](this.circleTransformed(_))
    .zip[CircleStyle](circleStream.map[Int]((whichCircle: CircleModel) => whichCircle.index).map[CircleStyle](this.styleByIndex))
    .map[Element](this.svgByCircleAndStyle)
    .doOnSubscribe(this.doOnStart)
    .doOnEach(this.appendToDoc(_))
    .doOnCompleted(this.saveDoc)
//    .doOnCompleted(this.savePng)
    .subscribe()

  def nextCircle(basedOnPreviousCircle: CircleModel): CircleModel = {
    val lastCircle = basedOnPreviousCircle
    val firstCircleRadius = 1f / this.numOfCirclesInDiameter / 2
    val firstCircle = this.startingCircle
    if (lastCircle == null) {
      return firstCircle
    }
    def firstLargerCircle(): CircleModel = {
      val r = firstCircle.r * (lastCircle.scaleFactor + 1)
      if (r > 0.5) return null
      val cx = r
      return new CircleModel(lastCircle.index + 1, cx, lastCircle.cy, r, lastCircle.scaleFactor + 1)
    }
    def nextRightCircle(): CircleModel = {
      if (lastCircle.cx + lastCircle.r * 3 > 1)
        return null
      return new CircleModel(lastCircle.index + 1, lastCircle.cx + lastCircle.r * 2, lastCircle.cy, lastCircle.r, lastCircle.scaleFactor)
    }
    val nextSameSizedCircle = nextRightCircle()
    if (nextSameSizedCircle == null)
      return firstLargerCircle()
    return nextSameSizedCircle
  }


  def circleTransformed(whichCircle: CircleModel): CircleModel = {
    return this.circleTransformer.transform(whichCircle)
  }

  /**
   * Return a circle style according to a given index
   */
  def styleByIndex(whichIndex: Int): CircleStyle = {
    var fillModel: FillModel = null
    if (fillModels.length > 0) {
      val fillIndex = whichIndex % this.fillModels.length
      fillModel = this.fillModels(fillIndex)
    }
    var strokeModel: StrokeModel = null
    if (strokeModels.length > 0) {
      val strokeIndex = whichIndex % this.strokeModels.length
      strokeModel = this.strokeModels(strokeIndex)
    }
    return new CircleStyle(fillModel, strokeModel)
  }

  /**
   * Parameter signature that Rx accepts
   */
  def svgByCircleAndStyle(whichCircleAndStyle: Tuple2[CircleModel, CircleStyle]): Element = {
    return this.svgByCircleAndStyle(whichCircleAndStyle._1, whichCircleAndStyle._2)
  }

  /**
   * Maps a circle style and model to a corresponding svg element
   */
  def svgByCircleAndStyle(whichCircle: CircleModel, whichStyle: CircleStyle): Element = {
    println(whichCircle)
    val svg: Element = doc.createElement("circle")
    svg.attr("cx", whichCircle.cx.toString())
    svg.attr("cy", whichCircle.cy.toString())
    svg.attr("r", whichCircle.r.toString())
    if (whichStyle.fill != null) {
      svg.attr("fill", whichStyle.fill.color)
      svg.attr("fill-opacity", whichStyle.fill.opacity.toString)
    }
    else
      svg.attr("fill", "none")
    if (whichStyle.stroke != null) {
      svg.attr("stroke", whichStyle.stroke.color)
      svg.attr("stroke-opacity", whichStyle.stroke.opacity.toString)
      svg.attr("stroke-width", whichStyle.stroke.strokeWidth.toString)
    }
    svg.attr("z-index", (9999999 - whichCircle.index).toString())
//    svg.attr("z-index", (whichCircle.index).toString())
    svg.attr("id", "circle$whichCircle.index")
    return svg
  }

  def appendToDoc(element: Element): Unit = {
//    this.circlesContainer.appendChild(element)
    try {
//      element.before(this.circlesContainer.child(0))
      this.circlesContainer.child(0).before(element)
    }
    catch {
      case e: Exception => {
        this.circlesContainer.appendChild(element)
      }
    }
  }

  def saveDoc(): Unit = {
    def aUniqueTag(): String = {
      val today = Calendar.getInstance().getTime()
      val minuteFormat = new SimpleDateFormat("mm")
      val currentMinuteAsString = minuteFormat.format(today)
      return currentMinuteAsString
    }
//    val wholeFilename = "%s%s.svg".format(this.docFilename, aUniqueTag())
    val wholeFilename = "%s%s.svg".format(this.docFilename, this.docTag)
    val savedDoc = new PrintWriter(wholeFilename)
    savedDoc.write(this.doc.toString())
    savedDoc.close()
  }

  def savePng(): Unit = {
    val pngConverter = new PNGTranscoder()
    val svgInput: TranscoderInput = new TranscoderInput(new ByteArrayInputStream(this.doc.toString.getBytes()))
    val svgOutFile = new FileOutputStream("%s%s.png".format(this.docFilename, this.docTag))
    val svgOut= new TranscoderOutput(svgOutFile)
    pngConverter.transcode(svgInput, svgOut)
    svgOutFile.flush()
    svgOutFile.close()
  }

  /**
   * Create our svg object and also generate our transform info from the doc we've decided to read
   * By generate transform info, I mean create something like a mapping from our unit space to our desired space
   */
  def doOnStart(): Unit = {
    def basisObjectFromDoc(): Element = {
      // read our basis template
      val baseDoc = scala.io.Source.fromFile(this.baseDocFilename).mkString
      this.doc = Jsoup.parse(baseDoc, "", Parser.xmlParser())
      // grab our basis object
      val basisObject = this.doc.select("#gpPlacement").get(0)
      this.circlesContainer = this.doc.createElement("g")
      // place in our generated doc our generated circles group instea of this place holder object
      this.doc.select("#gpPlacement").first().replaceWith(this.circlesContainer)
      return basisObject
    }

    def grabNumOfCirclesInDiameter() = {
      this.numOfCirclesInDiameter = Integer.valueOf(this.doc.select(".nCount").first().select("tspan").text())
      this.doc.select(".nCount").remove()
    }

    def initTransformer(fromBasisObject: Element) = {
      val basisCx = if (fromBasisObject.hasAttr("sodipodi:cx")) fromBasisObject.attr("sodipodi:cx").toFloat else fromBasisObject.attr("cx").toFloat
      val basisCy = if (fromBasisObject.hasAttr("sodipodi:cy")) fromBasisObject.attr("sodipodi:cy").toFloat else fromBasisObject.attr("cy").toFloat
      val basisR = if (fromBasisObject.hasAttr("sodipodi:rx")) fromBasisObject.attr("sodipodi:rx").toFloat else fromBasisObject.attr("r").toFloat

      // initialize linear mapping helper object with values from the base template
      val figuredScalar = basisR / this.lastCircle.r
      this.circleTransformer.scalar = figuredScalar
      this.circleTransformer.cxPlus = basisCx - (this.lastCircle.cx * figuredScalar)
      this.circleTransformer.cyPlus = basisCy - (this.lastCircle.cy * figuredScalar)
    }

    def initStyles() = {
      def grabStyle(fromElem: Element, whichStyle: String): String = {
        val styleAttr = fromElem.attr("style")
        if (styleAttr.substring(0, 1).equals("o")) {
          val debugHere = true
          println(debugHere)
        }
        println(styleAttr)
        val styleReg = new Regex(s".*\\b(?<!fill-)$whichStyle\\b:(.*?)(;|$$).*", whichStyle)
        val styleVal = styleReg.findFirstMatchIn(styleAttr).get.group(whichStyle)
        return styleVal
      }
      def grabFill(fromElem: Element) = grabStyle(fromElem, "fill")
      def grabOpacity(fromElem: Element) = {
        try
          grabStyle(fromElem, "opacity").toFloat
        catch {
          case e: Exception => {
            println("PROBLEMO")
            println(e)
            1f
          }
        }
      }
      def createFillModel(fillAndOpacity: (String, Float)): FillModel = {
        fillAndOpacity match {
          case ((fill: String, opacity: Float)) => return new FillModel(fill, opacity)
        }
      }
      def createStrokeModel(fillAndOpacity: (String, Float)): StrokeModel = {
        fillAndOpacity match {
          case ((fill: String, opacity: Float)) => return new StrokeModel(fill, opacity, 0.5f) // todo
        }
      }
      val fillReps: List[Element] = this.doc.select(".fillRep").listIterator().toList
      val fillColors: List[String] = fillReps.map(grabFill(_)).toList
      val fillOpacities: List[Float] = fillReps.map(grabOpacity(_)).toList
      this.fillModels = fillColors.zip(fillOpacities).map(createFillModel).toList
      val strokeReps: List[Element] = this.doc.select(".strokeRep").listIterator().toList
      val strokeColors: List[String] = strokeReps.map(grabFill(_)).toList
      val strokeOpacities: List[Float] = strokeReps.map(grabOpacity(_)).toList
      this.strokeModels = strokeColors.zip(strokeOpacities).map(createStrokeModel).toList
    }

    def initFirstAndLastCircle() = {
      this.startingCircle = new CircleModel(0,
        0.5f / this.numOfCirclesInDiameter,
        0.5f,
        0.5f / this.numOfCirclesInDiameter,
        1)
      this.lastCircle = new CircleModel(-1, 0.5f, 0.5f, 0.5f, -1)
    }

    val basisObject = basisObjectFromDoc()
    grabNumOfCirclesInDiameter()
    initFirstAndLastCircle()
    initTransformer(basisObject)
    initStyles()
  }



}

object GeoPicassoRx {
  def main(args: Array[String]): Unit = {
    new GeoPicassoRx()
//    new testing
  }

}

class testing {
//  val $re1 = "((?:[a-z][a-z0-9_]*))"
//  val $re2="(\\s+)" // # White Space 1
//  val $re3="((?:[a-z][a-z]+))"	// # Word 1
//  val $re4="(\\s+)"	// # White Space 2
//  val $re5="((?:[a-z][a-z0-9_]*))"	// # Variable Name 2

//  val r = ($re1 + $re2 + $re3 + $re4 + $re5).r
  val r2 = new Regex("(\\w*) (\\w*)", "j", "b")
  val result = r2.findFirstMatchIn("joe and bob").get
  val realResult = result.group("j")
  println(realResult)
  val s = "fill:#ff0000;stroke:none"
  val styleReg = new Regex("fill:(.*);.*", "fill")
  val fillVal = styleReg.findFirstMatchIn(s).get.group("fill")
  println(fillVal)
}
