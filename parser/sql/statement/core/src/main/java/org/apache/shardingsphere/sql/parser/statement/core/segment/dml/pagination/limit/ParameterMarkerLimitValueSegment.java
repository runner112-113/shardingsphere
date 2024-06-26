/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.sql.parser.statement.core.segment.dml.pagination.limit;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.pagination.ParameterMarkerPaginationValueSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.bounded.ColumnSegmentBoundedInfo;
import org.apache.shardingsphere.sql.parser.statement.core.value.identifier.IdentifierValue;

import java.util.Optional;

/**
 * Limit value segment for parameter marker.
 */
@Getter
@EqualsAndHashCode(exclude = "boundedInfo", callSuper = true)
public final class ParameterMarkerLimitValueSegment extends LimitValueSegment implements ParameterMarkerPaginationValueSegment {
    
    private final int parameterIndex;
    
    @Setter
    private ColumnSegmentBoundedInfo boundedInfo;
    
    public ParameterMarkerLimitValueSegment(final int startIndex, final int stopIndex, final int paramIndex) {
        super(startIndex, stopIndex);
        parameterIndex = paramIndex;
    }
    
    @Override
    public ColumnSegmentBoundedInfo getBoundedInfo() {
        return Optional.ofNullable(boundedInfo).orElseGet(() -> new ColumnSegmentBoundedInfo(new IdentifierValue("")));
    }
}
