package com.nmslite.verticles;

import com.nmslite.Bootstrap;

import com.nmslite.handlers.*;

import com.nmslite.middleware.AuthenticationMiddleware;

import com.nmslite.services.*;

import com.nmslite.utils.JWTUtil;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Promise;

import io.vertx.core.http.HttpMethod;

import io.vertx.core.http.HttpServer;

import io.vertx.core.http.HttpServerOptions;

import io.vertx.core.json.JsonObject;

import io.vertx.core.net.JksOptions;

import io.vertx.ext.web.Router;

import io.vertx.ext.web.handler.BodyHandler;

import io.vertx.ext.web.handler.CorsHandler;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * ServerVerticle - HTTPS API Server (Production - HTTPS Only)

 * Responsibilities:
 * - HTTPS REST API endpoints (via handlers)
 * - Service proxy initialization
 * - Router setup and middleware
 * - SSL/TLS configuration

 * All business logic has been extracted to handler classes:
 * - UserHandler: User management operations
 * - CredentialHandler: Credential profile operations
 * - DiscoveryProfileHandler: Discovery profile management operations
 * - DeviceHandler: Device management operations
 */
public class ServerVerticle extends AbstractVerticle
{

    private static final Logger logger = LoggerFactory.getLogger(ServerVerticle.class);

    private HttpServer httpsServer;

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

    private MetricsHandler metricsHandler;

    private AvailabilityHandler availabilityHandler;

    private AuthenticationMiddleware authMiddleware;

    /**
     * Starts the ServerVerticle by initializing service proxies, creating handlers,
     * setting up the HTTPS server with routing, and starting the server on configured port.
     *
     * @param startPromise Promise completed when server starts successfully
     */
    @Override
    public void start(Promise<Void> startPromise)
    {
        try
        {
            logger.info("Starting ServerVerticle (HTTPS Only)");

            var serverConfig = Bootstrap.getConfig().getJsonObject("server", new JsonObject());

            var httpsConfig = serverConfig.getJsonObject("https", new JsonObject());

            // Initialize all service proxies
            initializeServiceProxies();

            // Create handler instances
            createHandlers();

            // Create router
            var router = createRouter();

            if (router == null)
            {
                logger.error("Failed to create router - router is null");

                startPromise.fail("Router creation failed");

                return;
            }

            // Start HTTPS server
            startHttpsServer(router, httpsConfig, startPromise);
        }
        catch (Exception exception)
        {
            logger.error("Error in start: {}", exception.getMessage());

            startPromise.fail(exception);
        }
    }

    /**
     * Starts HTTPS server with SSL/TLS configuration.
     *
     * @param router Router instance with all routes configured
     * @param httpsConfig HTTPS configuration from application.conf
     * @param startPromise Promise to complete when server starts
     */
    private void startHttpsServer(Router router, JsonObject httpsConfig, Promise<Void> startPromise)
    {
        try
        {
            var httpsPort = httpsConfig.getInteger("port", 8443);

            var keystoreConfig = httpsConfig.getJsonObject("keystore", new JsonObject());

            var keystorePath = keystoreConfig.getString("path", "keystore.jks");

            var keystorePassword = keystoreConfig.getString("password", "motadataadmin");

            // Create HTTPS server options with SSL/TLS
            var options = new HttpServerOptions()
                .setSsl(true)
                .setKeyCertOptions(new JksOptions()
                    .setPath(keystorePath)
                    .setPassword(keystorePassword));

            httpsServer = vertx.createHttpServer(options);

            httpsServer.requestHandler(router)
                .listen(httpsPort)
                .onSuccess(server ->
                {
                    logger.info("✅ HTTPS Server started on port {} (SSL/TLS enabled)", httpsPort);

                    logger.info("🔒 Keystore: {}", keystorePath);

                    startPromise.complete();
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to start HTTPS server: {}", cause.getMessage());

                    logger.error("Check keystore path and password in configuration");

                    startPromise.fail(cause);
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in startHttpsServer: {}", exception.getMessage());

            startPromise.fail(exception);
        }
    }

    /**
     * Initializes all service proxies by creating proxy instances for each service.
     * These proxies communicate with database service implementations via the event bus.
     * Note: Database services are initialized at application startup before verticles deploy.
     */
    private void initializeServiceProxies()
    {
        try
        {
            this.userService = UserService.createProxy();

            this.deviceService = DeviceService.createProxy();

            this.deviceTypeService = DeviceTypeService.createProxy();

            this.credentialProfileService = CredentialProfileService.createProxy();

            this.discoveryProfileService = DiscoveryProfileService.createProxy();

            this.metricsService = MetricsService.createProxy();

            this.availabilityService = AvailabilityService.createProxy();
        }
        catch (Exception exception)
        {
            logger.error("Error in initializeServiceProxies: {}", exception.getMessage());
        }
    }

    /**
     * Creates all handler instances with their required dependencies.
     * Initializes JWT utilities and authentication middleware.
     */
    private void createHandlers()
    {
        try
        {
            // Initialize JWT utilities
            // JWT and Authentication
            var jwtUtil = new JWTUtil();

            this.authMiddleware = new AuthenticationMiddleware(jwtUtil);

            // Create handlers with JWT support
            this.userHandler = new UserHandler(userService, jwtUtil);

            this.credentialHandler = new CredentialHandler(credentialProfileService);

            this.discoveryProfileHandler = new DiscoveryProfileHandler(discoveryProfileService, deviceTypeService, credentialProfileService);

            this.deviceHandler = new DeviceHandler(deviceService, deviceTypeService);

            this.metricsHandler = new MetricsHandler(metricsService);

            this.availabilityHandler = new AvailabilityHandler(availabilityService);
        }
        catch (Exception exception)
        {
            logger.error("Error in createHandlers: {}", exception.getMessage());
        }
    }

    /**
     * Creates and configures the main router with middleware and all API routes.
     *
     * @return Configured Router instance
     */
    private Router createRouter()
    {
        try
        {
            var router = Router.router(vertx);

            // Middleware
            router.route().handler(CorsHandler.create()
                    .addOrigin("*")
                    .allowedMethod(HttpMethod.GET)
                    .allowedMethod(HttpMethod.POST)
                    .allowedMethod(HttpMethod.PUT)
                    .allowedMethod(HttpMethod.DELETE));

            router.route().handler(BodyHandler.create());

            // Setup all API routes using handlers
            setupUserRoutes(router);

            setupCredentialRoutes(router);

            setupDiscoveryRoutes(router);

            setupDeviceRoutes(router);

            setupMetricsRoutes(router);

            setupAvailabilityRoutes(router);

            // Setup Swagger UI routes
            setupSwaggerRoutes(router);

            // Global failure handler - catches all unhandled exceptions
            router.route().failureHandler(ctx ->
            {
                var failure = ctx.failure();

                var statusCode = ctx.statusCode();

                if (statusCode == -1)
                {
                    statusCode = 500;
                }

                logger.error("Request failed: {} - {}", ctx.request().path(),
                    failure != null ? failure.getMessage() : "Unknown error");

                var errorResponse = new JsonObject()
                    .put("success", false)
                    .put("error", failure != null ? failure.getMessage() : "Internal Server Error")
                    .put("path", ctx.request().path())
                    .put("timestamp", System.currentTimeMillis());

                ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", "application/json")
                    .end(errorResponse.encode());
            });

            // 404 handler for unmatched routes
            router.route("/*").handler(ctx ->
                    ctx.response()
                        .setStatusCode(404)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("error", "Not Found").encode()));

            return router;
        }
        catch (Exception exception)
        {
            logger.error("Error in createRouter: {}", exception.getMessage());

            return null;
        }
    }

    /**
     * Sets up user-related routes including authentication and user management.
     *
     * @param router Router instance to add routes to
     */
    private void setupUserRoutes(Router router)
    {
        try
        {
            // User Authentication APIs (No authentication required for login)
            router.post("/api/auth/login").handler(userHandler::authenticateUser);

            // User Management APIs (Authentication required)
            router.get("/api/users").handler(authMiddleware.requireAuthentication()).handler(userHandler::getUsers);

            router.post("/api/users").handler(authMiddleware.requireAuthentication()).handler(userHandler::createUser);

            router.put("/api/users/:id").handler(authMiddleware.requireAuthentication()).handler(userHandler::updateUser);

            router.delete("/api/users/:id").handler(authMiddleware.requireAuthentication()).handler(userHandler::deleteUser);
        }
        catch (Exception exception)
        {
            logger.error("Error in setupUserRoutes: {}", exception.getMessage());
        }
    }

    /**
     * Sets up credential profile management routes.
     *
     * @param router Router instance to add routes to
     */
    private void setupCredentialRoutes(Router router)
    {
        try
        {
            // Credential Profile APIs (Authentication required)
            router.get("/api/credentials").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::getCredentials);

            router.post("/api/credentials").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::createCredentials);

            router.put("/api/credentials/:id").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::updateCredentials);

            router.delete("/api/credentials/:id").handler(authMiddleware.requireAuthentication()).handler(credentialHandler::deleteCredentials);
        }
        catch (Exception exception)
        {
            logger.error("Error in setupCredentialRoutes: {}", exception.getMessage());
        }
    }

    /**
     * Sets up discovery profile and discovery operation routes.
     *
     * @param router Router instance to add routes to
     */
    private void setupDiscoveryRoutes(Router router)
    {
        try
        {
            // Discovery Profile Management APIs (Database CRUD) - Authentication required
            router.get("/api/discovery-profiles").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::getDiscoveryProfiles);

            router.post("/api/discovery-profiles").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::createDiscoveryProfile);

            router.delete("/api/discovery-profiles/:id").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::deleteDiscoveryProfile);

            // Discovery Operations APIs (GoEngine-based) - Authentication required
            router.post("/api/discovery/test").handler(authMiddleware.requireAuthentication()).handler(discoveryProfileHandler::testDiscovery);
        }
        catch (Exception exception)
        {
            logger.error("Error in setupDiscoveryRoutes: {}", exception.getMessage());
        }
    }

    /**
     * Sets up device management routes including provisioning, monitoring, and device types.
     *
     * @param router Router instance to add routes to
     */
    private void setupDeviceRoutes(Router router)
    {
        try
        {
            // Device Management API (Authentication required)
            router.get("/api/devices/discovered").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::getDiscoveredDevices);

            // Get provisioned devices
            router.get("/api/devices/provisioned").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::getProvisionedDevices);

            router.put("/api/devices/:id/config").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::updateDeviceConfig);

            router.post("/api/devices/:id/provisioning/enable").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::enableProvisioning);

            router.post("/api/devices/:id/provisioning/disable").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::disableProvisioning);

            router.delete("/api/devices/:id").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::softDeleteDevice);

            router.post("/api/devices/:id/restore").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::restoreDevice);

            // Device Types (Read-Only) - Authentication required
            router.get("/api/device-types").handler(authMiddleware.requireAuthentication()).handler(deviceHandler::getDeviceTypes);
        }
        catch (Exception exception)
        {
            logger.error("Error in setupDeviceRoutes: {}", exception.getMessage());
        }
    }

    /**
     * Sets up metrics-related routes.
     *
     * @param router Router instance to add routes to
     */
    private void setupMetricsRoutes(Router router)
    {
        try
        {
            // Metrics API (Authentication required)
            router.get("/api/metrics/:deviceId").handler(authMiddleware.requireAuthentication()).handler(metricsHandler::getDeviceMetrics);
        }
        catch (Exception exception)
        {
            logger.error("Error in setupMetricsRoutes: {}", exception.getMessage());
        }
    }

    /**
     * Sets up availability-related routes.
     *
     * @param router Router instance to add routes to
     */
    private void setupAvailabilityRoutes(Router router)
    {
        try
        {
            // Availability API (Authentication required)
            router.get("/api/availability/:deviceId").handler(authMiddleware.requireAuthentication()).handler(availabilityHandler::getDeviceAvailability);
        }
        catch (Exception exception)
        {
            logger.error("Error in setupAvailabilityRoutes: {}", exception.getMessage());
        }
    }

    /**
     * Sets up Swagger UI and OpenAPI documentation routes.
     * Serves the OpenAPI specification and Swagger UI interface.
     *
     * @param router Router instance to add routes to
     */
    private void setupSwaggerRoutes(Router router)
    {
        try
        {
            // Serve Swagger UI at /swagger with embedded OpenAPI spec
            router.get("/swagger").handler(context ->
                    vertx.fileSystem().readFile("src/main/resources/openapi.yaml")
                        .onSuccess(specBuffer ->
                        {
                            var specContent = specBuffer.toString()
                                .replace("\\", "\\\\")
                                .replace("`", "\\`")
                                .replace("$", "\\$");

                            var html = """
                                <!DOCTYPE html>
                                <html lang="en">
                                <head>
                                    <meta charset="UTF-8">
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                    <title>NMSLite API Documentation</title>
                                    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui.css">
                                    <style>
                                        .topbar { display: none; }
                                    </style>
                                </head>
                                <body>
                                    <div id="swagger-ui"></div>
                                    <script src="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui-bundle.js"></script>
                                    <script src="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui-standalone-preset.js"></script>
                                    <script src="https://unpkg.com/js-yaml@4.1.0/dist/js-yaml.min.js"></script>
                                    <script>
                                        window.onload = function() {
                                            var spec = `%s`;
                                            var specObj = jsyaml.load(spec);
                                            window.ui = SwaggerUIBundle({
                                                spec: specObj,
                                                dom_id: '#swagger-ui',
                                                deepLinking: true,
                                                presets: [
                                                    SwaggerUIBundle.presets.apis,
                                                    SwaggerUIStandalonePreset
                                                ],
                                                plugins: [
                                                    SwaggerUIBundle.plugins.DownloadUrl
                                                ],
                                                layout: "StandaloneLayout",
                                                persistAuthorization: true
                                            });
                                        };
                                    </script>
                                </body>
                                </html>
                                """.formatted(specContent);

                            context.response()
                                .putHeader("Content-Type", "text/html")
                                .end(html);
                        })
                        .onFailure(cause ->
                        {
                            logger.error("❌ Failed to read OpenAPI spec file: {}", cause.getMessage());

                            context.response()
                                .setStatusCode(500)
                                .putHeader("Content-Type", "text/html")
                                .end("<h1>Error loading API documentation</h1>");
                        }));
        }
        catch (Exception exception)
        {
            logger.error("Error in setupSwaggerRoutes: {}", exception.getMessage());
        }
    }

    /**
     * Stops the ServerVerticle by closing the HTTPS server.
     *
     * @param stopPromise Promise completed when server stops successfully
     */
    @Override
    public void stop(Promise<Void> stopPromise)
    {
        try
        {
            logger.info("🛑 Stopping ServerVerticle");

            if (httpsServer != null)
            {
                httpsServer.close()
                    .onComplete(result ->
                    {
                        logger.info("✅ HTTPS Server stopped");

                        stopPromise.complete();
                    });
            }
            else
            {
                stopPromise.complete();
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in stop: {}", exception.getMessage());

            stopPromise.fail(exception);
        }
    }

}
