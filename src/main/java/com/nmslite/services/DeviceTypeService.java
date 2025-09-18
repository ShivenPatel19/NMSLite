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
     * @param resultHandler Handler for the async result containing JsonArray of device types
     */
    void deviceTypeList(boolean includeInactive, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create a new device type
     * @param deviceTypeData JsonObject containing device type data (device_type_name, default_port, is_active)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void deviceTypeCreate(JsonObject deviceTypeData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Update device type information
     * @param deviceTypeId Device type ID to update
     * @param deviceTypeData JsonObject containing fields to update
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void deviceTypeUpdate(String deviceTypeId, JsonObject deviceTypeData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete (deactivate) a device type
     * @param deviceTypeId Device type ID to delete
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void deviceTypeDelete(String deviceTypeId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get device type by ID
     * @param deviceTypeId Device type ID
     * @param resultHandler Handler for the async result containing JsonObject with device type data or not found
     */
    void deviceTypeGetById(String deviceTypeId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get device type by name
     * @param deviceTypeName Device type name to search for
     * @param resultHandler Handler for the async result containing JsonObject with device type data or not found
     */
    void deviceTypeGetByName(String deviceTypeName, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Activate or deactivate device type
     * @param deviceTypeId Device type ID
     * @param isActive Active status
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void deviceTypeSetActive(String deviceTypeId, boolean isActive, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get all active device types (simplified list)
     * @param resultHandler Handler for the async result containing JsonArray of active device types
     */
    void deviceTypeListActive(Handler<AsyncResult<JsonArray>> resultHandler);
}
