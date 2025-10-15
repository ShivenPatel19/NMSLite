package com.nmslite.handlers;

import com.nmslite.services.DeviceService;

import com.nmslite.services.DeviceTypeService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ValidationUtil;

import com.nmslite.utils.ResponseUtil;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * DeviceHandler - Handles all device-related HTTP requests

 * This handler manages:
 * - Device listing and management
 * - Device types
 * - Device soft delete and restore
 * - Device discovery integration

 * Uses DeviceService and DeviceTypeService for database operations
 */
public class DeviceHandler
{

    private static final Logger logger = LoggerFactory.getLogger(DeviceHandler.class);

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

    /**
     * Get all discovered (unprovisioned) devices.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getDiscoveredDevices(RoutingContext ctx)
    {
        try
        {
            deviceService.deviceListByProvisioned(false)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to get unprovisioned devices"));
        }
        catch (Exception exception)
        {
            logger.error("Error in getDiscoveredDevices handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to get unprovisioned devices");
        }
    }

    /**
     * Get all provisioned devices.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getProvisionedDevices(RoutingContext ctx)
    {
        try
        {
            deviceService.deviceListByProvisioned(true)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to get provisioned devices"));
        }
        catch (Exception exception)
        {
            logger.error("Error in getProvisionedDevices handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to get provisioned devices");
        }
    }

    /**
     * Soft delete a device (mark as deleted).
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void softDeleteDevice(RoutingContext ctx)
    {
        try
        {
            var deviceId = ctx.pathParam("id");

            if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
            {
                return;
            }

            deviceService.deviceDelete(deviceId)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to delete device"));
        }
        catch (Exception exception)
        {
            logger.error("Error in softDeleteDevice handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to delete device");
        }
    }

    /**
     * Restore a soft-deleted device.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void restoreDevice(RoutingContext ctx)
    {
        try
        {
            var deviceId = ctx.pathParam("id");

            if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
            {
                return;
            }

            deviceService.deviceRestore(deviceId)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to restore device"));
        }
        catch (Exception exception)
        {
            logger.error("Error in restoreDevice handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to restore device");
        }
    }

    /**
     * Enable monitoring for a device.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void enableMonitoring(RoutingContext ctx)
    {
        try
        {
            var deviceId = ctx.pathParam("id");

            // Validate device ID
            if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
            {
                return;
            }

            deviceService.deviceEnableMonitoring(deviceId)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to enable monitoring for device"));
        }
        catch (Exception exception)
        {
            logger.error("Error in enableMonitoring handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to enable monitoring for device");
        }
    }

    /**
     * Disable monitoring for a device.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void disableMonitoring(RoutingContext ctx)
    {
        try
        {
            var deviceId = ctx.pathParam("id");

            if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
            {
                return;
            }

            deviceService.deviceDisableMonitoring(deviceId)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to disable monitoring for device"));
        }
        catch (Exception exception)
        {
            logger.error("Error in disableMonitoring handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to disable monitoring for device");
        }
    }

    /**
     * Provision devices and enable monitoring for them.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void provisionAndEnableMonitoring(RoutingContext ctx)
    {
        try
        {
            var body = ctx.body().asJsonObject();

            if (body == null || !body.containsKey("device_ids"))
            {
                ExceptionUtil.handleHttp(ctx,
                    new Exception("Request body must contain 'device_ids' array"), "Invalid request body");

                return;
            }

            var deviceIds = body.getJsonArray("device_ids");

            if (deviceIds == null || deviceIds.isEmpty())
            {
                ExceptionUtil.handleHttp(ctx,
                    new Exception("device_ids array cannot be empty"), "Invalid request body");

                return;
            }

            // Validate all device IDs are valid UUIDs
            for (var i = 0; i < deviceIds.size(); i++)
            {
                var deviceId = deviceIds.getString(i);

                if (!ValidationUtil.isValidUUID(deviceId))
                {
                    ExceptionUtil.handleHttp(ctx,
                        new Exception("Invalid device ID at index " + i), "Invalid device ID format");

                    return;
                }
            }

            deviceService.deviceProvisionAndEnableMonitoring(deviceIds)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, new JsonObject()
                            .put("results", result)
                            .put("total", deviceIds.size())))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to provision devices"));
        }
        catch (Exception exception)
        {
            logger.error("Error in provisionAndEnableMonitoring handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to provision devices");
        }
    }

    /**
     * Update device configuration (name, port, polling settings, alert thresholds).
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void updateDeviceConfig(RoutingContext ctx)
    {
        try
        {
            var deviceId = ctx.pathParam("id");

            var body = ctx.body().asJsonObject();

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

            // 3) Invoke service
            deviceService.deviceUpdateConfig(deviceId, body)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to update device configuration"));
        }
        catch (Exception exception)
        {
            logger.error("Error in updateDeviceConfig handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to update device configuration");
        }
    }

    /**
     * Get all active device types.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getDeviceTypes(RoutingContext ctx)
    {
        try
        {
            // Show only active device types by default (as requested)
            deviceTypeService.deviceTypeList(false)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, new JsonObject().put("device_types", result)))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to get device types"));
        }
        catch (Exception exception)
        {
            logger.error("Error in getDeviceTypes handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to get device types");
        }
    }

}
