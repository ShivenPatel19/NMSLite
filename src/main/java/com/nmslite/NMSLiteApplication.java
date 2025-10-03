package com.nmslite;

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
public class NMSLiteApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(NMSLiteApplication.class);

    // Shared Worker executor for blocking operations
    private WorkerExecutor workerExecutor;

    // Track deployed verticles for cleanup
    private String databaseVerticleId;
    private String discoveryVerticleId;
    private String pollingVerticleId;
    private String serverVerticleId;

    /**
     * Main method to start the NMSLite application
     */
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new NMSLiteApplication())
                .onSuccess(id -> {
                    System.out.println("🎉 NMSLite Application started successfully!");
                    System.out.println("📡 HTTP API available at: http://localhost:8080");

                    // Add shutdown hook for graceful shutdown
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        System.out.println("🛑 Shutdown signal received, stopping application gracefully...");
                        vertx.close()
                                .onSuccess(v -> System.out.println("✅ Application stopped gracefully"))
                                .onFailure(cause -> {
                                    System.err.println("❌ Error during graceful shutdown: " + cause.getMessage());
                                    cause.printStackTrace();
                                });
                    }));
                })
                .onFailure(cause -> {
                    System.err.println("❌ Failed to start NMSLite Application: " + cause.getMessage());
                    cause.printStackTrace();

                    // Ensure Vertx is closed on startup failure
                    vertx.close()
                            .onComplete(closeResult -> {
                                if (closeResult.succeeded()) {
                                    System.out.println("✅ Vertx instance closed after startup failure");
                                } else {
                                    System.err.println("❌ Failed to close Vertx instance: " + closeResult.cause().getMessage());
                                }
                                System.exit(1);
                            });
                });
    }

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("🚀 Starting NMSLite Application - 5-Verticle Architecture");

        // Load configuration from application.conf
        loadConfiguration()
            .onSuccess(config -> {
                logger.info("✅ Configuration loaded from application.conf");

                // Create shared worker executor for blocking operations
                setupWorkerExecutor(config);

                // Deploy all verticles (including PollingMetricsVerticle when enabled)
                Future<String> databaseFuture = deployDatabaseVerticle(config);
                Future<String> discoveryFuture = deployDiscoveryVerticle(config);
                Future<String> pollingFuture = deployPollingVerticle(config);
                Future<String> serverFuture = deployServerVerticle(config);

                // Wait for all verticles to deploy successfully
                CompositeFuture.all(databaseFuture, discoveryFuture, pollingFuture, serverFuture)
                    .onSuccess(result -> {
                        // Store deployment IDs for cleanup
                        databaseVerticleId = databaseFuture.result();
                        discoveryVerticleId = discoveryFuture.result();
                        pollingVerticleId = pollingFuture.result();
                        serverVerticleId = serverFuture.result();

                        logger.info("✅ All verticles deployed successfully!");
                        logger.info("📊 DatabaseVerticle (ProxyGen): {}", databaseVerticleId);
                        logger.info("🔍 DiscoveryVerticle: {}", discoveryVerticleId);
                        logger.info("📈 PollingMetricsVerticle: {}", pollingVerticleId != null ? pollingVerticleId : "DISABLED");
                        logger.info("🌐 ServerVerticle: {}", serverVerticleId);
                        logger.info("🎯 NMSLite is ready with full 4-verticle architecture!");
                        startPromise.complete();
                    })
                    .onFailure(cause -> {
                        logger.error("❌ Failed to deploy verticles", cause);

                        // Cleanup any successfully deployed verticles
                        cleanupPartialDeployment(databaseFuture, discoveryFuture, pollingFuture, serverFuture)
                            .onComplete(cleanupResult -> {
                                if (cleanupResult.succeeded()) {
                                    logger.info("✅ Cleanup completed successfully");
                                } else {
                                    logger.error("❌ Cleanup failed", cleanupResult.cause());
                                }
                                startPromise.fail(cause);
                            });
                    });
            })
            .onFailure(cause -> {
                logger.error("❌ Failed to load configuration", cause);
                startPromise.fail(cause);
            });
    }

    private Future<String> deployDatabaseVerticle(JsonObject config) {
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("database", new JsonObject()));

        return vertx.deployVerticle(new DatabaseVerticle(), options);
    }

    private Future<String> deployDiscoveryVerticle(JsonObject config) {
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("discovery", new JsonObject()));

        return vertx.deployVerticle(new DiscoveryVerticle(), options);
    }

    private Future<String> deployPollingVerticle(JsonObject config) {
        // Check if polling is enabled in configuration
        JsonObject pollingConfig = config.getJsonObject("polling", new JsonObject());
        boolean pollingEnabled = pollingConfig.getBoolean("enabled", false);

        if (!pollingEnabled) {
            logger.info("📈 PollingMetricsVerticle deployment skipped (disabled in config)");
            return Future.succeededFuture(null); // Return null deployment ID for disabled verticle
        }

        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config);

        logger.info("📈 Deploying PollingMetricsVerticle (enabled in config)");

        // Deploy the PollingMetricsVerticle
        return vertx.deployVerticle(new PollingMetricsVerticle(), options);
    }

    private Future<String> deployServerVerticle(JsonObject config) {
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("main", new JsonObject()));

        return vertx.deployVerticle(new ServerVerticle(), options);
    }

    /**
     * Cleanup any successfully deployed verticles when deployment fails
     */
    private Future<Void> cleanupPartialDeployment(Future<String> databaseFuture,
                                                  Future<String> discoveryFuture,
                                                  Future<String> pollingFuture,
                                                  Future<String> mainFuture) {
        logger.info("🧹 Starting cleanup of partially deployed verticles...");

        Promise<Void> cleanupPromise = Promise.promise();

        // Collect deployment IDs of successfully deployed verticles
        JsonObject deployedVerticles = new JsonObject();

        if (databaseFuture.succeeded()) {
            deployedVerticles.put("database", databaseFuture.result());
            logger.info("🔍 DatabaseVerticle deployed successfully, will undeploy: {}", databaseFuture.result());
        }

        if (discoveryFuture.succeeded()) {
            deployedVerticles.put("discovery", discoveryFuture.result());
            logger.info("🔍 DiscoveryVerticle deployed successfully, will undeploy: {}", discoveryFuture.result());
        }

        if (pollingFuture.succeeded() && pollingFuture.result() != null) {
            deployedVerticles.put("polling", pollingFuture.result());
            logger.info("🔍 PollingMetricsVerticle deployed successfully, will undeploy: {}", pollingFuture.result());
        }

        if (mainFuture.succeeded()) {
            deployedVerticles.put("server", mainFuture.result());
            logger.info("🔍 ServerVerticle deployed successfully, will undeploy: {}", mainFuture.result());
        }

        if (deployedVerticles.isEmpty()) {
            logger.info("✅ No verticles to cleanup");
            cleanupPromise.complete();
            return cleanupPromise.future();
        }

        // Undeploy all successfully deployed verticles
        undeployVerticles(deployedVerticles, cleanupPromise);

        return cleanupPromise.future();
    }

    /**
     * Undeploy verticles and cleanup resources
     */
    private void undeployVerticles(JsonObject deployedVerticles, Promise<Void> cleanupPromise) {
        Promise<Void> undeployPromise = Promise.promise();

        // Create futures for undeployment
        Future<Void> databaseUndeploy = Future.succeededFuture();
        Future<Void> discoveryUndeploy = Future.succeededFuture();
        Future<Void> pollingUndeploy = Future.succeededFuture();
        Future<Void> serverUndeploy = Future.succeededFuture();

        // Undeploy database verticle if deployed
        if (deployedVerticles.containsKey("database")) {
            String deploymentId = deployedVerticles.getString("database");
            databaseUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ DatabaseVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy DatabaseVerticle: {}", deploymentId, cause));
        }

        // Undeploy discovery verticle if deployed
        if (deployedVerticles.containsKey("discovery")) {
            String deploymentId = deployedVerticles.getString("discovery");
            discoveryUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ DiscoveryVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy DiscoveryVerticle: {}", deploymentId, cause));
        }

        // Undeploy polling verticle if deployed
        if (deployedVerticles.containsKey("polling")) {
            String deploymentId = deployedVerticles.getString("polling");
            pollingUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ PollingMetricsVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy PollingMetricsVerticle: {}", deploymentId, cause));
        }

        // Undeploy server verticle if deployed
        if (deployedVerticles.containsKey("server")) {
            String deploymentId = deployedVerticles.getString("server");
            serverUndeploy = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ ServerVerticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy ServerVerticle: {}", deploymentId, cause));
        }

        // Wait for all undeployments to complete
        CompositeFuture.all(databaseUndeploy, discoveryUndeploy, pollingUndeploy, serverUndeploy)
            .onSuccess(result -> {
                logger.info("✅ All verticles undeployed successfully");

                // Cleanup worker executor
                if (workerExecutor != null) {
                    workerExecutor.close();
                    logger.info("✅ Worker executor closed during cleanup");
                }

                undeployPromise.complete();
            })
            .onFailure(cause -> {
                logger.error("❌ Some verticles failed to undeploy", cause);

                // Still cleanup worker executor
                if (workerExecutor != null) {
                    workerExecutor.close();
                    logger.info("✅ Worker executor closed during cleanup (with errors)");
                }

                undeployPromise.fail(cause);
            });

        undeployPromise.future().onComplete(cleanupPromise);
    }

    private void setupWorkerExecutor(JsonObject config) {
        // Get worker pool configuration from config or use defaults
        int workerPoolSize = config.getInteger("worker.pool.size", 10);  // Start with 1 thread as requested
        long maxExecuteTime = config.getLong("worker.max.execute.time", 600000L);  // 10 minutes max

        // Create shared worker executor for all blocking operations
        workerExecutor = vertx.createSharedWorkerExecutor("nmslite-worker", workerPoolSize, maxExecuteTime);

        logger.info("🔧 Worker executor created: pool-size={}, max-execute-time={}ms",
            workerPoolSize, maxExecuteTime);
    }

    private Future<JsonObject> loadConfiguration() {
        Promise<JsonObject> promise = Promise.promise();

        // Configure to load from application.conf file
        ConfigStoreOptions fileStore = new ConfigStoreOptions()
            .setType("file")
            .setFormat("hocon")
            .setConfig(new JsonObject().put("path", "application.conf"));

        ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(fileStore);

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

        retriever.getConfig()
            .onSuccess(config -> {
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
            .onFailure(cause -> {
                logger.error("❌ Failed to load configuration from application.conf", cause);
                promise.fail(cause);
            });

        return promise.future();
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("🛑 Stopping NMSLite Application");

        // Gracefully undeploy all verticles
        Future<Void> databaseStop = Future.succeededFuture();
        Future<Void> discoveryStop = Future.succeededFuture();
        Future<Void> pollingStop = Future.succeededFuture();
        Future<Void> serverStop = Future.succeededFuture();

        // Undeploy verticles if they were deployed
        if (databaseVerticleId != null) {
            databaseStop = vertx.undeploy(databaseVerticleId)
                .onSuccess(v -> logger.info("✅ DatabaseVerticle stopped: {}", databaseVerticleId))
                .onFailure(cause -> logger.error("❌ Failed to stop DatabaseVerticle", cause));
        }

        if (discoveryVerticleId != null) {
            discoveryStop = vertx.undeploy(discoveryVerticleId)
                .onSuccess(v -> logger.info("✅ DiscoveryVerticle stopped: {}", discoveryVerticleId))
                .onFailure(cause -> logger.error("❌ Failed to stop DiscoveryVerticle", cause));
        }

        if (pollingVerticleId != null) {
            pollingStop = vertx.undeploy(pollingVerticleId)
                .onSuccess(v -> logger.info("✅ PollingMetricsVerticle stopped: {}", pollingVerticleId))
                .onFailure(cause -> logger.error("❌ Failed to stop PollingMetricsVerticle", cause));
        }

        if (serverVerticleId != null) {
            serverStop = vertx.undeploy(serverVerticleId)
                .onSuccess(v -> logger.info("✅ ServerVerticle stopped: {}", serverVerticleId))
                .onFailure(cause -> logger.error("❌ Failed to stop ServerVerticle", cause));
        }

        // Wait for all verticles to stop
        CompositeFuture.all(databaseStop, discoveryStop, pollingStop, serverStop)
            .onComplete(result -> {
                // Close worker executor if it exists
                if (workerExecutor != null) {
                    workerExecutor.close();
                    logger.info("🔧 Worker executor closed");
                }

                if (result.succeeded()) {
                    logger.info("✅ NMSLite Application stopped gracefully");
                    stopPromise.complete();
                } else {
                    logger.error("❌ Some components failed to stop gracefully", result.cause());
                    stopPromise.complete(); // Complete anyway to avoid hanging
                }
            });
    }
}
