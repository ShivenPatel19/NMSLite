package com.nmslite;

import ch.qos.logback.classic.Level;

import ch.qos.logback.classic.LoggerContext;

import com.nmslite.core.DatabaseInitializer;

import com.nmslite.verticles.DiscoveryVerticle;

import com.nmslite.verticles.PollingMetricsVerticle;

import com.nmslite.verticles.ServerVerticle;

import io.vertx.config.ConfigRetriever;

import io.vertx.config.ConfigRetrieverOptions;

import io.vertx.config.ConfigStoreOptions;

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
 * NMSLite Application - Main Entry Point (Vert.x 5.0.4)

 * 3-Verticle Architecture:
 * - ServerVerticle: HTTP API server
 * - PollingMetricsVerticle: Continuous device monitoring
 * - DiscoveryVerticle: Device discovery workflow

 * Database Services:
 * - Initialized at startup via DatabaseInitializer (no verticle needed)
 * - All 7 ProxyGen services registered before verticles deploy

 * Features:
 * - Database initialization before verticle deployment
 * - Single method to deploy all verticles
 * - Graceful deployment failure cleanup
 * - Comprehensive shutdown handling

 * Communication: Event Bus driven with async messaging + ProxyGen services
 */
public class NMSLiteApplication
{

    private static final Logger logger = LoggerFactory.getLogger(NMSLiteApplication.class);

    private static Vertx vertx;

    private static WorkerExecutor workerExecutor;

    private static DatabaseInitializer databaseInitializer;

    private static final List<String> deployedVerticleIds = new ArrayList<>();

    /**
     * Main entry point for the NMSLite application.
     * Creates Vert.x instance, loads configuration, and deploys all verticles.
     *
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args)
    {
        logger.info("Starting NMSLite Application");

        vertx = Vertx.vertx();

        // Load configuration, initialize database, and deploy all verticles
        // Deploy all verticles using single method
        loadConfiguration()
            .compose(config ->
            {
                // Configure logging based on application.conf
                configureLogging(config);

                logger.info("Configuration loaded successfully");

                // Create shared worker executor
                setupWorkerExecutor(config);

                // Initialize database services before deploying verticles
                return initializeDatabase(config);
            })
            .compose(NMSLiteApplication::deployAllVerticles)
            .onSuccess(v ->
            {
                logger.info("NMSLite Application started successfully - HTTP API available at http://localhost:8080");

                // Add shutdown hook for graceful shutdown
                Runtime.getRuntime().addShutdownHook(new Thread(() ->
                {
                    logger.info("Shutdown signal received");

                    cleanup()
                        .compose(cleanupResult -> vertx.close())
                        .onSuccess(closeResult -> logger.info("Application stopped gracefully"))
                        .onFailure(cause -> logger.error("Error during graceful shutdown", cause));
                }));
            })
            .onFailure(cause ->
            {
                logger.error("Failed to start NMSLite Application", cause);

                // Cleanup and close Vertx on startup failure
                cleanup()
                    .compose(cleanupResult -> vertx.close())
                    .onComplete(closeResult ->
                    {
                        if (closeResult.failed())
                        {
                            logger.error("Failed to close Vertx instance", closeResult.cause());
                        }

                        System.exit(1);
                    });
            });
    }

    /**
     * Initializes database connection and registers all ProxyGen services.
     * This happens BEFORE any verticles are deployed to ensure services are ready.
     *
     * @param config Application configuration
     * @return Future containing the config (for chaining)
     */
    private static Future<JsonObject> initializeDatabase(JsonObject config)
    {
        databaseInitializer = new DatabaseInitializer(vertx, config.getJsonObject("database", new JsonObject()));

        return databaseInitializer.initialize()
            .compose(v -> Future.succeededFuture(config));
    }

    /**
     * Deploys all verticles in sequence using a single method.
     * Order: ServerVerticle → PollingMetricsVerticle → DiscoveryVerticle
     * Note: Database services are already initialized before this method is called
     *
     * @param config Application configuration
     * @return Future that completes when all verticles are deployed
     */
    private static Future<Void> deployAllVerticles(JsonObject config)
    {
        logger.info("Deploying verticles");

        // Deploy ServerVerticle
        var serverOptions = new DeploymentOptions()
            .setConfig(config.getJsonObject("server", new JsonObject()));

        return vertx.deployVerticle(new ServerVerticle(), serverOptions)
            .compose(serverId ->
            {
                deployedVerticleIds.add(serverId);

                logger.debug("ServerVerticle deployed: {}", serverId);

                // Deploy PollingMetricsVerticle
                var pollingOptions = new DeploymentOptions()
                    .setConfig(config);

                return vertx.deployVerticle(new PollingMetricsVerticle(), pollingOptions);
            })
            .compose(pollingId ->
            {
                deployedVerticleIds.add(pollingId);

                logger.debug("PollingMetricsVerticle deployed: {}", pollingId);

                // Deploy DiscoveryVerticle
                var discoveryOptions = new DeploymentOptions()
                    .setConfig(config.getJsonObject("discovery", new JsonObject()));

                return vertx.deployVerticle(new DiscoveryVerticle(), discoveryOptions);
            })
            .compose(discoveryId ->
            {
                deployedVerticleIds.add(discoveryId);

                logger.debug("DiscoveryVerticle deployed: {}", discoveryId);

                logger.info("All {} verticles deployed successfully", deployedVerticleIds.size());

                return Future.<Void>succeededFuture();
            })
            .onFailure(cause ->
                    logger.error("Failed to deploy verticles", cause));
    }

    /**
     * Configure logging based on application configuration.

     * Features:
     * - Enable/disable logging globally
     * - Set log level (TRACE, DEBUG, INFO, WARN, ERROR)
     * - Enable/disable file logging
     * - Enable/disable console logging
     * - Configure log file path

     * Configuration in application.conf:
     * logging {
     *   enabled = true                    # Enable/disable all logging
     *   level = "INFO"                    # Log level
     *   file.path = "logs/nmslite.log"   # Log file path
     *   file.enabled = true               # Enable file logging
     *   console.enabled = true            # Enable console logging
     * }
     *
     * @param config Application configuration JsonObject
     */
    private static void configureLogging(JsonObject config)
    {
        var loggingConfig = config.getJsonObject("logging", new JsonObject());

        var loggingEnabled = loggingConfig.getBoolean("enabled", true);

        var logLevel = loggingConfig.getString("level", "INFO");

        var fileEnabled = loggingConfig.getBoolean("file.enabled", true);

        var consoleEnabled = loggingConfig.getBoolean("console.enabled", true);

        var filePath = loggingConfig.getString("file.path", "logs/nmslite.log");

        // Set system properties for logback.xml
        System.setProperty("nmslite.log.level", loggingEnabled ? logLevel : "OFF");

        System.setProperty("nmslite.log.file.path", filePath);

        // Configure appender based on enabled flags
        if (!consoleEnabled)
        {
            System.setProperty("nmslite.log.console.appender", "NULL");
        }
        else
        {
            System.setProperty("nmslite.log.console.appender", "CONSOLE");
        }

        if (!fileEnabled)
        {
            System.setProperty("nmslite.log.file.appender", "NULL");
        }
        else
        {
            System.setProperty("nmslite.log.file.appender", "FILE");
        }

        // Programmatically configure logback
        var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Set root logger level
        var rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

        if (loggingEnabled)
        {
            rootLogger.setLevel(Level.toLevel(logLevel, Level.INFO));
        }
        else
        {
            rootLogger.setLevel(Level.OFF);
        }

        // Set application logger level
        var appLogger = loggerContext.getLogger("com.nmslite");

        if (loggingEnabled)
        {
            appLogger.setLevel(Level.toLevel(logLevel, Level.INFO));
        }
        else
        {
            appLogger.setLevel(Level.OFF);
        }

        // Create logs directory if file logging is enabled
        if (fileEnabled && loggingEnabled)
        {
            var logFile = new java.io.File(filePath);

            var logDir = logFile.getParentFile();

            if (logDir != null && !logDir.exists())
            {
                logDir.mkdirs();
            }
        }
    }

    /**
     * Cleans up all deployed verticles, database resources, and worker executor.
     *
     * @return Future that completes when cleanup is done
     */
    private static Future<Void> cleanup()
    {
        logger.info("Starting cleanup");

        var cleanupFutures = new ArrayList<Future<Void>>();

        // Cleanup database resources
        if (databaseInitializer != null)
        {
            cleanupFutures.add(databaseInitializer.cleanup());
        }

        // Undeploy all verticles if any exist
        if (!deployedVerticleIds.isEmpty())
        {
            for (var deploymentId : deployedVerticleIds)
            {
                var undeployFuture = vertx.undeploy(deploymentId)
                    .onSuccess(v -> logger.debug("Verticle undeploy: {}", deploymentId))
                    .onFailure(cause -> logger.error("Failed to undeploy verticle: {}", deploymentId, cause));

                cleanupFutures.add(undeployFuture);
            }
        }

        // Wait for all cleanup operations to complete
        return Future.join(cleanupFutures)
            .onSuccess(result -> deployedVerticleIds.clear())
            .onFailure(cause ->
            {
                logger.error("Some cleanup operations failed", cause);

                deployedVerticleIds.clear();
            })
            .onComplete(result ->
            {
                // Always close worker executor, regardless of success or failure
                if (workerExecutor != null)
                {
                    workerExecutor.close();

                    logger.debug("Worker executor closed");
                }
            })
            .mapEmpty();
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
        var workerPoolSize = config.getInteger("worker.pool.size", 10);

        // Create shared worker executor for all blocking operations
        workerExecutor = vertx.createSharedWorkerExecutor("nmslite-worker", workerPoolSize);

        logger.debug("Worker executor created with pool size: {}", workerPoolSize);
    }

    /**
     * Loads application configuration from application.conf file using HOCON format.
     *
     * @return Future containing the loaded configuration as JsonObject
     */
    private static Future<JsonObject> loadConfiguration()
    {
        var promise = Promise.<JsonObject>promise();

        // Configure to load from application.conf file
        var fileStore = new ConfigStoreOptions()
            .setType("file")
            .setFormat("hocon")
            .setConfig(new JsonObject().put("path", "application.conf"));

        var options = new ConfigRetrieverOptions().addStore(fileStore);

        var retriever = ConfigRetriever.create(vertx, options);

        retriever.getConfig()
            .onSuccess(config ->
            {
                var dbConfig = config.getJsonObject("database");

                var serverConfig = config.getJsonObject("server");

                var toolsConfig = config.getJsonObject("tools");

                logger.info("Configuration loaded - Database: {}:{}/{}, HTTP Port: {}, GoEngine: {}",
                    dbConfig.getString("host"),
                    dbConfig.getInteger("port"),
                    dbConfig.getString("database"),
                    serverConfig.getJsonObject("http").getInteger("port"),
                    toolsConfig.getJsonObject("goengine").getString("path"));

                promise.complete(config);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to load configuration from application.conf", cause);

                promise.fail(cause);
            });

        return promise.future();
    }

}
