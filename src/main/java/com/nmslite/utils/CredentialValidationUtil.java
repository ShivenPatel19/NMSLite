package com.nmslite.utils;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * CredentialValidationUtil - Common validation methods for Credential-related operations
 *
 * This utility class provides reusable validation methods for all Credential handlers:
 * - Credential profile basic field validation
 * - String length validation
 * - UUID format validation
 * - Update field validation
 *
 * All methods return true if validation passes, false if validation fails
 * (with HTTP response already sent to client)
 */
public class CredentialValidationUtil
{

    private static final Logger logger = LoggerFactory.getLogger(CredentialValidationUtil.class);

    /**
     * Validate basic credential profile fields for creation
     * Validates: profile_name, username, password
     *
     * @param ctx routing context
     * @param credentialData credential data to validate
     * @return true if validation passes, false otherwise
     */
    public static boolean validateCredentialBasicFields(RoutingContext ctx, JsonObject credentialData)
    {
        // 1. Required fields validation
        if (!CommonValidationUtil.validateRequiredFields(ctx, credentialData, "profile_name", "username", "password"))
        {
            return false;
        }

        String profileName = credentialData.getString("profile_name");

        String username = credentialData.getString("username");

        String password = credentialData.getString("password");

        return true;
    }

    /**
     * Validate credential profile update fields
     * Used for updateCredentialProfile endpoint
     *
     * @param ctx routing context
     * @param requestBody request body to validate
     * @return true if validation passes, false otherwise
     */
    public static boolean validateCredentialUpdate(RoutingContext ctx, JsonObject requestBody)
    {
        // 1. Validate update fields - at least one field must be provided
        if (!CommonValidationUtil.validateUpdateFields(ctx, requestBody, "profile_name", "username", "password"))
        {
            return false;
        }

        // 2. Field validation (if fields are provided)
        if (requestBody.containsKey("profile_name"))
        {
            String profileName = requestBody.getString("profile_name");

            if (profileName != null && profileName.length() > 100)
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Profile name too long"),
                    "Profile name must be 100 characters or less");

                return false;
            }
        }

        if (requestBody.containsKey("username"))
        {
            String username = requestBody.getString("username");

            if (username != null && username.length() > 100)
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Username too long"),
                    "Username must be 100 characters or less");

                return false;
            }
        }

        if (requestBody.containsKey("password"))
        {
            String password = requestBody.getString("password");

            if (password != null && password.length() > 500)
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Password too long"),
                    "Password must be 500 characters or less");

                return false;
            }
        }

        return true;
    }

}
