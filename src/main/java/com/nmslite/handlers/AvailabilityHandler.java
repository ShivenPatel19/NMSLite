package com.nmslite.handlers;

import com.nmslite.services.AvailabilityService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ResponseUtil;

import com.nmslite.utils.ValidationUtil;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * AvailabilityHandler - HTTP request handler for availability operations

 * Handles:
 * - Fetching availability status by device ID
 */
public class AvailabilityHandler
{

    private static final Logger logger = LoggerFactory.getLogger(AvailabilityHandler.class);

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
        try
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
        catch (Exception exception)
        {
            logger.error("Error in getDeviceAvailability handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(context, exception, "Failed to retrieve availability status");
        }
    }

}

