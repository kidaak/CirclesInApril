import java.io.{FileOutputStream, ByteArrayInputStream, File, PrintWriter}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import org.apache.batik.transcoder.{TranscoderOutput, TranscoderInput}
import org.apache.batik.transcoder.image.{JPEGTranscoder, PNGTranscoder}
import org.jsoup.Jsoup
import org.jsoup.nodes.{Element, Document}
import org.jsoup.parser.Parser
import rx.lang.scala._
import scala.util.matching.Regex
import org.json._

/**
 * TODO:
 * placement
 * polygons
 */


/**
 *
 * Something like
 *   countdown (numberOfCircles) -> map (generateCircle) -> map (svgElement) -> do (appendDoc)
 *
 */

class GeoPicassoRx(contextInfoSerialized: JSONObject) {

  // Our globals
  val docFilename = "render"
  val docTag = "7"
  val baseDocFilename = "baseTemplate.svg"
  val generateFolderName = "generated"
  var startingCircle: CircleModel = null
  var lastCircle: CircleModel = null
  var outputDoc: Document = null
  var circlesContainer: Element = null

  // Our model classes
  case class CircleStyle(fill: FillModel, stroke: StrokeModel)
  case class CircleModel(index: Int, cx: Float, cy: Float, r: Float, scaleFactor: Int) {
    def diameter = this.r * 2
  }
  case class FillModel(color: String, opacity: Float)
  case class StrokeModel(color: String, opacity: Float, strokeWidth: Float)

  object contextInfo {
    val name = contextInfoSerialized.getString("name")
    val circlesAlongX = contextInfoSerialized.getInt("circlesAlongX")
    // fills
    private val fillsSerialized = contextInfoSerialized.getJSONArray("fills")
    val fillModels = (0 until fillsSerialized.length()).toList.map((i: Int) => {
      val fillSerialized = fillsSerialized.getJSONObject(i)
      new FillModel(fillSerialized.getString("color"), fillSerialized.getDouble("opacity").toFloat)
    })
    // strokes
    private val strokesSerialized = contextInfoSerialized.getJSONArray("strokes")
    val strokeModels = (0 until strokesSerialized.length()).toList.map((i: Int) => {
      val strokeSerialized = strokesSerialized.getJSONObject(i)
      new StrokeModel(strokeSerialized.getString("color"), strokeSerialized.getDouble("opacity").toFloat, strokeSerialized.getDouble("width").toFloat)
    })
    val scale = contextInfoSerialized.getDouble("scale").toFloat
    val left: Option[Float] = try {
      Option(contextInfoSerialized.getDouble("left").toFloat)
    } catch {
      case _ => None
    }
    val top: Option[Float] = try {
      Option(contextInfoSerialized.getDouble("top").toFloat)
    } catch {
      case _ => None
    }
    val width = contextInfoSerialized.getInt("width").toInt
    val height = contextInfoSerialized.getInt("height").toInt
  }

  /**
   * Responsible for providing functions to transform unit values to linear transformed values for proper placement
   */
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

  /**
   * For applying a transform to our group of circles
   * Move the left edge, as a percent, from 0 at the start, to 100 at the max of our width
   * Similarly with the top
   */
  object matrixApplier {
    val width = contextInfo.width
    val height = contextInfo.height
    val scale = contextInfo.scale

    val leftPercentWise = contextInfo.left
    val topPercentWise = contextInfo.top
    val desiredFinalRadius = circleTransformer.rTransform(lastCircle.r) * scale
    // left
    val scaledCx = circleTransformer.cxTransform(lastCircle.cx)
    val scaledAndTransformedCx = scaledCx * scale
    val leftOffset = leftPercentWise match {
      case None => (scaledAndTransformedCx - scaledCx) * -1
      case _ => {
          val desiredFinalLeft = (width * leftPercentWise.get)
          val desiredFinalCx = desiredFinalLeft + desiredFinalRadius
          ((scaledAndTransformedCx - scaledCx) + (scaledCx - desiredFinalCx)) * -1
      }
    }
    //top
    val scaledCy = circleTransformer.cyTransform(lastCircle.cy)
    val scaledAndTransformedCy = scaledCy * scale
    val topOffset = topPercentWise match {
      case None => (scaledAndTransformedCy - scaledCy) * -1
      case _ => {
        val desiredFinalTop = (height * topPercentWise.get)
        val desiredFinalCy = desiredFinalTop + desiredFinalRadius
        ((scaledAndTransformedCy - scaledCy) + (scaledCy - desiredFinalCy)) * -1
      }
    }
    val transformVal = s"matrix(${scale},0,0,${scale},${leftOffset},${topOffset})"
    def applyToElement(whichElement: Element) = {
      whichElement.attr("transform", transformVal)
    }
  }

  val circleStream = Observable.apply[CircleModel]((observer: Observer[CircleModel]) => {
    def someRecursion(lastCircleCreated: Option[CircleModel]): Unit = {
      val nextCircleCreated = this.nextCircle(lastCircleCreated)
      nextCircleCreated match {
        case None => observer.onCompleted()
        case _ => {
          observer.onNext(nextCircleCreated.get)
          someRecursion(nextCircleCreated)
        }
      }
    }
    someRecursion(None)
  }).onBackpressureBuffer

  val mainStream = circleStream
    .map[CircleModel](this.circleTransformed(_))
    .zip[CircleStyle](circleStream.map[Int]((whichCircle: CircleModel) => whichCircle.index).map[CircleStyle](this.styleByIndex))
    .map[Element](this.svgByCircleAndStyle)
    .doOnSubscribe(this.doOnStart)
    .doOnEach(this.appendToDoc(_))
    .doOnCompleted(this.saveDoc)
//    .doOnCompleted(this.savePng)
    .doOnCompleted(this.saveJpg)
    .subscribe()

  def nextCircle(basedOnPreviousCircle: Option[CircleModel]): Option[CircleModel] = {
    val firstCircle = this.startingCircle
    if (basedOnPreviousCircle == None) {
      return Some(firstCircle)
    }
    val lastCircle = basedOnPreviousCircle.get
    def firstLargerCircle(): Option[CircleModel] = {
      val r = firstCircle.r * (lastCircle.scaleFactor + 1)
      if (r > 0.5) return None
      val cx = r
      return Some(new CircleModel(lastCircle.index + 1, cx, lastCircle.cy, r, lastCircle.scaleFactor + 1))
    }
    def nextRightCircle(): Option[CircleModel] = {
      if (lastCircle.cx + lastCircle.r * 3 > 1)
        return None
      return Some(new CircleModel(lastCircle.index + 1, lastCircle.cx + lastCircle.r * 2, lastCircle.cy, lastCircle.r, lastCircle.scaleFactor))
    }
    val nextSameSizedCircle = nextRightCircle()
//    if (nextSameSizedCircle == None)
//      return Some(firstLargerCircle())
//    return Some(nextSameSizedCircle)
    return nextSameSizedCircle match {
      case None => return firstLargerCircle()
      case _ => return nextSameSizedCircle
    }
  }


  def circleTransformed(whichCircle: CircleModel): CircleModel = {
    return this.circleTransformer.transform(whichCircle)
  }

  /**
   * Return a circle style according to a given index
   */
  def styleByIndex(whichIndex: Int): CircleStyle = {
    var fillModel: FillModel = null
    val fillModels = this.contextInfo.fillModels
    val strokeModels = this.contextInfo.strokeModels
    if (fillModels.length > 0) {
      val fillIndex = whichIndex % fillModels.length
      fillModel = fillModels(fillIndex)
    }
    var strokeModel: StrokeModel = null
    if (strokeModels.length > 0) {
      val strokeIndex = whichIndex % strokeModels.length
      strokeModel = strokeModels(strokeIndex)
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
    val svg: Element = this.outputDoc.createElement("circle")
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
    savedDoc.write(this.outputDoc.toString())
    savedDoc.close()
  }

  def savePng(): Unit = {
    val pngConverter = new PNGTranscoder()
    val svgInput: TranscoderInput = new TranscoderInput(new ByteArrayInputStream(this.outputDoc.toString.getBytes()))
    val svgOutFile = new FileOutputStream("%s%s.png".format(this.docFilename, this.docTag))
    val svgOut= new TranscoderOutput(svgOutFile)
    pngConverter.transcode(svgInput, svgOut)
    svgOutFile.flush()
    svgOutFile.close()
  }

  def saveJpg(): Unit = {
//    def stripOutside(): Unit = {
//      val ourGroupContainer = this.docInfo.outputDoc.select("#layer1")
//      ourGroupContainer.empty()
//      ourGroupContainer.add(this.docInfo.circlesContainer)
//    }
//    stripOutside()
    val jpgConverter = new JPEGTranscoder()
    jpgConverter.addTranscodingHint(JPEGTranscoder.KEY_QUALITY, 1f)
    val svgInput: TranscoderInput = new TranscoderInput(new ByteArrayInputStream(this.outputDoc.toString.getBytes()))
    val svgOutFile = new FileOutputStream(s"generated/${contextInfo.name}.jpg")
    val svgOut= new TranscoderOutput(svgOutFile)
    jpgConverter.transcode(svgInput, svgOut)
    svgOutFile.flush()
    svgOutFile.close()
  }


  /**
   * Create our svg object and also generate our transform info from the doc we've decided to read
   * By generate transform info, I mean create something like a mapping from our unit space to our desired space
   */
  def doOnStart(): Unit = {

    def initTransformer() = {
      """
      val basisCx = if (fromBasisObject.hasAttr("sodipodi:cx")) fromBasisObject.attr("sodipodi:cx").toFloat else fromBasisObject.attr("cx").toFloat
      val basisCy = if (fromBasisObject.hasAttr("sodipodi:cy")) fromBasisObject.attr("sodipodi:cy").toFloat else fromBasisObject.attr("cy").toFloat
      val basisR = if (fromBasisObject.hasAttr("sodipodi:rx")) fromBasisObject.attr("sodipodi:rx").toFloat else fromBasisObject.attr("r").toFloat

      // initialize linear mapping helper object with values from the base template
      val figuredScalar = basisR / this.lastCircle.r
      this.circleTransformer.scalar = figuredScalar
      this.circleTransformer.cxPlus = basisCx - (this.lastCircle.cx * figuredScalar)
      this.circleTransformer.cyPlus = basisCy - (this.lastCircle.cy * figuredScalar)
      """
      // ASSUMPTION: height < width
      val figuredScalar = this.contextInfo.height / 2 / this.lastCircle.r
      this.circleTransformer.scalar = figuredScalar
      this.circleTransformer.cxPlus = this.contextInfo.width / 2 - (this.lastCircle.cx * figuredScalar)
      this.circleTransformer.cyPlus = this.contextInfo.height / 2 - (this.lastCircle.cy * figuredScalar)
    }

    def createOutputSvg(): Unit = {
      //  val circlesContainer = this.outputDoc.createElement("g")
      this.outputDoc = Jsoup.parse("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><svg xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:cc=\"http://creativecommons.org/ns#\" xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:svg=\"http://www.w3.org/2000/svg\" xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\">",  "", Parser.xmlParser())
      this.outputDoc.select("svg").first().attr("width", this.contextInfo.width.toString)
      this.outputDoc.select("svg").first().attr("height", this.contextInfo.height.toString)
      // and create our circles container from it
      this.circlesContainer = this.outputDoc.createElement("g")
      this.outputDoc.select("svg").first().appendChild(this.circlesContainer)

    }

    def initFirstAndLastCircle() = {
      this.startingCircle = new CircleModel(0,
        0.5f / this.contextInfo.circlesAlongX,
        0.5f,
        0.5f / this.contextInfo.circlesAlongX,
        1)
      this.lastCircle = new CircleModel(-1, 0.5f, 0.5f, 0.5f, -1)
    }

    def applyGroupMatrixTransform() = {
      this.matrixApplier.applyToElement(this.circlesContainer)
    }

    createOutputSvg()
    initFirstAndLastCircle()
    initTransformer()
    applyGroupMatrixTransform()


  }

}


/**
 * Generates a GeoPicasso stream or "run" for each entry in our json request object
 */
class GeoPicassoMetaRx {
  //  val contextInfoStream: Observable[JSONObject] = new JSONArray(scala.io.Source.fromFile("/generated/requests.json").mkString)
  val contextInfoStream: Observable[JSONObject] = Observable.apply[JSONObject]((observer: Observer[JSONObject]) => {
    val requestsSerialized = new JSONArray(new Minify().minify(scala.io.Source.fromFile("generated/requests.json").mkString))
      (0 until requestsSerialized.length()) foreach {(i: Int) => {
          observer.onNext(requestsSerialized.getJSONObject(i))
      }}
  })
  contextInfoStream.doOnEach((contextInfo: JSONObject) => {
    new GeoPicassoRx(contextInfo)
//    Observable.
  }).subscribe()
}

object GeoPicassoRx {
  def main(args: Array[String]): Unit = {
//    new GeoPicassoRx()
    new GeoPicassoMetaRx()
//    new testing
  }

}

