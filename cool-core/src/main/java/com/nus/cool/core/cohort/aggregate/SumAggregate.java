package com.nus.cool.core.cohort.aggregate;

import com.nus.cool.core.cohort.storage.ProjectedTuple;
import com.nus.cool.core.cohort.storage.RetUnit;

/**
 * Sum aggregator.
 */
public class SumAggregate implements AggregateFunc {

  private final AggregateType type = AggregateType.SUM;

  private String schema;

  public SumAggregate(String schema) {
    this.schema = schema;
  }

  @Override
  public void calculate(RetUnit retUnit, ProjectedTuple tuple) {
    float value = tuple.getValueBySchema(this.schema).getFloat();
    retUnit.setValue(retUnit.getValue() + value);
    retUnit.setCount(retUnit.getCount() + 1);
  }

  @Override
  public AggregateType getType() {
    return this.type;
  }

  @Override
  public String getSchema() {
    return null;
  }
}
