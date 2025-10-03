package com.nmslite.handlers;

import com.nmslite.services.DeviceService;
import com.nmslite.services.DeviceTypeService;
import com.nmslite.utils.ExceptionUtil;
import com.nmslite.utils.CommonValidationUtil;
import com.nmslite.utils.DeviceValidationUtil;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeviceHandler - Handles all device-related HTTP requests
 *
 * This handler manages:
 * - Device listing and management
 * - Device types
 * - Device soft delete and restore
 * - Device discovery integration
 *
 * Uses DeviceService and DeviceTypeService for database operations
 */
public class DeviceHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeviceHandler.class);
    private final DeviceService deviceService;
    private final DeviceTypeService deviceTypeService;
    private final Vertx vertx;
    private final JsonObject config;

    public DeviceHandler(DeviceService deviceService, DeviceTypeService deviceTypeService, Vertx vertx) {
        this.deviceService = deviceService;
        this.deviceTypeService = deviceTypeService;
        this.vertx = vertx;

        // Load configuration
        this.config = ConfigRetriever.create(vertx).getCachedConfig();
    }

    // ========================================
    // DEVICE MANAGEMENT
    // ========================================

    public void getDiscoveredDevices(RoutingContext ctx) {
        deviceService.deviceListByProvisioned(false, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get unprovisioned devices");
            }
        });
    }

    public void getProvisionedDevices(RoutingContext ctx) {
        deviceService.deviceListByProvisioned(true, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get provisioned devices");
            }
        });
    }

    public void softDeleteDevice(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();

        // ===== PATH PARAMETER VALIDATION =====
        if (!CommonValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID")) {
            return;
        }

        deviceService.deviceDelete(deviceId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete device");
            }
        });
    }

    public void restoreDevice(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");

        // ===== PATH PARAMETER VALIDATION =====
        if (!CommonValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID")) {
            return;
        }

        deviceService.deviceRestore(deviceId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to restore device");
            }
        });
    }


    public void enableMonitoring(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");

        // Validate device ID
        if (!CommonValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID")) {
            return;
        }

        deviceService.deviceEnableMonitoring(deviceId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to enable monitoring for device");
            }
        });
    }
    public void disableMonitoring(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");

        if (!CommonValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID")) {
            return;
        }

        deviceService.deviceDisableMonitoring(deviceId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to disable monitoring for device");
            }
        });
    }





    public void updateDeviceConfig(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");
        JsonObject body = ctx.body().asJsonObject();

        // ===== VALIDATION =====
        // 1) Validate path parameter
        if (!CommonValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID")) {
            return;
        }

        // 2) Validate request body and fields
        if (!DeviceValidationUtil.validateDeviceUpdate(ctx, body)) {
            return;
        }

        // 4) Invoke service
        deviceService.deviceUpdateConfig(deviceId, body, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update device configuration");
            }
        });
    }

    // ========================================
    // DEVICE PROVISIONING
    // ========================================

    // COMMENTED OUT FOR TESTING - Device Provisioning API
    // /**
    //  * Provision devices from a discovery profile (supports IP ranges)
    //  * This endpoint creates individual devices from discovery profile data
    //  */
    // public void provisionDevicesFromProfile(RoutingContext ctx) {
    //     String profileId = ctx.pathParam("profileId");

    //     // ===== PATH PARAMETER VALIDATION =====
    //     if (profileId == null || profileId.trim().isEmpty()) {
    //         ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Profile ID is required"),
    //             "Discovery profile ID is required");
    //         return;
    //     }

    //     try {
    //         java.util.UUID.fromString(profileId);
    //     } catch (IllegalArgumentException e) {
    //         ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid UUID format"),
    //             "Discovery profile ID must be a valid UUID");
    //         return;
    //     }

    //     logger.info("🔧 Provisioning devices from discovery profile: {}", profileId);

    //     // TODO: Implement device provisioning from discovery profile
    //     // This should find devices created from the discovery profile and set them as provisioned
    //     // For now, return a placeholder response
    //     JsonObject result = new JsonObject()
    //         .put("success", true)
    //         .put("message", "Device provisioning from discovery profile not yet implemented")
    //         .put("profile_id", profileId);

    //     logger.warn("⚠️ Device provisioning from discovery profile not yet implemented for profile: {}", profileId);
    //     ExceptionUtil.handleSuccess(ctx, result);
    // }

    // ========================================
    // DEVICE TYPES
    // ========================================

    public void getDeviceTypes(RoutingContext ctx) {
        // Show only active device types by default (as requested)
        deviceTypeService.deviceTypeList(false, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, new JsonObject().put("device_types", ar.result()));
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get device types");
            }
        });
    }


}
