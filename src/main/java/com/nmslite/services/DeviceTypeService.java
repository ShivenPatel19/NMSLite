package com.nmslite.services;

import com.nmslite.Bootstrap;
import io.vertx.codegen.annotations.ProxyGen;

import io.vertx.codegen.annotations.VertxGen;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * DeviceTypeService - Device type READ-ONLY operations with ProxyGen

 * This interface provides READ-ONLY operations:
 * - Device type listing and retrieval
 * - Device type lookup by ID/name
 * - Active device types filtering
 * - Type-safe method calls
 * - Automatic event bus communication

 * NOTE: Users cannot create, update, or delete device types for security reasons
 */
@ProxyGen
@VertxGen
public interface DeviceTypeService
{

    String SERVICE_ADDRESS = "devicetype.service";

    /**
     * Create a proxy instance for the device type service
     *
     * @return DeviceTypeService proxy instance
     */
    static DeviceTypeService createProxy()
    {
        return new ServiceProxyBuilder(Bootstrap.getVertxInstance())
            .setAddress(SERVICE_ADDRESS)
            .build(DeviceTypeService.class);
    }

    /**
     * Get all device types (active by default)
     *
     * @param includeInactive Include inactive device types (false = active only, true = all)
     * @return Future containing JsonArray of device types
     */
    Future<JsonArray> deviceTypeList(boolean includeInactive);

    /**
     * Get device type by ID
     *
     * @param deviceTypeId Device type ID
     * @return Future containing JsonObject with device type data or not found
     */
    Future<JsonObject> deviceTypeGetById(String deviceTypeId);

}
