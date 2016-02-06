package org.scalafmt

import com.ibm.couchdb._
import org.scalafmt.stats.GitInfo
import org.scalafmt.stats.TestStats

import scalaz.-\/
import scalaz.\/-

object Speed extends ScalaFmtLogger {
  lazy val dbName = "scalafmt-teststats"
  lazy val couch = CouchDb("speed.scalafmt.org", 443, https = true)
  lazy val db = couch.db(dbName, typeMapping)
  lazy val typeMapping = TypeMapping(classOf[TestStats] -> "TestStats")

  def submitStats(stat: TestStats): Unit = {
    val t = Stopwatch()
    val actions = db.docs.create(stat)
    actions.attemptRun match {
      case -\/(e) =>
        logger.warn("Unable to submit to speed.scalafmt.org", e)
      case f@ \/-(a) =>
        logger.debug(s"Submitted to speed.scalafmt.org (${t.elapsedMs}ms)")
    }
  }

  def writeComparisonReport(after: TestStats,
                            branch: String): Unit = {
    import sys.process._
    val t = Stopwatch()
    val commit = Seq("git", "rev-parse", branch).!!.trim

    val view = db.query
      .view[(String, Long), TestStats]("speed", "commits").get
      .endKey((commit, 0))
      .startKey((commit, Long.MaxValue))
      .descending(true)
      .limit(1)
    view.query.attemptRun match {
      case -\/(e: upickle.Invalid) =>
        logger.debug("Received invalid data from server. Has TestStats changed?")
      case -\/(e) =>
        val currentBranch = GitInfo.currentBranch
        logger.warn(
          s"""Found no data for branch $branch. Try this:
              |expected $commit
              |$$ git checkout $branch
              |$$ sbt test
              |$$ git checkout $currentBranch
              |$$ sbt test""".stripMargin)
      case \/-(CouchKeyVals(_, _, Seq(CouchKeyVal(_, _, before)))) =>
        val report = Report.compare(before, after)
        val filename = "target/compare.html"
        FilesUtil.writeFile(filename, report)
        logger.debug(s"Compare results in $filename (${t.elapsedMs}ms)")
      case \/-(other) =>
        logger.warn(s"Unexpected view result $other ${other.rows}")
    }
  }

}