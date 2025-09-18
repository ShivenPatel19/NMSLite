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
 * DiscoveryService - Discovery profile management operations with ProxyGen
 * 
 * This interface provides:
 * - Discovery profile CRUD operations
 * - IP address conflict detection
 * - Device type and credential integration
 * - Discovery execution and validation
 * - Type-safe method calls
 * - Automatic event bus communication
 */
@ProxyGen
@VertxGen
public interface DiscoveryService {

    String SERVICE_ADDRESS = "discovery.service";

    /**
     * Create a proxy instance for the discovery service
     */
    static DiscoveryService createProxy(Vertx vertx) {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(DiscoveryService.class);
    }

    // ========================================
    // DISCOVERY PROFILE MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all discovery profiles with full details (including device type and credential info)
     * @param includeInactive Include inactive discovery profiles
     * @param resultHandler Handler for the async result containing JsonArray of discovery profiles
     */
    void discoveryList(boolean includeInactive, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create a new discovery profile
     * @param profileData JsonObject containing discovery profile data (discovery_name, ip_address, device_type_id, credential_profile_id, port, timeout_seconds, retry_count, is_active, created_by)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void discoveryCreate(JsonObject profileData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Update discovery profile information
     * @param profileId Discovery profile ID to update
     * @param profileData JsonObject containing fields to update
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void discoveryUpdate(String profileId, JsonObject profileData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete (deactivate) a discovery profile
     * @param profileId Discovery profile ID to delete
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void discoveryDelete(String profileId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get discovery profile by ID
     * @param profileId Discovery profile ID
     * @param resultHandler Handler for the async result containing JsonObject with discovery profile data or not found
     */
    void discoveryGetById(String profileId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Find discovery profile by IP address
     * @param ipAddress IP address to search for
     * @param resultHandler Handler for the async result containing JsonObject with discovery profile data or not found
     */
    void discoveryFindByIp(String ipAddress, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get discovery profile by name
     * @param discoveryName Discovery name to search for
     * @param resultHandler Handler for the async result containing JsonObject with discovery profile data or not found
     */
    void discoveryGetByName(String discoveryName, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Activate or deactivate discovery profile
     * @param profileId Discovery profile ID
     * @param isActive Active status
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void discoverySetActive(String profileId, boolean isActive, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get all active discovery profiles (simplified list)
     * @param resultHandler Handler for the async result containing JsonArray of active discovery profiles
     */
    void discoveryListActive(Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Execute discovery for a specific profile
     * @param profileId Discovery profile ID
     * @param resultHandler Handler for the async result containing JsonObject with discovery execution result
     */
    void discoveryExecute(String profileId, Handler<AsyncResult<JsonObject>> resultHandler);
}
