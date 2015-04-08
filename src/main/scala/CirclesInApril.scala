import java.io._
import org.jsoup.nodes.{Element, Document}

class CirclesInApril {

  println("@CirclesInApril")
  var FILE_NAME = "CirclesInApril.svg"
  var f: Function[Double, Double] = null
  var doc = createStartDocument()

  def createStartDocument(): Document = {
    var doc: Document = new Document("")
    var svg: Element = doc.createElement("svg")
    doc.appendChild(svg)
    return doc
  }


  def fromFunction(f: Function[Double, Double]): CirclesInApril = {
    this.f = f
    return this
  }

  def drawShape(): CirclesInApril = {
    return this
  }

  def saveDoc(): CirclesInApril = {
    val savedDoc = new PrintWriter(new File(FILE_NAME))
    savedDoc.write(this.doc.toString())
    savedDoc.close()
    return this
  }



  drawShape().fromFunction(null).saveDoc()




}

object CirclesInApril {
  def main(args: Array[String]) {
    new CirclesInApril();
  }
}