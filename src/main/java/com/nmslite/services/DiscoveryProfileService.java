package com.nmslite.services;

import com.nmslite.Bootstrap;
import io.vertx.codegen.annotations.ProxyGen;

import io.vertx.codegen.annotations.VertxGen;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * DiscoveryService - Discovery profile management operations with ProxyGen

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
     * @return DiscoveryProfileService proxy instance
     */
    static DiscoveryProfileService createProxy()
    {
        return new ServiceProxyBuilder(Bootstrap.getVertxInstance())
            .setAddress(SERVICE_ADDRESS)
            .build(DiscoveryProfileService.class);
    }

    /**
     * Get all discovery profiles with full details (including device type and credential info)
     *
     * @return Future containing JsonArray of discovery profiles
     */
    Future<JsonArray> discoveryList();

    /**
     * Create a new discovery profile
     *
     * @param profileData JsonObject containing discovery profile data (discovery_name, ip_address, is_range, device_type_id, credential_profile_ids, port, protocol)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> discoveryCreate(JsonObject profileData);

    /**
     * Delete a discovery profile (hard delete)
     *
     * @param profileId Discovery profile ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> discoveryDelete(String profileId);

    /**
     * Get discovery profile by ID
     *
     * @param profileId Discovery profile ID
     * @return Future containing JsonObject with discovery profile data or not found
     */
    Future<JsonObject> discoveryGetById(String profileId);

}
