package com.nmslite.services;

import com.nmslite.Bootstrap;
import io.vertx.codegen.annotations.ProxyGen;

import io.vertx.codegen.annotations.VertxGen;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * DeviceService - Device management operations with ProxyGen

 * This interface provides:
 * - Device CRUD operations with soft delete
 * - Device provisioning (is_provisioned flag controls monitoring state)
 * - Device discovery integration
 * - Device status and availability tracking
 * - Type-safe method calls
 * - Automatic event bus communication

 * NOTE: is_provisioned flag now controls monitoring - when true, monitoring is enabled; when false, monitoring is disabled
 * NOTE: port and protocol are stored in credential_profiles table, not devices table
 */
@ProxyGen
@VertxGen
public interface DeviceService
{

    String SERVICE_ADDRESS = "device.service";

    /**
     * Create a proxy instance for the device service
     *
     * @return DeviceService proxy instance
     */
    static DeviceService createProxy()
    {
        return new ServiceProxyBuilder(Bootstrap.getVertxInstance())
                .setAddress(SERVICE_ADDRESS)
                .build(DeviceService.class);
    }

    /**
     * List devices by provision status only
     * FILTER: is_provisioned = <param>, is_deleted = false
     *
     * @param isProvisioned Whether to return provisioned (true) or non-provisioned (false) devices
     * @return Future containing JsonArray of devices
     */
    Future<JsonArray> deviceListByProvisioned(boolean isProvisioned);



    /**
     * Update device configuration in a single call.
     * Allows updating any subset of: device_name, polling_interval_seconds, timeout_seconds.

     * NOTE: ip_address, device_type, host_name are IMMUTABLE and cannot be updated here.
     * NOTE: port and protocol are stored in credential_profiles, not devices.
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
     * Enable provisioning for a device (sets is_provisioned = true).
     * NOTE: is_provisioned flag controls whether device is being monitored.
     *
     * @param deviceId Device ID
     * @return Future containing device_id and is_provisioned status
     */
    Future<JsonObject> deviceEnableProvisioning(String deviceId);

    /**
     * Disable provisioning for a device (sets is_provisioned = false).
     * NOTE: is_provisioned flag controls whether device is being monitored.
     *
     * @param deviceId Device ID
     * @return Future containing device_id and is_provisioned status
     */
    Future<JsonObject> deviceDisableProvisioning(String deviceId);

    /**
     * Create device from discovery result (called after successful discovery)
     * Creates device with: device_name = host_name, is_provisioned = true (auto-provisioned, monitoring enabled)
     * NOTE: ip_address, device_type, host_name are IMMUTABLE after creation
     * NOTE: port and protocol are stored in credential_profiles, not devices
     * NOTE: Devices are automatically provisioned upon successful discovery
     *
     * @param deviceData JsonObject containing device data from discovery (device_name, ip_address, device_type, credential_profile_id, host_name)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> deviceCreateFromDiscovery(JsonObject deviceData);

}
