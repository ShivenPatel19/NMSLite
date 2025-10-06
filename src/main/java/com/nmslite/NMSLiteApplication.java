package com.nmslite;

import com.nmslite.utils.LoggingConfigurator;

import com.nmslite.verticles.DatabaseVerticle;

import com.nmslite.verticles.DiscoveryVerticle;

import com.nmslite.verticles.PollingMetricsVerticle;

import com.nmslite.verticles.ServerVerticle;

import io.vertx.config.ConfigRetriever;

import io.vertx.config.ConfigRetrieverOptions;

import io.vertx.config.ConfigStoreOptions;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.CompositeFuture;

import io.vertx.core.DeploymentOptions;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.WorkerExecutor;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;

/**
 * NMSLite Application - 4-Verticle Event-Driven Architecture with ProxyGen
 *
 * 4-Verticle Architecture:
 * - ServerVerticle: HTTP API server
 * - DatabaseVerticle: All database operations (ProxyGen enabled)
 * - DiscoveryVerticle: Device discovery workflow
 * - PollingMetricsVerticle: Continuous device monitoring (configurable enable/disable)
 * - All services: ProxyGen enabled for event bus communication
 *
 * Features:
 * - Graceful deployment failure cleanup (undeploy successful verticles on any failure)
 * - Comprehensive shutdown handling (SIGTERM/SIGINT support)
 * - Configurable verticle deployment (polling can be enabled/disabled via config)
 *
 * Communication: Event Bus driven with async messaging + ProxyGen services
 */
public class NMSLiteApplication extends AbstractVerticle
{

    private static final Logger logger = LoggerFactory.getLogger(NMSLiteApplication.class);

    // Shared Worker executor for blocking operations
    private WorkerExecutor workerExecutor;

    // Track deployed verticles for cleanup
    private String databaseVerticleId;

    private String serverVerticleId;

    private String pollingVerticleId;

    private String discoveryVerticleId;

    /**
     * Main entry point for the NMSLite application.
     * Initializes Vert.x instance, deploys the main application verticle, and sets up shutdown hooks.
     *
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args)
    {
        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(new NMSLiteApplication())
                .onSuccess(id ->
                {
                    System.out.println("🎉 NMSLite Application started successfully!");

                    System.out.println("📡 HTTP API available at: http://localhost:8080");

                    // Add shutdown hook for graceful shutdown
                    Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    {
                        System.out.println("🛑 Shutdown signal received, stopping application gracefully...");

                        vertx.close()
                                .onSuccess(v -> System.out.println("✅ Application stopped gracefully"))
                                .onFailure(cause ->
                                {
                                    System.err.println("❌ Error during graceful shutdown: " + cause.getMessage());

                                    cause.printStackTrace();
                                });
                    }));
                })
                .onFailure(cause ->
                {
                    System.err.println("❌ Failed to start NMSLite Application: " + cause.getMessage());

                    cause.printStackTrace();

                    // Ensure Vertx is closed on startup failure
                    vertx.close()
                            .onComplete(closeResult ->
                            {
                                if (closeResult.succeeded())
                                {
                                    System.out.println("✅ Vertx instance closed after startup failure");
                                }
                                else
                                {
                                    System.err.println("❌ Failed to close Vertx instance: " + closeResult.cause().getMessage());
                                }

                                System.exit(1);
                            });
                });
    }

    /**
     * Starts the NMSLite application verticle.
     * Loads configuration, sets up logging, creates worker executor, and deploys all verticles sequentially.
     *
     * @param startPromise Promise to complete when startup is successful or fail on error
     */
    @Override
    public void start(Promise<Void> startPromise)
    {
        // Load configuration from application.conf FIRST
        loadConfiguration()
            .onSuccess(config ->
            {
                // Configure logging based on application.conf
                LoggingConfigurator.configure(config);

                logger.info("🚀 Starting NMSLite Application - 4-Verticle Architecture");

                logger.info("✅ Configuration loaded from application.conf");

                // Create shared worker executor for blocking operations
                setupWorkerExecutor(config);

                // Deploy verticles SEQUENTIALLY to ensure proper initialization order
                // Order: DatabaseVerticle → ServerVerticle → PollingMetricsVerticle → DiscoveryVerticle
                deployDatabaseVerticle(config)
                    .compose(dbId ->
                    {
                        databaseVerticleId = dbId;

                        logger.info("✅ DatabaseVerticle deployed: {}", databaseVerticleId);

                        return deployServerVerticle(config);
                    })
                    .compose(serverId ->
                    {
                        serverVerticleId = serverId;

                        logger.info("✅ ServerVerticle deployed: {}", serverVerticleId);

                        return deployPollingVerticle(config);
                    })
                    .compose(pollingId ->
                    {
                        pollingVerticleId = pollingId;

                        logger.info("✅ PollingMetricsVerticle deployed: {}", pollingVerticleId != null ? pollingVerticleId : "DISABLED");

                        return deployDiscoveryVerticle(config);
                    })
                    .onSuccess(discoveryId ->
                    {
                        discoveryVerticleId = discoveryId;

                        logger.info("✅ DiscoveryVerticle deployed: {}", discoveryVerticleId);

                        logger.info("🎯 All verticles deployed successfully in sequence!");

                        logger.info("📊 DatabaseVerticle (ProxyGen): {}", databaseVerticleId);

                        logger.info("🌐 ServerVerticle: {}", serverVerticleId);

                        logger.info("📈 PollingMetricsVerticle: {}", pollingVerticleId != null ? pollingVerticleId : "DISABLED");

                        logger.info("🔍 DiscoveryVerticle: {}", discoveryVerticleId);

                        logger.info("🎯 NMSLite is ready with full 4-verticle architecture!");

                        startPromise.complete();
                    })
                    .onFailure(cause ->
                    {
                        logger.error("❌ Failed to deploy verticles", cause);

                        // Cleanup any successfully deployed verticles
                        cleanupSequentialDeployment()
                            .onComplete(cleanupResult ->
                            {
                                if (cleanupResult.succeeded())
                                {
                                    logger.info("✅ Cleanup completed successfully");
                                }
                                else
                                {
                                    logger.error("❌ Cleanup failed", cleanupResult.cause());
                                }

                                startPromise.fail(cause);
                            });
                    });
            })
            .onFailure(cause ->
            {
                logger.error("❌ Failed to load configuration", cause);

                startPromise.fail(cause);
            });
    }status

    /**
     * Deploys the DatabaseVerticle with database configuration.
     *
     * @param config Application configuration containing database settings
     * @return Future with deployment ID
     */
    private Future<String> deployDatabaseVerticle(JsonObject config)
    {
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("database", new JsonObject()));

        return vertx.deployVerticle(new DatabaseVerticle(), options);
    }

    /**
     * Deploys the DiscoveryVerticle with discovery configuration.
     *
     * @param config Application configuration containing discovery settings
     * @return Future with deployment ID
     */
    private Future<String> deployDiscoveryVerticle(JsonObject config)
    {
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("discovery", new JsonObject()));

        return vertx.deployVerticle(new DiscoveryVerticle(), options);
    }

    /**
     * Deploys the PollingMetricsVerticle if enabled in configuration.
     * Returns null deployment ID if polling is disabled.
     *
     * @param config Application configuration containing polling settings
     * @return Future with deployment ID or null if disabled
     */
    private Future<String> deployPollingVerticle(JsonObject config)
    {
        // Check if polling is enabled in configuration
        JsonObject pollingConfig = config.getJsonObject("polling", new JsonObject());

        boolean pollingEnabled = pollingConfig.getBoolean("enabled", false);

        if (!pollingEnabled)
        {
            logger.info("📈 PollingMetricsVerticle deployment skipped (disabled in config)");

            return Future.succeededFuture(null); // Return null deployment ID for disabled verticle
        }

        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config);

        logger.info("📈 Deploying PollingMetricsVerticle (enabled in config)");

        // Deploy the PollingMetricsVerticle
        return vertx.deployVerticle(new PollingMetricsVerticle(), options);
    }

    /**
     * Deploys the ServerVerticle with HTTP server configuration.
     *
     * @param config Application configuration containing main/HTTP settings
     * @return Future with deployment ID
     */
    private Future<String> deployServerVerticle(JsonObject config)
    {
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("main", new JsonObject()));

        return vertx.deployVerticle(new ServerVerticle(), options);
    }

    /**
     * Cleans up any successfully deployed verticles when sequential deployment fails.
     * Collects all deployed verticle IDs and triggers undeployment.
     *
     * @return Future that completes when cleanup is done
     */
    private Future<Void> cleanupSequentialDeployment()
    {
        logger.info("🧹 Starting cleanup of partially deployed verticles...");

        Promise<Void> cleanupPromise = Promise.promise();

        // Collect deployment IDs of successfully deployed verticles
        JsonObject deployedVerticles = new JsonObject();

        if (databaseVerticleId != null)
        {
            deployedVerticles.put("database", databaseVerticleId);

            logger.info("🔍 DatabaseVerticle deployed successfully, will undeploy: {}", databaseVerticleId);
        }

        if (serverVerticleId != null)
        {
            deployedVerticles.put("server", serverVerticleId);

            logger.info("🔍 ServerVerticle deployed successfully, will undeploy: {}", serverVerticleId);
        }

        if (pollingVerticleId != null)
        {
            deployedVerticles.put("polling", pollingVerticleId);

            logger.info("🔍 PollingMetricsVerticle deployed successfully, will undeploy: {}", pollingVerticleId);
        }

        if (discoveryVerticleId != null)
        {
            deployedVerticles.put("discovery", discoveryVerticleId);

            logger.info("🔍 DiscoveryVerticle deployed successfully, will undeploy: {}", discoveryVerticleId);
        }

        if (deployedVerticles.isEmpty())
        {
            logger.info("✅ No verticles to cleanup");

            cleanupPromise.complete();

            return cleanupPromise.future();
        }

        // Undeploy all successfully deployed verticles
        undeployVerticles(deployedVerticles, cleanupPromise);

        return cleanupPromise.future();
    }

    /**
     * Undeploys all successfully deployed verticles and cleans up resources.
     * Waits for all undeployments to complete before closing worker executor.
     *
     * @param deployedVerticles JsonObject containing deployment IDs of deployed verticles
     * @param cleanupPromise Promise to complete when all cleanup is done
     */
    private void undeployVerticles(JsonObject deployedVerticles, Promise<Void> cleanupPromise)
    {
        Promise<Void> undeployPromise = Promise.promise();

        // Create futures for undeployment
        Future<Void> databaseUndeploy = Future.succeededFuture();

        Future<Void> discoveryUndeploy = Future.succeededFuture();

        Future<Void> pollingUndeploy = Future.succeededFuture();

        Future<Void> serverUndeploy = Future.succeededFuture();

        // Undeploy database verticle if deployed
        if (deployedVerticles.containsKey("database"))
        {
            String deploymentId = deployedVerticles.getString("database");

            databaseUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ DatabaseVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy DatabaseVerticle: {}", deploymentId, cause));
        }

        // Undeploy discovery verticle if deployed
        if (deployedVerticles.containsKey("discovery"))
        {
            String deploymentId = deployedVerticles.getString("discovery");

            discoveryUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ DiscoveryVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy DiscoveryVerticle: {}", deploymentId, cause));
        }

        // Undeploy polling verticle if deployed
        if (deployedVerticles.containsKey("polling"))
        {
            String deploymentId = deployedVerticles.getString("polling");

            pollingUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ PollingMetricsVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy PollingMetricsVerticle: {}", deploymentId, cause));
        }

        // Undeploy server verticle if deployed
        if (deployedVerticles.containsKey("server"))
        {
            String deploymentId = deployedVerticles.getString("server");

            serverUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ ServerVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy ServerVerticle: {}", deploymentId, cause));
        }

        // Wait for all undeployments to complete
        CompositeFuture.all(databaseUndeploy, discoveryUndeploy, pollingUndeploy, serverUndeploy)
            .onSuccess(result ->
            {
                logger.info("✅ All verticles undeployed successfully");

                // Cleanup worker executor
                if (workerExecutor != null)
                {
                    workerExecutor.close();

                    logger.info("✅ Worker executor closed during cleanup");
                }

                undeployPromise.complete();
            })
            .onFailure(cause ->
            {
                logger.error("❌ Some verticles failed to undeploy", cause);

                // Still cleanup worker executor
                if (workerExecutor != null)
                {
                    workerExecutor.close();

                    logger.info("✅ Worker executor closed during cleanup (with errors)");
                }

                undeployPromise.fail(cause);
            });

        undeployPromise.future().onComplete(cleanupPromise);
    }

    /**
     * Sets up the shared worker executor for blocking operations.
     * Reads configuration for pool size and max execution time.
     *
     * @param config Application configuration
     */
    private void setupWorkerExecutor(JsonObject config)
    {
        // Get worker pool configuration from config or use defaults
        int workerPoolSize = config.getInteger("worker.pool.size", 10);

        // Create shared worker executor for all blocking operations
        // Note: No max execute time limit - operations controlled by their own timeouts
        workerExecutor = vertx.createSharedWorkerExecutor("nmslite-worker", workerPoolSize);

        logger.info("🔧 Worker executor created: pool-size={}", workerPoolSize);
    }

    /**
     * Loads application configuration from application.conf file using HOCON format.
     *
     * @return Future containing the loaded configuration as JsonObject
     */
    private Future<JsonObject> loadConfiguration()
    {
        Promise<JsonObject> promise = Promise.promise();

        // Configure to load from application.conf file
        ConfigStoreOptions fileStore = new ConfigStoreOptions()
            .setType("file")
            .setFormat("hocon")
            .setConfig(new JsonObject().put("path", "application.conf"));

        ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(fileStore);

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

        retriever.getConfig()
            .onSuccess(config ->
            {
                logger.info("📋 Configuration loaded successfully");

                logger.info("🗄️  Database: {}:{}/{}",
                    config.getJsonObject("database").getString("host"),
                    config.getJsonObject("database").getInteger("port"),
                    config.getJsonObject("database").getString("database"));

                logger.info("🌐 HTTP Port: {}",
                    config.getJsonObject("main").getInteger("http.port"));

                logger.info("🔧 GoEngine Path: {}",
                    config.getJsonObject("tools").getString("goengine.path"));

                promise.complete(config);
            })
            .onFailure(cause ->
            {
                logger.error("❌ Failed to load configuration from application.conf", cause);

                promise.fail(cause);
            });

        return promise.future();
    }

    /**
     * Stops the NMSLite application verticle.
     * Closes worker executor and allows Vert.x to automatically undeploy child verticles.
     *
     * @param stopPromise Promise to complete when shutdown is successful
     */
    @Override
    public void stop(Promise<Void> stopPromise)
    {
        logger.info("🛑 Stopping NMSLite Application");

        // NOTE: Vert.x automatically undeploys all child verticles when parent stops
        // We don't need to manually undeploy them here

        // Close worker executor if it exists
        if (workerExecutor != null)
        {
            workerExecutor.close();

            logger.info("✅ Worker executor closed");
        }

        logger.info("✅ NMSLite Application stopped gracefully");

        stopPromise.complete();
    }

}
