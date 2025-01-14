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

package org.apache.shardingsphere.infra.metadata.schema.builder;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.exception.ShardingSphereException;
import org.apache.shardingsphere.infra.metadata.schema.builder.loader.ColumnMetaDataLoader;
import org.apache.shardingsphere.infra.metadata.schema.builder.loader.SchemaMetaDataLoader;
import org.apache.shardingsphere.infra.metadata.schema.builder.loader.adapter.MetaDataLoaderConnectionAdapter;
import org.apache.shardingsphere.infra.metadata.schema.builder.spi.DialectTableMetaDataLoader;
import org.apache.shardingsphere.infra.metadata.schema.model.ColumnMetaData;
import org.apache.shardingsphere.infra.metadata.schema.model.TableMetaData;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.rule.identifier.type.DataNodeContainedRule;
import org.apache.shardingsphere.infra.rule.identifier.type.TableContainedRule;
import org.apache.shardingsphere.infra.spi.ShardingSphereServiceLoader;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Schema builder.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SchemaBuilder {
    
    private static final ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors() * 2, Runtime.getRuntime().availableProcessors() * 2,
            0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new ThreadFactoryBuilder().setDaemon(true).setNameFormat("ShardingSphere-SchemaBuilder-%d").build());
    
    static {
        ShardingSphereServiceLoader.register(DialectTableMetaDataLoader.class);
    }
    
    /**
     * build actual and logic table meta data.
     *
     * @param materials schema builder materials
     * @return actual and logic table meta data
     * @throws SQLException SQL exception
     */
    public static Map<TableMetaData, TableMetaData> build(final SchemaBuilderMaterials materials) throws SQLException {
        Map<String, TableMetaData> actualTableMetaMap = buildActualTableMetaDataMap(materials);
        Map<String, TableMetaData> logicTableMetaMap = buildLogicTableMetaDataMap(materials, actualTableMetaMap);
        Map<TableMetaData, TableMetaData> tableMetaDataMap = new HashMap<>(actualTableMetaMap.size(), 1);
        for (Entry<String, TableMetaData> entry : actualTableMetaMap.entrySet()) {
            tableMetaDataMap.put(entry.getValue(), logicTableMetaMap.getOrDefault(entry.getKey(), entry.getValue()));
        }
        return tableMetaDataMap;
    }
    
    private static Map<String, TableMetaData> buildActualTableMetaDataMap(final SchemaBuilderMaterials materials) throws SQLException {
        Map<String, TableMetaData> result = new HashMap<>(materials.getRules().size(), 1);
        appendRemainTables(materials, result);
        appendLogicTables(materials, result);
        return result;
    }
    
    private static void appendRemainTables(final SchemaBuilderMaterials materials, final Map<String, TableMetaData> tables) throws SQLException {
        Optional<DialectTableMetaDataLoader> dialectLoader = findDialectTableMetaDataLoader(materials);
        if (dialectLoader.isPresent()) {
            appendDialectRemainTables(dialectLoader.get(), materials, tables);
            return;
        }
        appendDefaultRemainTables(materials, tables);
    }
    
    private static void appendLogicTables(final SchemaBuilderMaterials materials, final Map<String, TableMetaData> result) throws SQLException {
        result.putAll(TableMetaDataBuilder.loadLogicTables(materials, EXECUTOR_SERVICE)
                        .stream().collect(Collectors.toMap(TableMetaData::getName, Function.identity(), (oldValue, currentValue) -> oldValue)));
    }
    
    private static Map<String, TableMetaData> buildLogicTableMetaDataMap(final SchemaBuilderMaterials materials, final Map<String, TableMetaData> tables) {
        Map<String, TableMetaData> result = new HashMap<>(materials.getRules().size(), 1);
        for (ShardingSphereRule rule : materials.getRules()) {
            if (rule instanceof TableContainedRule) {
                for (String table : ((TableContainedRule) rule).getTables()) {
                    if (tables.containsKey(table)) {
                        TableMetaData metaData = TableMetaDataBuilder.decorate(table, tables.get(table), materials.getRules());
                        result.put(table, metaData);
                    }
                }
            }
        }
        return result;
    }
    
    private static Optional<DialectTableMetaDataLoader> findDialectTableMetaDataLoader(final SchemaBuilderMaterials materials) {
        for (DialectTableMetaDataLoader each : ShardingSphereServiceLoader.getSingletonServiceInstances(DialectTableMetaDataLoader.class)) {
            if (each.getDatabaseType().equals(materials.getDatabaseType().getName())) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }
    
    private static void appendDialectRemainTables(final DialectTableMetaDataLoader dialectLoader, final SchemaBuilderMaterials materials, final Map<String, TableMetaData> tables) throws SQLException {
        Collection<Future<Map<String, TableMetaData>>> futures = new LinkedList<>();
        Collection<String> existedTables = getExistedTables(materials.getRules(), tables);
        for (DataSource each : materials.getDataSourceMap().values()) {
            futures.add(EXECUTOR_SERVICE.submit(() -> dialectLoader.load(each, existedTables)));
        }
        for (Future<Map<String, TableMetaData>> each : futures) {
            try {
                tables.putAll(each.get());
            } catch (final InterruptedException | ExecutionException ex) {
                if (ex.getCause() instanceof SQLException) {
                    throw (SQLException) ex.getCause();
                }
                throw new ShardingSphereException(ex);
            }
        }
    }
    
    private static void appendDefaultRemainTables(final SchemaBuilderMaterials materials, final Map<String, TableMetaData> tables) throws SQLException {
        Collection<String> existedTableNames = getExistedTables(materials.getRules(), tables);
        for (Entry<String, DataSource> entry : materials.getDataSourceMap().entrySet()) {
            Collection<String> tableNames = SchemaMetaDataLoader.loadAllTableNames(entry.getValue(), materials.getDatabaseType());
            tableNames.removeAll(existedTableNames);
            for (String each : tableNames) {
                tables.put(each, loadTableMetaData(each, entry.getValue(), materials.getDatabaseType()));
            }
        }
    }
    
    private static TableMetaData loadTableMetaData(final String tableName, final DataSource dataSource, final DatabaseType databaseType) throws SQLException {
        TableMetaData result = new TableMetaData(tableName);
        try (Connection connection = new MetaDataLoaderConnectionAdapter(databaseType, dataSource.getConnection())) {
            result.getColumns().putAll(loadColumnMetaDataMap(tableName, databaseType, connection));
        }
        return result;
    }
    
    private static Map<String, ColumnMetaData> loadColumnMetaDataMap(final String tableName, final DatabaseType databaseType, final Connection connection) throws SQLException {
        return ColumnMetaDataLoader.load(connection, tableName, databaseType).stream().collect(Collectors.toMap(ColumnMetaData::getName, each -> each, (a, b) -> b, LinkedHashMap::new));
    }
    
    private static Collection<String> getExistedTables(final Collection<ShardingSphereRule> rules, final Map<String, TableMetaData> tables) {
        Collection<String> result = new LinkedHashSet<>();
        for (ShardingSphereRule each : rules) {
            if (each instanceof DataNodeContainedRule) {
                result.addAll(((DataNodeContainedRule) each).getAllActualTables());
            }
        }
        result.addAll(tables.keySet());
        return result;
    }
}
