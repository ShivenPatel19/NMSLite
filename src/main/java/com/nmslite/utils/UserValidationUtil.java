package com.nmslite.utils;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * UserValidationUtil - Common validation methods for User-related operations
 * 
 * This utility class provides reusable validation methods for all User handlers:
 * - User basic field validation
 * - Password validation
 * - Username validation
 * - String length validation
 * - UUID format validation
 * - Update field validation
 * 
 * All methods return true if validation passes, false if validation fails
 * (with HTTP response already sent to client)
 */
public class UserValidationUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(UserValidationUtil.class);
    
    /**
     * Validate basic user fields for creation
     * Validates: username, password, is_active
     */
    public static boolean validateUserBasicFields(RoutingContext ctx, JsonObject userData) {
        // 1. Required fields validation
        if (!CommonValidationUtil.validateRequiredFields(ctx, userData, "username", "password")) {
            return false;
        }

        String username = userData.getString("username");
        String password = userData.getString("password");

        // 2. Username format validation (basic rules)
        if (!validateUsernameFormat(ctx, username)) {
            return false;
        }

        // 3. Password strength validation (basic rules)
        if (!validatePasswordStrength(ctx, password)) {
            return false;
        }

        return true;
    }

    /**
     * Validate username format - min and max character length only
     */
    public static boolean validateUsernameFormat(RoutingContext ctx, String username) {
        if (username != null) {
            // Username should be between 8 and 100 characters
            if (username.length() < 8) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Username too short"),
                    "Username must be at least 8 characters long");
                return false;
            }
            if (username.length() > 100) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Username too long"),
                    "Username must be 100 characters or less");
                return false;
            }
        }
        return true;
    }

    /**
     * Validate password strength - min and max character length only
     */
    public static boolean validatePasswordStrength(RoutingContext ctx, String password) {
        if (password != null) {
            // Password should be between 8 and 100 characters
            if (password.length() < 8) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Password too short"),
                    "Password must be at least 8 characters long");
                return false;
            }
            if (password.length() > 100) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Password too long"),
                    "Password must be 100 characters or less");
                return false;
            }
        }
        return true;
    }

    /**
     * Validate user update fields
     * Used for updateUser endpoint
     */
    public static boolean validateUserUpdate(RoutingContext ctx, JsonObject requestBody) {
        // 1. Validate update fields - at least one field must be provided
        if (!CommonValidationUtil.validateUpdateFields(ctx, requestBody, "username", "password", "is_active")) {
            return false;
        }

        // 2. Format validation (if fields are provided)
        if (requestBody.containsKey("username")) {
            String username = requestBody.getString("username");
            // Username format validation
            if (!validateUsernameFormat(ctx, username)) {
                return false;
            }
        }

        if (requestBody.containsKey("password")) {
            String password = requestBody.getString("password");
            // Password strength validation
            if (!validatePasswordStrength(ctx, password)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Validate user authentication fields
     * Used for userAuthenticate endpoint
     */
    public static boolean validateUserAuthentication(RoutingContext ctx, JsonObject authData) {
        // Required fields validation
        return CommonValidationUtil.validateRequiredFields(ctx, authData, "username", "password");
    }
}
