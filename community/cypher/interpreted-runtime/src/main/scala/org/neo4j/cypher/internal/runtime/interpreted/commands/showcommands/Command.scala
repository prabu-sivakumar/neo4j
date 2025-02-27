/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands

import org.neo4j.cypher.internal.ast.CommandResultItem
import org.neo4j.cypher.internal.ast.ShowColumn
import org.neo4j.cypher.internal.runtime.ClosingIterator
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.values.AnyValue

abstract class Command(columns: List[ShowColumn]) {

  def originalNameRows(state: QueryState, baseRow: CypherRow): ClosingIterator[Map[String, AnyValue]]

  final def rows(state: QueryState, baseRow: CypherRow): ClosingIterator[Map[String, AnyValue]] = {
    originalNameRows(state, baseRow).map { map =>
      columns.map {
        case ShowColumn(lv, _, originalName) => lv.name -> map(originalName)
      }.toMap
    }
  }
}

abstract class TransactionCommand(defaultColumns: List[ShowColumn], yieldColumns: List[CommandResultItem])
    extends Command(TransactionCommand.getColumns(defaultColumns, yieldColumns)) {

  // Update to rename columns which have been renamed in YIELD
  def updateRowsWithPotentiallyRenamedColumns(rows: List[Map[String, AnyValue]]): List[Map[String, AnyValue]] =
    rows.map(row =>
      row.map { case (key, value) =>
        val newKey =
          yieldColumns.find(c => c.originalName.equals(key)).map(_.aliasedVariable.name).getOrElse(key)
        (newKey, value)
      }
    )
}

object TransactionCommand {

  // Make sure to get the yielded columns (and their potential renames) if YIELD was specified
  // otherwise get the default columns
  private def getColumns(defaultColumns: List[ShowColumn], yieldColumns: List[CommandResultItem]): List[ShowColumn] = {
    if (yieldColumns.nonEmpty) yieldColumns.map(c => {
      val column = defaultColumns.find(s => s.variable.name.equals(c.originalName)).get
      ShowColumn(c.aliasedVariable, column.cypherType, c.aliasedVariable.name)
    })
    else defaultColumns
  }
}
