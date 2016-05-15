package net.ladstatt.sudoku

import org.junit.Assert._
import org.junit.Test
import org.opencv.core.Mat

import scala.util.{Failure, Try}

/**
  * Created by lad on 27.04.14.
  */
class HistoryTest {

  @Test
  def invalidSCell() = {
    Try {
      SCell(new Mat, null)
      fail()
    } match {
      case Failure(e: AssertionError) =>
      case _ => fail("should throw an AssertionError")
    }
  }

  @Test
  def invalidSCell2() = {
    Try {
      SCell(new Mat, null)
      fail()
    } match {
      case Failure(e: AssertionError) =>
      case _ => fail("should throw an AssertionError")
    }
  }


}
