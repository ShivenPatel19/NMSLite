package com.nmslite.handlers;

import com.nmslite.services.DeviceService;
import com.nmslite.services.DeviceTypeService;
import com.nmslite.utils.ExceptionUtil;
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

    public void getDevices(RoutingContext ctx) {
        // Get query parameter for including deleted devices (default: false)
        boolean includeDeleted = "true".equals(ctx.request().getParam("includeDeleted"));

        deviceService.deviceList(includeDeleted, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get devices");
            }
        });
    }

    public void createDevice(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate required fields for device creation
        if (!ExceptionUtil.validateRequiredFields(ctx, requestBody,
            "device_name", "ip_address", "device_type", "port", "protocol", "username", "password")) {
            return; // Response already sent by validateRequiredFields
        }

        logger.info("🔧 Creating device: {}", requestBody.getString("device_name"));

        // Check if polling_interval_seconds is provided, if not, use default from config
        if (!requestBody.containsKey("polling_interval_seconds") || requestBody.getValue("polling_interval_seconds") == null) {
            Integer defaultPollingInterval = config.getInteger("polling.default.device.polling.interval", 300); // Default 5 minutes
            requestBody.put("polling_interval_seconds", defaultPollingInterval);
            logger.info("🔧 Polling interval not provided, using default: {} seconds", defaultPollingInterval);
        } else {
            logger.info("🔧 Using provided polling interval: {} seconds", requestBody.getValue("polling_interval_seconds"));
        }

        // Check if timeout_seconds is provided, if not, use default (300 seconds = 5 minutes)
        if (!requestBody.containsKey("timeout_seconds") || requestBody.getValue("timeout_seconds") == null) {
            Integer defaultTimeout = 300; // 5 minutes default timeout
            requestBody.put("timeout_seconds", defaultTimeout);
            logger.info("🔧 Timeout not provided, using default: {} seconds", defaultTimeout);
        } else {
            logger.info("🔧 Using provided timeout: {} seconds", requestBody.getValue("timeout_seconds"));
        }

        // Check if retry_count is provided, if not, use default (2 retries)
        if (!requestBody.containsKey("retry_count") || requestBody.getValue("retry_count") == null) {
            Integer defaultRetryCount = 2; // 2 retries default
            requestBody.put("retry_count", defaultRetryCount);
            logger.info("🔧 Retry count not provided, using default: {} retries", defaultRetryCount);
        } else {
            logger.info("🔧 Using provided retry count: {} retries", requestBody.getValue("retry_count"));
        }

        deviceService.deviceCreate(requestBody, ar -> {
            if (ar.succeeded()) {
                logger.info("✅ Device created successfully: {}", requestBody.getString("device_name"));
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                logger.error("❌ Failed to create device: {}", ar.cause().getMessage());
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to create device");
            }
        });
    }

    public void softDeleteDevice(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();
        
        // Get deletedBy from request body or use default
        String deletedBy = requestBody != null ? requestBody.getString("deleted_by", "system") : "system";

        deviceService.deviceDelete(deviceId, deletedBy, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete device");
            }
        });
    }

    public void restoreDevice(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");

        deviceService.deviceRestore(deviceId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to restore device");
            }
        });
    }

    public void updateDeviceMonitoringConfig(RoutingContext ctx) {
        String deviceId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate update fields - at least one monitoring field must be provided
        if (!ExceptionUtil.validateUpdateFields(ctx, requestBody,
            "polling_interval_seconds", "alert_threshold_cpu", "alert_threshold_memory", "alert_threshold_disk")) {
            return; // Response already sent by validateUpdateFields
        }

        // Validate threshold ranges (0-100) if provided
        if (!ExceptionUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_cpu", 0.0, 100.0) ||
            !ExceptionUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_memory", 0.0, 100.0) ||
            !ExceptionUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_disk", 0.0, 100.0)) {
            return; // Response already sent by validateDecimalRange
        }

        // Validate polling interval (positive integer) if provided
        if (requestBody.containsKey("polling_interval_seconds")) {
            Integer pollingInterval = requestBody.getInteger("polling_interval_seconds");
            if (pollingInterval != null && pollingInterval <= 0) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("polling_interval_seconds must be positive"));
                return;
            }
        }

        logger.info("🔧 Updating device monitoring configuration for device: {}", deviceId);

        deviceService.deviceUpdateMonitoringConfig(deviceId, requestBody, ar -> {
            if (ar.succeeded()) {
                logger.info("✅ Device monitoring configuration updated successfully for device: {}", deviceId);
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                logger.error("❌ Failed to update device monitoring configuration for device {}: {}", deviceId, ar.cause().getMessage());
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update device monitoring configuration");
            }
        });
    }



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
