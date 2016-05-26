/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.NotThreadSafe;
import io.debezium.config.Configuration;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.TableSchema;
import io.debezium.relational.TableSchemaBuilder;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlChanges;
import io.debezium.relational.ddl.DdlChanges.DatabaseStatementStringConsumer;
import io.debezium.relational.history.DatabaseHistory;
import io.debezium.text.ParsingException;
import io.debezium.util.Collect;

/**
 * Component that records the schema history for databases hosted by a MySQL database server. The schema information includes
 * the {@link Tables table definitions} and the Kafka Connect {@link #schemaFor(TableId) Schema}s for each table, where the
 * {@link Schema} excludes any columns that have been {@link MySqlConnectorConfig#COLUMN_BLACKLIST specified} in the
 * configuration.
 * <p>
 * The history is changed by {@link #applyDdl(SourceInfo, String, String, DatabaseStatementStringConsumer) applying DDL
 * statements}, and every change is {@link DatabaseHistory persisted} as defined in the supplied {@link MySqlConnectorConfig MySQL
 * connector configuration}. This component can be reconstructed (e.g., on connector restart) and the history
 * {@link #loadHistory(SourceInfo) loaded} from persisted storage.
 * <p>
 * Note that when {@link #applyDdl(SourceInfo, String, String, DatabaseStatementStringConsumer) applying DDL statements}, the
 * caller is able to supply a {@link DatabaseStatementStringConsumer consumer function} that will be called with the DDL
 * statements and the database to which they apply, grouped by database names. However, these will only be called based when the
 * databases are included by the database filters defined in the {@link MySqlConnectorConfig MySQL connector configuration}.
 * 
 * @author Randall Hauch
 */
@NotThreadSafe
public class MySqlSchema {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Set<String> ignoredQueryStatements = Collect.unmodifiableSet("BEGIN", "END", "FLUSH PRIVILEGES");
    private final MySqlDdlParser ddlParser;
    private final Map<TableId, TableSchema> tableSchemaByTableId = new HashMap<>();
    private final Filters filters;
    private final DatabaseHistory dbHistory;
    private final TableSchemaBuilder schemaBuilder;
    private final DdlChanges ddlChanges;
    private Tables tables;

    /**
     * Create a schema component given the supplied {@link MySqlConnectorConfig MySQL connector configuration}.
     * 
     * @param config the connector configuration, which is presumed to be valid
     */
    public MySqlSchema(Configuration config) {
        this.filters = new Filters(config);
        this.ddlParser = new MySqlDdlParser(false);
        this.tables = new Tables();
        this.ddlChanges = new DdlChanges(this.ddlParser.terminator());
        this.ddlParser.addListener(ddlChanges);
        this.schemaBuilder = new TableSchemaBuilder();

        // Create and configure the database history ...
        this.dbHistory = config.getInstance(MySqlConnectorConfig.DATABASE_HISTORY, DatabaseHistory.class);
        if (this.dbHistory == null) {
            throw new ConnectException("Unable to instantiate the database history class " +
                    config.getString(MySqlConnectorConfig.DATABASE_HISTORY));
        }
        // Do not remove the prefix from the subset of config properties ...
        Configuration dbHistoryConfig = config.subset(DatabaseHistory.CONFIGURATION_FIELD_PREFIX_STRING, false);
        this.dbHistory.configure(dbHistoryConfig); // validates
    }

    /**
     * Start by acquiring resources needed to persist the database history
     */
    public void start() {
        this.dbHistory.start();
    }

    /**
     * Stop recording history and release any resources acquired since {@link #start()}.
     */
    public void shutdown() {
        this.dbHistory.stop();
    }

    /**
     * Get the {@link Filters database and table filters} defined by the configuration.
     * 
     * @return the filters; never null
     */
    public Filters filters() {
        return filters;
    }

    /**
     * Get all of the table definitions for all database tables as defined by
     * {@link #applyDdl(SourceInfo, String, String, DatabaseStatementStringConsumer) applied DDL statements}, including those
     * that have been excluded by the {@link #filters() filters}.
     * 
     * @return the table definitions; never null
     */
    public Tables tables() {
        return tables.subset(filters.tableFilter());
    }

    /**
     * Get the {@link TableSchema Schema information} for the table with the given identifier, if that table exists and is
     * included by the {@link #filters() filter}.
     * 
     * @param id the fully-qualified table identifier; may be null
     * @return the current table definition, or null if there is no table with the given identifier, if the identifier is null,
     *         or if the table has been excluded by the filters
     */
    public Table tableFor(TableId id) {
        return filters.tableFilter().test(id) ? tables.forTable(id) : null;
    }

    /**
     * Get the {@link TableSchema Schema information} for the table with the given identifier, if that table exists and is
     * included by the {@link #filters() filter}.
     * <p>
     * Note that the {@link Schema} will not contain any columns that have been {@link MySqlConnectorConfig#COLUMN_BLACKLIST
     * filtered out}.
     * 
     * @param id the fully-qualified table identifier; may be null
     * @return the schema information, or null if there is no table with the given identifier, if the identifier is null,
     *         or if the table has been excluded by the filters
     */
    public TableSchema schemaFor(TableId id) {
        return filters.tableFilter().test(id) ? tableSchemaByTableId.get(id) : null;
    }

    /**
     * Get the information about where the DDL statement history is recorded.
     * 
     * @return the history description; never null
     */
    public String historyLocation() {
        return dbHistory.toString();
    }

    /**
     * Load the database schema information using the previously-recorded history, and stop reading the history when the
     * the history reaches the supplied starting point.
     * 
     * @param startingPoint the source information with the current {@link SourceInfo#partition()} and {@link SourceInfo#offset()
     *            offset} at which the database schemas are to reflect; may not be null
     */
    public void loadHistory(SourceInfo startingPoint) {
        tables = new Tables();
        dbHistory.recover(startingPoint.partition(), startingPoint.offset(), tables, ddlParser);
        refreshSchemas();
    }

    /**
     * Discard any currently-cached schemas and rebuild them using the filters.
     */
    protected void refreshSchemas() {
        tableSchemaByTableId.clear();
        // Create TableSchema instances for any existing table ...
        this.tables.tableIds().forEach(id -> {
            Table table = this.tables.forTable(id);
            TableSchema schema = schemaBuilder.create(table, filters.columnFilter(), filters.columnMappers());
            tableSchemaByTableId.put(id, schema);
        });
    }

    /**
     * Apply the supplied DDL statements to this database schema and record the history. If a {@code statementConsumer} is
     * supplied, then call it for each sub-sequence of the DDL statements that all apply to the same database.
     * <p>
     * Typically DDL statements are applied using a connection to a single database, and unless the statements use fully-qualified
     * names, the DDL statements apply to this database.
     * 
     * @param source the current {@link SourceInfo#partition()} and {@link SourceInfo#offset() offset} at which these changes are
     *            found; may not be null
     * @param databaseName the name of the default database under which these statements are applied; may not be null
     * @param ddlStatements the {@code ;}-separated DDL statements; may be null or empty
     * @param statementConsumer the consumer that should be called with each sub-sequence of DDL statements that apply to
     *            a single database; may be null if no action is to be performed with the changes
     * @return {@code true} if changes were made to the database schema, or {@code false} if the DDL statements had no
     *         effect on the database schema
     */
    public boolean applyDdl(SourceInfo source, String databaseName, String ddlStatements,
                            DatabaseStatementStringConsumer statementConsumer) {
        if (ignoredQueryStatements.contains(ddlStatements)) return false;
        try {
            this.ddlChanges.reset();
            this.ddlParser.setCurrentSchema(databaseName);
            this.ddlParser.parse(ddlStatements, tables);
        } catch (ParsingException e) {
            logger.error("Error parsing DDL statement and updating tables: {}", ddlStatements, e);
        } finally {
            if (statementConsumer != null) {

                // We are supposed to _also_ record the schema changes as SourceRecords, but these need to be filtered
                // by database. Unfortunately, the databaseName on the event might not be the same database as that
                // being modified by the DDL statements (since the DDL statements can have fully-qualified names).
                // Therefore, we have to look at each statement to figure out which database it applies and then
                // record the DDL statements (still in the same order) to those databases.

                if (!ddlChanges.isEmpty() && ddlChanges.applyToMoreDatabasesThan(databaseName)) {

                    // We understood at least some of the DDL statements and can figure out to which database they apply.
                    // They also apply to more databases than 'databaseName', so we need to apply the DDL statements in
                    // the same order they were read for each _affected_ database, grouped together if multiple apply
                    // to the same _affected_ database...
                    ddlChanges.groupStatementStringsByDatabase((dbName, ddl) -> {
                        if (filters.databaseFilter().test(dbName)) {
                            statementConsumer.consume(databaseName, ddlStatements);
                        }
                    });
                } else if (filters.databaseFilter().test(databaseName)) {
                    statementConsumer.consume(databaseName, ddlStatements);
                }
            }

            // Record the DDL statement so that we can later recover them if needed. We do this _after_ writing the
            // schema change records so that failure recovery (which is based on of the history) won't lose
            // schema change records.
            dbHistory.record(source.partition(), source.offset(), databaseName, tables, ddlStatements);
        }

        // Figure out what changed ...
        Set<TableId> changes = tables.drainChanges();
        changes.forEach(tableId -> {
            Table table = tables.forTable(tableId);
            if (table == null) { // removed
                tableSchemaByTableId.remove(tableId);
            } else {
                TableSchema schema = schemaBuilder.create(table, filters.columnFilter(), filters.columnMappers());
                tableSchemaByTableId.put(tableId, schema);
            }
        });
        return true;
    }
}
