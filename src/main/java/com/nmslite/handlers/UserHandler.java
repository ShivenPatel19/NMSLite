package com.nmslite.handlers;

import com.nmslite.services.UserService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ValidationUtil;

import com.nmslite.utils.ResponseUtil;

import com.nmslite.utils.JWTUtil;


import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * UserHandler - Handles all user-related HTTP requests

 * This handler manages:
 * - User CRUD operations
 * - User authentication
 * - Password management
 * - User status management

 * Uses UserService for all database operations via ProxyGen
 */
public class UserHandler
{

    private static final Logger logger = LoggerFactory.getLogger(UserHandler.class);

    private final UserService userService;

    private final JWTUtil jwtUtil;

    /**
     * Constructor for UserHandler.
     *
     * @param userService service proxy for user database operations
     * @param jwtUtil utility for JWT token generation and validation
     */
    public UserHandler(UserService userService, JWTUtil jwtUtil)
    {
        this.userService = userService;

        this.jwtUtil = jwtUtil;
    }

    /**
     * Get all users with optional filter for inactive users.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getUsers(RoutingContext ctx)
    {
        try
        {
            var includeInactiveParam = ctx.request().getParam("includeInactive");

            var includeInactive = false;

            if (includeInactiveParam != null)
            {
                if ("true".equalsIgnoreCase(includeInactiveParam) || "false".equalsIgnoreCase(includeInactiveParam))
                {
                    includeInactive = "true".equalsIgnoreCase(includeInactiveParam);
                }
                else
                {
                    ExceptionUtil.handleHttp(ctx, new Exception("Invalid includeInactive parameter"),
                        "includeInactive parameter must be 'true' or 'false'");

                    return;
                }
            }

            userService.userList(includeInactive)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, new JsonObject().put("users", result)))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to get users"));
        }
        catch (Exception exception)
        {
            logger.error("Error in getUsers handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to get users");
        }
    }

    /**
     * Create a new user.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void createUser(RoutingContext ctx)
    {
        try
        {
            var body = ctx.body().asJsonObject();

            if (!ValidationUtil.User.validateCreate(ctx, body))
            {
                return; // Validation failed, response already sent
            }

            var userData = new JsonObject()
                .put("username", body.getString("username"))
                .put("password", body.getString("password"))
                .put("is_active", body.getBoolean("is_active", true));

            userService.userCreate(userData)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to create user"));
        }
        catch (Exception exception)
        {
            logger.error("Error in createUser handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to create user");
        }
    }

    /**
     * Update an existing user.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void updateUser(RoutingContext ctx)
    {
        try
        {
            var userId = ctx.pathParam("id");

            var body = ctx.body().asJsonObject();

            // 1. Validate path parameter
            if (!ValidationUtil.validatePathParameterUUID(ctx, userId, "User ID"))
            {
                return; // Validation failed, response already sent
            }

            // 2. Validate user update fields
            if (!ValidationUtil.User.validateUpdate(ctx, body))
            {
                return; // Validation failed, response already sent
            }

            userService.userUpdate(userId, body)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to update user"));
        }
        catch (Exception exception)
        {
            logger.error("Error in updateUser handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to update user");
        }
    }

    /**
     * Delete a user.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void deleteUser(RoutingContext ctx)
    {
        try
        {
            var userId = ctx.pathParam("id");

            // ===== PATH PARAMETER VALIDATION =====
            if (!ValidationUtil.validatePathParameterUUID(ctx, userId, "User ID"))
            {
                return; // Validation failed, response already sent
            }

            userService.userDelete(userId)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to delete user"));
        }
        catch (Exception exception)
        {
            logger.error("Error in deleteUser handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to delete user");
        }
    }

    /**
     * Authenticate a user and generate JWT token on success.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void authenticateUser(RoutingContext ctx)
    {
        try
        {
            var body = ctx.body().asJsonObject();

            if (!ValidationUtil.User.validateAuthentication(ctx, body))
            {
                return; // Validation failed, response already sent
            }

            var username = body.getString("username");

            var password = body.getString("password");

            userService.userAuthenticate(username, password)
                .onSuccess(authResult ->
                {
                    var authenticated = authResult.getBoolean("authenticated", false);

                    if (authenticated)
                    {
                        // Generate JWT token for successful authentication
                        var userId = authResult.getString("user_id");

                        var authenticatedUsername = authResult.getString("username");

                        var isActive = authResult.getBoolean("is_active", false);

                        var jwtToken = jwtUtil.generateToken(userId, authenticatedUsername, isActive);

                        if (jwtToken == null)
                        {
                            logger.error("Failed to generate JWT token for user: {}", authenticatedUsername);

                            ExceptionUtil.handleHttp(ctx, new Exception("Failed to generate JWT token"),
                                "Authentication successful but failed to generate token");

                            return;
                        }

                        // Add JWT token to response
                        var enhancedResult = authResult.copy()
                            .put("jwt_token", jwtToken)
                            .put("token_type", "Bearer")
                            .put("expires_in_hours", JWTUtil.getTokenExpiryHours())
                            .put("message", "Authentication successful - JWT token generated");

                        ResponseUtil.handleSuccess(ctx, enhancedResult);
                    }
                    else
                    {
                        // Authentication failed - no token generation
                        ResponseUtil.handleSuccess(ctx, authResult);
                    }
                })
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to authenticate user"));
        }
        catch (Exception exception)
        {
            logger.error("Error in authenticateUser handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to authenticate user");
        }
    }
}

