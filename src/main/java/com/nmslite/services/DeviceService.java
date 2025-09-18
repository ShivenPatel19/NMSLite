package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * DeviceService - Device management operations with ProxyGen
 * 
 * This interface provides:
 * - Device CRUD operations with soft delete
 * - Device monitoring management
 * - Device provisioning and discovery integration
 * - Device status and availability tracking
 * - Type-safe method calls
 * - Automatic event bus communication
 */
@ProxyGen
@VertxGen
public interface DeviceService {

    String SERVICE_ADDRESS = "device.service";

    /**
     * Create a proxy instance for the device service
     */
    static DeviceService createProxy(Vertx vertx) {
        return new ServiceProxyBuilder(vertx)
                .setAddress(SERVICE_ADDRESS)
                .build(DeviceService.class);
    }

    // ========================================
    // DEVICE MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all devices
     *
     * @param includeDeleted Include soft-deleted devices
     * @return Future containing JsonArray of devices
     */
    Future<JsonArray> deviceList(boolean includeDeleted);

    /**
     * Create a new device
     *
     * @param deviceData JsonObject containing device data (device_name, ip_address, device_type, port, username, password, is_monitoring_enabled, discovery_profile_id)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> deviceCreate(JsonObject deviceData);

    /**
     * Update device monitoring status (only field directly editable)
     *
     * @param deviceId  Device ID
     * @param isEnabled Monitoring enabled status
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> deviceUpdateMonitoring(String deviceId, boolean isEnabled);

    /**
     * Update device monitoring configuration (directly editable fields)
     *
     * @param deviceId         Device ID
     * @param monitoringConfig JsonObject containing monitoring configuration (polling_interval_seconds, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk)
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> deviceUpdateMonitoringConfig(String deviceId, JsonObject monitoringConfig);

    /**
     * Soft delete a device
     *
     * @param deviceId  Device ID to delete
     * @param deletedBy User who deleted the device
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> deviceDelete(String deviceId, String deletedBy);

    /**
     * Restore a soft-deleted device
     *
     * @param deviceId Device ID to restore
     * @return Future containing JsonObject with restoration result
     */
    Future<JsonObject> deviceRestore(String deviceId);

    /**
     * Get device by ID
     *
     * @param deviceId       Device ID
     * @param includeDeleted Include soft-deleted devices
     * @return Future containing JsonObject with device data or not found
     */
    Future<JsonObject> deviceGetById(String deviceId, boolean includeDeleted);

    /**
     * Find device by IP address
     *
     * @param ipAddress      IP address to search for
     * @param includeDeleted Include soft-deleted devices
     * @return Future containing JsonObject with device data or not found
     */
    Future<JsonObject> deviceFindByIp(String ipAddress, boolean includeDeleted);

    /**
     * Get devices ready for polling (active monitoring enabled devices)
     *
     * @return Future containing JsonArray of devices ready for polling
     */
    Future<JsonArray> deviceListForPolling();

    /**
     * Get devices with availability statistics
     *
     * @param includeDeleted Include soft-deleted devices
     * @return Future containing JsonArray of devices with availability data
     */
    Future<JsonArray> deviceListWithAvailability(boolean includeDeleted);


    /**
     * Get devices with current status (includes availability status)
     *
     * @param includeDeleted Include soft-deleted devices
     * @return Future containing JsonArray of devices with current status
     */
    Future<JsonArray> deviceListWithStatus(boolean includeDeleted);

    /**
     * Get devices by name pattern (search)
     *
     * @param namePattern    Device name pattern to search for
     * @param includeDeleted Include soft-deleted devices
     * @return Future containing JsonArray of matching devices
     */
    Future<JsonArray> deviceSearchByName(String namePattern, boolean includeDeleted);

    /**
     * Get devices by monitoring status
     *
     * @param isMonitoringEnabled Monitoring enabled filter
     * @param includeDeleted      Include soft-deleted devices
     * @return Future containing JsonArray of devices
     */
    Future<JsonArray> deviceListByMonitoringStatus(boolean isMonitoringEnabled, boolean includeDeleted);

    /**
     * Get device count by status
     *
     * @return Future containing JsonObject with device counts (total, active, deleted, monitoring_enabled, monitoring_disabled)
     */
    Future<JsonObject> deviceGetCounts();

    /**
     * Synchronize all devices from attached tables (discovery profiles, credentials, device types)
     * This method will refresh all device data from their authoritative sources
     *
     * @return Future containing JsonObject with synchronization result
     */
    Future<JsonObject> deviceSync();
}
