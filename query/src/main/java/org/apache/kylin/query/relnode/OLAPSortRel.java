/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.query.relnode;

import com.google.common.base.Preconditions;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.EnumerableSort;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexNode;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.StorageContext;

/**
 */
public class OLAPSortRel extends Sort implements EnumerableRel, OLAPRel {

    private ColumnRowType columnRowType;
    private OLAPContext context;

    public OLAPSortRel(RelOptCluster cluster, RelTraitSet traitSet, RelNode child, RelCollation collation, RexNode offset, RexNode fetch) {
        super(cluster, traitSet, child, collation, offset, fetch);
        Preconditions.checkArgument(getConvention() == OLAPRel.CONVENTION);
        Preconditions.checkArgument(getConvention() == child.getConvention());
    }

    @Override
    public OLAPSortRel copy(RelTraitSet traitSet, RelNode newInput, RelCollation newCollation, RexNode offset, RexNode fetch) {
        return new OLAPSortRel(getCluster(), traitSet, newInput, newCollation, offset, fetch);
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner) {
        return super.computeSelfCost(planner).multiplyBy(.05);
    }

    @Override
    public void implementOLAP(OLAPImplementor implementor) {
        implementor.visitChild(getInput(), this);

        this.context = implementor.getContext();
        this.columnRowType = buildColumnRowType();
    }

    private ColumnRowType buildColumnRowType() {
        OLAPRel olapChild = (OLAPRel) getInput();
        ColumnRowType inputColumnRowType = olapChild.getColumnRowType();
        return inputColumnRowType;
    }

    @Override
    public void implementRewrite(RewriteImplementor implementor) {
        implementor.visitChild(this, getInput());

        for (RelFieldCollation fieldCollation : this.collation.getFieldCollations()) {
            int index = fieldCollation.getFieldIndex();
            StorageContext.OrderEnum order = getOrderEnum(fieldCollation.getDirection());
            OLAPRel olapChild = (OLAPRel) this.getInput();
            TblColRef orderCol = olapChild.getColumnRowType().getAllColumns().get(index);
            MeasureDesc measure = findMeasure(orderCol);
            if (measure != null) {
                this.context.storageContext.addSort(measure, order);
            }
            this.context.storageContext.markSort();
        }

        this.rowType = this.deriveRowType();
        this.columnRowType = buildColumnRowType();
    }

    private StorageContext.OrderEnum getOrderEnum(RelFieldCollation.Direction direction) {
        if (direction == RelFieldCollation.Direction.DESCENDING) {
            return StorageContext.OrderEnum.DESCENDING;
        } else {
            return StorageContext.OrderEnum.ASCENDING;
        }
    }

    private MeasureDesc findMeasure(TblColRef col) {
        for (MeasureDesc measure : this.context.realization.getMeasures()) {
            if (col.getName().equals(measure.getFunction().getRewriteFieldName())) {
                return measure;
            }
        }
        return null;
    }

    @Override
    public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
        OLAPRel childRel = (OLAPRel) getInput();
        childRel.replaceTraitSet(EnumerableConvention.INSTANCE);

        EnumerableSort enumSort = new EnumerableSort(getCluster(), getCluster().traitSetOf(EnumerableConvention.INSTANCE, collation), getInput(), collation, offset, fetch);

        Result res = enumSort.implement(implementor, pref);

        childRel.replaceTraitSet(OLAPRel.CONVENTION);

        return res;
    }

    @Override
    public OLAPContext getContext() {
        return context;
    }

    @Override
    public ColumnRowType getColumnRowType() {
        return columnRowType;
    }

    @Override
    public boolean hasSubQuery() {
        OLAPRel olapChild = (OLAPRel) getInput();
        return olapChild.hasSubQuery();
    }

    @Override
    public RelTraitSet replaceTraitSet(RelTrait trait) {
        RelTraitSet oldTraitSet = this.traitSet;
        this.traitSet = this.traitSet.replace(trait);
        return oldTraitSet;
    }

}
