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
 * AvailabilityService - Device current availability status operations with ProxyGen
 *
 * This interface provides:
 * - Current device availability status (one row per device)
 * - Real-time status updates
 * - Device status monitoring
 * - Dashboard status views
 * - Type-safe method calls
 * - Automatic event bus communication
 *
 * Note: This tracks CURRENT status only, not historical availability data
 */
@ProxyGen
@VertxGen
public interface AvailabilityService
{

    String SERVICE_ADDRESS = "availability.service";

    /**
     * Create a proxy instance for the availability service
     *
     * @param vertx Vert.x instance
     * @return AvailabilityService proxy instance
     */
    static AvailabilityService createProxy(Vertx vertx)
    {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(AvailabilityService.class);
    }

    // ========================================
    // AVAILABILITY STATUS OPERATIONS
    // ========================================

    /**
     * Get all device availability statuses (only for active, non-deleted devices)
     *
     * @param resultHandler Handler for the async result containing JsonArray of current device availability statuses
     */
    void availabilityListAll(Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create or update device availability status (upsert operation)
     *
     * @param availabilityData JsonObject containing availability data (device_id, status, response_time, checked_at)
     * @param resultHandler Handler for the async result containing JsonObject with upsert result
     */
    void availabilityCreateOrUpdate(JsonObject availabilityData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get current availability status for specific device (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @param resultHandler Handler for the async result containing JsonObject with current availability data or not found
     */
    void availabilityGetByDevice(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete availability status for specific device (when device is deleted)
     *
     * @param deviceId Device ID whose availability status should be removed
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void availabilityDeleteByDevice(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Update device status based on latest check (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @param status New status ("UP" or "DOWN")
     * @param responseTime Response time in milliseconds
     * @param resultHandler Handler for the async result containing JsonObject with status update result
     */
    void availabilityUpdateDeviceStatus(String deviceId, String status, Long responseTime, Handler<AsyncResult<JsonObject>> resultHandler);

}
