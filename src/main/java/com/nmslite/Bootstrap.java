package com.nmslite;

import ch.qos.logback.classic.Level;

import ch.qos.logback.classic.LoggerContext;

import com.nmslite.core.VerticleDeployer;

import com.nmslite.database.DatabaseInitializer;

import com.typesafe.config.Config;

import com.typesafe.config.ConfigFactory;

import com.typesafe.config.ConfigRenderOptions;

import io.vertx.core.Future;

import io.vertx.core.Vertx;

import io.vertx.core.VertxOptions;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;

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
 * - Centralized verticle deployment via VerticleDeployer
 * - Graceful deployment failure cleanup
 * - Comprehensive shutdown handling

 * Communication: Event Bus driven with async messaging + ProxyGen services
 */
public class Bootstrap
{

    private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

    private static Vertx vertx;

    private static JsonObject config;

    private static DatabaseInitializer databaseInitializer;

    private static VerticleDeployer verticleDeployer;

    /**
     * Gets the shared Vertx instance.
     * This method provides global access to the Vertx instance from anywhere in the application.
     *
     * @return The Vertx instance, or null if not yet initialized
     */
    public static Vertx getVertxInstance()
    {
            return vertx;
    }

    /**
     * Gets the application configuration.
     * This method provides global access to the configuration from anywhere in the application.
     *
     * @return The application configuration JsonObject, or null if not yet loaded
     */
    public static JsonObject getConfig()
    {
        return config;
    }

    /**
     * Main entry point for the NMSLite application.
     * Loads configuration, creates Vert.x instance with custom options, and deploys all verticles.
     *
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args)
    {
        try
        {
            logger.info("Starting NMSLite Application");

            loadConfigurationDirectly()
                .compose(v -> createVertxWithOptions())
                .compose(v -> configureLogging())
                .compose(v -> initializeDatabase())
                .compose(v -> deployAllVerticles())
                .onSuccess(v -> logServerStartupMessage())
                .onFailure(cause ->
                {
                    logger.error("Failed to start NMSLite Application: {}", cause.getMessage());

                    // Cleanup and close Vertx on startup failure
                    cleanup()
                        .onComplete(closeResult ->
                        {
                            if (closeResult.failed())
                            {
                                logger.error("Cleanup failed during shutdown: {}", closeResult.cause().getMessage());
                            }

                            // Use Runtime.halt() instead of System.exit() to prevent shutdown hook from running again
                            Runtime.getRuntime().halt(1);
                        });
                });
        }
        catch (Exception exception)
        {
            logger.error("Fatal error in main method: {}", exception.getMessage());

            System.exit(1);
        }
    }

    /**
     * Load configuration directly from application.conf file without Vertx.
     * Uses Typesafe Config library to parse HOCON format.
     * Sets the class-level config variable for global access.
     *
     * @return Future that completes when configuration is loaded and set
     */
    private static Future<Void> loadConfigurationDirectly()
    {
        try
        {
            logger.debug("Loading configuration from application.conf");

            // Load HOCON file using Typesafe Config
            // ConfigFactory.load("application") looks for application.conf in classpath
            Config typesafeConfig = ConfigFactory.load("application");

            logger.debug("Typesafe Config loaded successfully");

            // Convert Typesafe Config to JSON string
            // ConfigRenderOptions.concise() creates compact JSON (no comments, no whitespace)
            String jsonString = typesafeConfig.root().render(ConfigRenderOptions.concise());

            logger.debug("Config converted to JSON string: {} chars", jsonString.length());

            //  Convert JSON string to Vert.x JsonObject and Set the class-level config variable for global access
            config = new JsonObject(jsonString);

            logger.info("Configuration loaded successfully");

            return Future.succeededFuture();
        }
        catch (Exception exception)
        {
            logger.error("Failed to load configuration: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Create Vertx instance with custom VertxOptions based on configuration.
     * Configures blocked thread check timeouts and worker pool size.
     *
     * @return Future that completes when Vertx instance is created and configured
     */
    private static Future<Void> createVertxWithOptions()
    {
        try
        {
            logger.debug("Creating Vertx instance with custom options");

            // Read Vertx configuration from JsonObject
            var vertxConfig = config.getJsonObject("vertx", new JsonObject());

            var blockedThreadConfig = vertxConfig.getJsonObject("blocked", new JsonObject())
                    .getJsonObject("thread", new JsonObject())
                    .getJsonObject("check", new JsonObject());

            var workerPoolSize = vertxConfig.getJsonObject("worker", new JsonObject())
                    .getJsonObject("pool", new JsonObject())
                    .getInteger("size", 10);

            var checkIntervalSeconds = blockedThreadConfig.getInteger("interval", 1);

            var maxEventLoopTimeSeconds = blockedThreadConfig.getJsonObject("max", new JsonObject())
                    .getJsonObject("eventloop", new JsonObject())
                    .getJsonObject("execute", new JsonObject())
                    .getInteger("time", 2);

            var maxWorkerTimeSeconds = blockedThreadConfig.getJsonObject("max", new JsonObject())
                    .getJsonObject("worker", new JsonObject())
                    .getJsonObject("execute", new JsonObject())
                    .getInteger("time", 60);

            var warningExceptionTimeSeconds = blockedThreadConfig.getJsonObject("warning", new JsonObject())
                    .getJsonObject("exception", new JsonObject())
                    .getInteger("time", 5);

            // Convert to Vert.x units
            var checkInterval = checkIntervalSeconds * 1000L;  // seconds → milliseconds

            var maxEventLoopTime = maxEventLoopTimeSeconds * 1000000000L;  // seconds → nanoseconds

            var maxWorkerTime = maxWorkerTimeSeconds * 1000000000L;  // seconds → nanoseconds

            var warningExceptionTime = warningExceptionTimeSeconds * 1000000000L;  // seconds → nanoseconds

            // Create VertxOptions with custom configuration
            VertxOptions options = new VertxOptions()
                    .setBlockedThreadCheckInterval(checkInterval)
                    .setMaxEventLoopExecuteTime(maxEventLoopTime)
                    .setMaxWorkerExecuteTime(maxWorkerTime)
                    .setWarningExceptionTime(warningExceptionTime)
                    .setWorkerPoolSize(workerPoolSize);

            vertx = Vertx.vertx(options);

            logger.info("Vertx instance created with custom options");

            // This ensures graceful shutdown even if startup fails later
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
            {
                logger.info("Shutdown signal received");

                try
                {
                    // IMPORTANT: Block and wait for cleanup to complete (max 30 seconds)
                    cleanup()
                            .onSuccess(v -> logger.info("Application stopped gracefully"))
                            .onFailure(cause -> logger.error("Error during graceful shutdown: {}", cause.getMessage()))
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(30, java.util.concurrent.TimeUnit.SECONDS);
                }
                catch (Exception exception)
                {
                    logger.error("Shutdown hook interrupted: {}", exception.getMessage());
                }
            }));

            logger.info("Shutdown hook registered");

            return Future.succeededFuture();
        }
        catch (Exception exception)
        {
            logger.error("Error creating Vertx with options: {}", exception.getMessage());

            // Fallback to default Vertx instance
            logger.warn("Falling back to default Vertx instance");

            vertx = Vertx.vertx();

            return Future.succeededFuture();
        }
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
     * @return Future that completes when logging is configured successfully
     */
    private static Future<Void> configureLogging()
    {
        try
        {
            var loggingConfig = config.getJsonObject("logging", new JsonObject());

            var loggingEnabled = loggingConfig.getBoolean("enabled", true);

            // If logging is disabled, turn off all loggers and return early
            if (!loggingEnabled)
            {
                // Programmatically configure logback to disable all logging
                var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

                // Set root logger level to OFF
                var rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

                rootLogger.setLevel(Level.OFF);

                // Set application logger level to OFF
                var appLogger = loggerContext.getLogger("com.nmslite");

                appLogger.setLevel(Level.OFF);

                // Set system properties for logback.xml
                System.setProperty("nmslite.log.level", "OFF");

                System.setProperty("nmslite.log.console.appender", "NULL");

                System.setProperty("nmslite.log.file.appender", "NULL");

                return Future.succeededFuture();
            }

            // Logging is enabled - proceed with full configuration
            var logLevel = loggingConfig.getString("level", "INFO");

            // HOCON parses dotted keys as nested objects: file.enabled becomes file -> enabled
            var fileEnabled = loggingConfig.getJsonObject("file", new JsonObject())
                    .getBoolean("enabled", true);

            var consoleEnabled = loggingConfig.getJsonObject("console", new JsonObject())
                    .getBoolean("enabled", true);

            var filePath = loggingConfig.getJsonObject("file", new JsonObject())
                    .getString("path", "logs/nmslite.log");

            // Set system properties for logback.xml
            System.setProperty("nmslite.log.level", logLevel);

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

            rootLogger.setLevel(Level.toLevel(logLevel, Level.INFO));

            // Set application logger level
            var appLogger = loggerContext.getLogger("com.nmslite");

            appLogger.setLevel(Level.toLevel(logLevel, Level.INFO));

            // Create logs directory if file logging is enabled
            if (fileEnabled)
            {
                var logFile = new java.io.File(filePath);

                var logDir = logFile.getParentFile();

                if (logDir != null && !logDir.exists())
                {
                    logDir.mkdirs();
                }
            }

            logger.info("Logging configured successfully");

            return Future.succeededFuture();
        }
        catch (Exception exception)
        {
            logger.error("Error in configureLogging: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Logs server startup message with HTTPS port.
     */
    private static void logServerStartupMessage()
    {
        try
        {
            var serverConfig = config.getJsonObject("server", new JsonObject());

            var httpsConfig = serverConfig.getJsonObject("https", new JsonObject());

            var httpsPort = httpsConfig.getInteger("port", 8443);

            logger.info("✅ NMSLite Application started successfully");

            logger.info("🔒 HTTPS API available at https://localhost:{}", httpsPort);
        }
        catch (Exception exception)
        {
            logger.error("Error in logServerStartupMessage: {}", exception.getMessage());
        }
    }

    /**
     * Initializes database connection and registers all ProxyGen services.
     * This happens BEFORE any verticles are deployed to ensure services are ready.
     *
     * @return Future that completes when database initialization is done
     */
    private static Future<Void> initializeDatabase()
    {
        try
        {
            databaseInitializer = new DatabaseInitializer();

            return databaseInitializer.initialize();
        }
        catch (Exception exception)
        {
            logger.error("Error in initializeDatabase: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Deploys all verticles using VerticleDeployer.
     * Verticle deployment logic is centralized in VerticleDeployer class for better maintainability.
     * Note: Database services are already initialized before this method is called.
     *
     * @return Future that completes when all verticles are deployed
     */
    private static Future<Void> deployAllVerticles()
    {
        try
        {
            verticleDeployer = new VerticleDeployer();

            return verticleDeployer.deployAll();
        }
        catch (Exception exception)
        {
            logger.error("Error in deployAllVerticles: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Cleans up all deployed verticles, database resources, and closes Vertx instance.
     * Called either from shutdown hook (normal shutdown) or startup failure handler (with Runtime.halt()).
     *
     * @return Future that completes when cleanup is done
     */
    private static Future<Void> cleanup()
    {
        try
        {
            logger.info("Starting cleanup");

            var cleanupFutures = new ArrayList<Future<Void>>();

            // Cleanup database resources
            if (databaseInitializer != null)
            {
                cleanupFutures.add(databaseInitializer.cleanup());
            }

            // Undeploy all verticles (in REVERSE order)
            if (verticleDeployer != null)
            {
                cleanupFutures.add(verticleDeployer.undeployAll());
            }

            // Wait for all cleanup operations to complete, then close Vertx
            return Future.join(cleanupFutures)
                .compose(result ->
                {
                    if (result.succeeded())
                    {
                        logger.debug("All cleanup operations completed successfully");
                    }
                    else
                    {
                        logger.error("Some cleanup operations failed: {}", result.cause().getMessage());
                    }

                    // Close Vertx instance
                    if (vertx != null)
                    {
                        logger.info("Closing Vertx instance");

                        return vertx.close()
                            .onSuccess(v -> logger.info("Vertx instance closed successfully"))
                            .onFailure(cause -> logger.error("Failed to close Vertx instance: {}", cause.getMessage()));
                    }
                    else
                    {
                        logger.info("All cleanup operations completed successfully");

                        return Future.succeededFuture();
                    }
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in cleanup: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}
