package com.nmslite.core;

import com.nmslite.services.*;

import com.nmslite.services.impl.*;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.Vertx;

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
 *
 * This class replaces DatabaseVerticle by performing all database initialization
 * tasks during application startup, before any verticles are deployed.
 *
 * Tasks performed:
 * - Creates PostgreSQL connection pool
 * - Instantiates all service implementations
 * - Registers all ProxyGen services on event bus
 *
 * Benefits:
 * - No thread consumed by idle verticle
 * - Database services ready before other verticles start
 * - Maintains ProxyGen architecture
 * - Clean separation of initialization logic
 */
public class DatabaseInitializer
{

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final Vertx vertx;

    private final JsonObject databaseConfig;

    private Pool pgPool;

    private ServiceBinder serviceBinder;

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
     * @param vertx Vert.x instance
     * @param databaseConfig Database configuration from application.conf
     */
    public DatabaseInitializer(Vertx vertx, JsonObject databaseConfig)
    {
        this.vertx = vertx;
        this.databaseConfig = databaseConfig;
    }

    /**
     * Initializes database connection, creates service implementations,
     * and registers all ProxyGen services on the event bus.
     *
     * @return Future that completes when all initialization is done
     */
    public Future<Void> initialize()
    {
        logger.info("🔧 Starting Database Initialization - Setting up all services");

        // Setup PostgreSQL connection
        return setupDatabaseConnection()
            .compose(pool ->
            {
                this.pgPool = pool;

                logger.info("✅ Database connection established");

                // Create all service implementations
                setupAllServices();

                // Register all services with ProxyGen
                registerAllServiceProxies();

                logger.info("🚀 Database initialization completed successfully with all 7 services");

                return Future.<Void>succeededFuture();
            })
            .onFailure(cause ->
                logger.error("❌ Failed to initialize database services", cause));
    }

    /**
     * Sets up PostgreSQL connection pool and validates connectivity.
     * Reads host, port, database, user, password, and pool size from config and
     * returns a shared Vert.x SQL Pool on success.
     *
     * @return Future resolving to an initialized Pool when the test connection succeeds
     */
    private Future<Pool> setupDatabaseConnection()
    {
        var promise = Promise.<Pool>promise();

        try
        {
            // Get database configuration
            var connectOptions = new PgConnectOptions()
                .setPort(databaseConfig.getInteger("port", 5432))
                .setHost(databaseConfig.getString("host", "localhost"))
                .setDatabase(databaseConfig.getString("database", "nmslite"))
                .setUser(databaseConfig.getString("user", "nmslite"))
                .setPassword(databaseConfig.getString("password", "nmslite"));

            var poolOptions = new PoolOptions()
                .setMaxSize(databaseConfig.getInteger("maxSize", 20));

            // Create PostgreSQL connection pool
            var pool = PgBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

            // Test database connection
            pool.getConnection()
                .onSuccess(connection ->
                {
                    logger.info("✅ Database connection test successful");

                    connection.close();

                    promise.complete(pool);
                })
                .onFailure(cause ->
                {
                    logger.error("❌ Database connection test failed", cause);

                    promise.fail(cause);
                });
        }
        catch (Exception exception)
        {
            logger.error("❌ Failed to setup database connection", exception);

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
    private void setupAllServices()
    {
        this.userService = new UserServiceImpl(pgPool);

        this.deviceTypeService = new DeviceTypeServiceImpl(pgPool);

        this.credentialService = new CredentialProfileServiceImpl(pgPool);

        this.discoveryService = new DiscoveryProfileServiceImpl(pgPool);

        this.deviceService = new DeviceServiceImpl(vertx, pgPool);

        this.metricsService = new MetricsServiceImpl(pgPool);

        this.availabilityService = new AvailabilityServiceImpl(pgPool);

        logger.info("🔧 All 7 service implementations created successfully");
    }

    /**
     * Registers all service implementations with Vert.x ProxyGen on the event bus.
     * Binds each service to its SERVICE_ADDRESS via ServiceBinder so clients can use
     * generated proxies (e.g., UserService.createProxy(vertx)).
     */
    private void registerAllServiceProxies()
    {
        try
        {
            // Create service binder
            this.serviceBinder = new ServiceBinder(vertx);

            // Register UserService
            serviceBinder
                .setAddress(UserService.SERVICE_ADDRESS)
                .register(UserService.class, userService);

            logger.info("📡 UserService registered at: {}", UserService.SERVICE_ADDRESS);

            // Register DeviceTypeService
            serviceBinder
                .setAddress(DeviceTypeService.SERVICE_ADDRESS)
                .register(DeviceTypeService.class, deviceTypeService);

            logger.info("📡 DeviceTypeService registered at: {}", DeviceTypeService.SERVICE_ADDRESS);

            // Register CredentialService
            serviceBinder
                .setAddress(CredentialProfileService.SERVICE_ADDRESS)
                .register(CredentialProfileService.class, credentialService);

            logger.info("📡 CredentialService registered at: {}", CredentialProfileService.SERVICE_ADDRESS);

            // Register DiscoveryService
            serviceBinder
                .setAddress(DiscoveryProfileService.SERVICE_ADDRESS)
                .register(DiscoveryProfileService.class, discoveryService);

            logger.info("📡 DiscoveryService registered at: {}", DiscoveryProfileService.SERVICE_ADDRESS);

            // Register DeviceService
            serviceBinder
                .setAddress(DeviceService.SERVICE_ADDRESS)
                .register(DeviceService.class, deviceService);

            logger.info("📡 DeviceService registered at: {}", DeviceService.SERVICE_ADDRESS);

            // Register MetricsService
            serviceBinder
                .setAddress(MetricsService.SERVICE_ADDRESS)
                .register(MetricsService.class, metricsService);

            logger.info("📡 MetricsService registered at: {}", MetricsService.SERVICE_ADDRESS);

            // Register AvailabilityService
            serviceBinder
                .setAddress(AvailabilityService.SERVICE_ADDRESS)
                .register(AvailabilityService.class, availabilityService);

            logger.info("📡 AvailabilityService registered at: {}", AvailabilityService.SERVICE_ADDRESS);

            logger.info("🎉 All 7 database services registered with ProxyGen successfully");
        }
        catch (Exception exception)
        {
            logger.error("❌ Failed to register service proxies", exception);

            throw new RuntimeException("Failed to register service proxies", exception);
        }
    }

    /**
     * Closes the database connection pool and cleans up resources.
     * Should be called during application shutdown.
     *
     * @return Future that completes when cleanup is done
     */
    public Future<Void> cleanup()
    {
        logger.info("🛑 Cleaning up database resources");

        var promise = Promise.<Void>promise();

        // Close database pool
        if (pgPool != null)
        {
            pgPool.close()
                .onSuccess(v ->
                {
                    logger.info("✅ Database connection pool closed");

                    promise.complete();
                })
                .onFailure(cause ->
                {
                    logger.error("❌ Failed to close database pool", cause);

                    promise.fail(cause);
                });
        }
        else
        {
            promise.complete();
        }

        return promise.future();
    }

}

