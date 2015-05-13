import java.io.{File, PrintWriter}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import org.jsoup.Jsoup
import org.jsoup.nodes.{Element, Document}
import org.jsoup.parser.Parser
import rx.lang.scala._
import scala.collection.immutable.Range

/**
 *
 * Something like
 *   countdown (numberOfCircles) -> map (generateCircle) -> map (svgElement) -> do (appendDoc)
 */

class GeoPicassoRx() {

  // Our model classes

  case class CircleStyle(fill: String, stroke: String, opacity: Float)
  case class CircleModel(index: Int, cx: Float, cy: Float, r: Float, scaleFactor: Int)

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


//  var numOfCirclesInDiameter = 100
  var numOfCirclesInDiameter = 1000
  var docFilename = "render"
  var baseDocFilename = "baseTemplate.svg"
  var scaleFactor = 1

  var doc: Document = null
  var circlesContainer: Element = null

//  var fillColors = Array("red", "green", "blue")
  var fillColors = Array("#ff00ff", "#ffa500", "#0000ff")
//  var fillColors = Array()
//  var strokeColors = Array("black")
  var strokeColors = Array()

  val startingCircle = new CircleModel(0,
    0.5f / numOfCirclesInDiameter,
    0.5f,
    0.5f / numOfCirclesInDiameter,
    1)

  val lastCircle = new CircleModel(-1, 0.5f, 0.5f, 0.5f, -1)


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
    .doOnSubscribe(this.createDoc)
    .doOnEach(this.appendToDoc(_))
    .doOnCompleted(this.saveDoc)
    .subscribe()

  def nextCircle(basedOnPreviousCircle: CircleModel): CircleModel = {
    val lastCircle = basedOnPreviousCircle
    val firstCircleRadius = 1f / this.numOfCirclesInDiameter / 2
    val firstCircle = this.startingCircle
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
    if (lastCircle == null) {
      return firstCircle
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
    var fillColor: String = null
    if (fillColors.length > 0) {
      val fillIndex = whichIndex % this.fillColors.length
      fillColor = this.fillColors(fillIndex)
    }
    var strokeColor: String = null
    if (strokeColors.length > 0) {
      val strokeIndex = whichIndex % this.strokeColors.length
      strokeColor = this.strokeColors(strokeIndex)
    }
    return new CircleStyle(fillColor, strokeColor, 0.5f)
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
    svg.attr("opacity", whichStyle.opacity.toString)
    if (whichStyle.fill != null)
      svg.attr("fill", whichStyle.fill)
    else
      svg.attr("fill", "none")
    if (whichStyle.stroke != null) {
      svg.attr("stroke", whichStyle.stroke)
      svg.attr("stroke-width", "1")
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
    val wholeFilename = "%s%s.svg".format(this.docFilename, "3")
    val savedDoc = new PrintWriter(wholeFilename)
    savedDoc.write(this.doc.toString())
    savedDoc.close()
  }

  /**
   * Create our svg object and also generate our transform info from the doc we've decided to read
   * By generate transform info, I mean create something like a mapping from our unit space to our desired space
   */
  def createDoc(): Unit = {
    val baseDoc = scala.io.Source.fromFile(this.baseDocFilename).mkString
    this.doc = Jsoup.parse(baseDoc, "", Parser.xmlParser())
    this.circlesContainer = this.doc.createElement("g")
    val basisObject = this.doc.select("#gpPlacement").get(0)
    // one value of our desired mapping
    val basisCx = basisObject.attr("sodipodi:cx").toFloat
    val basisCy = basisObject.attr("sodipodi:cy").toFloat
    val basisR = basisObject.attr("sodipodi:rx").toFloat

    val figuredScalar = basisR / this.lastCircle.r
    this.circleTransformer.scalar = figuredScalar
    this.circleTransformer.cxPlus = basisCx - (this.lastCircle.cx * figuredScalar)
    this.circleTransformer.cyPlus = basisCy - (this.lastCircle.cy * figuredScalar)

//    this.doc.select("svg").first().appendChild(this.circlesContainer)
    this.doc.select("#gpPlacement").first().replaceWith(this.circlesContainer)
  }

}

object GeoPicassoRx {
  def main(args: Array[String]): Unit = {
    new GeoPicassoRx()
  }
}