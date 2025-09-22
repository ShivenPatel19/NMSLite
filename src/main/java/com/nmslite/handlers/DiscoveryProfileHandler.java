package com.nmslite.handlers;

import com.nmslite.services.DiscoveryProfileService;
import com.nmslite.services.DeviceTypeService;
import com.nmslite.utils.ExceptionUtil;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DiscoveryProfileHandler - Handles discovery profile management HTTP requests
 *
 * This handler manages:
 * - Discovery profile CRUD operations (database management)
 * - Discovery execution using GoEngine
 *
 * Uses DiscoveryService for profile management and event bus for discovery operations with GoEngine
 */
public class DiscoveryProfileHandler {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryProfileHandler.class);
    private final Vertx vertx;
    private final DiscoveryProfileService discoveryProfileService;
    private final DeviceTypeService deviceTypeService;

    public DiscoveryProfileHandler(Vertx vertx, DiscoveryProfileService discoveryProfileService, DeviceTypeService deviceTypeService) {
        this.vertx = vertx;
        this.discoveryProfileService = discoveryProfileService;
        this.deviceTypeService = deviceTypeService;
    }

    // ========================================
    // DISCOVERY PROFILE CRUD OPERATIONS (Database Management)
    // ========================================

    public void getDiscoveryProfiles(RoutingContext ctx) {
        discoveryProfileService.discoveryList(ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, new JsonObject().put("discovery_profiles", ar.result()));
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get discovery profiles");
            }
        });
    }

    /**
     * Create discovery profile in database (no validation, just database storage)
     * If port is not provided, uses the default port from the device type
     */
    public void createDiscoveryProfile(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate required fields for discovery profile creation
        if (!ExceptionUtil.validateRequiredFields(ctx, requestBody,
            "discovery_name", "ip_address", "device_type_id", "credential_profile_id", "protocol")) {
            return;
        }

        String ipAddress = requestBody.getString("ip_address");
        String deviceTypeId = requestBody.getString("device_type_id");
        logger.info("🔧 Creating discovery profile for {}", ipAddress);

        // Check if port is provided, if not, get default port from device type
        if (!requestBody.containsKey("port") || requestBody.getValue("port") == null) {
            logger.info("🔧 Port not provided, fetching default port from device type: {}", deviceTypeId);

            deviceTypeService.deviceTypeGetById(deviceTypeId, deviceTypeResult -> {
                if (deviceTypeResult.succeeded()) {
                    JsonObject deviceType = deviceTypeResult.result();
                    Integer defaultPort = deviceType.getInteger("default_port");

                    if (defaultPort != null) {
                        requestBody.put("port", defaultPort);
                        logger.info("🔧 Using default port {} for device type", defaultPort);
                    } else {
                        logger.warn("⚠️ No default port found for device type {}, proceeding without port", deviceTypeId);
                    }

                    // Create discovery profile with port set
                    createDiscoveryProfileInDatabase(ctx, requestBody, ipAddress);
                } else {
                    logger.error("❌ Failed to fetch device type {}: {}", deviceTypeId, deviceTypeResult.cause().getMessage());
                    ExceptionUtil.handleHttp(ctx, deviceTypeResult.cause(), "Failed to fetch device type information");
                }
            });
        } else {
            // Port is provided, create directly
            logger.info("🔧 Using provided port: {}", requestBody.getValue("port"));
            createDiscoveryProfileInDatabase(ctx, requestBody, ipAddress);
        }
    }

    /**
     * Helper method to create discovery profile in database
     */
    private void createDiscoveryProfileInDatabase(RoutingContext ctx, JsonObject requestBody, String ipAddress) {
        discoveryProfileService.discoveryCreate(requestBody, ar -> {
            if (ar.succeeded()) {
                logger.info("✅ Discovery profile created successfully for {} with port {}",
                    ipAddress, requestBody.getValue("port"));
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                logger.error("❌ Failed to create discovery profile for {}: {}", ipAddress, ar.cause().getMessage());
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to create discovery profile");
            }
        });
    }

    public void updateDiscoveryProfile(RoutingContext ctx) {
        String profileId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate update fields - at least one field must be provided
        if (!ExceptionUtil.validateUpdateFields(ctx, requestBody,
            "discovery_name", "ip_address", "device_type_id", "credential_profile_id", "protocol", "port")) {
            return; // Response already sent by validateUpdateFields
        }

        // Additional validation for port range if provided
        if (!ExceptionUtil.validateNumericRange(ctx, requestBody, "port", 1, 65535)) {
            return; // Response already sent by validateNumericRange
        }

        discoveryProfileService.discoveryUpdate(profileId, requestBody, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update discovery profile");
            }
        });
    }

    public void deleteDiscoveryProfile(RoutingContext ctx) {
        String profileId = ctx.pathParam("id");

        discoveryProfileService.discoveryDelete(profileId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete discovery profile");
            }
        });
    }

    // ========================================
    // DISCOVERY OPERATIONS (GoEngine-based Discovery)
    // ========================================

    /**
     * Execute actual discovery using GoEngine (validate credentials and connectivity)
     */
    public void executeDiscovery(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate required fields for discovery execution
        if (!ExceptionUtil.validateRequiredFields(ctx, requestBody,
            "address", "device_type", "username", "password", "port")) {
            return;
        }

        String ipAddress = requestBody.getString("address");
        logger.info("🔍 Executing discovery for {}", ipAddress);

        // Execute discovery using GoEngine via DiscoveryVerticle
        vertx.eventBus().request("discovery.validate_only", requestBody)
            .onSuccess(reply -> {
                JsonObject result = (JsonObject) reply.body();
                logger.info("✅ Discovery completed for {}: success={}", ipAddress, result.getBoolean("success", false));
                ExceptionUtil.handleSuccess(ctx, result);
            })
            .onFailure(cause -> {
                logger.error("❌ Discovery failed for {}: {}", ipAddress, cause.getMessage());
                ExceptionUtil.handleHttp(ctx, cause, "Failed to execute discovery");
            });
    }
}
