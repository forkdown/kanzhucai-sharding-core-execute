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

package org.apache.shardingsphere.core.execute.metadata;

import com.google.common.base.Optional;
import lombok.SneakyThrows;
import org.apache.shardingsphere.core.execute.ShardingExecuteEngine;
import org.apache.shardingsphere.core.metadata.datasource.DataSourceMetaData;
import org.apache.shardingsphere.core.metadata.datasource.ShardingDataSourceMetaData;
import org.apache.shardingsphere.core.metadata.table.TableMetaData;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.core.rule.TableRule;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Table meta data initializer.
 *
 * @author zhangliang
 */
public final class TableMetaDataInitializer {

    private final ShardingDataSourceMetaData shardingDataSourceMetaData;

    private final TableMetaDataConnectionManager connectionManager;

    private final TableMetaDataLoader tableMetaDataLoader;

    public TableMetaDataInitializer(final ShardingDataSourceMetaData shardingDataSourceMetaData, final ShardingExecuteEngine executeEngine,
                                    final TableMetaDataConnectionManager connectionManager, final int maxConnectionsSizePerQuery, final boolean isCheckingMetaData) {
        this.shardingDataSourceMetaData = shardingDataSourceMetaData;
        this.connectionManager = connectionManager;
        tableMetaDataLoader = new TableMetaDataLoader(shardingDataSourceMetaData, executeEngine, connectionManager, maxConnectionsSizePerQuery, isCheckingMetaData);
    }

    /**
     * Load table meta data.
     *
     * @param logicTableName logic table name
     * @param shardingRule   sharding rule
     * @return table meta data
     */
    @SneakyThrows
    public TableMetaData load(final String logicTableName, final ShardingRule shardingRule) {
        return tableMetaDataLoader.load(logicTableName, shardingRule);
    }

    /**
     * Load all table meta data.
     *
     * @param shardingRule sharding rule
     * @return all table meta data
     */
    @SneakyThrows
    public Map<String, TableMetaData> load(final ShardingRule shardingRule) {
        Map<String, TableMetaData> result = new HashMap<>();
        result.putAll(loadShardingTables(shardingRule));
        result.putAll(loadDefaultTables(shardingRule));
        return result;
    }

    private Map<String, TableMetaData> loadShardingTables(final ShardingRule shardingRule) throws SQLException {
        final ConcurrentHashMap<String, TableMetaData> result = new ConcurrentHashMap<>(shardingRule.getTableRules().size(), 1);

        //Use multi threads to boost the start up .
        List<Thread> threadList = new ArrayList<>();
        for (final TableRule each : shardingRule.getTableRules()) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        result.put(each.getLogicTable(), tableMetaDataLoader.load(each.getLogicTable(), shardingRule));
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            });
            thread.start();
            threadList.add(thread);
        }

        //use 'join()' wait all thread finished.
        for (Thread thread : threadList) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return result;
    }

    private Map<String, TableMetaData> loadDefaultTables(final ShardingRule shardingRule) throws SQLException {
        final ConcurrentHashMap<String, TableMetaData> result = new ConcurrentHashMap<>(shardingRule.getTableRules().size(), 1);
        Optional<String> actualDefaultDataSourceName = shardingRule.findActualDefaultDataSourceName();
        if (actualDefaultDataSourceName.isPresent()) {

            //Use multi threads to boost the start up .
            Collection<String> allTableNames = getAllTableNames(actualDefaultDataSourceName.get());
            List<String> allTableNamesList = new ArrayList<>(allTableNames);
            List<List<String>> parts = new ArrayList<>();

            Integer elementNumber = 16;

            for (int i = 0, len = allTableNamesList.size(); i < len; i += elementNumber) {
                if (i + elementNumber <= len) {
                    parts.add(allTableNamesList.subList(i, i + elementNumber));
                } else {
                    parts.add(allTableNamesList.subList(i, len));
                }
            }

            for (List<String> eachPart : parts) {
                System.out.println("part:");
                for (String each : eachPart) {
                    System.out.println("    " + each);
                }
            }


            List<Thread> threadList = new ArrayList<>();
            for (final String each : allTableNames) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            result.put(each, tableMetaDataLoader.load(each, shardingRule));
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }
                });
                thread.start();
                threadList.add(thread);
            }

            //use 'join()' wait all thread finished.
            for (Thread thread : threadList) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        return result;
    }

    private Collection<String> getAllTableNames(final String dataSourceName) throws SQLException {
        Collection<String> result = new LinkedHashSet<>();
        DataSourceMetaData dataSourceMetaData = shardingDataSourceMetaData.getActualDataSourceMetaData(dataSourceName);
        String catalog = null == dataSourceMetaData ? null : dataSourceMetaData.getSchemaName();
        try (Connection connection = connectionManager.getConnection(dataSourceName);
             ResultSet resultSet = connection.getMetaData().getTables(catalog, getCurrentSchemaName(connection), null, new String[]{"TABLE"})) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                if (!tableName.contains("$") && !tableName.contains("/")) {
                    result.add(tableName);
                }
            }
        }
        return result;
    }

    private String getCurrentSchemaName(final Connection connection) throws SQLException {
        try {
            return connection.getSchema();
        } catch (final AbstractMethodError | SQLFeatureNotSupportedException ignore) {
            return null;
        }
    }
}
