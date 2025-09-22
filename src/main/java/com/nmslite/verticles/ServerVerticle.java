package com.nmslite.verticles;

import com.nmslite.handlers.*;
import com.nmslite.services.*;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServerVerticle - Clean HTTP API and WebSocket Communication
 *
 * Responsibilities:
 * - HTTP REST API endpoints (via handlers)
 * - WebSocket real-time communication
 * - Service proxy initialization
 * - Router setup and middleware
 * - Event bus setup for WebSocket updates
 *
 * All business logic has been extracted to handler classes:
 * - UserHandler: User management operations
 * - CredentialHandler: Credential profile operations  
 * - DiscoveryProfileHandler: Discovery profile management operations
 * - DeviceHandler: Device management operations
 */
public class ServerVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(ServerVerticle.class);

    private HttpServer httpServer;
    private int httpPort;
    private final Set<ServerWebSocket> webSocketConnections = ConcurrentHashMap.newKeySet();

    // Service proxies
    private UserService userService;
    private DeviceService deviceService;
    private DeviceTypeService deviceTypeService;
    private CredentialProfileService credentialProfileService;
    private DiscoveryProfileService discoveryProfileService;
    // COMMENTED OUT FOR DISCOVERY TESTING
    // private MetricsService metricsService;
    // private AvailabilityService availabilityService;
    
    // Handler instances
    private UserHandler userHandler;
    private CredentialHandler credentialHandler;
    private DiscoveryProfileHandler discoveryProfileHandler;
    private DeviceHandler deviceHandler;

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("🌐 Starting ServerVerticle - HTTP API & WebSocket with ProxyGen");

        httpPort = config().getInteger("http.port", 8080);
        String websocketPath = config().getString("websocket.path", "/ws");

        // Initialize all service proxies
        initializeServiceProxies();
        logger.info("🔧 All service proxies initialized");
        
        // Create handler instances
        createHandlers();
        logger.info("🎯 All handlers created");

        // Setup HTTP server with routing
        httpServer = vertx.createHttpServer();
        Router router = createRouter();

        // Setup WebSocket endpoint
        httpServer.webSocketHandler(websocket -> {
            if (websocket.path().equals(websocketPath)) {
                handleWebSocketConnection(websocket);
            } else {
                websocket.reject();
            }
        });

        // Setup event bus consumers for WebSocket updates
        setupEventBusConsumers();

        // Start HTTP server
        httpServer.requestHandler(router)
            .listen(httpPort)
            .onSuccess(server -> {
                logger.info("✅ HTTP Server started on port {}", httpPort);
                logger.info("📡 WebSocket endpoint available at {}", websocketPath);
                startPromise.complete();
            })
            .onFailure(cause -> {
                logger.error("❌ Failed to start HTTP server", cause);
                startPromise.fail(cause);
            });
    }

    /**
     * Initialize all service proxies (metrics commented out for discovery testing)
     */
    private void initializeServiceProxies() {
        this.userService = UserService.createProxy(vertx);
        this.deviceService = DeviceService.createProxy(vertx);
        this.deviceTypeService = DeviceTypeService.createProxy(vertx);
        this.credentialProfileService = CredentialProfileService.createProxy(vertx);
        this.discoveryProfileService = DiscoveryProfileService.createProxy(vertx);
        // COMMENTED OUT FOR DISCOVERY TESTING
        // this.metricsService = MetricsService.createProxy(vertx);
        // this.availabilityService = AvailabilityService.createProxy(vertx);
    }
    
    /**
     * Create all handler instances
     */
    private void createHandlers() {
        this.userHandler = new UserHandler(userService);
        this.credentialHandler = new CredentialHandler(credentialProfileService);
        this.discoveryProfileHandler = new DiscoveryProfileHandler(vertx, discoveryProfileService, deviceTypeService);
        this.deviceHandler = new DeviceHandler(deviceService, deviceTypeService, vertx);
    }

    private Router createRouter() {
        Router router = Router.router(vertx);

        // Middleware
        router.route().handler(CorsHandler.create("*"));
        router.route().handler(BodyHandler.create());

        // Setup all API routes using handlers
        setupUserRoutes(router);
        setupCredentialRoutes(router);
        setupDiscoveryRoutes(router);
        setupDeviceRoutes(router);
        
        // 404 handler for unmatched routes
        router.route("/*").handler(ctx -> {
            ctx.response()
                .setStatusCode(404)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Not Found").encode());
        });

        return router;
    }

    // ========================================
    // CLEAN ROUTING SETUP METHODS
    // ========================================

    private void setupUserRoutes(Router router) {
        // User Management APIs
        router.get("/api/users").handler(userHandler::getUsers);
        router.post("/api/users").handler(userHandler::createUser);
        router.put("/api/users/:id").handler(userHandler::updateUser);
        router.delete("/api/users/:id").handler(userHandler::deleteUser);
        router.put("/api/users/:id/password").handler(userHandler::changeUserPassword);
    }

    private void setupCredentialRoutes(Router router) {
        // Credential Profile APIs
        router.get("/api/credentials").handler(credentialHandler::getCredentials);
        router.post("/api/credentials").handler(credentialHandler::createCredentials);
        router.put("/api/credentials/:id").handler(credentialHandler::updateCredentials);
        router.delete("/api/credentials/:id").handler(credentialHandler::deleteCredentials);
    }

    private void setupDiscoveryRoutes(Router router) {
        // Discovery Profile Management APIs (Database CRUD)
        router.get("/api/discovery-profiles").handler(discoveryProfileHandler::getDiscoveryProfiles);
        router.post("/api/discovery-profiles").handler(discoveryProfileHandler::createDiscoveryProfile);
        router.put("/api/discovery-profiles/:id").handler(discoveryProfileHandler::updateDiscoveryProfile);
        router.delete("/api/discovery-profiles/:id").handler(discoveryProfileHandler::deleteDiscoveryProfile);

        // Discovery Operations APIs (GoEngine-based)
        router.post("/api/discovery/execute").handler(discoveryProfileHandler::executeDiscovery);
    }

    private void setupDeviceRoutes(Router router) {
        // Device Management API
        router.get("/api/devices").handler(deviceHandler::getDevices);
        router.post("/api/devices").handler(deviceHandler::createDevice);
        router.put("/api/devices/:id/monitoring").handler(deviceHandler::updateDeviceMonitoringConfig);
        router.delete("/api/devices/:id").handler(deviceHandler::softDeleteDevice);
        router.post("/api/devices/:id/restore").handler(deviceHandler::restoreDevice);



        // Device Types (Read-Only) - Only list endpoint needed
        router.get("/api/device-types").handler(deviceHandler::getDeviceTypes);

        // COMMENTED OUT FOR DISCOVERY TESTING - Metrics routes
        // router.get("/api/devices/:id/metrics").handler(this::getDeviceMetrics);
        // router.put("/api/devices/:id/status").handler(this::updateDeviceStatus);
        // router.get("/api/metrics/devices/:id/latest").handler(this::getDeviceLatestMetrics);
        // router.get("/api/metrics/devices/:id/availability").handler(this::getDeviceAvailability);
    }

    // ========================================
    // WEBSOCKET MANAGEMENT
    // ========================================

    private void handleWebSocketConnection(ServerWebSocket websocket) {
        logger.info("📡 New WebSocket connection from {}", websocket.remoteAddress());
        
        webSocketConnections.add(websocket);
        
        websocket.closeHandler(v -> {
            webSocketConnections.remove(websocket);
            logger.info("📡 WebSocket connection closed from {}", websocket.remoteAddress());
        });
        
        websocket.exceptionHandler(cause -> {
            logger.error("📡 WebSocket error from {}", websocket.remoteAddress(), cause);
            webSocketConnections.remove(websocket);
        });
    }

    private void setupEventBusConsumers() {
        // Listen for discovery test results
        vertx.eventBus().consumer("discovery.test_result", message -> {
            JsonObject result = (JsonObject) message.body();
            broadcastToWebSockets(new JsonObject()
                .put("type", "discovery.test_result")
                .put("data", result));
        });

        // Listen for provisioning results
        vertx.eventBus().consumer("provision.result", message -> {
            JsonObject result = (JsonObject) message.body();
            broadcastToWebSockets(new JsonObject()
                .put("type", "provision.result")
                .put("data", result));
        });

        // COMMENTED OUT FOR DISCOVERY TESTING - Metrics event listeners
        // vertx.eventBus().consumer("connectivity.failed", message -> {
        //     JsonObject notification = (JsonObject) message.body();
        //     broadcastToWebSockets(new JsonObject()
        //         .put("type", "connectivity.failed")
        //         .put("data", notification));
        // });

        // vertx.eventBus().consumer("metrics.update", message -> {
        //     JsonObject metrics = (JsonObject) message.body();
        //     broadcastToWebSockets(new JsonObject()
        //         .put("type", "metrics.update")
        //         .put("data", metrics));
        // });

        logger.info("📡 Event bus consumers setup complete");
    }

    private void broadcastToWebSockets(JsonObject message) {
        String messageStr = message.encode();
        webSocketConnections.forEach(ws -> {
            if (!ws.isClosed()) {
                ws.writeTextMessage(messageStr);
            }
        });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("🛑 Stopping ServerVerticle");

        if (httpServer != null) {
            httpServer.close()
                .onComplete(result -> {
                    logger.info("✅ HTTP Server stopped");
                    stopPromise.complete();
                });
        } else {
            stopPromise.complete();
        }
    }
}
