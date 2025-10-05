package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;

import io.vertx.codegen.annotations.VertxGen;

import io.vertx.core.AsyncResult;

import io.vertx.core.Handler;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * MetricsService - Device metrics management operations with ProxyGen
 *
 * This interface provides:
 * - Metrics CRUD operations
 * - Time-series data management
 * - Device performance tracking
 * - Metrics aggregation and cleanup
 * - Dashboard data preparation
 * - Type-safe method calls
 * - Automatic event bus communication
 */
@ProxyGen
@VertxGen
public interface MetricsService
{

    String SERVICE_ADDRESS = "metrics.service";

    /**
     * Create a proxy instance for the metrics service
     *
     * @param vertx Vert.x instance
     * @return MetricsService proxy instance
     */
    static MetricsService createProxy(Vertx vertx)
    {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(MetricsService.class);
    }

    // ========================================
    // METRICS MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Store new metrics data
     *
     * @param metricsData JsonObject containing metrics data (device_id, cpu_usage, memory_usage, disk_usage, network_in, network_out, response_time)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void metricsCreate(JsonObject metricsData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get all metrics (paginated) - only for active, non-deleted devices
     *
     * @param page Page number (0-based)
     * @param pageSize Number of records per page
     * @param deviceId Optional device ID filter (must be active, non-deleted device)
     * @param resultHandler Handler for the async result containing JsonObject with paginated metrics data
     */
    void metricsList(int page, int pageSize, String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get specific metric by ID
     *
     * @param metricId Metric ID
     * @param resultHandler Handler for the async result containing JsonObject with metric data or not found
     */
    void metricsGet(String metricId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete metrics older than specified days
     *
     * @param olderThanDays Number of days to keep metrics
     * @param resultHandler Handler for the async result containing JsonObject with cleanup result
     */
    void metricsDeleteOlderThan(int olderThanDays, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete ALL metrics for a specific device (when device is soft deleted)
     * This ensures no metrics data exists for deleted devices
     *
     * @param deviceId Device ID whose metrics should be completely removed
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void metricsDeleteAllByDevice(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get all metrics for specific device (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @param page Page number (0-based)
     * @param pageSize Number of records per page
     * @param resultHandler Handler for the async result containing JsonObject with paginated device metrics
     */
    void metricsListByDevice(String deviceId, int page, int pageSize, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get latest metric for specific device (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @param resultHandler Handler for the async result containing JsonObject with latest metric data or not found
     */
    void metricsGetLatestByDevice(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get latest metrics for all active, non-deleted devices (dashboard view)
     *
     * @param resultHandler Handler for the async result containing JsonArray of latest metrics for all active devices
     */
    void metricsGetLatestAllDevices(Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Get metrics for device within time range (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @param startTime Start time (ISO 8601 format)
     * @param endTime End time (ISO 8601 format)
     * @param resultHandler Handler for the async result containing JsonArray of metrics within time range
     */
    void metricsGetByDeviceTimeRange(String deviceId, String startTime, String endTime, Handler<AsyncResult<JsonArray>> resultHandler);

}
