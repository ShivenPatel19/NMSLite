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
public interface DiscoveryProfileService
{

    String SERVICE_ADDRESS = "discovery.service";

    /**
     * Create a proxy instance for the discovery service
     *
     * @param vertx Vert.x instance
     * @return DiscoveryProfileService proxy instance
     */
    static DiscoveryProfileService createProxy(Vertx vertx)
    {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(DiscoveryProfileService.class);
    }

    // ========================================
    // DISCOVERY PROFILE MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all discovery profiles with full details (including device type and credential info)
     *
     * @param resultHandler Handler for the async result containing JsonArray of discovery profiles
     */
    void discoveryList(Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create a new discovery profile
     *
     * @param profileData JsonObject containing discovery profile data (discovery_name, ip_address, is_range, device_type_id, credential_profile_ids, port, protocol)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void discoveryCreate(JsonObject profileData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete a discovery profile (hard delete)
     *
     * @param profileId Discovery profile ID to delete
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void discoveryDelete(String profileId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get discovery profile by ID
     *
     * @param profileId Discovery profile ID
     * @param resultHandler Handler for the async result containing JsonObject with discovery profile data or not found
     */
    void discoveryGetById(String profileId, Handler<AsyncResult<JsonObject>> resultHandler);

}
