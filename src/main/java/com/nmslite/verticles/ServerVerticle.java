package com.nmslite.verticles;

import com.nmslite.services.*;
import com.nmslite.services.UserService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServerVerticle - HTTP API and WebSocket Communication with ProxyGen
 *
 * Responsibilities:
 * - HTTP REST API endpoints
 * - WebSocket real-time updates
 * - Event bus message forwarding to UI
 * - Request validation and response formatting
 * - ProxyGen database service integration
 */
public class ServerVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(ServerVerticle.class);

    // WebSocket connections for real-time updates
    private final Set<ServerWebSocket> webSocketConnections = ConcurrentHashMap.newKeySet();

    private HttpServer httpServer;
    private int httpPort;

    // Service proxies
    private UserService userService;
    private DeviceService deviceService;
    private DeviceTypeService deviceTypeService;
    private CredentialService credentialService;
    private DiscoveryService discoveryService;
    private MetricsService metricsService;
    private AvailabilityService availabilityService;

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("🌐 Starting ServerVerticle - HTTP API & WebSocket with ProxyGen");

        httpPort = config().getInteger("http.port", 8080);
        String websocketPath = config().getString("websocket.path", "/ws");

        // Initialize all service proxies
        initializeServiceProxies();
        logger.info("🔧 All service proxies initialized");

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

        // Start HTTP server
        httpServer
            .requestHandler(router)
            .listen(httpPort)
            .onSuccess(server -> {
                logger.info("✅ HTTP Server started on port {}", httpPort);
                logger.info("🔌 WebSocket endpoint: ws://localhost:{}{}", httpPort, websocketPath);
                setupEventBusConsumers();
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
        this.credentialService = CredentialService.createProxy(vertx);
        this.discoveryService = DiscoveryService.createProxy(vertx);
        this.metricsService = MetricsService.createProxy(vertx);
        this.availabilityService = AvailabilityService.createProxy(vertx);
    }

    private Router createRouter() {
        Router router = Router.router(vertx);

        // Middleware
        router.route().handler(CorsHandler.create("*"));
        router.route().handler(BodyHandler.create());

        // API Routes

        // Discovery API - Single Device Only
        router.post("/api/devices/discover").handler(this::discoverSingleDevice);

        // Provision API
        router.post("/api/provision").handler(this::startProvision);

        // User Management APIs
        router.get("/api/users").handler(this::getUsers);
        router.post("/api/users").handler(this::createUser);
        router.get("/api/users/:id").handler(this::getUserById);
        router.put("/api/users/:id").handler(this::updateUser);
        router.delete("/api/users/:id").handler(this::deleteUser);
        router.post("/api/users/authenticate").handler(this::authenticateUser);
        router.put("/api/users/:id/password").handler(this::changeUserPassword);
        router.put("/api/users/:id/status").handler(this::setUserStatus);

        // Configuration API
        router.get("/api/device-types").handler(this::getDeviceTypes);
        router.get("/api/credentials").handler(this::getCredentials);
        router.post("/api/credentials").handler(this::createCredentials);
        router.put("/api/credentials/:id").handler(this::updateCredentials);
        router.delete("/api/credentials/:id").handler(this::deleteCredentials);

        // Discovery Profile Management APIs
        router.get("/api/discovery/profiles").handler(this::getDiscoveryProfiles);
        router.post("/api/discovery/profiles").handler(this::createDiscoveryProfile);
        router.put("/api/discovery/profiles/:id").handler(this::updateDiscoveryProfile);
        router.delete("/api/discovery/profiles/:id").handler(this::deleteDiscoveryProfile);

        // Discovery Validation APIs
        router.post("/api/discovery/validate").handler(this::validateCredentials);
        router.get("/api/discovery/conflicts").handler(this::checkDiscoveryConflicts);

        // Device Management API
        router.get("/api/devices").handler(this::getDevices);
        router.get("/api/devices/:id/metrics").handler(this::getDeviceMetrics);
        router.put("/api/devices/:id/status").handler(this::updateDeviceStatus);
        router.delete("/api/devices/:id").handler(this::softDeleteDevice);
        router.post("/api/devices/:id/restore").handler(this::restoreDevice);

        // Discovery with Soft Delete Support
        router.post("/api/devices/restore-and-discover").handler(this::restoreAndDiscover);

        // Enhanced Metrics APIs
        router.get("/api/metrics/devices/:id/latest").handler(this::getDeviceLatestMetrics);
        router.get("/api/metrics/devices/:id/availability").handler(this::getDeviceAvailability);

        // Static files (if any)
        router.route("/*").handler(ctx -> {
            ctx.response()
                .setStatusCode(404)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Not Found").encode());
        });

        return router;
    }

    private void handleWebSocketConnection(ServerWebSocket websocket) {
        logger.info("🔌 New WebSocket connection: {}", websocket.textHandlerID());
        
        webSocketConnections.add(websocket);
        
        // Send welcome message
        JsonObject welcome = new JsonObject()
            .put("type", "connection")
            .put("status", "connected")
            .put("message", "Connected to NMSLite real-time updates");
        websocket.writeTextMessage(welcome.encode());

        // Handle disconnection
        websocket.closeHandler(v -> {
            logger.info("🔌 WebSocket disconnected: {}", websocket.textHandlerID());
            webSocketConnections.remove(websocket);
        });

        // Handle incoming messages (if needed)
        websocket.textMessageHandler(message -> {
            logger.debug("📨 WebSocket message received: {}", message);
            // Handle client messages if needed
        });
    }

    private void setupEventBusConsumers() {
        // Listen for discovery results to forward to UI
        vertx.eventBus().consumer("discovery.result", message -> {
            JsonObject result = (JsonObject) message.body();
            broadcastToWebSockets(new JsonObject()
                .put("type", "discovery.result")
                .put("data", result));
        });

        // Listen for discovery completion
        vertx.eventBus().consumer("discovery.completed", message -> {
            JsonObject summary = (JsonObject) message.body();
            broadcastToWebSockets(new JsonObject()
                .put("type", "discovery.completed")
                .put("data", summary));
        });

        // Listen for connectivity failures
        vertx.eventBus().consumer("connectivity.failed", message -> {
            JsonObject notification = (JsonObject) message.body();
            broadcastToWebSockets(new JsonObject()
                .put("type", "connectivity.failed")
                .put("data", notification));
        });

        // Listen for metrics updates
        vertx.eventBus().consumer("metrics.update", message -> {
            JsonObject metrics = (JsonObject) message.body();
            broadcastToWebSockets(new JsonObject()
                .put("type", "metrics.update")
                .put("data", metrics));
        });

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

    // API Handlers

    private void discoverSingleDevice(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();

        if (requestBody == null) {
            ctx.response().setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Request body required").encode());
            return;
        }

        // Validate required field for discovery profile-based discovery
        if (!requestBody.containsKey("discovery_profile_id")) {
            ctx.response().setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Missing required field: discovery_profile_id").encode());
            return;
        }

        // Forward to DiscoveryVerticle for profile-based discovery
        vertx.eventBus().request("discovery.profile_based", requestBody)
            .onSuccess(reply -> {
                JsonObject result = (JsonObject) reply.body();
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(result.encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to discover device", cause);
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to discover device").encode());
            });
    }



    private void startProvision(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();
        
        vertx.eventBus().request("provision.start", requestBody)
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to start provision").encode());
            });
    }

    private void getDevices(RoutingContext ctx) {
        // Get query parameter for including deleted devices (default: false)
        boolean includeDeleted = "true".equals(ctx.request().getParam("includeDeleted"));

        deviceService.deviceList(includeDeleted)
            .onSuccess(result -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(result.encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to get devices", cause);
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get devices").encode());
            });
    }

    private void getDeviceMetrics(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");
        
        vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "get_device_metrics")
                .put("params", new JsonObject().put("device_id", deviceId)))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get device metrics").encode());
            });
    }

    private void updateDeviceStatus(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();
        
        vertx.eventBus().request("db.update", new JsonObject()
                .put("operation", "update_device_status")
                .put("params", requestBody.put("device_id", deviceId)))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to update device status").encode());
            });
    }

    private void getDeviceTypes(RoutingContext ctx) {
        vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "get_device_types")
                .put("params", new JsonObject()))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get device types").encode());
            });
    }

    private void getCredentials(RoutingContext ctx) {
        vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "get_credentials")
                .put("params", new JsonObject()))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get credentials").encode());
            });
    }

    private void createCredentials(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();
        
        vertx.eventBus().request("db.insert", new JsonObject()
                .put("operation", "create_credentials")
                .put("params", requestBody))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to create credentials").encode());
            });
    }

    private void softDeleteDevice(RoutingContext context) {
        String deviceId = context.pathParam("id");
        JsonObject requestBody = context.getBodyAsJson();

        String deletedBy = requestBody.getString("deleted_by", "admin");
        String deletionReason = requestBody.getString("deletion_reason", "Manual deletion via API");

        vertx.eventBus().request("db.update", new JsonObject()
            .put("operation", "soft_delete_device")
            .put("params", new JsonObject()
                .put("device_id", deviceId)
                .put("deleted_by", deletedBy)
                .put("deletion_reason", deletionReason)), reply -> {

            if (reply.succeeded()) {
                JsonObject result = (JsonObject) reply.result().body();
                context.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("success", true)
                        .put("message", "Device soft deleted successfully")
                        .put("device_id", deviceId)
                        .put("deleted_by", deletedBy)
                        .put("deletion_reason", deletionReason)
                        .encode());
            } else {
                context.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("success", false)
                        .put("error", reply.cause().getMessage())
                        .encode());
            }
        });
    }

    private void restoreDevice(RoutingContext context) {
        String deviceId = context.pathParam("id");

        vertx.eventBus().request("discovery.restore_device", new JsonObject()
            .put("device_id", deviceId), reply -> {

            if (reply.succeeded()) {
                JsonObject result = (JsonObject) reply.result().body();
                context.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            } else {
                context.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("success", false)
                        .put("error", reply.cause().getMessage())
                        .encode());
            }
        });
    }

    private void restoreAndDiscover(RoutingContext context) {
        JsonObject requestBody = context.getBodyAsJson();
        String deviceId = requestBody.getString("device_id");

        if (deviceId == null) {
            context.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("success", false)
                    .put("error", "device_id is required")
                    .encode());
            return;
        }

        vertx.eventBus().request("discovery.restore_device", new JsonObject()
            .put("device_id", deviceId), reply -> {

            if (reply.succeeded()) {
                JsonObject result = (JsonObject) reply.result().body();
                context.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            } else {
                context.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("success", false)
                        .put("error", reply.cause().getMessage())
                        .encode());
            }
        });
    }



    private void deleteDiscoveryProfile(RoutingContext context) {
        String profileId = context.pathParam("id");

        vertx.eventBus().request("db.update", new JsonObject()
            .put("operation", "delete_discovery_profile")
            .put("params", new JsonObject().put("profile_id", profileId)), reply -> {

            if (reply.succeeded()) {
                JsonObject result = (JsonObject) reply.result().body();

                if (result.getBoolean("success", true)) {
                    context.response()
                        .putHeader("Content-Type", "application/json")
                        .end(result.encode());
                } else {
                    // Discovery profile deletion blocked due to active devices
                    context.response()
                        .setStatusCode(409) // Conflict
                        .putHeader("Content-Type", "application/json")
                        .end(result.encode());
                }
            } else {
                context.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("success", false)
                        .put("error", reply.cause().getMessage())
                        .encode());
            }
        });
    }

    // ========================================
    // ENHANCED METRICS APIs
    // ========================================

    private void getDeviceLatestMetrics(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");

        vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "get_device_latest_metrics")
                .put("params", new JsonObject().put("device_id", deviceId)))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get latest metrics").encode());
            });
    }

    private void getDeviceAvailability(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");

        vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "get_device_availability")
                .put("params", new JsonObject().put("device_id", deviceId)))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get device availability").encode());
            });
    }

    // ========================================
    // DISCOVERY PROFILE MANAGEMENT APIs
    // ========================================

    private void getDiscoveryProfiles(RoutingContext ctx) {
        vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "get_discovery_profiles")
                .put("params", new JsonObject()))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get discovery profiles").encode());
            });
    }

    private void createDiscoveryProfile(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate required fields
        if (requestBody.getString("discovery_name") == null ||
            requestBody.getString("ip_address") == null ||
            requestBody.getString("device_type_id") == null ||
            requestBody.getString("credential_profile_id") == null) {

            ctx.response().setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                    .put("error", "Missing required fields: discovery_name, ip_address, device_type_id, credential_profile_id")
                    .encode());
            return;
        }

        vertx.eventBus().request("db.insert", new JsonObject()
                .put("operation", "discovery_profile")
                .put("params", requestBody))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to create discovery profile").encode());
            });
    }

    private void updateDiscoveryProfile(RoutingContext ctx) {
        String profileId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();
        requestBody.put("profile_id", profileId);

        vertx.eventBus().request("db.update", new JsonObject()
                .put("operation", "discovery_profile")
                .put("params", requestBody))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to update discovery profile").encode());
            });
    }

    // ========================================
    // DISCOVERY VALIDATION APIs
    // ========================================

    private void validateCredentials(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate required fields
        if (requestBody.getString("address") == null ||
            requestBody.getString("device_type") == null ||
            requestBody.getString("username") == null ||
            requestBody.getString("password") == null ||
            requestBody.getInteger("port") == null) {

            ctx.response().setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                    .put("error", "Missing required fields: address, device_type, username, password, port")
                    .encode());
            return;
        }

        vertx.eventBus().request("discovery.validate_only", requestBody)
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to validate credentials").encode());
            });
    }

    private void checkDiscoveryConflicts(RoutingContext ctx) {
        String ipAddress = ctx.request().getParam("ip_address");

        if (ipAddress == null) {
            ctx.response().setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                    .put("error", "Missing required parameter: ip_address")
                    .encode());
            return;
        }

        vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "check_discovery_conflicts")
                .put("params", new JsonObject().put("ip_address", ipAddress)))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to check discovery conflicts").encode());
            });
    }



    // ========================================
    // ENHANCED CREDENTIAL MANAGEMENT APIs
    // ========================================

    private void updateCredentials(RoutingContext ctx) {
        String credentialId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();
        requestBody.put("credential_id", credentialId);

        vertx.eventBus().request("db.update", new JsonObject()
                .put("operation", "credential_profile")
                .put("params", requestBody))
            .onSuccess(reply -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(((JsonObject) reply.body()).encode());
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to update credentials").encode());
            });
    }

    private void deleteCredentials(RoutingContext ctx) {
        String credentialId = ctx.pathParam("id");

        vertx.eventBus().request("db.delete", new JsonObject()
                .put("operation", "credential_profile")
                .put("params", new JsonObject().put("credential_id", credentialId)))
            .onSuccess(reply -> {
                JsonObject result = (JsonObject) reply.body();
                if (result.getBoolean("success", true)) {
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(result.encode());
                } else {
                    // Credential deletion blocked due to active discovery profiles
                    ctx.response()
                        .setStatusCode(409) // Conflict
                        .putHeader("content-type", "application/json")
                        .end(result.encode());
                }
            })
            .onFailure(cause -> {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to delete credentials").encode());
            });
    }

    // User Management Handlers
    private void getUsers(RoutingContext ctx) {
        userService.userList()
            .onSuccess(users -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("users", users).encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to get users", cause);
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get users").encode());
            });
    }

    private void createUser(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null || !body.containsKey("username") || !body.containsKey("password")) {
            ctx.response()
                .setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Missing required fields: username, password").encode());
            return;
        }

        JsonObject userData = new JsonObject()
            .put("username", body.getString("username"))
            .put("password", body.getString("password"))
            .put("is_active", body.getBoolean("is_active", true));

        userService.userCreate(userData)
            .onSuccess(result -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(result.encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to create user", cause);
                int statusCode = cause.getMessage().contains("already exists") ? 409 : 500;
                ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", cause.getMessage()).encode());
            });
    }

    private void updateUser(RoutingContext ctx) {
        String userId = ctx.pathParam("id");
        JsonObject body = ctx.body().asJsonObject();

        if (body == null) {
            ctx.response()
                .setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Request body is required").encode());
            return;
        }

        userService.userUpdate(userId, body)
            .onSuccess(result -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(result.encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to update user", cause);
                int statusCode = cause.getMessage().contains("not found") ? 404 :
                               cause.getMessage().contains("already exists") ? 409 : 500;
                ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", cause.getMessage()).encode());
            });
    }

    private void getUserById(RoutingContext ctx) {
        String userId = ctx.pathParam("id");

        userService.userGetById(userId)
            .onSuccess(result -> {
                if (result.getBoolean("found", false)) {
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(result.encode());
                } else {
                    ctx.response()
                        .setStatusCode(404)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode());
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to get user by ID", cause);
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get user").encode());
            });
    }

    private void deleteUser(RoutingContext ctx) {
        String userId = ctx.pathParam("id");

        userService.userDelete(userId)
            .onSuccess(result -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(result.encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to delete user", cause);
                int statusCode = cause.getMessage().contains("not found") ? 404 : 500;
                ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", cause.getMessage()).encode());
            });
    }

    private void authenticateUser(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null || !body.containsKey("username") || !body.containsKey("password")) {
            ctx.response()
                .setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Missing required fields: username, password").encode());
            return;
        }

        String username = body.getString("username");
        String password = body.getString("password");

        userService.userAuthenticate(username, password)
            .onSuccess(result -> {
                if (result.getBoolean("authenticated", false)) {
                    // Update last login timestamp
                    String userId = result.getString("user_id");
                    userService.userUpdateLastLogin(userId)
                        .onComplete(ar -> {
                            // Return authentication result regardless of last login update result
                            ctx.response()
                                .putHeader("content-type", "application/json")
                                .end(result.encode());
                        });
                } else {
                    ctx.response()
                        .setStatusCode(401)
                        .putHeader("content-type", "application/json")
                        .end(result.encode());
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to authenticate user", cause);
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Authentication failed").encode());
            });
    }

    private void changeUserPassword(RoutingContext ctx) {
        String userId = ctx.pathParam("id");
        JsonObject body = ctx.body().asJsonObject();

        if (body == null || !body.containsKey("oldPassword") || !body.containsKey("newPassword")) {
            ctx.response()
                .setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Missing required fields: oldPassword, newPassword").encode());
            return;
        }

        String oldPassword = body.getString("oldPassword");
        String newPassword = body.getString("newPassword");

        userService.userChangePassword(userId, oldPassword, newPassword)
            .onSuccess(result -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(result.encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to change user password", cause);
                int statusCode = cause.getMessage().contains("not found") ? 404 :
                               cause.getMessage().contains("incorrect") ? 400 : 500;
                ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", cause.getMessage()).encode());
            });
    }

    private void setUserStatus(RoutingContext ctx) {
        String userId = ctx.pathParam("id");
        JsonObject body = ctx.body().asJsonObject();

        if (body == null || !body.containsKey("is_active")) {
            ctx.response()
                .setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Missing required field: is_active").encode());
            return;
        }

        boolean isActive = body.getBoolean("is_active");

        userService.userSetActive(userId, isActive)
            .onSuccess(result -> {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(result.encode());
            })
            .onFailure(cause -> {
                logger.error("Failed to set user status", cause);
                int statusCode = cause.getMessage().contains("not found") ? 404 : 500;
                ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", cause.getMessage()).encode());
            });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("🛑 Stopping MainVerticle");

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
