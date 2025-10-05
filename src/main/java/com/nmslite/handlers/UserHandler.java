package com.nmslite.handlers;

import com.nmslite.services.UserService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.JWTUtil;

import com.nmslite.utils.UserValidationUtil;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * UserHandler - Handles all user-related HTTP requests
 *
 * This handler manages:
 * - User CRUD operations
 * - User authentication
 * - Password management
 * - User status management
 *
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

    // ========================================
    // USER CRUD OPERATIONS
    // ========================================

    /**
     * Get all users with optional filter for inactive users.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getUsers(RoutingContext ctx)
    {
        // ===== QUERY PARAMETER VALIDATION =====
        String includeInactiveParam = ctx.request().getParam("includeInactive");

        boolean includeInactive = false;

        if (includeInactiveParam != null)
        {
            if ("true".equalsIgnoreCase(includeInactiveParam) || "false".equalsIgnoreCase(includeInactiveParam))
            {
                includeInactive = "true".equalsIgnoreCase(includeInactiveParam);
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid includeInactive parameter"),
                    "includeInactive parameter must be 'true' or 'false'");

                return;
            }
        }

        userService.userList(includeInactive, ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, new JsonObject().put("users", ar.result()));
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get users");
            }
        });
    }

    /**
     * Create a new user.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void createUser(RoutingContext ctx)
    {
        JsonObject body = ctx.body().asJsonObject();

        if (!UserValidationUtil.validateUserBasicFields(ctx, body))
        {
            return; // Validation failed, response already sent
        }

        JsonObject userData = new JsonObject()
            .put("username", body.getString("username"))
            .put("password", body.getString("password"))
            .put("is_active", body.getBoolean("is_active", true));

        userService.userCreate(userData, ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to create user");
            }
        });
    }

    /**
     * Update an existing user.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void updateUser(RoutingContext ctx)
    {
        String userId = ctx.pathParam("id");

        JsonObject body = ctx.body().asJsonObject();

        // 1. Validate path parameter
        if (userId == null || userId.trim().isEmpty())
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("User ID is required"),
                "User ID path parameter is required");

            return;
        }

        try
        {
            java.util.UUID.fromString(userId);
        }
        catch (IllegalArgumentException exception)
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid UUID format"),
                "User ID must be a valid UUID");

            return;
        }

        // 2. Validate user update fields
        if (!UserValidationUtil.validateUserUpdate(ctx, body))
        {
            return; // Validation failed, response already sent
        }

        userService.userUpdate(userId, body, ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update user");
            }
        });
    }

    /**
     * Delete a user.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void deleteUser(RoutingContext ctx)
    {
        String userId = ctx.pathParam("id");

        // ===== PATH PARAMETER VALIDATION =====
        if (userId == null || userId.trim().isEmpty())
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("User ID is required"),
                "User ID path parameter is required");

            return;
        }

        try
        {
            java.util.UUID.fromString(userId);
        }
        catch (IllegalArgumentException exception)
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid UUID format"),
                "User ID must be a valid UUID");

            return;
        }

        userService.userDelete(userId, ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete user");
            }
        });
    }

    // ========================================
    // USER AUTHENTICATION
    // ========================================

    /**
     * Authenticate a user and generate JWT token on success.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void authenticateUser(RoutingContext ctx)
    {
        JsonObject body = ctx.body().asJsonObject();

        if (!UserValidationUtil.validateUserAuthentication(ctx, body))
        {
            return; // Validation failed, response already sent
        }

        String username = body.getString("username");

        String password = body.getString("password");

        userService.userAuthenticate(username, password, ar ->
        {
            if (ar.succeeded())
            {
                JsonObject authResult = ar.result();

                boolean authenticated = authResult.getBoolean("authenticated", false);

                if (authenticated)
                {
                    // Generate JWT token for successful authentication
                    String userId = authResult.getString("user_id");

                    String authenticatedUsername = authResult.getString("username");

                    boolean isActive = authResult.getBoolean("is_active", false);

                    try
                    {
                        String jwtToken = jwtUtil.generateToken(userId, authenticatedUsername, isActive);

                        // Add JWT token to response
                        JsonObject enhancedResult = authResult.copy()
                            .put("jwt_token", jwtToken)
                            .put("token_type", "Bearer")
                            .put("expires_in_hours", JWTUtil.getTokenExpiryHours())
                            .put("message", "Authentication successful - JWT token generated");

                        logger.info("✅ JWT token generated for user: {}", authenticatedUsername);

                        ExceptionUtil.handleSuccess(ctx, enhancedResult);

                    }
                    catch (Exception exception)
                    {
                        logger.error("❌ Failed to generate JWT token for user: {}", authenticatedUsername, exception);

                        ExceptionUtil.handleHttp(ctx, exception, "Authentication successful but failed to generate token");
                    }
                }
                else
                {
                    // Authentication failed - no token generation
                    ExceptionUtil.handleSuccess(ctx, authResult);
                }
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to authenticate user");
            }
        });
    }
}
