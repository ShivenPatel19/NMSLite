package com.nmslite.handlers;

import com.nmslite.services.AvailabilityService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ResponseUtil;

import com.nmslite.utils.ValidationUtil;

import io.vertx.ext.web.RoutingContext;

/**
 * AvailabilityHandler - HTTP request handler for availability operations

 * Handles:
 * - Fetching availability status by device ID
 */
public class AvailabilityHandler
{
    private final AvailabilityService availabilityService;

    /**
     * Constructor for AvailabilityHandler
     *
     * @param availabilityService Availability service instance
     */
    public AvailabilityHandler(AvailabilityService availabilityService)
    {
        this.availabilityService = availabilityService;
    }

    /**
     * Get availability status for a specific device
     *
     * @param context Routing context
     */
    public void getDeviceAvailability(RoutingContext context)
    {
        var deviceId = context.pathParam("deviceId");

        if (!ValidationUtil.validatePathParameterUUID(context, deviceId, "deviceId"))
        {
            return; // Validation failed, response already sent
        }

        availabilityService.availabilityGetByDevice(deviceId)
                .onSuccess(availability ->
                        ResponseUtil.handleSuccess(context, availability))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(context, cause, "Failed to retrieve availability status"));
    }

}

