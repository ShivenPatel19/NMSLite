package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
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
public interface MetricsService {

    String SERVICE_ADDRESS = "metrics.service";

    /**
     * Create a proxy instance for the metrics service
     */
    static MetricsService createProxy(Vertx vertx) {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(MetricsService.class);
    }

    // ========================================
    // METRICS MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Store new metrics data
     * @param metricsData JsonObject containing metrics data (device_id, cpu_usage, memory_usage, disk_usage, network_in, network_out, response_time)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> metricsCreate(JsonObject metricsData);

    /**
     * Get all metrics (paginated) - only for active, non-deleted devices
     * @param page Page number (0-based)
     * @param pageSize Number of records per page
     * @param deviceId Optional device ID filter (must be active, non-deleted device)
     * @return Future containing JsonObject with paginated metrics data
     */
    Future<JsonObject> metricsList(int page, int pageSize, String deviceId);

    /**
     * Get specific metric by ID
     * @param metricId Metric ID
     * @return Future containing JsonObject with metric data or not found
     */
    Future<JsonObject> metricsGet(String metricId);

    /**
     * Delete metrics older than specified days
     * @param olderThanDays Number of days to keep metrics
     * @return Future containing JsonObject with cleanup result
     */
    Future<JsonObject> metricsDeleteOlderThan(int olderThanDays);

    /**
     * Delete ALL metrics for a specific device (when device is soft deleted)
     * This ensures no metrics data exists for deleted devices
     * @param deviceId Device ID whose metrics should be completely removed
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> metricsDeleteAllByDevice(String deviceId);

    /**
     * Get all metrics for specific device (device must be active, non-deleted)
     * @param deviceId Device ID (must be active, non-deleted)
     * @param page Page number (0-based)
     * @param pageSize Number of records per page
     * @return Future containing JsonObject with paginated device metrics
     */
    Future<JsonObject> metricsListByDevice(String deviceId, int page, int pageSize);

    /**
     * Get latest metric for specific device (device must be active, non-deleted)
     * @param deviceId Device ID (must be active, non-deleted)
     * @return Future containing JsonObject with latest metric data or not found
     */
    Future<JsonObject> metricsGetLatestByDevice(String deviceId);

    /**
     * Get latest metrics for all active, non-deleted devices (dashboard view)
     * @return Future containing JsonArray of latest metrics for all active devices
     */
    Future<JsonArray> metricsGetLatestAllDevices();

    /**
     * Get metrics for device within time range (device must be active, non-deleted)
     * @param deviceId Device ID (must be active, non-deleted)
     * @param startTime Start time (ISO 8601 format)
     * @param endTime End time (ISO 8601 format)
     * @return Future containing JsonArray of metrics within time range
     */
    Future<JsonArray> metricsGetByDeviceTimeRange(String deviceId, String startTime, String endTime);
}
