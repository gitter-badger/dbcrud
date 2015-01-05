package org.dbcrud

import java.sql.Types
import java.util.Date

import org.h2.jdbcx.JdbcDataSource
import ColumnOps._

/**
 * Created by rinconj on 15/12/14.
 */
class JdbcCrudTest extends org.specs2.mutable.Specification {
  val dataSource = new ManagedDataSource({
    val ds = new JdbcDataSource()
    ds.setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
    ds
  })

  val dbCrud = new JdbcCrud(dataSource)

  step{
    dbCrud.createTable('ACCOUNT, 'id->Types.INTEGER, 'name -> Types.VARCHAR, 'opened_at -> Types.DATE)
    dbCrud.createTable('TRANSACTION, 'id->Types.INTEGER, 'desc -> Types.VARCHAR, 'amount -> Types.DOUBLE)
  }

  "list tables" in {
    dbCrud.tableNames.toSet shouldEqual(Set("ACCOUNT", "TRANSACTION"))
  }

  "insert data" in {
    dbCrud.insert('ACCOUNT, 'id->1, 'name->"account 1", 'opened_at->new Date) shouldEqual(1)
  }

  "update data" in {
    dbCrud.insert('ACCOUNT, 'id->99, 'name->"account 99", 'opened_at->new Date)
    dbCrud.updateAll('ACCOUNT, 'name->"account updated") should beGreaterThan(0)
    dbCrud.updateWhere('ACCOUNT, ('id is 99) or ('name is "account 99"), 'name -> "Account 99") shouldEqual(1)
  }

  "select data" in {
    dbCrud.select('ACCOUNT) should not beEmpty
  }

}