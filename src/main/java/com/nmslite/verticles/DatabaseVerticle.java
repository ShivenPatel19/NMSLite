package com.nmslite.verticles;

import com.nmslite.services.*;
import com.nmslite.services.impl.*;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DatabaseVerticle - Comprehensive Database Operations with Vert.x ProxyGen
 *
 * This verticle manages all database services using ProxyGen:
 * - UserService - User management operations
 * - DeviceTypeService - Device type management
 * - CredentialService - Credential profile management
 * - DiscoveryService - Discovery profile management
 * - DeviceService - Device management operations
 * - MetricsService - Device metrics and time-series data
 * - AvailabilityService - Device availability status tracking
 *
 * Features:
 * - PostgreSQL connection management
 * - Multiple service proxy registration
 * - Automatic event bus binding
 * - Health monitoring for all services
 * - Centralized database operations
 */
public class DatabaseVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseVerticle.class);

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

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("🔧 Starting DatabaseVerticle - Comprehensive Database Services");

        // Setup PostgreSQL connection
        setupDatabaseConnection()
            .onSuccess(pool -> {
                this.pgPool = pool;
                logger.info("✅ Database connection established");

                // Create all service implementations
                setupAllServices();

                // Register all services with ProxyGen
                registerAllServiceProxies();

                logger.info("🚀 DatabaseVerticle started successfully with all 7 services");
                startPromise.complete();
            })
            .onFailure(cause -> {
                logger.error("❌ Failed to start DatabaseVerticle", cause);
                startPromise.fail(cause);
            });
    }

    /**
     * Setup PostgreSQL connection pool
     */
    private Future<Pool> setupDatabaseConnection() {
        Promise<Pool> promise = Promise.promise();

        try {
            // Get database configuration
            PgConnectOptions connectOptions = new PgConnectOptions()
                .setPort(config().getInteger("port", 5432))
                .setHost(config().getString("host", "localhost"))
                .setDatabase(config().getString("database", "nmslite"))
                .setUser(config().getString("user", "nmslite"))
                .setPassword(config().getString("password", "nmslite"));

            PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(config().getInteger("maxSize", 20));

            Pool pool = Pool.pool(vertx, connectOptions, poolOptions);

            // Test database connection
            pool.getConnection()
                .onSuccess(connection -> {
                    logger.info("✅ Database connection test successful");
                    connection.close();
                    promise.complete(pool);
                })
                .onFailure(cause -> {
                    logger.error("❌ Database connection test failed", cause);
                    promise.fail(cause);
                });

        } catch (Exception e) {
            logger.error("❌ Failed to setup database connection", e);
            promise.fail(e);
        }

        return promise.future();
    }

    /**
     * Create all service implementations
     */
    private void setupAllServices() {
        PgPool pool = (PgPool) pgPool;

        this.userService = new UserServiceImpl(vertx, pool);
        this.deviceTypeService = new DeviceTypeServiceImpl(vertx, pool);
        this.credentialService = new CredentialProfileServiceImpl(vertx, pool);
        this.discoveryService = new DiscoveryProfileServiceImpl(vertx, pool);
        this.deviceService = new DeviceServiceImpl(vertx, pool);
        this.metricsService = new MetricsServiceImpl(vertx, pool);
        this.availabilityService = new AvailabilityServiceImpl(vertx, pool);

        logger.info("🔧 All 7 service implementations created successfully");
    }

    /**
     * Register all services with ProxyGen
     */
    private void registerAllServiceProxies() {
        try {
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

        } catch (Exception e) {
            logger.error("❌ Failed to register service proxies", e);
            throw new RuntimeException("Failed to register service proxies", e);
        }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("🛑 Stopping DatabaseVerticle");

        Promise<Void> closePoolPromise = Promise.promise();
        Promise<Void> unregisterServicePromise = Promise.promise();

        // Close database pool
        if (pgPool != null) {
            pgPool.close()
                .onSuccess(v -> {
                    logger.info("✅ Database connection pool closed");
                    closePoolPromise.complete();
                })
                .onFailure(cause -> {
                    logger.error("❌ Failed to close database pool", cause);
                    closePoolPromise.fail(cause);
                });
        } else {
            closePoolPromise.complete();
        }

        // Unregister all services
        if (serviceBinder != null) {
            try {
                // Note: ServiceBinder doesn't have unregister method in newer versions
                // The services will be automatically unregistered when verticle stops
                logger.info("✅ All 7 database services will be unregistered automatically");
                unregisterServicePromise.complete();
            } catch (Exception e) {
                logger.error("❌ Failed to unregister services", e);
                unregisterServicePromise.fail(e);
            }
        } else {
            unregisterServicePromise.complete();
        }

        // Wait for both operations to complete
        Future.all(closePoolPromise.future(), unregisterServicePromise.future())
            .onComplete(result -> {
                if (result.succeeded()) {
                    logger.info("✅ DatabaseVerticle stopped successfully");
                    stopPromise.complete();
                } else {
                    logger.error("❌ Failed to stop DatabaseVerticle", result.cause());
                    stopPromise.fail(result.cause());
                }
            });
    }

    /**
     * Get database pool (for testing or direct access)
     */
    public Pool getDatabasePool() {
        return pgPool;
    }

    /**
     * Get service implementations (for testing or direct access)
     */
    public UserServiceImpl getUserService() {
        return userService;
    }

    public DeviceTypeServiceImpl getDeviceTypeService() {
        return deviceTypeService;
    }

    public CredentialProfileServiceImpl getCredentialService() {
        return credentialService;
    }

    public DiscoveryProfileServiceImpl getDiscoveryService() {
        return discoveryService;
    }

    public DeviceServiceImpl getDeviceService() {
        return deviceService;
    }

    public MetricsServiceImpl getMetricsService() {
        return metricsService;
    }

    public AvailabilityServiceImpl getAvailabilityService() {
        return availabilityService;
    }
}
