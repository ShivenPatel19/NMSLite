package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;

import io.vertx.codegen.annotations.VertxGen;

import io.vertx.core.Future;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * AvailabilityService - Device current availability status operations with ProxyGen

 * This interface provides:
 * - Current device availability status (one row per device)
 * - Real-time status updates
 * - Device status monitoring
 * - Dashboard status views
 * - Type-safe method calls
 * - Automatic event bus communication

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
     * @return Future containing JsonArray of current device availability statuses
     */
    Future<JsonArray> availabilityListAll();

    /**
     * Create or update device availability status (upsert operation)
     *
     * @param availabilityData JsonObject containing availability data (device_id, status, response_time, checked_at)
     * @return Future containing JsonObject with upsert result
     */
    Future<JsonObject> availabilityCreateOrUpdate(JsonObject availabilityData);

    /**
     * Get current availability status for specific device (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @return Future containing JsonObject with current availability data or not found
     */
    Future<JsonObject> availabilityGetByDevice(String deviceId);

    /**
     * Delete availability status for specific device (when device is deleted)
     *
     * @param deviceId Device ID whose availability status should be removed
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> availabilityDeleteByDevice(String deviceId);

    /**
     * Update device status based on latest check (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @param status New status ("UP" or "DOWN")
     * @param responseTime Response time in milliseconds
     * @return Future containing JsonObject with status update result
     */
    Future<JsonObject> availabilityUpdateDeviceStatus(String deviceId, String status, Long responseTime);

}
