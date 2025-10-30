package com.nmslite.database;

import com.nmslite.Bootstrap;

import com.nmslite.services.*;

import com.nmslite.services.impl.*;

import io.vertx.core.Future;

import io.vertx.core.json.JsonObject;

import io.vertx.pgclient.PgBuilder;

import io.vertx.pgclient.PgConnectOptions;

import io.vertx.serviceproxy.ServiceBinder;

import io.vertx.sqlclient.Pool;

import io.vertx.sqlclient.PoolOptions;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * DatabaseInitializer - One-time Database Setup and Service Registration
 * <p>
 * This class replaces DatabaseVerticle by performing all database initialization
 * tasks during application startup, before any verticles are deployed.
 * <p>
 * Tasks performed:
 * - Creates PostgreSQL connection pool
 * - Instantiates all service implementations
 * - Registers all ProxyGen services on event bus
 * <p>
 * Benefits:
 * - No thread consumed by idle verticle
 * - Database services ready before other verticles start
 * - Maintains ProxyGen architecture
 * - Clean separation of initialization logic
 * <p>
 * Configuration Access:
 * - Uses Bootstrap.getConfig().getJsonObject("database") to access database configuration
 * - No parameters needed in constructor (follows architectural principle)
 */
public class DatabaseInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    private static Pool pgPool;

    private static DatabaseHelper databaseHelper;

    // Service implementations
    private UserServiceImpl userService;

    private DeviceTypeServiceImpl deviceTypeService;

    private CredentialProfileServiceImpl credentialService;

    private DiscoveryProfileServiceImpl discoveryService;

    private DeviceServiceImpl deviceService;

    private MetricsServiceImpl metricsService;

    private AvailabilityServiceImpl availabilityService;

    /**
     * Gets the shared PostgreSQL connection pool.
     * This method provides global access to the database pool from service implementations.
     *
     * @return The PostgreSQL connection pool, or null if not yet initialized
     */
    public static Pool getPool()
    {
        return pgPool;
    }

    /**
     * Gets the shared DatabaseHelper instance.
     * This method provides global access to the database helper from service implementations.

     * DatabaseHelper provides generic methods for executing database queries with
     * consistent error handling and logging, reducing boilerplate code in services.
     *
     * @return The DatabaseHelper instance, or null if not yet initialized
     */
    public static DatabaseHelper getDatabaseHelper()
    {
        return databaseHelper;
    }

    /**
     * Initializes database migration, connection, creates service implementations,
     * and registers all ProxyGen services on the event bus.
     *
     * @return Future that completes when all initialization is done
     */
    public Future<Void> initialize() {
        try
        {
            logger.info("Initializing database services");

            // Run database migration first, then setup connection
            return DatabaseMigrationService.runMigration()
                    .compose(v -> setupDatabaseConnection())
                    .compose(v -> setupAllServices())
                    .compose(v -> registerAllServiceProxies())
                    .onSuccess(v -> logger.info("Database initialization completed - all 7 services registered"))
                    .onFailure(cause -> logger.error("Failed to initialize database services: {}", cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in initialize: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Sets up PostgreSQL connection pool and validates connectivity.
     * Reads host, port, database, user, password, and pool size from Bootstrap.getConfig()
     * and returns a shared Vert.x SQL Pool on success.
     * Uses Direct Future pattern - delegates to pool.getConnection() which returns a Future.
     *
     * @return Future resolving when the test connection succeeds
     */
    private Future<Void> setupDatabaseConnection()
    {
        try
        {
            // Get database configuration from Bootstrap
            var databaseConfig = Bootstrap.getConfig().getJsonObject("database", new JsonObject());

            var port = databaseConfig.getInteger("port", 5432);

            var host = databaseConfig.getString("host", "localhost");

            var database = databaseConfig.getString("database", "nmslite");

            var user = databaseConfig.getString("user", "nmslite");

            var password = databaseConfig.getString("password", "nmslite");

            var maxSize = databaseConfig.getInteger("maxSize", 5);

            var connectOptions = new PgConnectOptions()
                    .setPort(port)
                    .setHost(host)
                    .setDatabase(database)
                    .setUser(user)
                    .setPassword(password);

            var poolOptions = new PoolOptions().setMaxSize(maxSize);

            // Create PostgreSQL connection pool
            var pool = PgBuilder.pool()
                    .with(poolOptions)
                    .connectingTo(connectOptions)
                    .using(Bootstrap.getVertx())
                    .build();

            // Test database connection
            return pool.getConnection()
                    .onSuccess(connection ->
                    {
                        logger.info("Database connection established");

                        connection.close();

                        pgPool = pool;

                        databaseHelper = new DatabaseHelper();

                        logger.info("DatabaseHelper initialized");
                    })
                    .onFailure(cause -> logger.error("Database connection failed: {}", cause.getMessage()))
                    .mapEmpty();
        }
        catch (Exception exception)
        {
            logger.error("Failed to setup database connection: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Instantiates all database service implementations.
     * Each service implementation accesses the database pool via DatabaseInitializer.getPool().
     * Note: Services no longer receive pgPool as constructor parameter.
     *
     * @return Future that completes when all services are created successfully
     */
    private Future<Void> setupAllServices()
    {
        try
        {
            this.userService = new UserServiceImpl();

            this.deviceTypeService = new DeviceTypeServiceImpl();

            this.credentialService = new CredentialProfileServiceImpl();

            this.discoveryService = new DiscoveryProfileServiceImpl();

            this.deviceService = new DeviceServiceImpl();

            this.metricsService = new MetricsServiceImpl();

            this.availabilityService = new AvailabilityServiceImpl();

            logger.debug("All 7 service implementations created");

            return Future.succeededFuture();
        }
        catch (Exception exception)
        {
            logger.error("Error in setupAllServices: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Registers all service implementations with Vert.x ProxyGen on the event bus.
     * Binds each service to its SERVICE_ADDRESS via ServiceBinder so clients can use
     * generated proxies (e.g., UserService.createProxy()).
     *
     * @return Future that completes when all services are registered successfully
     */
    private Future<Void> registerAllServiceProxies()
    {
        try
        {
            // Create service binder
            var serviceBinder = new ServiceBinder(Bootstrap.getVertx());

            // Register UserService
            serviceBinder
                    .setAddress(UserService.SERVICE_ADDRESS)
                    .register(UserService.class, userService);

            // Register DeviceTypeService
            serviceBinder
                    .setAddress(DeviceTypeService.SERVICE_ADDRESS)
                    .register(DeviceTypeService.class, deviceTypeService);

            // Register CredentialService
            serviceBinder
                    .setAddress(CredentialProfileService.SERVICE_ADDRESS)
                    .register(CredentialProfileService.class, credentialService);

            // Register DiscoveryService
            serviceBinder
                    .setAddress(DiscoveryProfileService.SERVICE_ADDRESS)
                    .register(DiscoveryProfileService.class, discoveryService);

            // Register DeviceService
            serviceBinder
                    .setAddress(DeviceService.SERVICE_ADDRESS)
                    .register(DeviceService.class, deviceService);

            // Register MetricsService
            serviceBinder
                    .setAddress(MetricsService.SERVICE_ADDRESS)
                    .register(MetricsService.class, metricsService);

            // Register AvailabilityService
            serviceBinder
                    .setAddress(AvailabilityService.SERVICE_ADDRESS)
                    .register(AvailabilityService.class, availabilityService);

            logger.debug("All 7 services registered with ProxyGen");

            return Future.succeededFuture();
        }
        catch (Exception exception)
        {
            logger.error("Error in registerAllServiceProxies: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Closes the database connection pool and cleans up resources.
     * Should be called during application shutdown.
     * Uses Direct Future pattern - delegates to pgPool.close() which returns a Future.
     *
     * @return Future that completes when cleanup is done
     */
    public Future<Void> cleanup()
    {
        try
        {
            logger.info("Cleaning up database resources");

            if (pgPool != null)
            {
                return pgPool.close()
                        .onSuccess(v -> logger.debug("Database connection pool closed"))
                        .onFailure(cause -> logger.error("Failed to close database pool: {}", cause.getMessage()));
            }
            else
            {
                return Future.succeededFuture();
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in cleanup: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}

