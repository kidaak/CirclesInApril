import java.io.{PrintWriter, File}

import org.jsoup.nodes.{Element, Document}

class GeoPicasso() {

  var FILE_NAME = "GeoPicasso.svg"
  var doc: Document = null
  var colorResolver: ColorResolver = null
  var _circlesAddedToDocument = 0


  def createStartDocument(): Document = {
    var doc: Document = new Document("")
    var svg: Element = doc.createElement("svg")
    doc.appendChild(svg)
    return doc
  }

  def withStandardStartDocument(): GeoPicasso = {
    this.doc = this.createStartDocument()
    return this
  }

  def withStandardColorResolver(): GeoPicasso = {
    this.colorResolver = new ColorResolver()
    return this
  }

  def andSaved(): GeoPicasso = {
    val savedDoc = new PrintWriter(new File(FILE_NAME))
    savedDoc.write(this.doc.toString())
    savedDoc.close()
    return this
  }

  def executed(): GeoPicasso = {
    val ourCircleGenerator: CircleGenerator = new CircleGenerator()
    while (ourCircleGenerator.hasNext()) {
      this.addCircleToSvg(ourCircleGenerator.getNext())

    }

    return this
  }

  // private helpers

  def addCircleToSvg(circle: Circle) = {
    val newCircle: Element = doc.createElement("circle")
    val scale = 100.0
    newCircle.attr("cx", (circle.cx * scale).toString())
    newCircle.attr("cy", (circle.cy * scale).toString())
    newCircle.attr("r", (circle.d * scale / 2).toString())
    newCircle.attr("fill", this.colorResolver.getNext())
    newCircle.attr("opacity", "0.5")
    newCircle.attr("z-index", (9999999 - this._circlesAddedToDocument).toString())
//    doc.appendChild(newCircle)
//    doc.select("svg").add(newCircle)
//    doc.select("svg").get(0).appendChild(newCircle)
    try {
      doc.select("svg").get(0).select("circle").first().before(newCircle)
    }
    catch {
      case e: NullPointerException => {
        doc.select("svg").get(0).appendChild(newCircle)
      }
    }
    this._circlesAddedToDocument += 1
  }

  // inner classes
  class ColorResolver() {
    val firstColor = "#ff00ff"
    val secondColor = "#ffa500"
    val thirdColor = "#0000ff"

    var _i = 0
    val colors = Array(firstColor, secondColor, thirdColor)

    def getNext(): String = {
      val nextColor = this.colors(this._i)
      if (this._i >= this.colors.length - 1)
        this._i = 0
      else
        this._i += 1
      return nextColor
    }

  }


  class CircleGenerator() {

    var diameterLength: Int = 0
//    var circlesGenerated: Int = 0
    var maxCircles: Int = 0
    var _hasNext: Boolean = false
    var lastCircleCreated: Circle = null
//    var newCircle: Circle = null
    var _lineWidthIndex: Float = 0
    var _maxLineWidth: Float = 0

    def init() = {
      this.diameterLength = 100 // how many circles make up a diameter
//      this.circlesGenerated = 0
      this.maxCircles = diameterLength
      this._hasNext = true
      this.lastCircleCreated = null
      this._lineWidthIndex = 0
      this._maxLineWidth = 1
    }
    //    var maxCircles =

    def hasNext(): Boolean = {
      return this._hasNext
    }

    def createNextCircle(): Circle  = {
      var nextCircle: Circle = null
      val circleInLine = this.createCircleToRightOf(this.lastCircleCreated)

      println (this._lineWidthIndex + circleInLine.width())

      if (((math floor (this._lineWidthIndex + circleInLine.width()) * 100) / 100) <= _maxLineWidth) {
        nextCircle = circleInLine
        println("small")
      }
      else {
        println("big")
        this._lineWidthIndex = 0
        val circleLargerThanPrevious = this.createCircleLargerThan(this.lastCircleCreated)
//        if (circleLargerThanPrevious.width() <= this._maxLineWidth)
        if (((math floor (this._lineWidthIndex + circleLargerThanPrevious.width()) * 100) / 100) <= _maxLineWidth)
          nextCircle = circleLargerThanPrevious
      }
      if (nextCircle == null)
        this._hasNext = false
      else {
        this._lineWidthIndex += nextCircle.width()
      }
      return nextCircle
    }

    def createCircleToRightOf(circle: Circle): Circle = {
      if (circle == null)
        return new Circle(1.0f / this.diameterLength / 2, 0, 1.0f / this.diameterLength)
      var _ctro: Circle = circle.copy()
      _ctro.shift(circle.width())
      return _ctro
    }

    def createCircleLargerThan(circle: Circle): Circle = {
      var _clt: Circle = circle.copy()
      _clt.d = _clt.d + this._maxLineWidth / this.diameterLength
      _clt.cx = _clt.d / 2
      return _clt
    }

    def getNext(): Circle = {
      var _next = this.lastCircleCreated
      if (_next == null) {
        this.lastCircleCreated = this.createNextCircle()
        _next = this.lastCircleCreated
      }
      this.lastCircleCreated = this.createNextCircle()
      return _next
    }

    this.init()
  }
  case class Circle(var cx: Float, var cy: Float,  var d: Float) {
    def width(): Float = {
      return d
    }

    def shift(amount: Float): Unit = {
      this.cx += amount
    }
  }
}

object GeoPicasso {
  def main(args: Array[String]): Unit = {
    var ourGeoPicasso: GeoPicasso = new GeoPicasso()
      .withStandardStartDocument()
      .withStandardColorResolver()
      .executed()
      .andSaved()
  }
}

























