package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * DeviceTypeService - Device type management operations with ProxyGen
 * 
 * This interface provides:
 * - Device type CRUD operations
 * - Device type validation
 * - Default port management
 * - Type-safe method calls
 * - Automatic event bus communication
 */
@ProxyGen
@VertxGen
public interface DeviceTypeService {

    String SERVICE_ADDRESS = "devicetype.service";

    /**
     * Create a proxy instance for the device type service
     */
    static DeviceTypeService createProxy(Vertx vertx) {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(DeviceTypeService.class);
    }

    // ========================================
    // DEVICE TYPE MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all device types
     * @param includeInactive Include inactive device types
     * @return Future containing JsonArray of device types
     */
    Future<JsonArray> deviceTypeList(boolean includeInactive);

    /**
     * Create a new device type
     * @param deviceTypeData JsonObject containing device type data (device_type_name, default_port, is_active)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> deviceTypeCreate(JsonObject deviceTypeData);

    /**
     * Update device type information
     * @param deviceTypeId Device type ID to update
     * @param deviceTypeData JsonObject containing fields to update
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> deviceTypeUpdate(String deviceTypeId, JsonObject deviceTypeData);

    /**
     * Delete (deactivate) a device type
     * @param deviceTypeId Device type ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> deviceTypeDelete(String deviceTypeId);

    /**
     * Get device type by ID
     * @param deviceTypeId Device type ID
     * @return Future containing JsonObject with device type data or not found
     */
    Future<JsonObject> deviceTypeGetById(String deviceTypeId);

    /**
     * Get device type by name
     * @param deviceTypeName Device type name to search for
     * @return Future containing JsonObject with device type data or not found
     */
    Future<JsonObject> deviceTypeGetByName(String deviceTypeName);

    /**
     * Activate or deactivate device type
     * @param deviceTypeId Device type ID
     * @param isActive Active status
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> deviceTypeSetActive(String deviceTypeId, boolean isActive);

    /**
     * Get all active device types (simplified list)
     * @return Future containing JsonArray of active device types
     */
    Future<JsonArray> deviceTypeListActive();
}
