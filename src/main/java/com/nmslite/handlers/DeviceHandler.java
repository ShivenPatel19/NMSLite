package com.nmslite.handlers;

import com.nmslite.services.DeviceService;

import com.nmslite.services.DeviceTypeService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ValidationUtil;

import com.nmslite.utils.ResponseUtil;



import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import java.util.UUID;

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
public class DeviceHandler
{

    private final DeviceService deviceService;

    private final DeviceTypeService deviceTypeService;

    /**
     * Constructor for DeviceHandler.
     *
     * @param deviceService service proxy for device database operations
     * @param deviceTypeService service proxy for device type database operations
     */
    public DeviceHandler(DeviceService deviceService, DeviceTypeService deviceTypeService)
    {
        this.deviceService = deviceService;

        this.deviceTypeService = deviceTypeService;
    }

    // ========================================
    // DEVICE MANAGEMENT
    // ========================================

    /**
     * Get all discovered (unprovisioned) devices.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getDiscoveredDevices(RoutingContext ctx)
    {
        deviceService.deviceListByProvisioned(false, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get unprovisioned devices");
            }
        });
    }

    /**
     * Get all provisioned devices.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getProvisionedDevices(RoutingContext ctx)
    {
        deviceService.deviceListByProvisioned(true, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get provisioned devices");
            }
        });
    }

    /**
     * Soft delete a device (mark as deleted).
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void softDeleteDevice(RoutingContext ctx)
    {
        String deviceId = ctx.pathParam("id");

        // ===== PATH PARAMETER VALIDATION =====
        if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
        {
            return;
        }

        deviceService.deviceDelete(deviceId, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete device");
            }
        });
    }

    /**
     * Restore a soft-deleted device.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void restoreDevice(RoutingContext ctx)
    {
        String deviceId = ctx.pathParam("id");

        // ===== PATH PARAMETER VALIDATION =====
        if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
        {
            return;
        }

        deviceService.deviceRestore(deviceId, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to restore device");
            }
        });
    }

    /**
     * Enable monitoring for a device.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void enableMonitoring(RoutingContext ctx)
    {
        String deviceId = ctx.pathParam("id");

        // Validate device ID
        if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
        {
            return;
        }

        deviceService.deviceEnableMonitoring(deviceId, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to enable monitoring for device");
            }
        });
    }

    /**
     * Disable monitoring for a device.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void disableMonitoring(RoutingContext ctx)
    {
        String deviceId = ctx.pathParam("id");

        if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
        {
            return;
        }

        deviceService.deviceDisableMonitoring(deviceId, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to disable monitoring for device");
            }
        });
    }

    /**
     * Provision devices and enable monitoring for them.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void provisionAndEnableMonitoring(RoutingContext ctx)
    {
        JsonObject body = ctx.body().asJsonObject();

        if (body == null || !body.containsKey("device_ids"))
        {
            ExceptionUtil.handleHttp(ctx,
                new IllegalArgumentException("Request body must contain 'device_ids' array"),
                "Invalid request body");

            return;
        }

        JsonArray deviceIds = body.getJsonArray("device_ids");

        if (deviceIds == null || deviceIds.isEmpty())
        {
            ExceptionUtil.handleHttp(ctx,
                new IllegalArgumentException("device_ids array cannot be empty"),
                "Invalid request body");

            return;
        }

        // Validate all device IDs are valid UUIDs
        for (int i = 0; i < deviceIds.size(); i++)
        {
            try
            {
                String deviceId = deviceIds.getString(i);

                UUID.fromString(deviceId);
            }
            catch (Exception exception)
            {
                ExceptionUtil.handleHttp(ctx,
                    new IllegalArgumentException("Invalid device ID at index " + i),
                    "Invalid device ID format");

                return;
            }
        }

        deviceService.deviceProvisionAndEnableMonitoring(deviceIds, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, new JsonObject()
                    .put("results", ar.result())
                    .put("total", deviceIds.size()));
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to provision devices");
            }
        });
    }

    /**
     * Update device configuration (name, port, polling settings, alert thresholds).
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void updateDeviceConfig(RoutingContext ctx)
    {
        String deviceId = ctx.pathParam("id");

        JsonObject body = ctx.body().asJsonObject();

        // ===== VALIDATION =====
        // 1) Validate path parameter
        if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
        {
            return;
        }

        // 2) Validate request body and fields
        if (!ValidationUtil.Device.validateUpdate(ctx, body))
        {
            return;
        }

        // 4) Invoke service
        deviceService.deviceUpdateConfig(deviceId, body, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update device configuration");
            }
        });
    }

    // ========================================
    // DEVICE TYPES
    // ========================================

    /**
     * Get all active device types.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getDeviceTypes(RoutingContext ctx)
    {
        // Show only active device types by default (as requested)
        deviceTypeService.deviceTypeList(false, ar ->
        {
            if (ar.succeeded())
            {
                ResponseUtil.handleSuccess(ctx, new JsonObject().put("device_types", ar.result()));
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get device types");
            }
        });
    }

}
