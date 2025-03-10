package jzon.internal

import jzon._

object GenAst {
  // all self-references to impl must be in a Gen.delay to avoid NPE
  private def impl(depth: Int, objects: Boolean): Gen[JsValue] = {
    // objects and arrays should get smaller with depth to avoid infinite recursion
    val size = 0 min (5 - depth)

    lazy val value: Gen[JsValue] = impl(depth + 1, false)
    lazy val entry               = Gen.tupled(Gen.ascii(4), value)

    val obj: Gen[JsValue] = Gen.delay(Gen.list(entry, size)).map(JsObject(_))
    val arr: Gen[JsValue] = Gen.delay(Gen.list(value, size)).map(JsArray(_))

    if (objects)
      Gen.oneOf(obj, arr)
    else
      Gen.oneOf(obj, arr, boo, str, num, nul)
  }

  private val boo: Gen[JsValue] = Gen.boolean.map(JsBoolean(_))
  // because of escaping, strings don't roundtrip in the general case, so stick
  // to a subset we know.
  private val str: Gen[JsValue]  = Gen.alphanumeric(16).map(JsString(_))
  private val num: Gen[JsValue]  = Gen.bigdecimal(128).map(JsNumber(_))
  private val nul: Gen[JsValue]  = Gen.pure(JsNull)
  lazy val ast: Gen[JsValue]     = impl(0, false)
  lazy val objects: Gen[JsValue] = impl(0, true)

}
