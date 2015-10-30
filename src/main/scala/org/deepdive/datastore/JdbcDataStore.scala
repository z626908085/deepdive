package org.deepdive.datastore

import java.sql.{Connection, DriverManager, ResultSet, PreparedStatement}
import scalikejdbc._
import scalikejdbc.config._
import org.deepdive.Logging
import com.typesafe.config._
import play.api.libs.json._
import org.deepdive.inference.InferenceNamespace
import scala.sys.process.ProcessLogger
import scala.sys.process._

trait JdbcDataStoreComponent {
  def dataStore : JdbcDataStore
}

trait JdbcDataStore extends Logging {

  def DB = scalikejdbc.DB

  /* Borrows a connection from the connection pool. You should close the connection when done. */
  def borrowConnection() : Connection = ConnectionPool.borrow()

  /* Executes a block with a borrowed connection */
  def withConnection[A](block: Connection => A) = using(ConnectionPool.borrow())(block)

  /* Closes the connection pool and all of its connections */
  def close() = ConnectionPool.closeAll()

  def init() : Unit = {}

  def prepareStatement(query: String)
          (op: (java.sql.PreparedStatement) => Unit) = {
    val conn = borrowConnection()
    try {
      conn.setAutoCommit(false)
      val ps = conn.prepareStatement(query)
      op(ps)
      conn.commit()
    } catch {
      // SQL cmd exception
      case exception : Throwable =>
        log.error(exception.toString)
        throw exception
    } finally {
      conn.close()
    }
  }


  /**
   *  Execute one or multiple SQL update commands with connection to JDBC datastore
   *
   */
  def executeSqlQueries(sql: String, split: Boolean = true) : Unit = {
    log.debug("Executing SQL:\n" + sql)
    val sqlCmd = Seq("deepdive-sql", sql)
    val exitValue = sqlCmd ! (ProcessLogger(out => log.info(out)))
    exitValue match {
      case 0 =>
      case c => throw new RuntimeException(s"Failure (exit status = ${c}) while executing SQL: ${sql}")
    }
  }

  def executeSqlQueryGetTSV(sql: String, index: Int) : String = {
    log.debug("Executing SQL:\n" + sql)
    val sqlCmd = Seq("deepdive-sql", "eval", sql.stripSuffix(";"))
    var result = ""
    val exitValue = sqlCmd ! (ProcessLogger(out => result = out))
    exitValue match {
      case 0 =>
      case c => throw new RuntimeException(s"Failure (exit status = ${c}) while executing SQL: ${sql}")
    }
    result.split("\t")(index)
  }

  // execute a SQL query and get boolean result
  def executeSqlQueryGetBoolean(sql: String, index: Int = 0) : Boolean = {
    val result = executeSqlQueryGetTSV(sql, index)
    result == "t"
  }

  // execute a SQL query and get long result
  def executeSqlQueryGetLong(sql: String, index: Int = 0) : Long = {
    val result = executeSqlQueryGetTSV(sql, index)
    result.toLong
  }

  // check if the given table name is deepdive's internal table, if not throw exception
  def checkTableNamespace(name: String) = {
    if (!name.startsWith(InferenceNamespace.deepdivePrefix)) {
      throw new RuntimeException("Dropping a non-deepdive internal table!")
    }
  }

  /**
   * Drops a table if it exists, and then create it
   * Ensures we are only dropping tables inside the DeepDive namespace.
   */
  def dropAndCreateTable(name: String, schema: String) = {
    checkTableNamespace(name)
    executeSqlQueries(s"""DROP TABLE IF EXISTS ${name} CASCADE;""")
    executeSqlQueries(s"""CREATE ${unlogged} TABLE ${name} (${schema});""")
  }

  /**
   * Drops a table if it exists, and then create it using the given query
   * Ensures we are only dropping tables inside the DeepDive namespace.
   */
  def dropAndCreateTableAs(name: String, query: String) = {
    checkTableNamespace(name)
    executeSqlQueries(s"""DROP TABLE IF EXISTS ${name} CASCADE;""")
    executeSqlQueries(s"""CREATE ${unlogged} TABLE ${name} AS ${query};""")
  }

  /**
   * Creates a table if it doesn't exist
   * Ensures we are only creating tables inside the DeepDive namespace.
   */
  def createTableIfNotExists(name: String, schema: String) = {
    checkTableNamespace(name)
    executeSqlQueries(s"""CREATE TABLE IF NOT EXISTS ${name} (${schema});""")
  }

  /**
   * Creates a table if it doesn't exist
   * Ensures we are only creating tables inside the DeepDive namespace.
   */
  def createTableIfNotExistsLike(name: String, source: String) = {
    checkTableNamespace(name)
    executeSqlQueries(s"""CREATE TABLE IF NOT EXISTS ${name} (LIKE ${source});""")
  }

  private def unwrapSQLType(x: Any) : Any = {
    x match {
      case x : org.postgresql.jdbc4.Jdbc4Array => x.getArray().asInstanceOf[Array[_]].toList
      case x : org.postgresql.util.PGobject =>
        x.getType match {
          case "json" => Json.parse(x.getValue)
          case _ => JsNull
        }
      case x => x
    }
  }

  // returns if a language exists in Postgresql or Greenplum
  def existsLanguage(language: String) : Boolean = {
    val sql = s"""SELECT EXISTS (
      SELECT 1
      FROM   pg_language
      WHERE  lanname = '${language}');"""
    executeSqlQueryGetBoolean(sql)
  }

  // return if a function of the same name exists in
  // Postgresql or Greenplum
  def existsFunction(function: String) : Boolean = {
    val sql = s"""
      SELECT EXISTS (
        SELECT *
        FROM information_schema.routines
        WHERE routine_name = '${function}'
      );
    """
    executeSqlQueryGetBoolean(sql)
  }

  // check whether greenplum is used
  def isUsingGreenplum() : Boolean = {
    executeSqlQueryGetBoolean("SELECT version() LIKE '%Greenplum%';")
  }

  // check whether postgres-xl is used
  lazy val isUsingPostgresXL : Boolean = {
    executeSqlQueryGetBoolean("SELECT version() LIKE '%Postgres-XL%';")
  }

  def unlogged = if (isUsingPostgresXL) "UNLOGGED" else ""

  // ========================================
  // Extraction

  def queryUpdate(query: String) {
    executeSqlQueries(query)
  }

  def addBatch(result: Iterator[JsObject], outputRelation: String) : Unit = {}

  // Datastore-specific methods:
  // Below are methods to implement in any type of datastore.

  /**
   * Drop and create a sequence, based on database type.
   *
   * @see http://dev.mysql.com/doc/refman/5.0/en/user-variables.html
   * @see http://www.it-iss.com/mysql/mysql-renumber-field-values/
   */
  def createSequenceFunction(seqName: String) : String = null

  /**
   * Cast an expression to a type
   */
  def cast(expr: Any, toType: String): String = null

  /**
   * Given a string column name, Get a quoted version dependent on DB.
   *          if psql, return "column"
   *          if mysql, return `column`
   */
  def quoteColumn(column: String) : String = null

  /**
   * Generate a random real number from 0 to 1.
   */
  def randomFunction : String = null

  /**
   * Concatenate a list of strings in the database.
   * @param list
   *     the list to concat
   * @param delimiter
   *     the delimiter used to seperate elements
   * @return
   *   Use '||' in psql, use 'concat' function in mysql
   */
  def concat(list: Seq[String], delimiter: String) : String = null

  // fast sequential id assign function
  def createSpecialUDFs() : Unit = {}

  /**
   * ANALYZE TABLE
   */
  def analyzeTable(table: String) : String = ""

  // assign sequential ids to table's id column
  def assignIds(table: String, startId: Long, sequence: String) : Long = 0

  // check if a table exists
  def existsTable(table: String) : Boolean = false;

  // assign sequential ids in particular order
  def assignIdsOrdered(table: String, startId: Long, sequence: String, orderBy: String = "") : Long = throw new UnsupportedOperationException

  // end: Datastore-specific methods

}

object JdbcDataStoreObject extends JdbcDataStore with Logging {

  class JdbcDBsWithEnv(envValue: String, configObj: Config) extends DBsWithEnv(envValue) {
    override lazy val config = configObj
  }

  /* Initializes the data stores */
  def init(config: Config) : Unit = {
    val initializer = new JdbcDBsWithEnv("deepdive", config)
    log.info("Intializing all JDBC data stores")
    initializer.setupAll()
  }

  override def init() : Unit = init(ConfigFactory.load)

  /* Closes the data store */
  override def close() = {
    log.info("Closing all JDBC data stores")
    ConnectionPool.closeAll() // TODO not tested
    DBsWithEnv("deepdive").closeAll()
  }

}
