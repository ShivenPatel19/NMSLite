package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * CredentialService - Credential profile management operations with ProxyGen
 * 
 * This interface provides:
 * - Credential profile CRUD operations
 * - Password encryption/decryption
 * - Protocol management
 * - Type-safe method calls
 * - Automatic event bus communication
 */
@ProxyGen
@VertxGen
public interface CredentialService {

    String SERVICE_ADDRESS = "credential.service";

    /**
     * Create a proxy instance for the credential service
     */
    static CredentialService createProxy(Vertx vertx) {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(CredentialService.class);
    }

    // ========================================
    // CREDENTIAL MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all credential profiles
     * @param includeInactive Include inactive credential profiles
     * @return Future containing JsonArray of credential profiles
     */
    Future<JsonArray> credentialList(boolean includeInactive);

    /**
     * Create a new credential profile
     * @param credentialData JsonObject containing credential data (profile_name, username, password, protocol, is_active, created_by)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> credentialCreate(JsonObject credentialData);

    /**
     * Update credential profile information
     * @param credentialId Credential profile ID to update
     * @param credentialData JsonObject containing fields to update
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> credentialUpdate(String credentialId, JsonObject credentialData);

    /**
     * Delete (deactivate) a credential profile
     * @param credentialId Credential profile ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> credentialDelete(String credentialId);

    /**
     * Get credential profile by ID
     * @param credentialId Credential profile ID
     * @return Future containing JsonObject with credential profile data or not found
     */
    Future<JsonObject> credentialGetById(String credentialId);

    /**
     * Get credential profile by name
     * @param profileName Profile name to search for
     * @return Future containing JsonObject with credential profile data or not found
     */
    Future<JsonObject> credentialGetByName(String profileName);

    /**
     * Activate credential profile
     * @param credentialId Credential profile ID
     * @param isActive Active status
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> credentialSetActive(String credentialId, boolean isActive);

    /**
     * Get all active credential profiles (simplified list)
     * @return Future containing JsonArray of active credential profiles
     */
    Future<JsonArray> credentialListActive();
}
