/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.spark.sql.execution.datasources.v2

import org.apache.iceberg.relocated.com.google.common.base.Preconditions
import org.apache.iceberg.spark.source.SparkTable
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.TagOptions
import org.apache.spark.sql.connector.catalog._

case class CreateOrReplaceTagExec(
    catalog: TableCatalog,
    ident: Identifier,
    tag: String,
    tagOptions: TagOptions,
    create: Boolean,
    replace: Boolean,
    ifNotExists: Boolean) extends V2CommandExec {

  import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._

  override lazy val output: Seq[Attribute] = Nil

  override protected def run(): Seq[InternalRow] = {
    catalog.loadTable(ident) match {
      case iceberg: SparkTable =>
        val snapshotId: java.lang.Long = tagOptions.snapshotId
          .orElse(Option(iceberg.table.currentSnapshot()).map(_.snapshotId()))
          .map(java.lang.Long.valueOf)
          .orNull

        Preconditions.checkArgument(snapshotId != null,
          "Cannot complete create or replace tag operation on %s, main has no snapshot", ident)

        val manageSnapshot = iceberg.table.manageSnapshots()
        val refExists = null != iceberg.table().refs().get(tag)

        if (create && replace && !refExists) {
          manageSnapshot.createTag(tag, snapshotId)
        } else if (replace) {
          manageSnapshot.replaceTag(tag, snapshotId)
        } else {
          if (refExists && ifNotExists) {
            return Nil
          }

          manageSnapshot.createTag(tag, snapshotId)
        }

        if (tagOptions.snapshotRefRetain.nonEmpty) {
          manageSnapshot.setMaxRefAgeMs(tag, tagOptions.snapshotRefRetain.get)
        }

        manageSnapshot.commit()

      case table =>
        throw new UnsupportedOperationException(s"Cannot create tag to non-Iceberg table: $table")
    }

    Nil
  }

  override def simpleString(maxFields: Int): String = {
    s"Create tag: $tag for table: ${ident.quoted}"
  }
}
