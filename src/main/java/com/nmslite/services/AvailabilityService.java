package com.nmslite.services;

import com.nmslite.Bootstrap;
import io.vertx.codegen.annotations.ProxyGen;

import io.vertx.codegen.annotations.VertxGen;

import io.vertx.core.Future;

import io.vertx.core.json.JsonObject;

import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * AvailabilityService - Device current availability status operations with ProxyGen

 * This interface provides:
 * - Current device availability status retrieval
 * - Real-time status updates for polling
 * - Availability cleanup operations
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
     * @return AvailabilityService proxy instance
     */
    static AvailabilityService createProxy()
    {
        return new ServiceProxyBuilder(Bootstrap.getVertxInstance())
            .setAddress(SERVICE_ADDRESS)
            .build(AvailabilityService.class);
    }

    /**
     * Get current availability status for specific device (device must be active, non-deleted)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @return Future containing JsonObject with current availability data or not found
     */
    Future<JsonObject> availabilityGetByDevice(String deviceId);

    /**
     * Update device status based on latest check (used by PollingMetricsVerticle)
     *
     * @param deviceId Device ID (must be active, non-deleted)
     * @param status New status ("UP" or "DOWN")
     * @return Future containing JsonObject with status update result
     */
    Future<JsonObject> availabilityUpdateDeviceStatus(String deviceId, String status);

    /**
     * Delete availability status for specific device (when device is deleted)
     *
     * @param deviceId Device ID whose availability status should be removed
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> availabilityDeleteByDevice(String deviceId);

}
