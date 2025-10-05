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
 * UserService - User management operations with ProxyGen
 *
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

    // ========================================
    // USER MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all users
     *
     * @param includeInactive Include inactive users (false = active users only, true = all users)
     * @param resultHandler Handler for the async result containing JsonArray of users
     */
    void userList(boolean includeInactive, Handler<AsyncResult<JsonArray>> resultHandler);

    /**
     * Create a new user
     *
     * @param userData JsonObject containing user data (username, password, is_active)
     * @param resultHandler Handler for the async result containing JsonObject with creation result
     */
    void userCreate(JsonObject userData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Update user information
     *
     * @param userId User ID to update
     * @param userData JsonObject containing fields to update
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void userUpdate(String userId, JsonObject userData, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Delete a user
     *
     * @param userId User ID to delete
     * @param resultHandler Handler for the async result containing JsonObject with deletion result
     */
    void userDelete(String userId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Authenticate user with username and password
     *
     * @param username Username
     * @param password Plain text password
     * @param resultHandler Handler for the async result containing JsonObject with authentication result
     */
    void userAuthenticate(String username, String password, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Get user by ID
     *
     * @param userId User ID
     * @param resultHandler Handler for the async result containing JsonObject with user data or not found
     */
    void userGetById(String userId, Handler<AsyncResult<JsonObject>> resultHandler);

    /**
     * Activate or deactivate user
     *
     * @param userId User ID
     * @param isActive Active status
     * @param resultHandler Handler for the async result containing JsonObject with update result
     */
    void userSetActive(String userId, boolean isActive, Handler<AsyncResult<JsonObject>> resultHandler);

}
