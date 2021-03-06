/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational.history;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.Configuration;
import io.debezium.document.Array;
import io.debezium.function.Predicates;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlParser;
import io.debezium.relational.history.TableChanges.TableChange;
import io.debezium.relational.history.TableChanges.TableChangeType;
import io.debezium.text.ParsingException;

/**
 * @author Randall Hauch
 *
 */
public abstract class AbstractDatabaseHistory implements DatabaseHistory {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected Configuration config;
    private HistoryRecordComparator comparator = HistoryRecordComparator.INSTANCE;
    private boolean skipUnparseableDDL;
    private Function<String, Optional<Pattern>> ddlFilter = (x -> Optional.empty());

    protected AbstractDatabaseHistory() {
    }

    @Override
    public void configure(Configuration config, HistoryRecordComparator comparator) {
        this.config = config;
        this.comparator = comparator != null ? comparator : HistoryRecordComparator.INSTANCE;
        this.skipUnparseableDDL = config.getBoolean(DatabaseHistory.SKIP_UNPARSEABLE_DDL_STATEMENTS);

        final String ddlFilter = config.getString(DatabaseHistory.DDL_FILTER);
        this.ddlFilter = (ddlFilter != null) ? Predicates.matchedBy(ddlFilter) : this.ddlFilter;
    }

    @Override
    public void start() {
        // do nothing
    }

    @Override
    public final void record(Map<String, ?> source, Map<String, ?> position, String databaseName, String ddl)
            throws DatabaseHistoryException {

        record(source, position, databaseName, null, ddl, null);
    }

    @Override
    public final void record(Map<String, ?> source, Map<String, ?> position, String databaseName, String schemaName, String ddl, TableChanges changes)
            throws DatabaseHistoryException {
            storeRecord(new HistoryRecord(source, position, databaseName, schemaName, ddl, changes));
    }

    @Override
    public final void recover(Map<String, ?> source, Map<String, ?> position, Tables schema, DdlParser ddlParser) {
        logger.debug("Recovering DDL history for source partition {} and offset {}", source, position);
        HistoryRecord stopPoint = new HistoryRecord(source, position, null, null, null, null);
        recoverRecords(recovered -> {
            if (comparator.isAtOrBefore(recovered, stopPoint)) {
                Array tableChanges = recovered.tableChanges();
                String ddl = recovered.ddl();

                if (tableChanges != null) {
                    TableChanges changes = TableChanges.fromArray(tableChanges);
                    for (TableChange entry : changes) {
                        if (entry.getType() == TableChangeType.CREATE || entry.getType() == TableChangeType.ALTER) {
                            schema.overwriteTable(entry.getTable());
                        }
                        // DROP
                        else {
                            schema.removeTable(entry.getId());
                        }
                    }
                }
                else if (ddl != null && ddlParser != null) {
                    if (recovered.databaseName() != null) {
                        ddlParser.setCurrentDatabase(recovered.databaseName()); // may be null
                    }
                    if (recovered.schemaName() != null) {
                        ddlParser.setCurrentSchema(recovered.schemaName()); // may be null
                    }
                    Optional<Pattern> filteredBy = ddlFilter.apply(ddl);
                    if (filteredBy.isPresent()) {
                        logger.info("a DDL '{}' was filtered out of processing by regular expression '{}", ddl, filteredBy.get());
                        return;
                    }
                    try {
                        logger.debug("Applying: {}", ddl);
                        ddlParser.parse(ddl, schema);
                    } catch (final ParsingException e) {
                        if (skipUnparseableDDL) {
                            logger.warn("Ignoring unparseable statements '{}' stored in database history: {}", ddl, e);
                        } else {
                            throw e;
                        }
                    }
                }
            } else {
                logger.debug("Skipping: {}", recovered.ddl());
            }
        });
    }

    protected abstract void storeRecord(HistoryRecord record) throws DatabaseHistoryException;

    protected abstract void recoverRecords(Consumer<HistoryRecord> records);

    @Override
    public void stop() {
        // do nothing
    }

    @Override
    public void initializeStorage() {
    }
}
