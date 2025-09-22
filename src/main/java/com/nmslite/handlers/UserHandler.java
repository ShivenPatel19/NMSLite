package com.nmslite.handlers;

import com.nmslite.services.UserService;
import com.nmslite.utils.ExceptionUtil;
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
public class UserHandler {

    private static final Logger logger = LoggerFactory.getLogger(UserHandler.class);
    private final UserService userService;

    public UserHandler(UserService userService) {
        this.userService = userService;
    }

    // ========================================
    // USER CRUD OPERATIONS
    // ========================================

    public void getUsers(RoutingContext ctx) {
        // Get includeInactive parameter from query string (default: false)
        boolean includeInactive = "true".equals(ctx.request().getParam("includeInactive"));

        userService.userList(includeInactive, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, new JsonObject().put("users", ar.result()));
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get users");
            }
        });
    }

    public void createUser(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        
        // Validate required fields
        if (!ExceptionUtil.validateRequiredFields(ctx, body, "username", "password")) {
            return; // Response already sent by validateRequiredFields
        }

        JsonObject userData = new JsonObject()
            .put("username", body.getString("username"))
            .put("password", body.getString("password"))
            .put("is_active", body.getBoolean("is_active", true));

        userService.userCreate(userData, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to create user");
            }
        });
    }

    public void updateUser(RoutingContext ctx) {
        String userId = ctx.pathParam("id");
        JsonObject body = ctx.body().asJsonObject();

        // Validate update fields - at least one field must be provided
        if (!ExceptionUtil.validateUpdateFields(ctx, body, "username", "password", "is_active")) {
            return; // Response already sent by validateUpdateFields
        }

        userService.userUpdate(userId, body, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update user");
            }
        });
    }



    public void deleteUser(RoutingContext ctx) {
        String userId = ctx.pathParam("id");

        userService.userDelete(userId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete user");
            }
        });
    }



    // ========================================
    // PASSWORD MANAGEMENT
    // ========================================

    public void changeUserPassword(RoutingContext ctx) {
        String userId = ctx.pathParam("id");
        JsonObject body = ctx.body().asJsonObject();
        
        // Validate required fields
        if (!ExceptionUtil.validateRequiredFields(ctx, body, "oldPassword", "newPassword")) {
            return; // Response already sent by validateRequiredFields
        }

        String oldPassword = body.getString("oldPassword");
        String newPassword = body.getString("newPassword");

        userService.userChangePassword(userId, oldPassword, newPassword, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to change password");
            }
        });
    }


}
