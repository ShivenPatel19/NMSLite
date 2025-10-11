package com.nmslite.handlers;

import com.nmslite.services.CredentialProfileService;

import com.nmslite.services.DiscoveryProfileService;

import com.nmslite.services.DeviceTypeService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ValidationUtil;

import com.nmslite.utils.ResponseUtil;

import io.vertx.core.json.JsonArray;

import io.vertx.core.AsyncResult;

import io.vertx.core.Future;

import io.vertx.core.Handler;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DiscoveryProfileHandler - Handles discovery profile management HTTP requests

 * This handler manages:
 * - Discovery profile CRUD operations (database management)
 * - Discovery execution using GoEngine

 * Uses DiscoveryService for profile management and event bus for discovery operations with GoEngine
 */
public class DiscoveryProfileHandler
{

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryProfileHandler.class);

    private final Vertx vertx;

    private final DiscoveryProfileService discoveryProfileService;

    private final DeviceTypeService deviceTypeService;

    private final CredentialProfileService credentialService;

    /**
     * Constructor for DiscoveryProfileHandler.
     *
     * @param vertx Vert.x instance for event bus communication
     * @param discoveryProfileService service proxy for discovery profile database operations
     * @param deviceTypeService service proxy for device type database operations
     * @param credentialService service proxy for credential profile database operations
     */
    public DiscoveryProfileHandler(Vertx vertx, DiscoveryProfileService discoveryProfileService,
                                   DeviceTypeService deviceTypeService, CredentialProfileService credentialService)
    {
        this.vertx = vertx;

        this.discoveryProfileService = discoveryProfileService;

        this.deviceTypeService = deviceTypeService;

        this.credentialService = credentialService;
    }

    /**
     * Get all discovery profiles.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getDiscoveryProfiles(RoutingContext ctx)
    {
        discoveryProfileService.discoveryList()
            .onSuccess(result ->
                    ResponseUtil.handleSuccess(ctx, new JsonObject().put("discovery_profiles", result)))
            .onFailure(cause ->
                    ExceptionUtil.handleHttp(ctx, cause, "Failed to get discovery profiles"));
    }

    /**
     * Create discovery profile in database (no validation, just database storage)
     * If port is not provided, uses the default port from the device type
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void createDiscoveryProfile(RoutingContext ctx)
    {
        var requestBody = ctx.body().asJsonObject();

        // Validate all discovery profile basic fields
        if (!ValidationUtil.DiscoveryProfile.validateCreate(ctx, requestBody))
        {
            return; // Validation failed, response already sent
        }

        var ipAddress = requestBody.getString("ip_address");

        var isRange = requestBody.getBoolean("is_range", false);

        var deviceTypeId = requestBody.getString("device_type_id");

        var credentialProfileIds = requestBody.getJsonArray("credential_profile_ids");

        // Step 1: Validate device type exists
        deviceTypeService.deviceTypeGetById(deviceTypeId)
            .onSuccess(deviceType ->
            {
                // Step 2: Validate all credential profile IDs exist
                validateCredentialProfileIdsExist(credentialProfileIds, validationResult ->
                {
                    if (validationResult.failed())
                    {
                        logger.error("Invalid credential profile IDs: {}", validationResult.cause().getMessage());

                        ExceptionUtil.handleHttp(ctx, validationResult.cause(),
                            "Invalid device type ID or one or more credential profile IDs");

                        return;
                    }

                    // Step 3: Set default port if not provided
                    if (!requestBody.containsKey("port") || requestBody.getValue("port") == null)
                    {
                        var defaultPort = deviceType.getInteger("default_port");

                        if (defaultPort != null)
                        {
                            requestBody.put("port", defaultPort);
                        }
                        else
                        {
                            logger.debug("No default port found for device type {}, proceeding without port", deviceTypeId);
                        }
                    }

                    // Step 4: Create discovery profile in database
                    createDiscoveryProfileInDatabase(ctx, requestBody, ipAddress);
                });
            })
            .onFailure(cause ->
            {
                logger.error("Invalid device type ID: {}", deviceTypeId);

                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid device type ID: " + deviceTypeId),
                    "Invalid device type ID");
            });
    }

    /**
     * Helper method to validate that all credential profile IDs exist in the database
     *
     * @param credentialProfileIds array of credential profile IDs to validate
     * @param resultHandler handler to call with validation result
     */
    private void validateCredentialProfileIdsExist(JsonArray credentialProfileIds,
                                                     Handler<AsyncResult<Void>> resultHandler)
    {
        // Use Atomic Variables to track validation progress
        var validatedCount = new AtomicInteger(0);

        var hasError = new AtomicBoolean(false);

        for (var i = 0; i < credentialProfileIds.size(); i++)
        {
            var credentialId = credentialProfileIds.getString(i);

            credentialService.credentialGetById(credentialId)
                .onSuccess(result ->
                {
                    if (hasError.get())
                    {
                        return; // Already failed, skip further processing
                    }

                    // Check if credential was found
                    if (result.getBoolean("found", false) == false)
                    {
                        hasError.set(true);

                        resultHandler.handle(Future.failedFuture(
                            new IllegalArgumentException("Invalid credential profile ID: " + credentialId)));

                        return;
                    }

                    // Check if this was the last credential to validate
                    if (validatedCount.incrementAndGet() == credentialProfileIds.size())
                    {
                        resultHandler.handle(Future.succeededFuture());
                    }
                })
                .onFailure(cause ->
                {
                    if (hasError.get())
                    {
                        return; // Already failed, skip further processing
                    }

                    hasError.set(true);

                    resultHandler.handle(Future.failedFuture(
                        new IllegalArgumentException("Invalid credential profile ID: " + credentialId)));
                });
        }
    }

    /**
     * Helper method to create discovery profile in database
     *
     * @param ctx routing context
     * @param requestBody request body containing discovery profile data
     * @param ipAddress IP address for logging
     */
    private void createDiscoveryProfileInDatabase(RoutingContext ctx, JsonObject requestBody, String ipAddress)
    {
        discoveryProfileService.discoveryCreate(requestBody)
            .onSuccess(result ->
            {
                ResponseUtil.handleSuccess(ctx, result);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to create discovery profile for {}: {}", ipAddress, cause.getMessage());

                ExceptionUtil.handleHttp(ctx, cause, "Failed to create discovery profile");
            });
    }

    /**
     * Delete a discovery profile.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void deleteDiscoveryProfile(RoutingContext ctx)
    {
        var profileId = ctx.pathParam("id");

        // ===== PATH PARAMETER VALIDATION =====
        if (!ValidationUtil.validatePathParameterUUID(ctx, profileId, "Discovery profile ID"))
        {
            return;
        }

        discoveryProfileService.discoveryDelete(profileId)
            .onSuccess(result ->
                    ResponseUtil.handleSuccess(ctx, result))
            .onFailure(cause ->
                    ExceptionUtil.handleHttp(ctx, cause, "Failed to delete discovery profile"));
    }

    // ========================================
    // DISCOVERY OPERATIONS (GoEngine-based Discovery)
    // ========================================

    /**
     * Test discovery from discovery profile (NEW UNIFIED API)
     * Handles both single IP and IP range based on isRange flag
     * Creates devices for successful discoveries with is_provisioned=false, is_monitoring_enabled=false
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void testDiscovery(RoutingContext ctx)
    {
        var requestBody = ctx.body().asJsonObject();

        // Validate required fields
        if (!ValidationUtil.validateRequiredFields(ctx, requestBody, "profile_id"))
        {
            return;
        }

        var profileId = requestBody.getString("profile_id");

        // Execute discovery test via DiscoveryVerticle
        vertx.eventBus().request("discovery.test_profile", new JsonObject().put("profile_id", profileId))
            .onSuccess(reply ->
            {
                var result = (JsonObject) reply.body();

                logger.info("Test discovery completed for profile {}: {} devices discovered, {} failed",
                           profileId,
                           result.getInteger("devices_discovered", 0),
                           result.getInteger("devices_failed", 0));

                ResponseUtil.handleSuccess(ctx, result);
            })
            .onFailure(cause ->
            {
                logger.error("Test discovery failed for profile {}: {}", profileId, cause.getMessage());

                ExceptionUtil.handleHttp(ctx, cause, "Failed to execute test discovery");
            });
    }

}
