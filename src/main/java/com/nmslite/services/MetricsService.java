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

 * This interface provides:
 * - Metrics creation for polling
 * - Metrics retrieval by device
 * - Metrics cleanup operations
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
     * Store new metrics data (used by PollingMetricsVerticle)
     *
     * @param metricsData JsonObject containing metrics data (device_id, duration_ms, cpu_usage_percent, memory_usage_percent, memory_total_bytes, memory_used_bytes, memory_free_bytes, disk_usage_percent, disk_total_bytes, disk_used_bytes, disk_free_bytes)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> metricsCreate(JsonObject metricsData);

    /**
     * Get all metrics for specific device ordered by timestamp DESC (latest to old)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @return Future containing JsonArray of all metrics for the device
     */
    Future<JsonArray> metricsGetAllByDevice(String deviceId);

    /**
     * Delete ALL metrics for a specific device (when device is soft deleted)
     *
     * @param deviceId Device ID whose metrics should be completely removed
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> metricsDeleteAllByDevice(String deviceId);

}
