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
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.expressions.NilPathStep
import org.neo4j.cypher.internal.expressions.NodePathStep
import org.neo4j.cypher.internal.expressions.PathExpression
import org.neo4j.cypher.internal.runtime.spec.Edition
import org.neo4j.cypher.internal.runtime.spec.LogicalQueryBuilder
import org.neo4j.cypher.internal.runtime.spec.RecordingRuntimeResult
import org.neo4j.cypher.internal.runtime.spec.RuntimeTestSuite
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.graphdb.ConstraintViolationException
import org.neo4j.kernel.impl.util.ValueUtils

import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter

abstract class DeleteExpressionTestBase[CONTEXT <: RuntimeContext](
  edition: Edition[CONTEXT],
  runtime: CypherRuntime[CONTEXT],
  sizeHint: Int
) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("delete node in map") {
    val nodeCount = sizeHint
    val nodes = given {
      nodeGraph(nodeCount)
    }

    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("map")
      .deleteExpression("map.node")
      .projection("{node: n} AS map")
      .allNodeScan("n")
      .build(readOnly = false)

    val expectedRows = nodes
      .map(n => ValueUtils.asMapValue(Map("node" -> n).asJava))
      .map(m => Array(m))
    // then
    val runtimeResult: RecordingRuntimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)
    runtimeResult should beColumns("map")
      .withRows(expectedRows)
      .withStatistics(nodesDeleted = nodeCount)
    tx.getAllNodes.stream().count() shouldBe 0
  }

  test("delete relationship in map") {
    val nodes = given {
      chainGraphs(3, "LOVES", "LOVES", "LOVES_TO_HATE")
    }

    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("map")
      .deleteExpression("map.relationship")
      .projection("{relationship: r} AS map")
      .expand("(n)-[r]->()")
      .allNodeScan("n")
      .build(readOnly = false)

    val expectedRows = nodes
      .flatMap(_.relationships().asScala)
      .map(n => ValueUtils.asMapValue(Map("relationship" -> n).asJava))
      .map(m => Array(m))

    // then
    val runtimeResult: RecordingRuntimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)
    runtimeResult should beColumns("map")
      .withRows(expectedRows)
      .withStatistics(relationshipsDeleted = 3*3)
    tx.getAllRelationships.stream().count() shouldBe 0
  }

  test("delete path in map") {
    val nodeCount = 10
    val nodes = given {
      nodeGraph(nodeCount)
    }

    val pathExpression = PathExpression(NodePathStep(varFor("n"), NilPathStep))(InputPosition.NONE)
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("map")
      .deleteExpression("map.path")
      .projection("{path: p} AS map")
      .projection(Map("p" -> pathExpression))
      .allNodeScan("n")
      .build(readOnly = false)

    // then
    val runtimeResult: RecordingRuntimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)
    runtimeResult should beColumns("map")
      .withStatistics(nodesDeleted = nodeCount)
    tx.getAllNodes.stream().count() shouldBe 0
  }

  test("fail to delete nodes with relationships in map") {
    val nodes = given {
      chainGraphs(3, "PUSHES")
    }

    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("map")
      .deleteExpression("map.node")
      .projection("{node: n} AS map")
      .allNodeScan("n")
      .build(readOnly = false)

    // then
    val runtimeResult: RecordingRuntimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val thrown = the [ConstraintViolationException] thrownBy restartTx()
    thrown.getMessage should include regex "Cannot delete.*because it still has relationships"

    runtimeTestSupport.startNewTx()
    // Nodes and relationships should still be there
    tx.getAllNodes.stream().count() shouldBe 3*2
    tx.getAllRelationships.stream().count() shouldBe 3
  }
}