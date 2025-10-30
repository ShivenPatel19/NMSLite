package com.nmslite.database;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import org.flywaydb.core.Flyway;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import com.nmslite.Bootstrap;

/**
 * DatabaseMigrationService - Handles automatic database schema migration using Flyway
 *
 * <p>Runs Flyway migrations on application startup to create/update database tables.
 * Migration files: src/main/resources/db/migration/V{version}__{description}.sql</p>
 */
public class DatabaseMigrationService
{

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrationService.class);

    /**
     * Runs Flyway database migration on worker thread.
     *
     * @return Future that completes when migration is successful
     */
    public static Future<Void> runMigration()
    {
        var promise = Promise.<Void>promise();

        try
        {
            logger.info("Starting database migration");

            // Run migration on worker thread (blocking operation)
            Bootstrap.getVertx().executeBlocking(() ->
            {
                try
                {
                    // Get database configuration
                    var databaseConfig = Bootstrap.getConfig().getJsonObject("database");

                    var host = databaseConfig.getString("host", "localhost");

                    var port = databaseConfig.getInteger("port", 5432);

                    var database = databaseConfig.getString("database", "nmslite");

                    var user = databaseConfig.getString("user", "nmslite");

                    var password = databaseConfig.getString("password", "nmslite");

                    // Build JDBC URL
                    var jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);

                    logger.debug("Configuring Flyway with URL: {}", jdbcUrl);

                    // Configure Flyway
                    var flyway = Flyway.configure()
                            .dataSource(jdbcUrl, user, password)
                            .locations("classpath:db/migration")
                            .baselineOnMigrate(true)
                            .validateOnMigrate(true)
                            .load();

                    logger.debug("Executing Flyway migration");

                    // Execute migration
                    var result = flyway.migrate();

                    logger.info("Database migration completed successfully - {} migrations applied", result.migrationsExecuted);

                    return null;
                }
                catch (Exception exception)
                {
                    logger.error("Database migration failed: {}", exception.getMessage());

                    // Re-throw to propagate to .onFailure() - this is worker thread context, not service layer
                    throw exception;
                }
            })
            .onSuccess(v ->
            {
                logger.info("Migration worker thread completed");

                promise.complete();
            })
            .onFailure(cause ->
            {
                logger.error("Migration worker thread failed: {}", cause.getMessage());

                promise.fail(cause);
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in runMigration: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

}

