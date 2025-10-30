package com.nmslite.database;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.sqlclient.Row;

import io.vertx.sqlclient.RowSet;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * DatabaseHelper - Generic database query execution utility
 *
 * <p>Provides consistent database query execution with automatic connection pool retrieval,
 * error handling, and Promise/Future management for async operations.</p>
 */
public class DatabaseHelper
{

    private static final Logger logger = LoggerFactory.getLogger(DatabaseHelper.class);

    /**
     * Execute a simple query without parameters.
     *
     * @param sql SQL query string
     * @return Future containing RowSet with query results
     */
    public Future<RowSet<Row>> executeQuery(String sql)
    {
        var promise = Promise.<RowSet<Row>>promise();

        try
        {
            var pool = DatabaseInitializer.getPool();

            if (pool == null)
            {
                var errorMessage = "Database pool is not initialized";

                logger.error(errorMessage);

                promise.fail(new Exception(errorMessage));

                return promise.future();
            }

            pool.query(sql)
                .execute()
                .onSuccess(rows ->
                {
                    logger.debug("Query executed successfully, returned {} rows", rows.size());

                    promise.complete(rows);
                })
                .onFailure(cause ->
                {
                    logger.error("Query execution failed: {}", cause.getMessage());

                    promise.fail(cause);
                });
        }
        catch (Exception exception)
        {
            logger.error("Database query error: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Execute a prepared query with parameters.
     *
     * @param sql SQL query string with placeholders ($1, $2, etc.)
     * @param params Tuple of parameters to bind to the query
     * @return Future containing RowSet with query results
     */
    public Future<RowSet<Row>> executePreparedQuery(String sql, Tuple params)
    {
        var promise = Promise.<RowSet<Row>>promise();

        try
        {
            var pool = DatabaseInitializer.getPool();

            if (pool == null)
            {
                var errorMessage = "Database pool is not initialized";

                logger.error(errorMessage);

                promise.fail(new Exception(errorMessage));

                return promise.future();
            }

            pool.preparedQuery(sql)
                .execute(params)
                .onSuccess(rows ->
                {
                    logger.debug("Prepared query executed successfully, returned {} rows", rows.size());

                    promise.complete(rows);
                })
                .onFailure(cause ->
                {
                    logger.error("Prepared query execution failed: {}", cause.getMessage());

                    promise.fail(cause);
                });
        }
        catch (Exception exception)
        {
            logger.error("Database prepared query error: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

}

