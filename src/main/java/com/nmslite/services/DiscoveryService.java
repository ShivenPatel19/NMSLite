package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
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
     * @return Future containing JsonArray of discovery profiles
     */
    Future<JsonArray> discoveryList(boolean includeInactive);

    /**
     * Create a new discovery profile
     * @param profileData JsonObject containing discovery profile data (discovery_name, ip_address, device_type_id, credential_profile_id, port, timeout_seconds, retry_count, is_active, created_by)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> discoveryCreate(JsonObject profileData);

    /**
     * Update discovery profile information
     * @param profileId Discovery profile ID to update
     * @param profileData JsonObject containing fields to update
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> discoveryUpdate(String profileId, JsonObject profileData);

    /**
     * Delete (deactivate) a discovery profile
     * @param profileId Discovery profile ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> discoveryDelete(String profileId);

    /**
     * Get discovery profile by ID
     * @param profileId Discovery profile ID
     * @return Future containing JsonObject with discovery profile data or not found
     */
    Future<JsonObject> discoveryGetById(String profileId);

    /**
     * Find discovery profile by IP address
     * @param ipAddress IP address to search for
     * @return Future containing JsonObject with discovery profile data or not found
     */
    Future<JsonObject> discoveryFindByIp(String ipAddress);

    /**
     * Get discovery profile by name
     * @param discoveryName Discovery name to search for
     * @return Future containing JsonObject with discovery profile data or not found
     */
    Future<JsonObject> discoveryGetByName(String discoveryName);

    /**
     * Activate or deactivate discovery profile
     * @param profileId Discovery profile ID
     * @param isActive Active status
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> discoverySetActive(String profileId, boolean isActive);

    /**
     * Get all active discovery profiles (simplified list)
     * @return Future containing JsonArray of active discovery profiles
     */
    Future<JsonArray> discoveryListActive();

    /**
     * Execute discovery for a specific profile
     * @param profileId Discovery profile ID
     * @return Future containing JsonObject with discovery execution result
     */
    Future<JsonObject> discoveryExecute(String profileId);
}
