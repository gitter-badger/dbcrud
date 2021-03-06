package org.dbcrud

import java.sql.{DatabaseMetaData, ResultSet}
import java.util.logging.{Level, Logger}

import org.dbcrud.dialects.DbmsDialect

import scala.annotation.tailrec
import scala.util.{Failure, Try}
import scalaz.Scalaz._


class JdbcCrud(ds: ManagedDataSource, schema: String = null, dbmsDialect: DbmsDialect = null) extends DataCrud {

  private val logger = Logger.getLogger(getClass.getName)

  private lazy val dbms = ds.doWith(_.getMetaData.getDatabaseProductName)

  private val dialect = Option(dbmsDialect).orElse(inferDialect(ds))

  private lazy val tables = ds.doWith { conn =>
    val rs = conn.getMetaData.getTables(null, schema, "%", Array("TABLE"))
    collect(rs, { rs =>
      val tableName = Symbol(rs.getString("TABLE_NAME"))
      logger.info(s"table:$tableName")
      for {
        cols <- columns(tableName.name).toOption
        pks <- primaryKeys(tableName.name).toOption
      } yield tableName -> DbTable(tableName, cols, pks)
    }).flatten.toMap
  }.getOrElse(Map())

  private lazy val typeMappings: Map[SqlType[_], String] = dialect.map(_.typeMapping).getOrElse {
    ds.doWith { conn =>
      collect(conn.getMetaData.getTypeInfo, rs => {
        SqlType.get(rs.getInt("DATA_TYPE")) -> rs.getString("TYPE_NAME")
      }).toMap.filterKeys(_.isDefined).mapKeys(_.get)
    }.getOrElse(Map())
  }

  private def inferDialect(ds: ManagedDataSource): Option[DbmsDialect] = {
    val dbms = ds.doWith(_.getMetaData.getDatabaseProductName)

    Try(Class.forName(s"org.dbcrud.dialects.${dbms.get}SqlDialect")).recoverWith {
      case e: Exception =>
        logger.warning(s"no dialect found in classpath: $e")
        Failure(e)
    }.flatMap(cls => Try(cls.newInstance().asInstanceOf[DbmsDialect]).recoverWith {
      case e: Exception =>
        logger.log(Level.SEVERE, "failed instantiating dialect class", e)
        Failure(e)
    }).toOption
  }

  private def primaryKeys(table: String) = ds.doWith { conn =>
    collect(conn.getMetaData.getPrimaryKeys(null, null, table), rs => {
      rs.getString("COLUMN_NAME") -> rs.getInt("KEY_SEQ")
    }).sortBy(_._2).map(p => Symbol(p._1))
  }

  private def columns(table: String) = ds.doWith { conn =>
    collect(conn.getMetaData.getColumns(null, null, table, "%"), { rs =>
      DbColumn(Symbol(rs.getString("COLUMN_NAME")), SqlType(rs.getInt("DATA_TYPE")), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
        rs.getInt("NULLABLE") == DatabaseMetaData.attributeNullable, rs.getString("IS_AUTOINCREMENT") == "YES", false)
    })
  }

  private def collect[R](rs: ResultSet, rowMapper: ResultSet => R): Seq[R] = {
    Iterator.continually {
      if (rs.next()) {
        Some(rowMapper(rs))
      } else None
    }.takeWhile(_.isDefined).flatten.toList
  }

  @tailrec
  private def skip(rs: ResultSet, count: Int): ResultSet = {
    if (!rs.next() || count <= 0) rs
    else skip(rs, count - 1)
  }

  def createTable(name: Symbol, columns: DbColumn[_]*) {
    ds.doWith { conn =>
      val columnsLines = columns.map { col =>
        val dbType = typeMappings(col.dbType)
        s"${col.name.name} ${col.dbType.ddl(dbType, col.size, col.nullable)}"
      }
      val sql = s"CREATE TABLE ${name.name} (${columnsLines.mkString(",")})"
      logger.info(s"executing: $sql")
      val res = conn.createStatement().execute(sql)
      logger.info(s"table created:$res")
    }
  }

  override def tableNames = tables.keys


  override def tableDef(table: Symbol) = tables.get(table)

  override def insert(table: Symbol, values: (Symbol, Any)*) = {
    val (cols, vals) = values.unzip
    ds.doWith { conn =>
      val stmt = conn.prepareStatement( s"""INSERT INTO ${table.name} (${cols.map(_.name).mkString(",")})
        VALUES(${cols.map(_ => "?").mkString(",")})""")
      vals.zipWithIndex.foreach { t =>
        stmt.setObject(t._2 + 1, t._1)
      }
      stmt.executeUpdate()
    }
  }

  override def update(table: Symbol, id: Any, values: (Symbol, Any)*) = ds.doWith { conn =>
    val predicate = id match {
      case seq: Seq[(Symbol, Any)] => new SimpleConditions(seq)
      case other => tables(table).primaryKey.ensuring(_.size == 1).map(k => new SimpleConditions(Seq(k -> other))).head
    }
    updateWhere(table, predicate, values: _*)
  }

  override def updateAll(table: Symbol, values: (Symbol, Any)*) = {
    updateWhere(table, EmptyPredicate, values: _*)
  }

  override def updateWhere(table: Symbol, where: Predicate, values: (Symbol, Any)*) = {
    val (cols, vals) = values.unzip
    ds.doWith { conn =>
      val ps = conn.prepareStatement(s"UPDATE ${table.name} SET ${cols.map(_.name + " = ?").mkString(",")} ${where.whereSql}")
      vals.zipWithIndex.foreach {t =>
        ps.setObject(t._2 + 1, t._1)
      }
      where.constants.zipWithIndex.foreach { t =>
        ps.setObject(values.size + t._2 + 1, t._1)
      }
      ps.executeUpdate()
    }
  }

  override def delete(table: Symbol, id: Any) = ds.doWith { conn =>
    val predicate = id match {
      case seq: Seq[(Symbol, Any)] => new SimpleConditions(seq)
      case other => tables(table).primaryKey.ensuring(_.size == 1).map(k => new SimpleConditions(Seq(k -> other))).head
    }
    val ps = conn.prepareStatement(s"DELETE FROM ${table.name} ${predicate.whereSql}")
    predicate.constants.zipWithIndex.foreach { t =>
      ps.setObject(t._2 + 1, t._1)
    }
    ps.executeUpdate()
  }

  override def select(table: Symbol, where: Predicate = EmptyPredicate, offset: Int = 0, count: Int = 0
                      , orderBy: Seq[ColumnOrder] = Nil): Try[QueryData] = {
    ds.doWith { conn =>
      val rs = (offset > 0).option().flatMap { _ =>
        dialect.map { d =>
          val ps = d.selectStatement(conn, table.name, where, offset, count, orderBy)
          ps.executeQuery()
        }
      }.getOrElse {
        val ps = conn.prepareStatement(s"SELECT * FROM ${table.name} ${where.whereSql} " + (orderBy.isEmpty ? "" | s"ORDER BY ${orderBy.mkString(",")}"))
        where.constants.zipWithIndex.foreach { t =>
          ps.setObject(t._2 + 1, t._1)
        }
        if (count > 0) {
          ps.setMaxRows(offset + count)
        }
        skip(ps.executeQuery(), offset)
      }
      toQueryData(rs)
    }
  }


  override def selectById(table: Symbol, id: Any): Try[Map[Symbol, Any]] = ???

  private def toQueryData(rs: ResultSet): QueryData = {
    val rsMeta = rs.getMetaData
    val columns = for (c <- 1 to rsMeta.getColumnCount) yield Symbol(rsMeta.getColumnName(c))
    val rows = collect(rs, { rs =>
      Array.range(1, columns.size + 1).map(rs.getObject(_).asInstanceOf[Any])
    })
    new QueryData(columns, rows)
  }

  def execSql(sql: String) = ds.doWith{con=>
    con.createStatement().execute(sql);
  }
}
