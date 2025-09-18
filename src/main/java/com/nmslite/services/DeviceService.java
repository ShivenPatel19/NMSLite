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
     * @param resultHandler Handler for the async result containing JsonArray of devices
     */
    void deviceList(boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create a new device
     *
     * @param deviceData JsonObject containing device data (device_name, ip_address, device_type, port, username, password, is_monitoring_enabled, discovery_profile_id)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void deviceCreate(JsonObject deviceData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Update device monitoring status (only field directly editable)
     *
     * @param deviceId  Device ID
     * @param isEnabled Monitoring enabled status
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void deviceUpdateMonitoring(String deviceId, boolean isEnabled, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Update device monitoring configuration (directly editable fields)
     *
     * @param deviceId         Device ID
     * @param monitoringConfig JsonObject containing monitoring configuration (polling_interval_seconds, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk)
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void deviceUpdateMonitoringConfig(String deviceId, JsonObject monitoringConfig, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Soft delete a device
     *
     * @param deviceId  Device ID to delete
     * @param deletedBy User who deleted the device
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void deviceDelete(String deviceId, String deletedBy, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Restore a soft-deleted device
     *
     * @param deviceId Device ID to restore
     * @param resultHandler Handler for the async result containing JsonObject with restoration result
     */
    void deviceRestore(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get device by ID
     *
     * @param deviceId       Device ID
     * @param includeDeleted Include soft-deleted devices
     * @param resultHandler Handler for the async result containing JsonObject with device data or not found
     */
    void deviceGetById(String deviceId, boolean includeDeleted, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Find device by IP address
     *
     * @param ipAddress      IP address to search for
     * @param includeDeleted Include soft-deleted devices
     * @param resultHandler Handler for the async result containing JsonObject with device data or not found
     */
    void deviceFindByIp(String ipAddress, boolean includeDeleted, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get devices ready for polling (active monitoring enabled devices)
     *
     * @param resultHandler Handler for the async result containing JsonArray of devices ready for polling
     */
    void deviceListForPolling(Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Get devices with availability statistics
     *
     * @param includeDeleted Include soft-deleted devices
     * @param resultHandler Handler for the async result containing JsonArray of devices with availability data
     */
    void deviceListWithAvailability(boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler);


    /**
     * Get devices with current status (includes availability status)
     *
     * @param includeDeleted Include soft-deleted devices
     * @param resultHandler Handler for the async result containing JsonArray of devices with current status
     */
    void deviceListWithStatus(boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Get devices by name pattern (search)
     *
     * @param namePattern    Device name pattern to search for
     * @param includeDeleted Include soft-deleted devices
     * @param resultHandler Handler for the async result containing JsonArray of matching devices
     */
    void deviceSearchByName(String namePattern, boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Get devices by monitoring status
     *
     * @param isMonitoringEnabled Monitoring enabled filter
     * @param includeDeleted      Include soft-deleted devices
     * @param resultHandler Handler for the async result containing JsonArray of devices
     */
    void deviceListByMonitoringStatus(boolean isMonitoringEnabled, boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Get device count by status
     *
     * @param resultHandler Handler for the async result containing JsonObject with device counts (total, active, deleted, monitoring_enabled, monitoring_disabled)
     */
    void deviceGetCounts(Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Synchronize all devices from attached tables (discovery profiles, credentials, device types)
     * This method will refresh all device data from their authoritative sources
     *
     * @param resultHandler Handler for the async result containing JsonObject with synchronization result
     */
    void deviceSync(Handler<AsyncResult<JsonObject>> resultHandler);
}
