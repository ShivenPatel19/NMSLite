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
     * @param resultHandler Handler for the async result containing JsonArray of credential profiles
     */
    void credentialList(boolean includeInactive, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create a new credential profile
     * @param credentialData JsonObject containing credential data (profile_name, username, password, protocol, is_active, created_by)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void credentialCreate(JsonObject credentialData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Update credential profile information
     * @param credentialId Credential profile ID to update
     * @param credentialData JsonObject containing fields to update
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void credentialUpdate(String credentialId, JsonObject credentialData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete (deactivate) a credential profile
     * @param credentialId Credential profile ID to delete
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void credentialDelete(String credentialId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get credential profile by ID
     * @param credentialId Credential profile ID
     * @param resultHandler Handler for the async result containing JsonObject with credential profile data or not found
     */
    void credentialGetById(String credentialId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get credential profile by name
     * @param profileName Profile name to search for
     * @param resultHandler Handler for the async result containing JsonObject with credential profile data or not found
     */
    void credentialGetByName(String profileName, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Activate credential profile
     * @param credentialId Credential profile ID
     * @param isActive Active status
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void credentialSetActive(String credentialId, boolean isActive, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get all active credential profiles (simplified list)
     * @param resultHandler Handler for the async result containing JsonArray of active credential profiles
     */
    void credentialListActive(Handler<AsyncResult<JsonArray>> resultHandler);
}
