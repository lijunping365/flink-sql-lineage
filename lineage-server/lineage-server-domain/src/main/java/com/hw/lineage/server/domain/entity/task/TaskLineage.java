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

package com.hw.lineage.server.domain.entity.task;

import com.hw.lineage.server.domain.vo.SqlId;
import com.hw.lineage.server.domain.vo.TaskId;
import lombok.Data;
import lombok.experimental.Accessors;

import static com.hw.lineage.common.util.Constant.DELIMITER;

/**
 * @description: TaskLineage
 * @author: HamaWhite
 */
@Data
@Accessors(chain = true)
public class TaskLineage {

    private TaskId taskId;

    private SqlId sqlId;

    private String sourceCatalog;

    private String sourceDatabase;

    private String sourceTable;

    private String sourceColumn;

    private String targetCatalog;

    private String targetDatabase;

    private String targetTable;

    private String targetColumn;

    private String transform;

    private Boolean invalid;

    public String buildSourceTableName() {
        return String.join(DELIMITER, sourceCatalog, sourceDatabase, sourceTable);
    }

    public String buildTargetTableName() {
        return String.join(DELIMITER, targetCatalog, targetDatabase, targetTable);
    }

    public String buildSourceColumnName() {
        return String.join(DELIMITER, buildSourceTableName(), sourceColumn);
    }

    public String buildTargetColumnName() {
        return String.join(DELIMITER, buildTargetTableName(), targetColumn);
    }

}
