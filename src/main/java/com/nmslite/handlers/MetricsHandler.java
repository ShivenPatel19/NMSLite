package com.nmslite.handlers;

import com.nmslite.services.MetricsService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ResponseUtil;

import com.nmslite.utils.ValidationUtil;

import io.vertx.ext.web.RoutingContext;

/**
 * MetricsHandler - HTTP request handler for metrics operations

 * Handles:
 * - Fetching metrics by device ID
 */
public class MetricsHandler
{

    private final MetricsService metricsService;

    /**
     * Constructor for MetricsHandler
     *
     * @param metricsService Metrics service instance
     */
    public MetricsHandler(MetricsService metricsService)
    {
        this.metricsService = metricsService;
    }

    /**
     * Get all metrics for a specific device
     *
     * @param context Routing context
     */
    public void getDeviceMetrics(RoutingContext context)
    {
        var deviceId = context.pathParam("deviceId");

        if (!ValidationUtil.validatePathParameterUUID(context, deviceId, "deviceId"))
        {
            return; // Validation failed, response already sent
        }

        metricsService.metricsGetAllByDevice(deviceId)
                .onSuccess(metrics ->
                        ResponseUtil.handleSuccess(context, metrics))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(context, cause, "Failed to retrieve metrics"));
    }

}

