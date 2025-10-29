package com.nmslite.handlers;

import com.nmslite.Bootstrap;

import com.nmslite.services.DeviceService;

import com.nmslite.services.DeviceTypeService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ValidationUtil;

import com.nmslite.utils.ResponseUtil;

import io.vertx.core.Vertx;

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
 * - Event bus publishing for device state changes

 * Uses DeviceService and DeviceTypeService for database operations
 * Publishes events to PollingMetricsVerticle for cache synchronization
 */
public class DeviceHandler
{

    private static final Logger logger = LoggerFactory.getLogger(DeviceHandler.class);

    private final Vertx vertx;

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
        this.vertx = Bootstrap.getVertxInstance();

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
                {
                    // Publish event to notify PollingMetricsVerticle to remove device from cache
                    vertx.eventBus().publish("device.deleted", new JsonObject()
                        .put("device_id", deviceId));

                    logger.debug("Published device.deleted event for device: {}", deviceId);

                    ResponseUtil.handleSuccess(ctx, result);
                })
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
                {
                    // Publish event to notify PollingMetricsVerticle to add device back to cache
                    vertx.eventBus().publish("device.restored", new JsonObject()
                        .put("device_id", deviceId));

                    logger.debug("Published device.restored event for device: {}", deviceId);

                    ResponseUtil.handleSuccess(ctx, result);
                })
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
     * Enable provisioning for a device (sets is_provisioned = true).
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void enableProvisioning(RoutingContext ctx)
    {
        try
        {
            var deviceId = ctx.pathParam("id");

            // Validate device ID
            if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
            {
                return;
            }

            deviceService.deviceEnableProvisioning(deviceId)
                .onSuccess(result ->
                {
                    // Publish event to notify PollingMetricsVerticle to add device to cache
                    vertx.eventBus().publish("device.provision.enabled", new JsonObject()
                        .put("device_id", deviceId));

                    logger.debug("Published device.provision.enabled event for device: {}", deviceId);

                    ResponseUtil.handleSuccess(ctx, result);
                })
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to enable provisioning for device"));
        }
        catch (Exception exception)
        {
            logger.error("Error in enableProvisioning handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to enable provisioning for device");
        }
    }

    /**
     * Disable provisioning for a device (sets is_provisioned = false).
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void disableProvisioning(RoutingContext ctx)
    {
        try
        {
            var deviceId = ctx.pathParam("id");

            if (!ValidationUtil.validatePathParameterUUID(ctx, deviceId, "Device ID"))
            {
                return;
            }

            deviceService.deviceDisableProvisioning(deviceId)
                .onSuccess(result ->
                {
                    // Publish event to notify PollingMetricsVerticle to remove device from cache
                    vertx.eventBus().publish("device.provision.disabled", new JsonObject()
                        .put("device_id", deviceId));

                    logger.debug("Published device.provision.disabled event for device: {}", deviceId);

                    ResponseUtil.handleSuccess(ctx, result);
                })
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to disable provisioning for device"));
        }
        catch (Exception exception)
        {
            logger.error("Error in disableProvisioning handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to disable provisioning for device");
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
                {
                    // Publish event to notify PollingMetricsVerticle to update device in cache
                    // Only publish if device is provisioned (monitoring enabled)
                    if (result.getBoolean("is_provisioned", false))
                    {
                        vertx.eventBus().publish("device.config.updated", new JsonObject()
                            .put("device_id", deviceId));

                        logger.debug("Published device.config.updated event for device: {}", deviceId);
                    }

                    ResponseUtil.handleSuccess(ctx, result);
                })
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
