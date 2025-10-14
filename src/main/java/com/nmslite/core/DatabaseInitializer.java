package com.nmslite.core;

import com.nmslite.Bootstrap;

import com.nmslite.services.*;

import com.nmslite.services.impl.*;

import io.vertx.core.Future;

import io.vertx.core.Promise;

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
 */
public class DatabaseInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JsonObject databaseConfig;

    private Pool pgPool;

    // Service implementations
    private UserServiceImpl userService;

    private DeviceTypeServiceImpl deviceTypeService;

    private CredentialProfileServiceImpl credentialService;

    private DiscoveryProfileServiceImpl discoveryService;

    private DeviceServiceImpl deviceService;

    private MetricsServiceImpl metricsService;

    private AvailabilityServiceImpl availabilityService;

    /**
     * Creates a new DatabaseInitializer instance.
     *
     * @param databaseConfig Database configuration from application.conf
     */
    public DatabaseInitializer(JsonObject databaseConfig)
    {
        this.databaseConfig = databaseConfig;
    }

    /**
     * Initializes database connection, creates service implementations,
     * and registers all ProxyGen services on the event bus.
     *
     * @return Future that completes when all initialization is done
     */
    public Future<Void> initialize() {
        try
        {
            logger.info("Initializing database services");

            // Setup PostgreSQL connection
            return setupDatabaseConnection()
                    .compose(pool ->
                    {
                        this.pgPool = pool;

                        // Create all service implementations
                        setupAllServices();

                        // Register all services with ProxyGen
                        registerAllServiceProxies();

                        logger.info("Database initialization completed - all 7 services registered");

                        return Future.<Void>succeededFuture();
                    })
                    .onFailure(cause ->
                            logger.error("Failed to initialize database services: {}", cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in initialize: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Sets up PostgreSQL connection pool and validates connectivity.
     * Reads host, port, database, user, password, and pool size from config and
     * returns a shared Vert.x SQL Pool on success.
     *
     * @return Future resolving to an initialized Pool when the test connection succeeds
     */
    private Future<Pool> setupDatabaseConnection() {
        var promise = Promise.<Pool>promise();

        try {
            // Get database configuration
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

            var poolOptions = new PoolOptions()
                    .setMaxSize(maxSize);

            // Create PostgreSQL connection pool
            var pool = PgBuilder.pool()
                    .with(poolOptions)
                    .connectingTo(connectOptions)
                    .using(Bootstrap.getVertxInstance())
                    .build();

            // Test database connection
            pool.getConnection()
                    .onSuccess(connection ->
                    {
                        logger.info("Database connection established");

                        connection.close();

                        promise.complete(pool);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Database connection failed: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        } catch (Exception exception) {
            logger.error("Failed to setup database connection: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Instantiates all database service implementations backed by the shared PgPool.
     * This wires PgPool into each service (User, DeviceType, CredentialProfile,
     * DiscoveryProfile, Device, Metrics, Availability).
     * Note: Only DeviceService needs Vertx instance for event bus publishing.
     */
    private void setupAllServices() {
        try
        {
            this.userService = new UserServiceImpl(pgPool);

            this.deviceTypeService = new DeviceTypeServiceImpl(pgPool);

            this.credentialService = new CredentialProfileServiceImpl(pgPool);

            this.discoveryService = new DiscoveryProfileServiceImpl(pgPool);

            this.deviceService = new DeviceServiceImpl(pgPool);

            this.metricsService = new MetricsServiceImpl(pgPool);

            this.availabilityService = new AvailabilityServiceImpl(pgPool);

            logger.debug("All 7 service implementations created");
        }
        catch (Exception exception)
        {
            logger.error("Error in setupAllServices: {}", exception.getMessage());
        }
    }

    /**
     * Registers all service implementations with Vert.x ProxyGen on the event bus.
     * Binds each service to its SERVICE_ADDRESS via ServiceBinder so clients can use
     * generated proxies (e.g., UserService.createProxy()).
     *
     */
    private void registerAllServiceProxies() {
        try
        {
            // Create service binder
            var serviceBinder = new ServiceBinder(Bootstrap.getVertxInstance());

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
        }
        catch (Exception exception)
        {
            logger.error("Error in registerAllServiceProxies: {}", exception.getMessage());
        }
    }

    /**
     * Closes the database connection pool and cleans up resources.
     * Should be called during application shutdown.
     *
     * @return Future that completes when cleanup is done
     */
    public Future<Void> cleanup() {
        var promise = Promise.<Void>promise();

        try
        {
            logger.info("Cleaning up database resources");

            // Close database pool
            if (pgPool != null)
            {
                pgPool.close()
                        .onSuccess(v ->
                        {
                            logger.debug("Database connection pool closed");

                            promise.complete();
                        })
                        .onFailure(cause ->
                        {
                            logger.error("Failed to close database pool: {}", cause.getMessage());

                            promise.fail(cause);
                        });
            }
            else
            {
                promise.complete();
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in cleanup: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

}

