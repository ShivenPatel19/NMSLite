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
public interface CredentialProfileService {

    String SERVICE_ADDRESS = "credential.service";

    /**
     * Create a proxy instance for the credential service
     */
    static CredentialProfileService createProxy(Vertx vertx) {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(CredentialProfileService.class);
    }

    // ========================================
    // CREDENTIAL MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all credential profiles
     * @param resultHandler Handler for the async result containing JsonArray of credential profiles
     */
    void credentialList(Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create a new credential profile
     * @param credentialData JsonObject containing credential data (profile_name, username, password, protocol)
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
     * Delete a credential profile (hard delete)
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
     * Get multiple credential profiles by IDs
     * @param credentialIds JsonArray containing credential profile IDs
     * @param resultHandler Handler for the async result containing JsonObject with credentials array
     */
    void credentialGetByIds(JsonArray credentialIds, Handler<AsyncResult<JsonObject>> resultHandler);
}
