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

package org.apache.shardingsphere.infra.metadata.schema.builder.spi;

import org.apache.shardingsphere.infra.database.type.DatabaseTypeAwareSPI;
import org.apache.shardingsphere.infra.metadata.schema.model.TableMetaData;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

/**
 * Dialect table meta data loader.
 */
public interface DialectTableMetaDataLoader extends DatabaseTypeAwareSPI {
    
    /**
     * Load table meta data.
     *
     * @param dataSource data source
     * @param existedTables existed tables
     * @return table meta data map
     * @throws SQLException SQL exception
     */
    Map<String, TableMetaData> load(DataSource dataSource, Collection<String> existedTables) throws SQLException;
    
    /**
     * Load table meta data with tables.
     *
     * @param dataSource data source
     * @param tables tables
     * @return table meta data map
     * @throws SQLException SQL exception
     */
    Map<String, TableMetaData> loadWithTables(DataSource dataSource, Collection<String> tables) throws SQLException;
}
