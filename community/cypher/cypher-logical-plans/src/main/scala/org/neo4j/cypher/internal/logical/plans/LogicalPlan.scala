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
package org.neo4j.cypher.internal.logical.plans

import org.neo4j.common.EntityType

import java.lang.reflect.Method
import org.neo4j.cypher.internal.expressions.CachedProperty
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LabelToken
import org.neo4j.cypher.internal.expressions.RelationshipTypeToken
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.util.Foldable
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.Rewritable
import org.neo4j.cypher.internal.util.Rewritable.IteratorEq
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.cypher.internal.util.attribution.IdGen
import org.neo4j.cypher.internal.util.attribution.Identifiable
import org.neo4j.cypher.internal.util.attribution.SameId
import org.neo4j.exceptions.InternalException

import scala.collection.mutable
import scala.collection.mutable.ArrayStack
import scala.util.hashing.MurmurHash3

object LogicalPlan {
  val LOWEST_TX_LAYER = 0
}

/*
A LogicalPlan is an algebraic query, which is represented by a query tree whose leaves are database relations and
non-leaf nodes are algebraic operators like selections, projections, and joins. An intermediate node indicates the
application of the corresponding operator on the relations generated by its children, the result of which is then sent
further up. Thus, the edges of a tree represent data flow from bottom to top, i.e., from the leaves, which correspond
to data in the database, to the root, which is the final operator producing the query answer. */
abstract class LogicalPlan(idGen: IdGen)
  extends Product
  with Foldable
  with Rewritable
  with Identifiable {

  self =>

  def lhs: Option[LogicalPlan]
  def rhs: Option[LogicalPlan]
  def availableSymbols: Set[String]

  override val id: Id = idGen.id()

  override val hashCode: Int = MurmurHash3.productHash(self)

  override def equals(obj: scala.Any): Boolean = {
    if (!obj.isInstanceOf[LogicalPlan]) false
    else {
      val otherPlan = obj.asInstanceOf[LogicalPlan]
      if (this.eq(otherPlan)) return true
      if (this.getClass != otherPlan.getClass) return false
      val stack = new ArrayStack[(Iterator[Any], Iterator[Any])]()
      var p1 = this.productIterator
      var p2 = otherPlan.productIterator
      while (p1.hasNext && p2.hasNext) {
        val continue =
          (p1.next, p2.next) match {
            case (lp1:LogicalPlan, lp2:LogicalPlan) =>
              if (lp1.getClass != lp2.getClass) {
                false
              } else {
                stack.push((p1, p2))
                p1 = lp1.productIterator
                p2 = lp2.productIterator
                true
              }
            case (_:LogicalPlan, _) => false
            case (_, _:LogicalPlan) => false
            case (a1, a2) => a1 == a2
          }

        if (!continue) return false
        while (!p1.hasNext && !p2.hasNext && stack.nonEmpty) {
          val (p1New, p2New) = stack.pop
          p1 = p1New
          p2 = p2New
        }
      }
      p1.isEmpty && p2.isEmpty
    }
  }

  def leaves: Seq[LogicalPlan] = this.treeFold(Seq.empty[LogicalPlan]) {
    case plan: LogicalPlan
      if plan.lhs.isEmpty && plan.rhs.isEmpty => acc => TraverseChildren(acc :+ plan)
  }

  def leftmostLeaf: LogicalPlan = lhs.map(_.leftmostLeaf).getOrElse(this)

  def copyPlanWithIdGen(idGen: IdGen): LogicalPlan = {
    try {
      val arguments = this.treeChildren.toList :+ idGen
      copyConstructor.invoke(this, arguments: _*).asInstanceOf[this.type]
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("wrong number of arguments") =>
        throw new InternalException("Logical plans need to be case classes, and have the IdGen in a separate constructor", e)
    }
  }

  lazy val copyConstructor: Method = this.getClass.getMethods.find(_.getName == "copy").get

  def dup(children: Seq[AnyRef]): this.type =
    if (children.iterator eqElements this.treeChildren) {
      this
    } else {
      val constructor = this.copyConstructor
      val params = constructor.getParameterTypes
      val args = children.toIndexedSeq
      val resultingPlan =
        if (params.length == args.length + 1
          && params.last.isAssignableFrom(classOf[IdGen]))
          constructor.invoke(this, args :+ SameId(this.id): _*).asInstanceOf[this.type]
        else if ((params.length == args.length + 2)
          && params(params.length - 2).isAssignableFrom(classOf[SinglePlannerQuery])
          && params(params.length - 1).isAssignableFrom(classOf[IdGen]))
          constructor.invoke(this, args :+ SameId(this.id): _*).asInstanceOf[this.type]
        else
          constructor.invoke(this, args: _*).asInstanceOf[this.type]
      resultingPlan
    }

  def isLeaf: Boolean = lhs.isEmpty && rhs.isEmpty

  override def toString: String = {
    def indent(level: Int, in: String): String = level match {
      case 0 => in
      case _ => System.lineSeparator() + "  " * level + in
    }

    val childrenHeap = new mutable.ArrayStack[(String, Int, Option[LogicalPlan])]
    childrenHeap.push(("", 0, Some(this)))
    val sb = new StringBuilder()

    while (childrenHeap.nonEmpty) {
      childrenHeap.pop() match {
        case (prefix, level, Some(plan)) =>
          val children = plan.lhs.toIndexedSeq ++ plan.rhs.toIndexedSeq
          val nonChildFields = plan.productIterator.filterNot(children.contains).mkString(", ")
          val prodPrefix = plan.productPrefix
          sb.append(indent(level, s"""$prefix$prodPrefix($nonChildFields) {""".stripMargin))

          (plan.lhs, plan.rhs) match {
            case (None, None) =>
              sb.append("}")
            case (Some(_), None) =>
              childrenHeap.push((System.lineSeparator() + "  " * level + "}", level + 1, None))
              childrenHeap.push(("LHS -> ", level + 1, plan.lhs))
            case _ =>
              childrenHeap.push((System.lineSeparator() + "  " * level + "}", level + 1, None))
              childrenHeap.push(("RHS -> ", level + 1, plan.rhs))
              childrenHeap.push(("LHS -> ", level + 1, plan.lhs))
          }
        case (prefix, _, _) =>
          sb.append(prefix)
      }
    }

    sb.toString()
  }

  def satisfiesExpressionDependencies(e: Expression): Boolean = e.dependencies.map(_.name).forall(availableSymbols.contains)

  def debugId: String = f"0x$hashCode%08x"

  def flatten: Seq[LogicalPlan] = Flattener.create(this)

  def indexUsage: Seq[IndexUsage] = {
    this.fold(Seq.empty[IndexUsage]) {
      case NodeIndexSeek(idName, label, properties, _, _, _) =>
        acc => acc :+ SchemaIndexSeekUsage(idName, label.nameId.id, label.name, properties.map(_.propertyKeyToken.name))
      case NodeUniqueIndexSeek(idName, label, properties, _, _, _) =>
        acc => acc :+ SchemaIndexSeekUsage(idName, label.nameId.id, label.name, properties.map(_.propertyKeyToken.name))
      case NodeIndexScan(idName, label, properties, _, _) =>
        acc => acc :+ SchemaIndexScanUsage(idName, label.nameId.id, label.name, properties.map(_.propertyKeyToken.name))
      case MultiNodeIndexSeek(indexPlans) =>
        acc => acc ++ indexPlans.flatMap(_.indexUsage)
      case NodeByLabelScan(idName, _, _, _) => acc => acc :+ SchemaIndexLookupUsage(idName, EntityType.NODE)
      }
  }
}

// Marker interface for all plans that aggregate inputs.
trait AggregatingPlan extends LogicalPlan {
  def groupingExpressions: Map[String, Expression]
  def aggregationExpressions: Map[String, Expression]
}

// Marker interface for all plans that performs updates
trait UpdatingPlan extends LogicalUnaryPlan {
  override def withLhs(source: LogicalPlan)(idGen: IdGen): UpdatingPlan
}

abstract class LogicalBinaryPlan(idGen: IdGen) extends LogicalPlan(idGen) {
  final def lhs: Option[LogicalPlan] = Some(left)
  final def rhs: Option[LogicalPlan] = Some(right)

  def left: LogicalPlan
  def right: LogicalPlan

  /**
   * A copy of this plan with a new LHS
   */
  def withLhs(newLHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan
  /**
   * A copy of this plan with a new LHS
   */
  def withRhs(newRHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan
}

abstract class LogicalUnaryPlan(idGen: IdGen) extends LogicalPlan(idGen) {
  final def lhs: Option[LogicalPlan] = Some(source)
  final def rhs: Option[LogicalPlan] = None

  def source: LogicalPlan

  /**
   * A copy of this plan with a new LHS
   */
  def withLhs(newLHS: LogicalPlan)(idGen: IdGen): LogicalUnaryPlan
}

abstract class LogicalLeafPlan(idGen: IdGen) extends LogicalPlan(idGen)  {
  final def lhs: Option[LogicalPlan] = None
  final def rhs: Option[LogicalPlan] = None
  def argumentIds: Set[String]

  def usedVariables: Set[String]

  def withoutArgumentIds(argsToExclude: Set[String]): LogicalLeafPlan
}

abstract class NodeLogicalLeafPlan(idGen: IdGen) extends LogicalLeafPlan(idGen) {
  def idName: String
}

abstract class RelationshipLogicalLeafPlan(idGen: IdGen) extends LogicalLeafPlan(idGen) {
  def idName: String
  def leftNode: String
  def rightNode: String
}

abstract class MultiNodeLogicalLeafPlan(idGen: IdGen) extends LogicalLeafPlan(idGen) {
  def idNames: Set[String]
}

trait IndexedPropertyProvidingPlan {
  /**
   * All properties
   */
  def properties: Seq[IndexedProperty]

  /**
   * Indexed properties that will be retrieved from the index and cached in the row.
   */
  def cachedProperties: Seq[CachedProperty]

  /**
   * Create a copy of this plan, swapping out the properties
   */
  def withMappedProperties(f: IndexedProperty => IndexedProperty): IndexedPropertyProvidingPlan

  /**
   * Get a copy of this index plan where getting values is disabled
   */
  def copyWithoutGettingValues: IndexedPropertyProvidingPlan
}

abstract class NodeIndexLeafPlan(idGen: IdGen) extends NodeLogicalLeafPlan(idGen) with IndexedPropertyProvidingPlan {
  def label: LabelToken

  override def cachedProperties: Seq[CachedProperty] = properties.flatMap(_.maybeCachedProperty(idName))

  override def withMappedProperties(f: IndexedProperty => IndexedProperty): NodeIndexLeafPlan

  override def copyWithoutGettingValues: NodeIndexLeafPlan
}

abstract class RelationshipIndexLeafPlan(idGen: IdGen) extends RelationshipLogicalLeafPlan(idGen) with IndexedPropertyProvidingPlan {
  def typeToken: RelationshipTypeToken

  override def cachedProperties: Seq[CachedProperty] = properties.flatMap(_.maybeCachedProperty(idName))

  override def withMappedProperties(f: IndexedProperty => IndexedProperty): RelationshipIndexLeafPlan

  override def copyWithoutGettingValues: RelationshipIndexLeafPlan
}

abstract class MultiNodeIndexLeafPlan(idGen: IdGen) extends MultiNodeLogicalLeafPlan(idGen) with IndexedPropertyProvidingPlan {

}

abstract class NodeIndexSeekLeafPlan(idGen: IdGen) extends NodeIndexLeafPlan(idGen) {

  def valueExpr: QueryExpression[Expression]

  def properties: Seq[IndexedProperty]

  def indexOrder: IndexOrder

  override def withMappedProperties(f: IndexedProperty => IndexedProperty): NodeIndexSeekLeafPlan
}

case object Flattener extends LogicalPlans.Mapper[Seq[LogicalPlan]] {
  override def onLeaf(plan: LogicalPlan): Seq[LogicalPlan] = Seq(plan)

  override def onOneChildPlan(plan: LogicalPlan, source: Seq[LogicalPlan]): Seq[LogicalPlan] = plan +: source

  override def onTwoChildPlan(plan: LogicalPlan, lhs: Seq[LogicalPlan], rhs: Seq[LogicalPlan]): Seq[LogicalPlan] = (plan +: lhs) ++ rhs

  def create(plan: LogicalPlan): Seq[LogicalPlan] =
    LogicalPlans.map(plan, this)
}

sealed trait IndexUsage {
  def identifier:String
}

final case class SchemaIndexSeekUsage(identifier: String, labelId : Int, label: String, propertyKeys: Seq[String]) extends IndexUsage
final case class SchemaIndexScanUsage(identifier: String, labelId : Int, label: String, propertyKeys: Seq[String]) extends IndexUsage
final case class SchemaIndexLookupUsage(identifier: String, entityType: EntityType) extends IndexUsage
