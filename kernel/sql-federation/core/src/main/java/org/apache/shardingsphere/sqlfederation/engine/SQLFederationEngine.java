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

package org.apache.shardingsphere.sqlfederation.engine;

import com.cedarsoftware.util.CaseInsensitiveSet;
import lombok.Getter;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.shardingsphere.infra.binder.context.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.context.statement.dml.SelectStatementContext;
import org.apache.shardingsphere.infra.datanode.DataNode;
import org.apache.shardingsphere.infra.exception.core.ShardingSpherePreconditions;
import org.apache.shardingsphere.infra.exception.dialect.exception.syntax.table.NoSuchTableException;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.driver.jdbc.JDBCExecutionUnit;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.driver.jdbc.JDBCExecutor;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.driver.jdbc.JDBCExecutorCallback;
import org.apache.shardingsphere.infra.executor.sql.execute.result.ExecuteResult;
import org.apache.shardingsphere.infra.executor.sql.prepare.driver.DriverExecutionPrepareEngine;
import org.apache.shardingsphere.infra.executor.sql.process.ProcessEngine;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.rule.RuleMetaData;
import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereTable;
import org.apache.shardingsphere.infra.metadata.database.schema.util.SystemSchemaUtils;
import org.apache.shardingsphere.infra.metadata.statistics.ShardingSphereStatistics;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.spi.type.ordered.OrderedSPILoader;
import org.apache.shardingsphere.sqlfederation.executor.context.SQLFederationBindContext;
import org.apache.shardingsphere.sqlfederation.executor.context.SQLFederationContext;
import org.apache.shardingsphere.sqlfederation.executor.context.SQLFederationExecutorContext;
import org.apache.shardingsphere.sqlfederation.executor.enumerable.EnumerableScanExecutor;
import org.apache.shardingsphere.sqlfederation.optimizer.SQLFederationCompilerEngine;
import org.apache.shardingsphere.sqlfederation.optimizer.SQLFederationExecutionPlan;
import org.apache.shardingsphere.sqlfederation.optimizer.context.OptimizerContext;
import org.apache.shardingsphere.sqlfederation.optimizer.context.planner.OptimizerMetaData;
import org.apache.shardingsphere.sqlfederation.optimizer.exception.SQLFederationSchemaNotFoundException;
import org.apache.shardingsphere.sqlfederation.optimizer.exception.SQLFederationUnsupportedSQLException;
import org.apache.shardingsphere.sqlfederation.optimizer.metadata.schema.SQLFederationTable;
import org.apache.shardingsphere.sqlfederation.optimizer.planner.cache.ExecutionPlanCacheKey;
import org.apache.shardingsphere.sqlfederation.optimizer.planner.util.SQLFederationPlannerUtils;
import org.apache.shardingsphere.sqlfederation.optimizer.statement.SQLStatementCompiler;
import org.apache.shardingsphere.sqlfederation.resultset.SQLFederationResultSet;
import org.apache.shardingsphere.sqlfederation.rule.SQLFederationRule;
import org.apache.shardingsphere.sqlfederation.spi.SQLFederationDecider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * SQL federation engine.
 */
@Getter
public final class SQLFederationEngine implements AutoCloseable {
    
    private static final int DEFAULT_METADATA_VERSION = 0;
    
    private static final JavaTypeFactory DEFAULT_DATA_TYPE_FACTORY = new JavaTypeFactoryImpl();
    
    private final ProcessEngine processEngine = new ProcessEngine();
    
    @SuppressWarnings("rawtypes")
    private final Map<ShardingSphereRule, SQLFederationDecider> deciders;
    
    private final String defaultDatabaseName;
    
    private final String defaultSchemaName;
    
    private final ShardingSphereMetaData metaData;
    
    private final ShardingSphereStatistics statistics;
    
    private final JDBCExecutor jdbcExecutor;
    
    private final SQLFederationRule sqlFederationRule;
    
    private ResultSet resultSet;
    
    public SQLFederationEngine(final String defaultDatabaseName, final String defaultSchemaName, final ShardingSphereMetaData metaData, final ShardingSphereStatistics statistics,
                               final JDBCExecutor jdbcExecutor) {
        deciders = OrderedSPILoader.getServices(SQLFederationDecider.class, metaData.getDatabase(defaultDatabaseName).getRuleMetaData().getRules());
        this.defaultDatabaseName = defaultDatabaseName;
        this.defaultSchemaName = defaultSchemaName;
        this.metaData = metaData;
        this.statistics = statistics;
        this.jdbcExecutor = jdbcExecutor;
        sqlFederationRule = metaData.getGlobalRuleMetaData().getSingleRule(SQLFederationRule.class);
    }
    
    /**
     * Decide use SQL federation or not.
     *
     * @param sqlStatementContext SQL statement context
     * @param parameters SQL parameters
     * @param defaultDatabase default database
     * @param globalRuleMetaData global rule meta data
     * @return use SQL federation or not
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean decide(final SQLStatementContext sqlStatementContext, final List<Object> parameters, final ShardingSphereDatabase defaultDatabase, final RuleMetaData globalRuleMetaData) {
        // TODO BEGIN: move this logic to SQLFederationDecider implement class when we remove sql federation type
        if (isQuerySystemSchema(sqlStatementContext, defaultDatabase)) {
            return true;
        }
        // TODO END
        // 是否开启联邦查询
        boolean sqlFederationEnabled = sqlFederationRule.getConfiguration().isSqlFederationEnabled();
        if (!sqlFederationEnabled || !(sqlStatementContext instanceof SelectStatementContext)) {
            return false;
        }
        // 是否全部查询 SQL 使用联邦查询
        boolean allQueryUseSQLFederation = sqlFederationRule.getConfiguration().isAllQueryUseSQLFederation();
        if (allQueryUseSQLFederation) {
            return true;
        }
        SelectStatementContext selectStatementContext = (SelectStatementContext) sqlStatementContext;
        Collection<String> usedDatabaseNames = getUsedDatabaseNames(selectStatementContext, defaultDatabase);
        if (usedDatabaseNames.size() > 1) {
            return true;
        }
        ShardingSphereDatabase currentDatabase = metaData.getDatabase(usedDatabaseNames.iterator().next());
        Collection<DataNode> includedDataNodes = new HashSet<>();
        for (Entry<ShardingSphereRule, SQLFederationDecider> entry : deciders.entrySet()) {
            boolean isUseSQLFederation = entry.getValue().decide(selectStatementContext, parameters, globalRuleMetaData, currentDatabase, entry.getKey(), includedDataNodes);
            if (isUseSQLFederation) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isQuerySystemSchema(final SQLStatementContext sqlStatementContext, final ShardingSphereDatabase defaultDatabase) {
        if (!(sqlStatementContext instanceof SelectStatementContext)) {
            return false;
        }
        SelectStatementContext selectStatementContext = (SelectStatementContext) sqlStatementContext;
        ShardingSphereDatabase database = selectStatementContext.getTablesContext().getDatabaseNames().stream().map(metaData::getDatabase).findFirst().orElse(defaultDatabase);
        return SystemSchemaUtils.containsSystemSchema(sqlStatementContext.getDatabaseType(), selectStatementContext.getTablesContext().getSchemaNames(), database)
                || SystemSchemaUtils.isOpenGaussSystemCatalogQuery(sqlStatementContext.getDatabaseType(), selectStatementContext.getSqlStatement().getProjections().getProjections());
    }
    
    private Collection<String> getUsedDatabaseNames(final SelectStatementContext selectStatementContext, final ShardingSphereDatabase defaultDatabase) {
        Collection<String> result = new CaseInsensitiveSet<>(selectStatementContext.getTablesContext().getDatabaseNames());
        result.add(defaultDatabase.getName());
        return result;
    }
    
    /**
     * Execute query.
     *
     * @param prepareEngine prepare engine
     * @param callback callback
     * @param federationContext federation context
     * @return result set
     * @throws SQLFederationUnsupportedSQLException SQL federation unsupported SQL exception
     */
    public ResultSet executeQuery(final DriverExecutionPrepareEngine<JDBCExecutionUnit, Connection> prepareEngine,
                                  final JDBCExecutorCallback<? extends ExecuteResult> callback, final SQLFederationContext federationContext) {
        try {
            String databaseName = federationContext.getQueryContext().getDatabaseNameFromSQLStatement().orElse(defaultDatabaseName);
            String schemaName = federationContext.getQueryContext().getSchemaNameFromSQLStatement().orElse(defaultSchemaName);
            OptimizerMetaData optimizerMetaData = sqlFederationRule.getOptimizerContext().getMetaData(databaseName);
            CalciteConnectionConfig connectionConfig = new CalciteConnectionConfigImpl(sqlFederationRule.getOptimizerContext().getParserContext(databaseName).getDialectProps());
            CalciteCatalogReader catalogReader = SQLFederationPlannerUtils.createCatalogReader(schemaName, optimizerMetaData.getSchema(schemaName), DEFAULT_DATA_TYPE_FACTORY, connectionConfig);
            SqlValidator validator = SQLFederationPlannerUtils.createSqlValidator(catalogReader, DEFAULT_DATA_TYPE_FACTORY,
                    sqlFederationRule.getOptimizerContext().getParserContext(databaseName).getDatabaseType(), connectionConfig);
            SqlToRelConverter converter = SQLFederationPlannerUtils.createSqlToRelConverter(catalogReader, validator, SQLFederationPlannerUtils.createRelOptCluster(DEFAULT_DATA_TYPE_FACTORY),
                    sqlFederationRule.getOptimizerContext().getSqlParserRule(), sqlFederationRule.getOptimizerContext().getParserContext(databaseName).getDatabaseType(), true);
            Schema sqlFederationSchema = catalogReader.getRootSchema().plus().getSubSchema(schemaName);
            ShardingSpherePreconditions.checkNotNull(sqlFederationSchema, () -> new SQLFederationSchemaNotFoundException(federationContext.getQueryContext().getSql()));
            SQLFederationExecutionPlan executionPlan = compileQuery(prepareEngine, callback, federationContext, databaseName, schemaName, sqlFederationSchema, converter);
            resultSet = executePlan(federationContext, executionPlan, validator, converter, sqlFederationSchema);
            return resultSet;
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            throw new SQLFederationUnsupportedSQLException(federationContext.getQueryContext().getSql(), ex);
        }
    }
    
    private SQLFederationExecutionPlan compileQuery(final DriverExecutionPrepareEngine<JDBCExecutionUnit, Connection> prepareEngine, final JDBCExecutorCallback<? extends ExecuteResult> callback,
                                                    final SQLFederationContext federationContext, final String databaseName, final String schemaName, final Schema sqlFederationSchema,
                                                    final SqlToRelConverter converter) {
        SQLStatementContext sqlStatementContext = federationContext.getQueryContext().getSqlStatementContext();
        ShardingSpherePreconditions.checkState(sqlStatementContext instanceof SelectStatementContext, () -> new IllegalArgumentException("SQL statement context must be select statement context."));
        registerTableScanExecutor(sqlFederationSchema, prepareEngine, callback, federationContext, sqlFederationRule.getOptimizerContext(), databaseName, schemaName);
        SQLStatementCompiler sqlStatementCompiler = new SQLStatementCompiler(converter);
        SQLFederationCompilerEngine compilerEngine = new SQLFederationCompilerEngine(databaseName, schemaName, sqlFederationRule.getConfiguration().getExecutionPlanCache());
        // TODO open useCache flag when ShardingSphereTable contains version
        return compilerEngine.compile(buildCacheKey(federationContext, (SelectStatementContext) sqlStatementContext, sqlStatementCompiler, databaseName, schemaName), false);
    }
    
    @SuppressWarnings("unchecked")
    private ResultSet executePlan(final SQLFederationContext federationContext, final SQLFederationExecutionPlan executionPlan, final SqlValidator validator, final SqlToRelConverter converter,
                                  final Schema sqlFederationSchema) {
        try {
            Bindable<Object> executablePlan = EnumerableInterpretable.toBindable(Collections.emptyMap(), null, (EnumerableRel) executionPlan.getPhysicalPlan(), EnumerableRel.Prefer.ARRAY);
            Map<String, Object> params = createParameters(federationContext.getQueryContext().getParameters());
            Enumerator<Object> enumerator = executablePlan.bind(new SQLFederationBindContext(validator, converter, params)).enumerator();
            return new SQLFederationResultSet(enumerator, sqlFederationSchema, (SelectStatementContext) federationContext.getQueryContext().getSqlStatementContext(),
                    executionPlan.getResultColumnType());
        } finally {
            processEngine.completeSQLExecution(federationContext.getProcessId());
        }
    }
    
    private ExecutionPlanCacheKey buildCacheKey(final SQLFederationContext federationContext, final SelectStatementContext selectStatementContext,
                                                final SQLStatementCompiler sqlStatementCompiler, final String databaseName, final String schemaName) {
        ShardingSphereSchema schema = federationContext.getMetaData().getDatabase(databaseName).getSchema(schemaName);
        ExecutionPlanCacheKey result =
                new ExecutionPlanCacheKey(federationContext.getQueryContext().getSql(), selectStatementContext.getSqlStatement(), selectStatementContext.getDatabaseType().getType(),
                        sqlStatementCompiler);
        for (String each : selectStatementContext.getTablesContext().getTableNames()) {
            ShardingSphereTable table = schema.getTable(each);
            ShardingSpherePreconditions.checkNotNull(table, () -> new NoSuchTableException(each));
            // TODO replace DEFAULT_METADATA_VERSION with actual version in ShardingSphereTable
            result.getTableMetaDataVersions().put(table.getName(), DEFAULT_METADATA_VERSION);
        }
        return result;
    }
    
    private void registerTableScanExecutor(final Schema sqlFederationSchema, final DriverExecutionPrepareEngine<JDBCExecutionUnit, Connection> prepareEngine,
                                           final JDBCExecutorCallback<? extends ExecuteResult> callback, final SQLFederationContext federationContext,
                                           final OptimizerContext optimizerContext, final String databaseName, final String schemaName) {
        if (null == sqlFederationSchema) {
            return;
        }
        SQLFederationExecutorContext executorContext = new SQLFederationExecutorContext(databaseName, schemaName, metaData.getProps());
        EnumerableScanExecutor scanExecutor =
                new EnumerableScanExecutor(prepareEngine, jdbcExecutor, callback, optimizerContext, executorContext, federationContext, metaData.getGlobalRuleMetaData(), statistics);
        // TODO register only the required tables
        for (ShardingSphereTable each : metaData.getDatabase(databaseName).getSchema(schemaName).getTables().values()) {
            Table table = sqlFederationSchema.getTable(each.getName());
            if (table instanceof SQLFederationTable) {
                ((SQLFederationTable) table).setScanExecutor(scanExecutor);
            }
        }
    }
    
    private Map<String, Object> createParameters(final List<Object> params) {
        Map<String, Object> result = new HashMap<>(params.size(), 1F);
        int index = 0;
        for (Object each : params) {
            result.put("?" + index++, each);
        }
        return result;
    }
    
    @Override
    public void close() throws SQLException {
        if (null != resultSet) {
            resultSet.close();
        }
    }
}
