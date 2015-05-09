/**
 * Created by ben on 4/25/15.
 */
class Testing() {

  val s = "translate(-171.42857,192.85714)"
//  val r = """^(tom)+( and jerry 5)$""".r
  val r = """([+-]?\d*\.\d+)(?![-+0-9\.])""".r
  val x, y, _ = r.findAllIn(s).toList
  println(x)
  println(y)

}
//
//new Testing()


