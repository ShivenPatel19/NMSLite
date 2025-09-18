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
public interface UserService {

    String SERVICE_ADDRESS = "user.service";

    /**
     * Create a proxy instance for the user service
     */
    static UserService createProxy(Vertx vertx) {
        return new ServiceProxyBuilder(vertx)
            .setAddress(SERVICE_ADDRESS)
            .build(UserService.class);
    }

    // ========================================
    // USER MANAGEMENT OPERATIONS
    // ========================================

    /**
     * Get all users
     * @return Future containing JsonArray of users
     */
    Future<JsonArray> userList();

    /**
     * Create a new user
     * @param userData JsonObject containing user data (username, password, is_active)
     * @return Future containing JsonObject with creation result
     */
    Future<JsonObject> userCreate(JsonObject userData);

    /**
     * Update user information
     * @param userId User ID to update
     * @param userData JsonObject containing fields to update
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> userUpdate(String userId, JsonObject userData);

    /**
     * Delete a user
     * @param userId User ID to delete
     * @return Future containing JsonObject with deletion result
     */
    Future<JsonObject> userDelete(String userId);

    /**
     * Authenticate user with username and password
     * @param username Username
     * @param password Plain text password
     * @return Future containing JsonObject with authentication result
     */
    Future<JsonObject> userAuthenticate(String username, String password);

    /**
     * Update user's last login timestamp
     * @param userId User ID
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> userUpdateLastLogin(String userId);

    /**
     * Get user by username
     * @param username Username to search for
     * @return Future containing JsonObject with user data or not found
     */
    Future<JsonObject> userGetByUsername(String username);

    /**
     * Change user password
     * @param userId User ID
     * @param oldPassword Current password
     * @param newPassword New password
     * @return Future containing JsonObject with change result
     */
    Future<JsonObject> userChangePassword(String userId, String oldPassword, String newPassword);

    /**
     * Get user by ID
     * @param userId User ID
     * @return Future containing JsonObject with user data or not found
     */
    Future<JsonObject> userGetById(String userId);

    /**
     * Activate or deactivate user
     * @param userId User ID
     * @param isActive Active status
     * @return Future containing JsonObject with update result
     */
    Future<JsonObject> userSetActive(String userId, boolean isActive);
}
