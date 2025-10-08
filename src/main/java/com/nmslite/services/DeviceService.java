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
public interface DeviceService
{

    String SERVICE_ADDRESS = "device.service";

    /**
     * Create a proxy instance for the device service
     *
     * @param vertx Vert.x instance
     * @return DeviceService proxy instance
     */
    static DeviceService createProxy(Vertx vertx)
    {
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
     * @return Future containing JsonArray of devices
     */
    Future<JsonArray> deviceListByProvisioned(boolean isProvisioned);

    /**
     * List devices where both provisioned and monitoring are enabled
     * FILTER: is_provisioned = true AND is_monitoring_enabled = true AND is_deleted = false
     *
     * @return Future containing JsonArray of devices
     */
    Future<JsonArray> deviceListProvisionedAndMonitoringEnabled();

    /**
     * Update device configuration in a single call.
     * Allows updating any subset of: device_name, port, polling_interval_seconds,
     * timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk.

     * NOTE: ip_address, device_type, host_name are IMMUTABLE and cannot be updated here.
     *
     * @param deviceId Device ID
     * @param updateFields JsonObject with any of the allowed fields above
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> deviceUpdateConfig(String deviceId, JsonObject updateFields);

    /**
     * Soft delete a device
     *
     * @param deviceId  Device ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> deviceDelete(String deviceId);

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
     * @return Future containing JsonObject with device data or not found
     */
    Future<JsonObject> deviceGetById(String deviceId);

    /**
     * Find device by IP address
     *
     * @param ipAddress      IP address to search for
     * @param includeDeleted Include soft-deleted devices
     * @return Future containing JsonObject with device data or not found
     */
    Future<JsonObject> deviceFindByIp(String ipAddress, boolean includeDeleted);

    /**
     * Enable monitoring for a device and set monitoring_enabled_at timestamp.
     * If already enabled, preserves existing timestamp.
     *
     * @param deviceId Device ID
     * @return Future containing device_id, is_monitoring_enabled, monitoring_enabled_at
     */
    Future<JsonObject> deviceEnableMonitoring(String deviceId);

    /**
     * Disable monitoring for a device (does not change monitoring_enabled_at).
     *
     * @param deviceId Device ID
     * @return Future containing device_id, is_monitoring_enabled, monitoring_enabled_at
     */
    Future<JsonObject> deviceDisableMonitoring(String deviceId);

    /**
     * Provision devices and enable monitoring (bulk operation).
     * Sets is_provisioned=true AND is_monitoring_enabled=true for multiple devices.
     * Only provisions devices that are currently unprovisioned (is_provisioned=false).
     *
     * @param deviceIds List of device IDs to provision
     * @return Future containing JsonArray with results for each device
     */
    Future<JsonArray> deviceProvisionAndEnableMonitoring(JsonArray deviceIds);

    /**
     * Create device from discovery result (called after successful discovery)
     * Creates device with: device_name = host_name, is_provisioned = false, is_monitoring_enabled = false
     * NOTE: ip_address, device_type, host_name are IMMUTABLE after creation
     *
     * @param deviceData JsonObject containing device data from discovery (device_name, ip_address, device_type, port, protocol, credential_profile_id, host_name)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> deviceCreateFromDiscovery(JsonObject deviceData);

}
