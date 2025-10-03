package com.nmslite.verticles;

import com.nmslite.handlers.*;
import com.nmslite.middleware.AuthenticationMiddleware;
import com.nmslite.services.*;
import com.nmslite.utils.JWTUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * ServerVerticle - Clean HTTP API Server
 *
 * Responsibilities:
 * - HTTP REST API endpoints (via handlers)
 * - Service proxy initialization
 * - Router setup and middleware
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

    // Service proxies
    private UserService userService;
    private DeviceService deviceService;
    private DeviceTypeService deviceTypeService;
    private CredentialProfileService credentialProfileService;
    private DiscoveryProfileService discoveryProfileService;
     private MetricsService metricsService;
     private AvailabilityService availabilityService;

    // Handler instances
    private UserHandler userHandler;
    private CredentialHandler credentialHandler;
    private DiscoveryProfileHandler discoveryProfileHandler;
    private DeviceHandler deviceHandler;

    // JWT and Authentication
    private JWTUtil jwtUtil;
    private AuthenticationMiddleware authMiddleware;

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("🌐 Starting ServerVerticle - HTTP API with ProxyGen");

        httpPort = config().getInteger("http.port", 8080);

        // Initialize all service proxies
        initializeServiceProxies();
        logger.info("🔧 All service proxies initialized");

        // Create handler instances
        createHandlers();
        logger.info("🎯 All handlers created");

        // Setup HTTP server with routing
        httpServer = vertx.createHttpServer();
        Router router = createRouter();

        // Start HTTP server
        httpServer.requestHandler(router)
            .listen(httpPort)
            .onSuccess(server -> {
                logger.info("✅ HTTP Server started on port {}", httpPort);
                startPromise.complete();
            })
            .onFailure(cause -> {
                logger.error("❌ Failed to start HTTP server", cause);
                startPromise.fail(cause);
            });
    }

    /**
     * Initialize all service proxies
     */
    private void initializeServiceProxies() {
        this.userService = UserService.createProxy(vertx);
        this.deviceService = DeviceService.createProxy(vertx);
        this.deviceTypeService = DeviceTypeService.createProxy(vertx);
        this.credentialProfileService = CredentialProfileService.createProxy(vertx);
        this.discoveryProfileService = DiscoveryProfileService.createProxy(vertx);
        this.metricsService = MetricsService.createProxy(vertx);
        this.availabilityService = AvailabilityService.createProxy(vertx);
    }

    /**
     * Create all handler instances
     */
    private void createHandlers() {
        // Initialize JWT utilities
        this.jwtUtil = new JWTUtil(vertx);
        this.authMiddleware = new AuthenticationMiddleware(jwtUtil);

        // Create handlers with JWT support
        this.userHandler = new UserHandler(userService, jwtUtil);
        this.credentialHandler = new CredentialHandler(credentialProfileService);
        this.discoveryProfileHandler = new DiscoveryProfileHandler(vertx, discoveryProfileService, deviceTypeService, credentialProfileService);
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

    private void setupUserRoutes(Router router) {
        // User Authentication APIs (No authentication required)
        router.post("/api/auth/login").handler(userHandler::authenticateUser);

        // User Management APIs (Authentication required)
        router.get("/api/users").handler(authMiddleware.requireAuthentication()).handler(userHandler::getUsers);
        router.post("/api/users").handler(authMiddleware.requireAuthentication()).handler(userHandler::createUser);
        router.put("/api/users/:id").handler(authMiddleware.requireAuthentication()).handler(userHandler::updateUser);
        router.delete("/api/users/:id").handler(authMiddleware.requireAuthentication()).handler(userHandler::deleteUser);
    }

    private void setupCredentialRoutes(Router router) {
        // Credential Profile APIs (Authentication required)
        router.get("/api/credentials").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::getCredentials);
        router.post("/api/credentials").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::createCredentials);
        router.put("/api/credentials/:id").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::updateCredentials);
        router.delete("/api/credentials/:id").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::deleteCredentials);
    }

    private void setupDiscoveryRoutes(Router router) {
        // Discovery Profile Management APIs (Database CRUD) - Authentication required
        router.get("/api/discovery-profiles").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::getDiscoveryProfiles);
        router.post("/api/discovery-profiles").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::createDiscoveryProfile);

        router.delete("/api/discovery-profiles/:id").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::deleteDiscoveryProfile);

        // Discovery Operations APIs (GoEngine-based) - Authentication required
        router.post("/api/discovery/test").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::testDiscovery);
    }

    private void setupDeviceRoutes(Router router) {
        // Device Management API (Authentication required)
        // Listing routes split by provision status
        router.get("/api/devices/discovered").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::getDiscoveredDevices);
        router.get("/api/devices/provisioned").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::getProvisionedDevices);
        // REMOVED - Manual device creation not needed (devices created via discovery only)
        // router.post("/api/devices").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::createDevice);
        router.put("/api/devices/:id/config").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::updateDeviceConfig);
        router.delete("/api/devices/:id").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::softDeleteDevice);
        router.post("/api/devices/:id/monitoring/enable").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::enableMonitoring);

        router.post("/api/devices/:id/monitoring/disable").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::disableMonitoring);

        router.post("/api/devices/:id/restore").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::restoreDevice);

        // Device Types (Read-Only) - Authentication required
        router.get("/api/device-types").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::getDeviceTypes);

        // router.post("/api/devices/:profileId/provision").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::provisionDevicesFromProfile);

        // COMMENTED OUT FOR DISCOVERY TESTING - Metrics routes
        // router.get("/api/devices/:id/metrics").handler(this::getDeviceMetrics);
        // router.put("/api/devices/:id/status").handler(this::updateDeviceStatus);
        // router.get("/api/metrics/devices/:id/latest").handler(this::getDeviceLatestMetrics);
        // router.get("/api/metrics/devices/:id/availability").handler(this::getDeviceAvailability);
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
