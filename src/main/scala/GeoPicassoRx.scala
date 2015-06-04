import java.io.{FileOutputStream, ByteArrayInputStream, File, PrintWriter}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import org.apache.batik.transcoder.{TranscoderOutput, TranscoderInput}
import org.apache.batik.transcoder.image.{JPEGTranscoder, PNGTranscoder}
import org.jsoup.Jsoup
import org.jsoup.nodes.{Element, Document}
import org.jsoup.parser.Parser
import rx.lang.scala._
import scala.collection.mutable
import scala.util.matching.Regex
import org.json._

/**
 * Credit for original initial idea
 * http://www.sievesofchaos.com/
 */


/**
 * TODO:
 * placement
 */


/**
 *
 * Something like
 *    next shape specification -> zip nextShapeKeyVal -> next shape model ->               (zip) next shape style -> next svg element                    ->         (do) append to doc
 *      (values)                       eg: 0            (instance from parameters)
 *
 *
 *
 */

//class GeoPicassoRx(contextInfoSerialized: JSONObject) {
class GeoPicassoRx(contextInfo: ContextInfo) {

  // Our globals
  val docFilename = "render"
  val docTag = "7"
  val baseDocFilename = "baseTemplate.svg"
  val generateFolderName = "generated"
  var startingShape: ShapeModel = null
  var lastShape: ShapeModel = null
  var outputDoc: Document = null
  var shapesContainer: Element = null

  // Our model classes
  case class ShapeStyle(fill: FillStyle, stroke: StrokeStyle)

  class ShapeModel(
    _index: Int,
    _cx: Float,
    _cy: Float,
    _r: Float,
    _scaleFactor: Int) {
    val index = _index
    val cx = _cx
    val cy = _cy
    val r = _r
    val scaleFactor = _scaleFactor
    def diameter = this.r * 2
  }

  case class CircleModel(override val index: Int, override val cx: Float, override val cy: Float, override val r: Float, override val scaleFactor: Int) extends ShapeModel(index, cx, cy, r, scaleFactor) {
  }

  case class PolygonModel(override val index: Int, override val cx: Float, override val cy: Float, override val r: Float, override val scaleFactor: Int, numOfSides: Int, points: List[Tuple2[Float, Float]]) extends ShapeModel(index, cx, cy, r, scaleFactor) {
  }



  object polygonPointsHelper {

    // This could certainly be more elegant. Wishing I could think more like a math guy...
    def createPoint(fromAngle: Float, forWhichPolygon: ShapeModel): Tuple2[Float, Float] = {
      var inWhichQuadrant = 0
      def calculateInnerAngleAndQuadrant(angle: Float): Float = {
        angle match {
          case _ if angle <= 90 => {
            inWhichQuadrant = 1
            angle
          }
          case _ if angle <= 180 => {
            inWhichQuadrant = 2
            180 - angle
          }
          case _ if angle <= 270 => {
            inWhichQuadrant = 3
            angle - 180
          }
          case _ if angle <= 360 => {
            inWhichQuadrant = 4
            360 - angle
          }
        }
      }
      val calcAngle = calculateInnerAngleAndQuadrant(fromAngle)
      val relY = (scala.math.sin(scala.math.toRadians(calcAngle)) * forWhichPolygon.r).toFloat
      val relX = (scala.math.sqrt(scala.math.pow(forWhichPolygon.r, 2) - scala.math.pow(relY, 2))).toFloat
      val createdPoint: Tuple2[Float, Float] = inWhichQuadrant match {
        case 1 => Tuple2(forWhichPolygon.cx + relX, forWhichPolygon.cy + relY)
        case 2 => Tuple2(forWhichPolygon.cx + relX * -1, forWhichPolygon.cy + relY)
        case 3 => Tuple2(forWhichPolygon.cx + relX * -1, forWhichPolygon.cy + relY * -1)
        case 4 => Tuple2(forWhichPolygon.cx + relX, forWhichPolygon.cy + relY * -1)
      }
      return createdPoint
    }

    def pointsFor(whichPolygon: ShapeModel, withNumberOfSides: Int): List[Tuple2[Float, Float]] = {
      var atThetas = mutable.MutableList[Float]()
      // Not scala-ish
      val innerAngle = 360f / withNumberOfSides
      var thetaAngle = 0f
      while (thetaAngle < 360f){
        atThetas.+=(thetaAngle)
        thetaAngle += innerAngle
      }
//      atThetas = atThetas map((innerAngle: Float) => if (innerAngle + 90 < 360) innerAngle + 90 else (innerAngle + 90) - 360)
      atThetas = atThetas map((innerAngle: Float) => if (innerAngle - 90 > 0) innerAngle - 90 else (innerAngle - 90) + 360)
      val atThetasFinal = atThetas.toList

      return atThetasFinal map(createPoint(_, whichPolygon))

    }
  }

  /**
   * Responsible for providing functions to transform unit values to linear transformed values for proper placement
   */
  object shapeTransformer {

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

    def transform(whichShape: ShapeModel): ShapeModel = {
      return new ShapeModel(whichShape.index, this.cxTransform(whichShape.cx), this.cyTransform(whichShape.cy),this.rTransform(whichShape.r), whichShape.scaleFactor)
    }

  }

  /**
   * For applying a transform to our group of shapes
   * Move the left edge, as a percent, from 0 at the start, to 100 at the max of our width
   * Similarly with the top
   */
  object matrixApplier {
    val width = contextInfo.width
    val height = contextInfo.height
    val scale = contextInfo.scale

//    val leftPercentWise = contextInfo.left
//    val topPercentWise = contextInfo.top
    val desiredFinalRadius = shapeTransformer.rTransform(lastShape.r) * scale
    // left
    val scaledCx = shapeTransformer.cxTransform(lastShape.cx)
    val scaledAndTransformedCx = scaledCx * scale
    val leftOffset = contextInfo.left match {
      case None => (scaledAndTransformedCx - scaledCx) * -1
      case _ => {
        contextInfo.left.get
      }
    }
    //top
    val scaledCy = shapeTransformer.cyTransform(lastShape.cy)
    val scaledAndTransformedCy = scaledCy * scale
    val topOffset = contextInfo.top match {
      case None => (scaledAndTransformedCy - scaledCy) * -1
      case _ => {
        contextInfo.top.get
      }
    }
    val transformVal = s"matrix(${scale},0,0,${scale},${leftOffset},${topOffset})"
    def applyToElement(whichElement: Element) = {
      whichElement.attr("transform", transformVal)
    }
  }

  val shapeStream = Observable.apply[ShapeModel]((observer: Observer[ShapeModel]) => {
    def someRecursion(lastShapeCreated: Option[ShapeModel]): Unit = {
      val nextShapeCreated = this.nextShape(lastShapeCreated)
      nextShapeCreated match {
        case None => observer.onCompleted()
        case _ => {
          observer.onNext(nextShapeCreated.get)
          someRecursion(nextShapeCreated)
        }
      }
    }
    someRecursion(None)
  }).onBackpressureBuffer

//  *    next shape specification -> zip nextShapeKeyVal -> next shape model ->               (zip) next shape style -> next svg element                    ->         (do) append to doc
//    *      (values)                       eg: 0            (instance from parameters)

  val mainStream = shapeStream
    .map[ShapeModel](this.shapeTransformed(_))
    .zip[Int](shapeStream.map[Int]((whichShape: ShapeModel) => whichShape.index).map[Int](this.shapeKeyByIndex))
    .map[Tuple2[Int, ShapeModel]]((shapeAndKey: Tuple2[ShapeModel, Int]) => {
      Tuple2(shapeAndKey._2, shapeAndKey._1)
    })
    .map[ShapeModel](this.shapeByKey)
    .zip[ShapeStyle](shapeStream.map[Int]((whichShape: ShapeModel) => whichShape.index).map[ShapeStyle](this.styleByIndex))
    .map[Element](this.svgByShapeAndStyle)
    .doOnSubscribe(this.doOnStart)
    .doOnEach(this.appendToDoc(_))
    .doOnCompleted(this.saveDoc)
    .doOnCompleted(this.savePng)
    .doOnCompleted(this.saveJpg)
    .subscribe()

  def nextShape(basedOnPreviousShape: Option[ShapeModel]): Option[ShapeModel] = {
    val firstShape = this.startingShape
    if (basedOnPreviousShape == None) {
      return Some(firstShape)
    }
    val lastShape = basedOnPreviousShape.get
    def firstLargerShape(): Option[ShapeModel] = {
      val r = firstShape.r * (lastShape.scaleFactor + 1)
      if (r > 0.5) return None
      val cx = r
      return Some(new ShapeModel(lastShape.index + 1, cx, lastShape.cy, r, lastShape.scaleFactor + 1))
    }
    def nextRightShape(): Option[ShapeModel] = {
      if (lastShape.cx + lastShape.r * 3 > 1)
        return None
      return Some(new ShapeModel(lastShape.index + 1, lastShape.cx + lastShape.r * 2, lastShape.cy, lastShape.r, lastShape.scaleFactor))
    }
    val nextSameSizedShape = nextRightShape()
//    if (nextSameSizedShape == None)
//      return Some(firstLargerShape())
//    return Some(nextSameSizedShape)
    return nextSameSizedShape match {
      case None => return firstLargerShape()
      case _ => return nextSameSizedShape
    }
  }


  def shapeTransformed(whichShape: ShapeModel): ShapeModel = {
    return this.shapeTransformer.transform(whichShape)
  }

  /**
   * Return a shape style according to a given index
   */
  def styleByIndex(whichIndex: Int): ShapeStyle = {
    var fillModel: FillStyle = null
    val fillStyles = this.contextInfo.fillStyles
    val strokeStyles = this.contextInfo.strokeStyles
    if (fillStyles.length > 0) {
      val fillIndex = whichIndex % fillStyles.length
      fillModel = fillStyles(fillIndex)
    }
    var strokeModel: StrokeStyle = null
    if (strokeStyles.length > 0) {
      val strokeIndex = whichIndex % strokeStyles.length
      strokeModel = strokeStyles(strokeIndex)
    }
    return new ShapeStyle(fillModel, strokeModel)
  }

  def shapeKeyByIndex(whichIndex: Int): Int = {
    val shapesSequence = this.contextInfo.shapesSequence
    val shapeIndex = whichIndex % shapesSequence.length
    return shapesSequence(shapeIndex)
  }

  def shapeByKey(whichKey: Int, whichShape: ShapeModel): ShapeModel = {
    return whichKey match {
      case ContextInfo.CIRCLE => new CircleModel(whichShape.index, whichShape.cx, whichShape.cy, whichShape.r, whichShape.scaleFactor)
      case numberOfPolygonSides: Int => new PolygonModel(whichShape.index, whichShape.cx, whichShape.cy, whichShape.r, whichShape.scaleFactor, numberOfPolygonSides, polygonPointsHelper.pointsFor(whichShape, numberOfPolygonSides))
    }
  }

  /**
   * Parameter signatures that Rx accepts
   */
  def svgByShapeAndStyle(whichShapeAndStyle: Tuple2[ShapeModel, ShapeStyle]): Element = {
    return this.svgByShapeAndStyle(whichShapeAndStyle._1, whichShapeAndStyle._2)
  }

  def shapeByKey(whichShapeAndKey: Tuple2[Int, ShapeModel]): ShapeModel = {
    return this.shapeByKey(whichShapeAndKey._1, whichShapeAndKey._2)
  }

  /**
   * Maps a shape style and model to a corresponding svg element
   */
  def svgByShapeAndStyle(whichShape: ShapeModel, whichStyle: ShapeStyle): Element = {
    println(whichShape)
    var svgElement: Element = null
    whichShape match {
      case circle: CircleModel => {
        svgElement = this.outputDoc.createElement("circle")
        svgElement.attr("cx", circle.cx.toString())
        svgElement.attr("cy", circle.cy.toString())
        svgElement.attr("r", circle.r.toString())
      }
      case polygon: PolygonModel => {
        def svgFormattedPoints(forWhichPoints: List[Tuple2[Float, Float]]) = {
          val simplyCommaSeparated = forWhichPoints.map((point: Tuple2[Float, Float]) => {
            var csv = s"${point._1}, ${point._2}"
            if (!point.equals(forWhichPoints(forWhichPoints.length - 1))) csv = csv +  ","
            csv
          }).mkString
          simplyCommaSeparated
        }
        svgElement = this.outputDoc.createElement("polygon")
        svgElement.attr("points", svgFormattedPoints(polygon.points))
      }
    }
    if (whichStyle.fill != null) {
      svgElement.attr("fill", whichStyle.fill.color)
      svgElement.attr("fill-opacity", whichStyle.fill.opacity.toString)
    }
    else
      svgElement.attr("fill", "none")
    if (whichStyle.stroke != null) {
      svgElement.attr("stroke", whichStyle.stroke.color)
      svgElement.attr("stroke-opacity", whichStyle.stroke.opacity.toString)
      svgElement.attr("stroke-width", whichStyle.stroke.strokeWidth.toString)
    }
    svgElement.attr("z-index", (9999999 - whichShape.index).toString())
//    svgElement.attr("z-index", (whichShape.index).toString())
    svgElement.attr("id", "shape$whichShape.index")
    return svgElement
  }

  def appendToDoc(element: Element): Unit = {
    try {
      this.shapesContainer.child(0).before(element)
    }
    catch {
      case e: Exception => {
        this.shapesContainer.appendChild(element)
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
//      ourGroupContainer.add(this.docInfo.shapesContainer)
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
      // ASSUMPTION: height < width
      val figuredScalar = this.contextInfo.height / 2 / this.lastShape.r
      this.shapeTransformer.scalar = figuredScalar
      this.shapeTransformer.cxPlus = this.contextInfo.width / 2 - (this.lastShape.cx * figuredScalar)
      this.shapeTransformer.cyPlus = this.contextInfo.height / 2 - (this.lastShape.cy * figuredScalar)
    }

    def createOutputSvg(): Unit = {
      //  val shapesContainer = this.outputDoc.createElement("g")
      this.outputDoc = Jsoup.parse("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><svg xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:cc=\"http://creativecommons.org/ns#\" xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:svg=\"http://www.w3.org/2000/svg\" xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\">",  "", Parser.xmlParser())
      this.outputDoc.select("svg").first().attr("width", this.contextInfo.width.toString)
      this.outputDoc.select("svg").first().attr("height", this.contextInfo.height.toString)
      def backgroundHack(): Element = {
        val rect = this.outputDoc.createElement("rect")
        rect.attr("x", "0")
        rect.attr("y", "0")
        rect.attr("width", this.contextInfo.width.toString)
        rect.attr("height", this.contextInfo.height.toString)
        rect.attr("fill", this.contextInfo.background)
        return rect
      }
      this.outputDoc.select("svg").first().appendChild(backgroundHack())
      // and create our shapes container from it
      this.shapesContainer = this.outputDoc.createElement("g")
      this.outputDoc.select("svg").first().appendChild(this.shapesContainer)

    }

    def initFirstAndLastShape() = {
    // base all of our calculations on a circle at origin
      this.startingShape = new ShapeModel(0,
        0.5f / this.contextInfo.shapesAlongX,
        0.5f,
        0.5f / this.contextInfo.shapesAlongX,
        1)
      this.lastShape = new ShapeModel(-1, 0.5f, 0.5f, 0.5f, -1)
    }

    def applyGroupMatrixTransform() = {
      this.matrixApplier.applyToElement(this.shapesContainer)
    }

    createOutputSvg()
    initFirstAndLastShape()
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
  contextInfoStream.doOnEach((contextInfoSerialized: JSONObject) => {
    new GeoPicassoRx(ContextInfo.generateDefault(contextInfoSerialized))
//    Observable.
  })
  .onBackpressureBuffer
  .subscribe()
}

case class FillStyle(color: String, opacity: Float)
case class StrokeStyle(color: String, opacity: Float, strokeWidth: Float)

class ContextInfo(val name: String,
                  val background: String,
                  val shapesAlongX: Int,
                  val shapesSequence: List[Int],
                  val fillStyles: List[FillStyle],
                  val strokeStyles: List[StrokeStyle],
                  val scale: Float,
                  val left: Option[Float],
                  val top: Option[Float],
                  val width: Int,
                  val height: Int
                   ) {
}

object ContextInfo {
  final val CIRCLE = 0
  final val TRIANGLE = 3
  final val SQUARE = 4

  object fromSvg {
    val svgDoc = Jsoup.parse(scala.io.Source.fromFile("minimizedOutputTemplate.svg").mkString, "", Parser.xmlParser())
    def width: Some[Int] = Some(svgDoc.select("svg").first().attr("width").toInt)
    def height: Some[Int] = Some(svgDoc.select("svg").first().attr("height").toInt)
    var scale = None: Option[Float]
    var top = None: Option[Float]
    var left = None: Option[Float]
    val transformAttr: String = svgDoc.select("#gpPlacement").first().attr("transform")
    transformAttr match {
      case "" => {
        scale = Some(1f)
      }
      case _ => {
        val transformRegex = """(matrix)(\()([+-]?\d*\.\d+)(?![-+0-9\.])(,)(0)(,)(0)(,)([+-]?\d*\.\d+)(?![-+0-9\.])(,)([+-]?\d*\.\d+)(?![-+0-9\.])(,)([+-]?\d*\.\d+)(?![-+0-9\.])(\))""".r
        val regexParsed = transformRegex.findFirstMatchIn(transformAttr).get
        scale = Some(regexParsed.group(3).toFloat)
        left = Some(regexParsed.group(11).toFloat)
        top = Some(regexParsed.group(13).toFloat)
      }
    }

  }

  object fromJson {
    var jsonSource: Some[JSONObject] = Some[JSONObject](null)
    def name = this.jsonSource.get.getString("name")
    def background = try {
      jsonSource.get.getString("background")
    } catch {
      case _ => "white"
    }
    def shapesAlongX = jsonSource.get.getInt("shapesAlongX")
    // shape elements
    private lazy val shapesSerialized = jsonSource.get.getJSONArray("shapes")
    def shapesSequence: List[Int] = (0 until shapesSerialized.length()).toList.map((i: Int) => {
      val shapeSerialized = shapesSerialized.get(i)
      shapeSerialized match {
        case s: String => s match {
          case "circle" => CIRCLE
          case "triangle" => TRIANGLE
          case "square" => SQUARE
        }
        case n: java.lang.Integer => n.toInt
      }
    })
    // fills
    private lazy val fillsSerialized = jsonSource.get.getJSONArray("fills")
    def fillStyles = (0 until fillsSerialized.length()).toList.map((i: Int) => {
      val fillSerialized = fillsSerialized.getJSONObject(i)
      new FillStyle(fillSerialized.getString("color"), fillSerialized.getDouble("opacity").toFloat)
    })
    // strokes
    private lazy val strokesSerialized = jsonSource.get.getJSONArray("strokes")
    def strokeStyles = (0 until strokesSerialized.length()).toList.map((i: Int) => {
      val strokeSerialized = strokesSerialized.getJSONObject(i)
      new StrokeStyle(strokeSerialized.getString("color"), strokeSerialized.getDouble("opacity").toFloat, strokeSerialized.getDouble("width").toFloat)
    })
    //    val scale = contextInfoSerialized.getDouble("scale").toFloat
    def scale = try {
      jsonSource.get.getDouble("scale").toFloat
    } catch {
      case _ => 1f
    }
    def left: Option[Float] = try {
      Option(jsonSource.get.getDouble("left").toFloat)
    } catch {
      case _ => None
    }
    def top: Option[Float] = try {
      Option(jsonSource.get.getDouble("top").toFloat)
    } catch {
      case _ => None
    }
    def width = jsonSource.get.getInt("width").toInt
    def height = jsonSource.get.getInt("height").toInt
  }
  def generateDefault(withJsonSource: JSONObject): ContextInfo = {
    fromJson.jsonSource = Some(withJsonSource)
    return new ContextInfo(
      fromJson.name,
      fromJson.background,
      fromJson.shapesAlongX,
      fromJson.shapesSequence,
      fromJson.fillStyles,
      fromJson.strokeStyles,
      fromSvg.scale.getOrElse[Float](fromJson.scale),
      if (fromSvg.left != None) fromSvg.left else fromJson.left,
      if (fromSvg.top != None) fromSvg.top else fromJson.top,
      fromSvg.width.getOrElse[Int](fromJson.width),
      fromSvg.height.getOrElse[Int](fromJson.height))
//      fromJson.width,
//      fromJson.height)
  }
}

object GeoPicassoRx {
  def main(args: Array[String]): Unit = {
//    new GeoPicassoRx()
    new GeoPicassoMetaRx()
//    new testing
  }

}

