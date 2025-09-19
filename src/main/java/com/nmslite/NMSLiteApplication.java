package com.nmslite;

import com.nmslite.verticles.DatabaseVerticle;
import com.nmslite.verticles.DiscoveryVerticle;
import com.nmslite.verticles.ServerVerticle;
import com.nmslite.verticles.PollingMetricsVerticle;
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

/**
 * NMSLite Application - 5-Verticle Event-Driven Architecture with ProxyGen
 *
 * Architecture:
 * - ServerVerticle: HTTP API + WebSocket communication
 * - DatabaseVerticle: All database operations (ProxyGen enabled)
 * - UserServiceVerticle: User management operations (ProxyGen enabled)
 * - DiscoveryVerticle: Device discovery workflow
 * - PollingMetricsVerticle: Continuous monitoring
 *
 * Communication: Event Bus driven with async messaging + ProxyGen services
 */
public class NMSLiteApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(NMSLiteApplication.class);

    // Worker executor for blocking operations
    private WorkerExecutor workerExecutor;

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("🚀 Starting NMSLite Application - 5-Verticle Architecture");

        // Load configuration from application.conf
        loadConfiguration()
            .onSuccess(config -> {
                logger.info("✅ Configuration loaded from application.conf");

                // Create shared worker executor for blocking operations
                setupWorkerExecutor(config);

                // Deploy all verticles in parallel (PollingMetricsVerticle commented out for discovery testing)
                Future<String> databaseFuture = deployDatabaseVerticle(config);
                Future<String> discoveryFuture = deployDiscoveryVerticle(config);
                // Future<String> pollingFuture = deployPollingVerticle(config); // COMMENTED OUT FOR DISCOVERY TESTING
                Future<String> mainFuture = deployMainVerticle(config);

                // Wait for all verticles to deploy successfully
                CompositeFuture.all(databaseFuture, discoveryFuture, mainFuture)
                    .onSuccess(result -> {
                        logger.info("✅ All verticles deployed successfully!");
                        logger.info("📊 DatabaseVerticle (ProxyGen): {}", databaseFuture.result());
                        logger.info("🔍 DiscoveryVerticle: {}", discoveryFuture.result());
                        // logger.info("📈 PollingMetricsVerticle: {}", pollingFuture.result()); // COMMENTED OUT FOR DISCOVERY TESTING
                        logger.info("🌐 ServerVerticle: {}", mainFuture.result());
                        logger.info("🎯 NMSLite is ready for discovery testing!");
                        startPromise.complete();
                    })
                    .onFailure(cause -> {
                        logger.error("❌ Failed to deploy verticles", cause);
                        startPromise.fail(cause);
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
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("polling", new JsonObject()));

        return vertx.deployVerticle(new PollingMetricsVerticle(), options);
    }

    private Future<String> deployMainVerticle(JsonObject config) {
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(config.getJsonObject("main", new JsonObject()));

        return vertx.deployVerticle(new ServerVerticle(), options);
    }

    private void setupWorkerExecutor(JsonObject config) {
        // Get worker pool configuration from config or use defaults
        int workerPoolSize = config.getInteger("worker.pool.size", 1);  // Start with 1 thread as requested
        long maxExecuteTime = config.getLong("worker.max.execute.time", 300000L);  // 5 minutes max

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
                    config.getJsonObject("discovery").getString("goengine.path"));

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

        // Close worker executor if it exists
        if (workerExecutor != null) {
            workerExecutor.close();
            logger.info("🔧 Worker executor closed");
        }

        stopPromise.complete();
    }

    /**
     * Main method to start the NMSLite application
     */
    public static void main(String[] args) {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        vertx.deployVerticle(new NMSLiteApplication())
            .onSuccess(id -> {
                System.out.println("🎉 NMSLite Application started successfully!");
                System.out.println("📡 HTTP API available at: http://localhost:8080");
                System.out.println("🔌 WebSocket endpoint: ws://localhost:8080/ws");
            })
            .onFailure(cause -> {
                System.err.println("❌ Failed to start NMSLite Application: " + cause.getMessage());
                cause.printStackTrace();
                System.exit(1);
            });
    }
}
