package org.management.observations.processing.bolts.qc.block.threshold

// Used for connecting to the Redis registry
import com.redis.RedisClient

// The function being extended and related
import org.apache.flink.streaming.api.scala.function.RichWindowFunction
import org.apache.flink.api.java.tuple.Tuple
import org.apache.flink.streaming.api.windowing.windows.TimeWindow

// Used for passing parameters to the open() function
import org.apache.flink.configuration.Configuration

// The collector for objects to return into the datastream
import org.apache.flink.util.Collector

// The tuples used within this bolt
import org.management.observations.processing.tuples.{QCOutcomeQuantitative, SemanticObservation, SemanticObservationFlow}

// Used to parse date time
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}

// Used to calculate variance
import org.apache.commons.math.stat.descriptive.SummaryStatistics

/**
  * QCBlockThresholdSigmaCheck
  *
  * - performs a check on a window of observations, comparing the
  *     variance of the observations to a minimum and maximum reference
  *     threshold.  There can be multiple pairs of thresholds for each
  *     unique feature, procedure, observed phenomenon combination, for
  *     differing window lengths, and temporal points.
  *
  * - Thresholds may be created/referenced in a range of ways depending
  *     on the method of generation.  Some thresholds may use values that
  *     do not change over time, others may use thresholds centered around
  *     hourly, daily, or monthly points.  With those that change centre around
  *     specific temporal points it is necessary to identify the resolution of
  *     the point (hourly, half-daily, daily, monthly), and to then identify
  *     the exact point closest to the current observation.
  *
  * - This bolt expects window sizes of a hour, twelve hours, and twenty four
  *     hours.  If other window sizes are used, they will be grouped into these
  *     categories.
  */
class QCBlockThresholdSigmaCheck extends RichWindowFunction[SemanticObservation, QCOutcomeQuantitative, Tuple, TimeWindow] with SemanticObservationFlow{

  // Create the connection to the registry
  @transient var redisCon: RedisClient = new RedisClient("localhost", 6379)

  override def open(parameters: Configuration) = {
    this.redisCon = new RedisClient("localhost", 6379)
  }

  def apply(key: Tuple, window: TimeWindow, input: Iterable[SemanticObservation], out: Collector[QCOutcomeQuantitative]): Unit = {


    // Retrieve the meta-data fields from the key and window elements
    val feature: String = key.getField(0).toString
    val procedure: String = key.getField(1).toString
    val observableproperty: String = key.getField(2).toString
    val regKey: String = feature + "::" + procedure + "::" + observableproperty + "::thresholds::sigma"

    /**
      * Identify window size:
      *
      * Identify the correct threshold bracket(1h, 12h, 24h) by checking the start
      * and end time of the window.  It is possible that a 24h window may have observations
      * covering only a 10h duration due to nulls and missing data, and so the
      * following will use the 12h threshold for both the 12h and 24h windows.
      *
      * Missing data will already be flagged in other QC checks, and so treating
      * a 24 hour window as a 12 hour window if that's what the data suggest seems
      * sensible rather than creating another QC event that's caused by already raised
      * issues.
      *
      * Compare against a duration of 1.5 hours, 12.5 hours, and 24.5 hours
      * 1.5 hours = 5400
      * 12.5 hours = 45000
      * 24.5 hours = 88200
      */
    val windowcentre: Long = window.getStart + (window.getEnd+window.getStart)/2
    val timediff: Long = window.getEnd-window.getStart
    val windowduration: String = if(timediff < 5400000) "1h" else if(timediff < 45000000) "12h" else "24h"

    val testKey: String = regKey +"::" +windowduration

    // Create the summary statistic variable with all the observation values
    val stats: SummaryStatistics = new SummaryStatistics()
    input.map(_.numericalObservation.get).foreach(stats.addValue(_))

    // Calculate the variance
    val windowVariance: Double = stats.getVariance

    // Using the stream meta-data lookup the threshold tests
    val sigmaTests: Option[String] = try {
      this.redisCon.get(regKey)
    }catch {
      case e: Exception => None
    }

    // Check that a value was returned from the registry, and if so
    // split on ':', and iterate over each item
    if(sigmaTests.isDefined){
      val individualTests: Array[String] = sigmaTests.get.split("::")

      // Call the test iterator
      processTest(individualTests, input, windowVariance, windowcentre)

      /**
        * This function takes the list of tests to be applied, and recursively iterates
        * over it.  For each test, the upper and lower bounds are retrieved and compared
        * with the observation value.  If the bounds are not exceeded, a pass value is
        * emitted, else a fail is emitted for every observation within the window, or
        * observations iterable - which in the case of Range checks is a single observation.
        *
        * Not separated into its own trait as unsure of how to deal with the
        * Redis connection at the moment.
        *
        * @param testList The list of checks necessary to undertake
        * @param observations The list of observations
        * @param observationValue The value being used with the checks
        * @param timeInstantMilli The middle time instant of multivalue observations,
        *                         or a single point's time instant.
        */
      def processTest(testList: Array[String],
                      observations: Iterable[SemanticObservation],
                      observationValue: Double,
                      timeInstantMilli: Long): Unit = {

        // Retrieve the current test at the head of the list
        val test: String = testList.head

        // Retrieve the type of test (whether static, or time point based)
        val testType: Option[String] = this.redisCon.get(testKey + "::" + test)

        if (testType.isDefined) {

          val timeInstant: LocalDateTime = LocalDateTime.ofEpochSecond(timeInstantMilli / 1000, 0, ZoneOffset.UTC)
          val currMin: Int = timeInstant.getMinute

          // Retrieve the min and max bounds based on the test type
          val minMaxKeys: (Option[String], Option[String]) = testType.getOrElse(None) match {
            case "single" => {
              /**
                * Single min/max value, not changing over time, simply retrieve
                * the min/max entries
                */
              (Some("::min"), Some("::max"))
            }
            case "hour" => {
              /**
                * Hourly point based threshold, must identify the closest hour
                * to the observation, and retrieve using the format:
                * %Y-%m-%dT%H:%M:%S e.g. 2016-01-01T22:23:45 => 2016-01-01T22
                */
              if (currMin <= 30) {
                val target: String = timeInstant.format(DateTimeFormatter.ofPattern("y-MM-dd'T'HH"))
                (Some("::min::" + target), Some("::max::" + target))
              }
              else {
                val target: String = timeInstant.plusHours(1).format(DateTimeFormatter.ofPattern("y-MM-dd'T'HH"))
                (Some("::min::" + target), Some("::max::" + target))
              }
            }
            case "day" => {
              /**
                * Daily point based threshold, must identify and retrieve using
                * the midday point closest to the observation
                * e.g. 2016-01-01T22:23:45 => 2016-01-01
                *
                * For this, it is a simple format, as we assume that exactly
                * midnight should fall on that day rather than the previous
                */
              val target: String = timeInstant.format(DateTimeFormatter.ofPattern("y-MM-dd"))
              (Some("::min::" + target), Some("::max::" + target))
            }
            case "month" => {
              /**
                * Month point based threshold, must identify and retrieve using
                * the month ID e.g. 2016-01-01T22:23:45 => 2016-01
                */
              val target: String = timeInstant.format(DateTimeFormatter.ofPattern("y-MM"))
              (Some("::min::" + target), Some("::max::" + target))
            }
            case _ => {
              /**
                * No match, error, do nothing at present
                */
              (None, None)
            }
          }

          /**
            * Min Max keys may or may not be defined, but Redis can still
            * be queried, and check only the value for comparison for
            * existence
            */
          val minCompareVal: Option[String] = try {
            this.redisCon.get(testKey + "::" + test + minMaxKeys._1.getOrElse(None))
          }catch {
            case e: Exception => None
          }
          val maxCompareVal: Option[String] = try {
            this.redisCon.get(testKey + "::" + test + minMaxKeys._2.getOrElse(None))
          }catch {
            case e: Exception => None
          }
          /**
            * For the min and max, compare to see if the bound is
            * exceeded, generate an outcome as necessary.
            */
          if(minCompareVal.isDefined) {

            val quantitativeVal: Double = minCompareVal.get.toDouble  - observationValue
            val testId: String = "http://placeholder.catalogue.ceh.ac.uk/qc/sigma/" + windowduration +"/" + test + "/min"
            val outcome: String = if(quantitativeVal > 0) "fail" else "pass"

            observations.foreach(x =>
              out.collect(createQCOutcomeQuantitative(
                x,
                testId,
                outcome,
                quantitativeVal
              ))
            )
          }

          if(maxCompareVal.isDefined) {

            val quantitativeVal: Double =  observationValue - maxCompareVal.get.toDouble
            val testId: String = "http://placeholder.catalogue.ceh.ac.uk/qc/sigma/" + windowduration +"/" + test + "/max"
            val outcome: String = if(quantitativeVal > 0) "fail" else "pass"

            observations.foreach(x =>
              out.collect(createQCOutcomeQuantitative(
                x,
                testId,
                outcome,
                quantitativeVal
              ))
            )
          }

        }

        // If there are more tests to undertake, do so
        if(!testList.tail.isEmpty){
          processTest(testList.tail,
            observations,
            observationValue,
            timeInstantMilli)
        }
      }
    }
  }
}