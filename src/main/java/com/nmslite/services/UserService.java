package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;

import io.vertx.codegen.annotations.VertxGen;

import io.vertx.core.Future;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.serviceproxy.ServiceProxyBuilder;

/**
 * UserService - User management operations with ProxyGen

 * This interface provides:
 * - User CRUD operations
 * - Password hashing and validation
 * - User authentication support
 * - Type-safe method calls
 * - Automatic event bus communication
 */
@ProxyGen
@VertxGen
public interface UserService
{

    String SERVICE_ADDRESS = "user.service";

    /**
     * Create a proxy instance for the user service
     *
     * @param vertx Vert.x instance
     * @return UserService proxy instance
     */
    static UserService createProxy(Vertx vertx)
    {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(UserService.class);
    }

    /**
     * Get all users
     *
     * @param includeInactive Include inactive users (false = active users only, true = all users)
     * @return Future containing JsonArray of users
     */
    Future<JsonArray> userList(boolean includeInactive);

    /**
     * Create a new user
     *
     * @param userData JsonObject containing user data (username, password, is_active)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> userCreate(JsonObject userData);

    /**
     * Update user information
     *
     * @param userId User ID to update
     * @param userData JsonObject containing fields to update
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> userUpdate(String userId, JsonObject userData);

    /**
     * Delete a user
     *
     * @param userId User ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> userDelete(String userId);

    /**
     * Authenticate user with username and password
     *
     * @param username Username
     * @param password Plain text password
     * @return Future containing JsonObject with authentication result
     */
    Future<JsonObject> userAuthenticate(String username, String password);

    /**
     * Get user by ID
     *
     * @param userId User ID
     * @return Future containing JsonObject with user data or not found
     */
    Future<JsonObject> userGetById(String userId);

}
