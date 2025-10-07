package com.nmslite;

import com.nmslite.core.LoggingConfigurator;

import com.nmslite.verticles.DatabaseVerticle;

import com.nmslite.verticles.DiscoveryVerticle;

import com.nmslite.verticles.PollingMetricsVerticle;

import com.nmslite.verticles.ServerVerticle;

import io.vertx.config.ConfigRetriever;

import io.vertx.config.ConfigRetrieverOptions;

import io.vertx.config.ConfigStoreOptions;

import io.vertx.core.CompositeFuture;

import io.vertx.core.DeploymentOptions;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.WorkerExecutor;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;

import java.util.ArrayList;

import java.util.List;

/**
 * NMSLite Application - Main Entry Point (Vert.x 4.5.10)
 *
 * 4-Verticle Architecture:
 * - DatabaseVerticle: All database operations (ProxyGen enabled)
 * - ServerVerticle: HTTP API server
 * - PollingMetricsVerticle: Continuous device monitoring
 * - DiscoveryVerticle: Device discovery workflow
 *
 * Features:
 * - Single method to deploy all verticles
 * - Graceful deployment failure cleanup
 * - Comprehensive shutdown handling
 *
 * Communication: Event Bus driven with async messaging + ProxyGen services
 */
public class NMSLiteApplication
{

    private static final Logger logger = LoggerFactory.getLogger(NMSLiteApplication.class);

    private static Vertx vertx;

    private static WorkerExecutor workerExecutor;

    private static final List<String> deployedVerticleIds = new ArrayList<>();

    /**
     * Main entry point for the NMSLite application.
     * Creates Vert.x instance, loads configuration, and deploys all verticles.
     *
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args)
    {
        logger.info("🚀 Starting NMSLite Application...");

        vertx = Vertx.vertx();

        // Load configuration and deploy all verticles
        loadConfiguration()
            .compose(config ->
            {
                // Configure logging based on application.conf
                LoggingConfigurator.configure(config);

                logger.info("✅ Configuration loaded from application.conf");

                // Create shared worker executor
                setupWorkerExecutor(config);

                // Deploy all verticles using single method
                return deployAllVerticles(config);
            })
            .onSuccess(v ->
            {
                logger.info("🎉 NMSLite Application started successfully!");

                logger.info("📡 HTTP API available at: http://localhost:8080");

                // Add shutdown hook for graceful shutdown
                Runtime.getRuntime().addShutdownHook(new Thread(() ->
                {
                    logger.info("🛑 Shutdown signal received, stopping application gracefully...");

                    cleanup()
                        .compose(cleanupResult -> vertx.close())
                        .onSuccess(closeResult -> logger.info("✅ Application stopped gracefully"))
                        .onFailure(cause -> logger.error("❌ Error during graceful shutdown", cause));
                }));
            })
            .onFailure(cause ->
            {
                logger.error("❌ Failed to start NMSLite Application", cause);

                // Cleanup and close Vertx on startup failure
                cleanup()
                    .compose(cleanupResult -> vertx.close())
                    .onComplete(closeResult ->
                    {
                        if (closeResult.succeeded())
                        {
                            logger.info("✅ Vertx instance closed after startup failure");
                        }
                        else
                        {
                            logger.error("❌ Failed to close Vertx instance", closeResult.cause());
                        }

                        System.exit(1);
                    });
            });
    }

    /**
     * Deploys all verticles in sequence using a single method.
     * Order: DatabaseVerticle → ServerVerticle → PollingMetricsVerticle → DiscoveryVerticle
     *
     * @param config Application configuration
     * @return Future that completes when all verticles are deployed
     */
    private static Future<Void> deployAllVerticles(JsonObject config)
    {
        logger.info("🚀 Deploying all verticles - 4-Verticle Architecture");

        // Deploy DatabaseVerticle
        DeploymentOptions dbOptions = new DeploymentOptions()
            .setConfig(config.getJsonObject("database", new JsonObject()));

        return vertx.deployVerticle(new DatabaseVerticle(), dbOptions)
            .compose(dbId ->
            {
                deployedVerticleIds.add(dbId);

                logger.info("✅ DatabaseVerticle deployed: {}", dbId);

                // Deploy ServerVerticle
                DeploymentOptions serverOptions = new DeploymentOptions()
                    .setConfig(config.getJsonObject("server", new JsonObject()));

                return vertx.deployVerticle(new ServerVerticle(), serverOptions);
            })
            .compose(serverId ->
            {
                deployedVerticleIds.add(serverId);

                logger.info("✅ ServerVerticle deployed: {}", serverId);

                // Deploy PollingMetricsVerticle
                DeploymentOptions pollingOptions = new DeploymentOptions()
                    .setConfig(config);

                return vertx.deployVerticle(new PollingMetricsVerticle(), pollingOptions);
            })
            .compose(pollingId ->
            {
                deployedVerticleIds.add(pollingId);

                logger.info("✅ PollingMetricsVerticle deployed: {}", pollingId);

                // Deploy DiscoveryVerticle
                DeploymentOptions discoveryOptions = new DeploymentOptions()
                    .setConfig(config.getJsonObject("discovery", new JsonObject()));

                return vertx.deployVerticle(new DiscoveryVerticle(), discoveryOptions);
            })
            .compose(discoveryId ->
            {
                deployedVerticleIds.add(discoveryId);

                logger.info("✅ DiscoveryVerticle deployed: {}", discoveryId);

                logger.info("🎯 All verticles deployed successfully!");

                logger.info("📊 Total verticles deployed: {}", deployedVerticleIds.size());

                logger.info("🎯 NMSLite is ready with full 4-verticle architecture!");

                return Future.<Void>succeededFuture();
            })
            .onFailure(cause ->
            {
                logger.error("❌ Failed to deploy verticles", cause);
            });
    }

    /**
     * Cleans up all deployed verticles and resources.
     *
     * @return Future that completes when cleanup is done
     */
    private static Future<Void> cleanup()
    {
        logger.info("🧹 Starting cleanup...");

        if (deployedVerticleIds.isEmpty())
        {
            logger.info("✅ No verticles to cleanup");

            // Close worker executor
            if (workerExecutor != null)
            {
                workerExecutor.close();

                logger.info("✅ Worker executor closed");
            }

            return Future.succeededFuture();
        }

        // Undeploy all verticles
        List<Future<Void>> undeployFutures = new ArrayList<>();

        for (String deploymentId : deployedVerticleIds)
        {
            Future<Void> undeployFuture = vertx.undeploy(deploymentId)
                .onSuccess(v -> logger.info("✅ Verticle undeployed: {}", deploymentId))
                .onFailure(cause -> logger.error("❌ Failed to undeploy verticle: {}", deploymentId, cause));

            undeployFutures.add(undeployFuture);
        }

        // Wait for all undeployments to complete
        return Future.join(undeployFutures)
            .compose(result ->
            {
                logger.info("✅ All verticles undeployed successfully");

                deployedVerticleIds.clear();

                // Close worker executor
                if (workerExecutor != null)
                {
                    workerExecutor.close();

                    logger.info("✅ Worker executor closed");
                }

                return Future.<Void>succeededFuture();
            })
            .recover(cause ->
            {
                logger.error("❌ Some verticles failed to undeploy", cause);

                deployedVerticleIds.clear();

                // Still close worker executor
                if (workerExecutor != null)
                {
                    workerExecutor.close();

                    logger.info("✅ Worker executor closed (with errors)");
                }

                return Future.<Void>succeededFuture();
            });
    }

    /**
     * Sets up the shared worker executor for blocking operations.
     * Reads configuration for pool size.
     *
     * @param config Application configuration
     */
    private static void setupWorkerExecutor(JsonObject config)
    {
        // Get worker pool configuration from config or use defaults
        int workerPoolSize = config.getInteger("worker.pool.size", 10);

        // Create shared worker executor for all blocking operations
        workerExecutor = vertx.createSharedWorkerExecutor("nmslite-worker", workerPoolSize);

        logger.info("🔧 Worker executor created: pool-size={}", workerPoolSize);
    }

    /**
     * Loads application configuration from application.conf file using HOCON format.
     *
     * @return Future containing the loaded configuration as JsonObject
     */
    private static Future<JsonObject> loadConfiguration()
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
                    config.getJsonObject("server").getInteger("http.port"));

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

}
