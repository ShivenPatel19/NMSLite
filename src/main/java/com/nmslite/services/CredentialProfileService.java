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

 * This interface provides:
 * - Credential profile CRUD operations
 * - Password encryption/decryption
 * - Protocol management
 * - Type-safe method calls
 * - Automatic event bus communication
 */
@ProxyGen
@VertxGen
public interface CredentialProfileService
{

    String SERVICE_ADDRESS = "credential.service";

    /**
     * Create a proxy instance for the credential service
     *
     * @param vertx Vert.x instance
     * @return CredentialProfileService proxy instance
     */
    static CredentialProfileService createProxy(Vertx vertx)
    {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(CredentialProfileService.class);
    }

    /**
     * Get all credential profiles
     *
     * @return Future containing JsonArray of credential profiles
     */
    Future<JsonArray> credentialList();

    /**
     * Create a new credential profile
     *
     * @param credentialData JsonObject containing credential data (profile_name, username, password, protocol)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> credentialCreate(JsonObject credentialData);

    /**
     * Update credential profile information
     *
     * @param credentialId Credential profile ID to update
     * @param credentialData JsonObject containing fields to update
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> credentialUpdate(String credentialId, JsonObject credentialData);

    /**
     * Delete a credential profile (hard delete)
     *
     * @param credentialId Credential profile ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> credentialDelete(String credentialId);

    /**
     * Get credential profile by ID
     *
     * @param credentialId Credential profile ID
     * @return Future containing JsonObject with credential profile data or not found
     */
    Future<JsonObject> credentialGetById(String credentialId);

    /**
     * Get multiple credential profiles by IDs
     *
     * @param credentialIds JsonArray containing credential profile IDs
     * @return Future containing JsonObject with credentials array
     */
    Future<JsonObject> credentialGetByIds(JsonArray credentialIds);

}
