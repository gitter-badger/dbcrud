package org.dbcrud

import scala.util.Try

/**
 * Created by rinconj on 15/12/14.
 */

case class DbTable(name:Symbol, columns:Seq[DbColumn[_]], primaryKey:Seq[Symbol]){
  private val columnsByName = columns.map(c=>c.name->c).toMap[Symbol, DbColumn[_]]
  def column(name:Symbol) = columnsByName(name)
  def coerce[T](name:Symbol, value:String):Option[Try[T]] = columnsByName.get(name).map(c=>Try(c.dbType.asInstanceOf[SqlType[T]].fromString(value)))
}

case class DbColumn[T](name: Symbol, dbType: SqlType[T], size:Int=0, decimalDigits:Int=0, nullable:Boolean=true, autoIncremented:Boolean=false, autoGenerated:Boolean=false)

trait Row extends Traversable[(Symbol, Any)] {
  def apply[T](column: Symbol): T

  def apply[T](column: String): T
}

class QueryData(columns: Seq[Symbol], rows: Iterable[Array[Any]]) extends Iterable[Row] {

  def asMaps = rows.map(r => columns.map(_.name).zip(r).toMap)

  private lazy val columnToIndex = columns.map(_.name.toUpperCase).zipWithIndex.toMap

  override def iterator: Iterator[Row] = rows.iterator.map(values => new RowImpl(values))

  class RowImpl(values: Array[Any]) extends Row {

    override def foreach[U](f: ((Symbol, Any)) => U): Unit = columns.zip(values).foreach(f)

    def apply[T](column: Symbol): T = values(columnToIndex(column.name.toUpperCase)).asInstanceOf[T]

    def apply[T](column: String): T = values(columnToIndex(column.toUpperCase)).asInstanceOf[T]
  }

}

trait DataCrud {

  def createTable(name: Symbol, columns: DbColumn[_]*)

  def tableNames: Iterable[Symbol]

  def tableDef(table:Symbol):Option[DbTable]

  def insert(table: Symbol, values: (Symbol, Any)*): Try[Int]

  def update(table: Symbol, id:Any, values: (Symbol, Any)*):Try[Unit]

  def updateAll(table: Symbol, values: (Symbol, Any)*): Try[Int]

  def updateWhere(table: Symbol, where: Predicate, values: (Symbol, Any)*): Try[Int]

  def delete(table: Symbol, id: Any): Try[Int]

  def select(table: Symbol, where: Predicate=EmptyPredicate, offset: Int=0, count: Int=10, orderBy: Seq[ColumnOrder]=Nil): Try[QueryData]

  def selectById(table: Symbol, id:Any):Try[Map[Symbol, Any]]
}

