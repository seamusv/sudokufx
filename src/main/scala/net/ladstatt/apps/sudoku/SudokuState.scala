package net.ladstatt.apps.sudoku

import java.util.concurrent.TimeUnit

import net.ladstatt.apps.sudoku.Parameters._
import net.ladstatt.apps.sudoku.SudokuAlgos._
import net.ladstatt.core.CanLog
import net.ladstatt.opencv.OpenCV
import net.ladstatt.opencv.OpenCV._
import org.opencv.core._
import org.opencv.imgproc.Imgproc

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

sealed trait SudokuResult

case class SSuccess(candidate: SCandidate,
                    detectedCells: Cells,
                    solution: SudokuDigitSolution,
                    solutionMat: Mat,
                    solutionCells: Cells) extends SudokuResult {

  def solutionAsString: String = solution.sliding(9, 9).map(new String(_)).mkString("\n")
}

case class SFailure(candidate: SCandidate) extends SudokuResult

object SCandidate {

  def sectorIndizes(i: Int): Set[Int] = {
    val rowSector: Seq[Int] = sectors(row(i) / 3)
    val colSector: Seq[Int] = sectors(col(i) / 3)
    (for {r <- rowSector
          c <- colSector} yield r * 9 + c).toSet -- Set(i)
  }

  def isValid(hitCounts: HitCounts, values: Seq[Int], cap: Int): Boolean =
    values.zipWithIndex.forall {
      case (c, i) => SCandidate.posWellFormed(hitCounts, i, c, cap)
    }

  /*
private def sectorWellFormed(hitCounts: HitCounts, index: SIndex, value: Int): Boolean = {
val rowSector = sectors(row(index) / 3)
val colSector = sectors(col(index) / 3)
val sectorVals =
for {
r <- rowSector if (r != row(index))
c <- colSector if (c != col(index))
(count, num) <- hitCounts(index).zipWithIndex if (count == cap)
} yield num
!sectorVals.contains(value)
}      */

  // searches rows and cols if there exist already the value in the same row or column
  private def rowColWellFormed(hitCounts: HitCounts, i: Int, value: Int, cap: Int): Boolean = {
    // val otherCells = cellRange.filter(u => u != i && ((row(u) == row(i) || col(u) == col(i)) || sectorIndizes(i).contains(u)))
    val otherCells = cellRange.filter(u => u != i && ((row(u) == row(i) || col(u) == col(i))))
    !otherCells.exists(i => hitCounts(i).contains(value) && hitCounts(value) == cap)
  }

  private def posWellFormed(hitCounts: HitCounts, i: SIndex, value: Int, cap: Int): Boolean = {
    value == 0 || rowColWellFormed(hitCounts, i, value, cap) //&& sectorWellFormed(hitCounts, i, value)
  }

}

/**
 *
 * @param nr number of the frame
 * @param frame the frame information itself
 * @param cap how often should a digit be detected before it is considered "stable enough"
 * @param minHits how many digits have to be detected before a solution attempt is executed
 * @param hCounts the individual detection numbers
 * @param digitQuality indicates the best hit for each number
 * @param digitData saves the picture information for the best hit
 */
case class SCandidate(nr: Int,
                      frame: Mat,
                      cap: Int = 8,
                      minHits: Int = 20,
                      // for each of the 81 sudoku cells, there exists a list which depicts how often a certain number
                      // was found in the sudoku, where the index in the list is the number (from 0 to 9, with 0 being
                      // the "empty" cell)
                      hCounts: HitCounts = Array.fill(cellRange.size)(Array.fill[SCount](digitRange.size)(0)),
                      digitQuality: Array[Double] = Array.fill(digitRange.size)(Double.MaxValue),
                      digitData: Array[Option[Mat]] = Array.fill(digitRange.size)(None),
                      blockSizes: Array[Size] = Array.fill(cellCount)(new Size)) extends CanLog {


  def statsAsString(): String =
    s"""$digitQualityAsString
       |$hitCountsAsString
       |""".stripMargin

  def digitQualityAsString: String =
    s"""Quality:
      |--------
      |${digitQuality.map(q => q).mkString("\n")}
      |""".stripMargin

  def hitCountsAsString(): String = {
    s"""Hitcounts:
      |----------
      |
      |${hCounts.map(_.mkString(",")).mkString("\n")}
      |""".stripMargin
  }


  val start = System.nanoTime()

  val frameCorners = mkCorners(frame.size)

  val imageIoChain: ImageIOChain =
    Await.result(for {
      imgIo <- imageIOChain(frame)
    } yield imgIo, Duration(1400, TimeUnit.MILLISECONDS))

  val sudokuCorners: MatOfPoint2f =
    Await.result(for {
      corners <- detectSudokuCorners(imageIoChain.dilated) // if (!detectedCorners.empty)
    } yield corners, Duration(1400, TimeUnit.MILLISECONDS))

  lazy val colorWarped = warp(frame, sudokuCorners, frameCorners)
  lazy val sudokuSize = cellSize(colorWarped.size)

  lazy val warpedToFit = OpenCV.resize(colorWarped, Parameters.sudokuSize)
  lazy val warpedToFitSize = cellSize(warpedToFit.size)

  lazy val rects = cellRange.map(mkRect(_, sudokuSize))
  lazy val warpedToFitRects = cellRange.map(mkRect(_, warpedToFitSize))

  lazy val cellMats: IndexedSeq[Mat] = rects.map(colorWarped.submat(_))
  lazy val cellNumbers: IndexedSeq[Future[SCell]] = cellMats.map(detectCell(_, digitQuality))

  // search on all positions for potential hits (don't count the "empty"/"zero" fields
  // TODO remove, see cellNumbers
  def detectedNumbers(hitCounts: HitCounts): Iterable[SCount] = {
    (for {
      hitcount <- hitCounts
    } yield {
      val filtered = hitcount.drop(1).filter(_ >= cap)
      if (filtered.isEmpty) None else Some(filtered.max)
    }).flatten
  }

  /**
   * This function uses an input image and a detection method to calculate the sudoku.
   *
   * @return
   */
  def calc(): Future[SudokuResult] = {

    // we have to walk two paths here: either we have detected something in the image
    // stream which resembles a sudoku, or we don't and we skip the rest of the processing
    // pipeline
    if (!foundCorners) {
      for {
        detectedCells <- Future.fold(cellNumbers)(Seq[SCell]())((cells, c) => cells ++ Seq(c))
        //dCells <- Future.sequence(cellNumbers)
        //for {detectedCells <- Future.sequence(detectCells(warpedToFit, warpedToFitRects))
        s0 <- updateLibrary(detectedCells)
        _ <- countHits(hCounts, detectedCells.map(_.value))
        //cms <- Future.successful(cellMats)
        //_ <- Future.successful(cms.map(persist(_, new File(s"target/cm${UUID.randomUUID}.png"))))
        //   _ <- persist(colorWarped, new File(s"colorwarped$nr.png"))
        //   _ <- persist(warpedToFit, new File(s"warpedToFit$nr.png"))
        _ <- resetIfInvalidCellsDetected(hCounts, detectedCells.map(_.value))
        someDigitSolution <- computeSolution(hCounts)
        someSolutionCells <- Future.successful(someDigitSolution.map(s => toSolutionCells(s))) //for (solution <- someDigitSolution) yield toSolutionCells(solution))
        withSolution <- paintSolution(colorWarped, detectedCells, someSolutionCells)
        annotatedSolution <- paintCorners(withSolution, detectedCells, someSolutionCells, hCounts)
        unwarped <- Future.successful(warp(annotatedSolution, frameCorners, sudokuCorners))
        solutionMat <- copySrcToDestWithMask(unwarped, imageIoChain.working, unwarped) // copy solution mat to input mat
      } yield {
        if (someDigitSolution.isDefined) {
          val x = s0
          SSuccess(copy(), detectedCells.toArray, someDigitSolution.get, solutionMat, someSolutionCells.get)
        } else {
          SFailure(copy())
        }
      }
      //  someResult = Some(FrameSuccess(detectedCells.toArray, someDigitSolution, someSolutionCells))))
    } else {
      Future.successful(SFailure(copy()))
    }

  }


  def foundCorners: Boolean = {
    sudokuCorners.empty
  }

  def detectSudokuCorners(preprocessed: Mat): Future[MatOfPoint2f] = {
    execFuture {
      extractCurveWithMaxArea(coreFindContours(preprocessed)) match {
        case None => {
          logWarn("Could not detect any curve ... ")
          new MatOfPoint2f()
        }
        case Some((maxArea, c)) => {
          val expectedMaxArea = Imgproc.contourArea(frameCorners) / 30
          val approxCurve = mkApproximation(new MatOfPoint2f(c.toList: _*))
          if (maxArea > expectedMaxArea) {
            if (has4Sides(approxCurve)) {
              val corners = mkSortedCorners(approxCurve)
              if (isSomewhatSquare(corners.toList)) {
                corners
              } else {
                logTrace(s"Detected ${approxCurve.size} shape, but it doesn't look like a sudoku!")
                new MatOfPoint2f()
              }
            } else {
              logTrace(s"Detected only ${approxCurve.size} shape, but need 1x4!")
              new MatOfPoint2f()
            }
          } else {
            logTrace(s"The detected area of interest was too small ($maxArea < $expectedMaxArea).")
            new MatOfPoint2f()
          }
        }
      }
    }

  }

  def computeSolution(hitCounts: HitCounts): Future[Option[SudokuDigitSolution]] =
    Future {
      if (detectedNumbers(hitCounts).size > minHits) solve(mkSudokuMatrix(hitCounts)) else Some(mkIntermediateSudokuMatrix(hitCounts))
    }


  /**
   * paints the solution to the canvas.
   *
   * returns the modified canvas with the solution painted upon.
   */
  // potentially modifies canvas
  private def paintSolution(canvas: Mat, detectedCells: Seq[SCell], someSolution: Option[Cells]): Future[Mat] =
    Future {
      for (solution <- someSolution if (isValid(solution))) {
        time(traverseWithIndex(solution)((cell, i) => if (detectedCells(i).value == 0) copyTo(cell.data, canvas, rects(i))), t => logTrace(s"copied in $t ms"))
      }
      canvas
    }

  // modifies canvas
  private def paintCorners(canvas: Mat, detectedCells: Seq[SCell], someSolution: Option[Cells], hitCounts: HitCounts): Future[Mat] =
    Future {
      if (someSolution.isEmpty) {
        traverseWithIndex(detectedCells)((cell, i) =>
          paintRect(canvas, rects(i), color(hitCounts, i), 3)
        )
      }

      canvas
    }


  // TODO update colors
  def color(hitCounts: HitCounts, i: Int): Scalar = {
    val vals = hitCounts(i)
    val n = vals.max.toDouble
    val s = new Scalar(0, n * 256 / cap, 256 - n * 256 / cap)
    s
  }


  def isValid(solution: Cells): Boolean = {
    solution.foldLeft(0)((acc, s) => acc + s.value) == 405
  }

  private def solve(solutionCandidate: SudokuDigitSolution) = BruteForceSolver.solve(solutionCandidate)

  val qualityFilter: PartialFunction[SCell, Boolean] = {
    case c => (c.value != 0) && (c.quality < digitQuality(c.value)) // lower means "better"
  }

  def updateLibrary(detectedCells: Traversable[SCell]): Future[SCandidate] = execFuture {
    val hits = detectedCells.filter(qualityFilter)
    val grouped: Map[Int, Traversable[SCell]] = detectedCells.groupBy(f => f.value)
    val optimal: Map[Int, SCell] = grouped.map { case (i, cells) => i -> cells.maxBy(c => c.quality)}
    optimal.values.foreach(c => {
      digitData(c.value) = Some(c.data)
      digitQuality(c.value) = c.quality
    })
    this
  }


  // TODO remove
  def countHits(hitCounts: HitCounts, cells: Seq[Int]): Future[Unit] = Future {

    def updateHitCounts(i: SIndex, value: Int): Unit = {
      val hitCountAtPos = hitCounts(i)
      if (hitCountAtPos.max < cap) {
        hitCountAtPos(value) = (1 + hitCountAtPos(value))
      }
    }

    traverseWithIndex(cells)((c, i) => {
      if (SCandidate.posWellFormed(hitCounts, i, c, cap)) {
        updateHitCounts(i, c)
      }
    })
  }


  // TODO remove, create new SCandidate object
  def resetIfInvalidCellsDetected(hitCounts: HitCounts, cells: Seq[Int]): Future[Unit] = Future {
    if (!SCandidate.isValid(hitCounts, cells, cap)) {
      hitCounts.transform(_ => Array.fill[SCount](Parameters.digitRange.size)(0))
      digitData.transform(_ => None)
      digitQuality.transform(_ => Double.MaxValue)
      ()
    }
  }


  def withCap(v: Int) = v == cap

  def mkSudokuMatrix(hitCounts: HitCounts): SudokuDigitSolution = mkVM(hitCounts)(withCap(_))

  def mkIntermediateSudokuMatrix(hitCounts: HitCounts): SudokuDigitSolution = mkVM(hitCounts)(_ => true)

  // returns the sudoku matrix by analyzing the hitcounts array
  def mkVM(hitCounts: HitCounts)(p: Int => Boolean): SudokuDigitSolution = {
    val h =
      for (i <- cellRange) yield {
        ((for ((v, i) <- hitCounts(i).zipWithIndex if p(v)) yield i).headOption.getOrElse(0) + 48).toChar
      }
    h.toArray
  }


  /**
   * Performance:
   *
   * Benchmark                                          Mode   Samples         Mean   Mean error    Units
   * n.l.a.s.SudokuBenchmark.measureToSolutionCells     avgt        10        0.009        0.000    ms/op
   *
   * @return
   */
  private def toSolutionCells(digitSolution: SudokuDigitSolution): Cells = {
    val allCells: Cells =
      (for (pos <- cellRange) yield {
        val value = digitSolution(pos).asDigit

        val x: Option[SCell] =
          if (value != 0) {
            val someM = digitData(value)
            (if (someM.isEmpty) {
              digitData(value) = mkFallback(value, digitData)
              digitData(value)
            } else someM)
              .map(SCell(value, 0, _))
          } else None
        x
      }).flatten.toArray

    allCells
  }


  /**
   * provides a fallback if there is no digit detected for this number.
   *
   * the size and type of the mat is calculated by looking at the other elements of the digit
   * library. if none found there, just returns null
   *
   * @param number
   * @return
   */
  private def mkFallback(number: Int, digitData: Array[Option[Mat]]): Option[Mat] = {
    /**
     * returns size and type of Mat's contained int he digitLibrary
     * @return
     */
    def determineMatParams(digitData: Array[Option[Mat]]): Option[(Size, Int)] = {
      digitData.flatten.headOption.map {
        case m => (m.size, m.`type`)
      }
    }

    for ((size, matType) <- determineMatParams(digitData)) yield {
      val mat = new Mat(size.height.toInt, size.width.toInt, matType).setTo(new Scalar(255, 255, 255))
      Core.putText(mat, number.toString, new Point(size.width * 0.3, size.height * 0.9), Core.FONT_HERSHEY_TRIPLEX, 2, new Scalar(0, 0, 0))
      mat
    }
  }


}



