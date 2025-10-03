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
     * List devices by provision status only
     * FILTER: is_provisioned = <param>, is_deleted = false
     *
     * @param isProvisioned Whether to return provisioned (true) or non-provisioned (false) devices
     * @param resultHandler Handler for the async result containing JsonArray of devices
     */
    void deviceListByProvisioned(boolean isProvisioned, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * List devices where both provisioned and monitoring are enabled
     * FILTER: is_provisioned = true AND is_monitoring_enabled = true AND is_deleted = false
     *
     * @param resultHandler Handler for the async result containing JsonArray of devices
     */
    void deviceListProvisionedAndMonitoringEnabled(Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Update device configuration in a single call.
     * Allows updating any subset of: device_name, port, polling_interval_seconds,
     * timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk.
     *
     * NOTE: ip_address, device_type, host_name are IMMUTABLE and cannot be updated here.
     *
     * @param deviceId Device ID
     * @param updateFields JsonObject with any of the allowed fields above
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void deviceUpdateConfig(String deviceId, JsonObject updateFields, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Soft delete a device
     *
     * @param deviceId  Device ID to delete
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void deviceDelete(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

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
     * @param resultHandler Handler for the async result containing JsonObject with device data or not found
     */
    void deviceGetById(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Find device by IP address
     *
     * @param ipAddress      IP address to search for
     * @param includeDeleted Include soft-deleted devices
     * @param resultHandler Handler for the async result containing JsonObject with device data or not found
     */
    void deviceFindByIp(String ipAddress, boolean includeDeleted, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Enable monitoring for a device and set monitoring_enabled_at timestamp.
     * If already enabled, preserves existing timestamp.
     *
     * @param deviceId Device ID
     * @param resultHandler Handler for async result containing device_id, is_monitoring_enabled, monitoring_enabled_at
     */
    void deviceEnableMonitoring(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);
    
    /**
     * Disable monitoring for a device (does not change monitoring_enabled_at).
     *
     * @param deviceId Device ID
     * @param resultHandler Handler for async result containing device_id, is_monitoring_enabled, monitoring_enabled_at
     */
    void deviceDisableMonitoring(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Create device from discovery result (called after successful discovery)
     * Creates device with: device_name = host_name, is_provisioned = false, is_monitoring_enabled = false
     * NOTE: ip_address, device_type, host_name are IMMUTABLE after creation
     *
     * @param deviceData JsonObject containing device data from discovery (device_name, ip_address, device_type, port, protocol, credential_profile_id, host_name)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void deviceCreateFromDiscovery(JsonObject deviceData, Handler<AsyncResult<JsonObject>> resultHandler);
}
