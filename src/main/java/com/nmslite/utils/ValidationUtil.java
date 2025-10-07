package com.nmslite.utils;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * ValidationUtil - Centralized validation utility for all API requests
 *
 * Organized with nested static classes for domain-specific validations:
 * - Common validations (outer class): Shared validation methods
 * - User: User-related validations
 * - Device: Device-related validations
 * - Credential: Credential profile validations
 * - DiscoveryProfile: Discovery profile validations
 *
 * All methods return true if validation passes, false if validation fails
 * (with HTTP response already sent to client)
 */
public class ValidationUtil
{
    
    // ========================================
    // COMMON VALIDATIONS (OUTER CLASS)
    // ========================================

    /**
     * Validate port range (database CHECK constraint: port BETWEEN 1 AND 65535)
     *
     * @param ctx Routing context
     * @param port Port number to validate
     * @return true if valid port range, false otherwise (response already sent)
     */
    public static boolean validatePortRange(RoutingContext ctx, Integer port)
    {
        if (port != null && (port < 1 || port > 65535))
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid port range"),
                "Port must be between 1 and 65535");

            return false;
        }

        return true;
    }

    /**
     * Validate string length
     *
     * @param ctx Routing context
     * @param value String value to validate
     * @param maxLength Maximum allowed length
     * @param fieldName Name of the field for error message
     * @return true if valid string length, false otherwise (response already sent)
     */
    public static boolean validateStringLength(RoutingContext ctx, String value, int maxLength, String fieldName)
    {
        if (value != null && value.length() > maxLength)
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException(fieldName + " too long"),
                fieldName + " must be " + maxLength + " characters or less");

            return false;
        }

        return true;
    }

    /**
     * Validate percentage range (0-100)
     *
     * @param ctx Routing context
     * @param value Percentage value to validate (Integer or Double)
     * @param fieldName Name of the field for error message
     * @return true if valid percentage range, false otherwise (response already sent)
     */
    public static boolean validatePercentageRange(RoutingContext ctx, Number value, String fieldName)
    {
        if (value != null)
        {
            double doubleValue = value.doubleValue();

            if (doubleValue < 0.0 || doubleValue > 100.0)
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid " + fieldName + " percentage"),
                    fieldName + " must be between 0 and 100");

                return false;
            }
        }

        return true;
    }

    /**
     * Validate required fields in JSON request
     *
     * @param context Routing context
     * @param json Request JSON
     * @param requiredFields Array of required field names
     * @return true if all fields present, false otherwise (response already sent)
     */
    public static boolean validateRequiredFields(RoutingContext context, JsonObject json, String... requiredFields)
    {
        for (String field : requiredFields)
        {
            if (!json.containsKey(field) || json.getValue(field) == null)
            {
                ExceptionUtil.handleHttp(context, new IllegalArgumentException("Missing required field: " + field));

                return false;
            }
        }

        return true;
    }

    /**
     * Validate update request - ensures at least one valid field is provided
     *
     * @param context Routing context
     * @param json Request JSON
     * @param allowedFields Array of allowed field names for update
     * @return true if validation passes, false otherwise (response already sent)
     */
    public static boolean validateUpdateFields(RoutingContext context, JsonObject json, String... allowedFields)
    {
        if (json == null || json.isEmpty())
        {
            ExceptionUtil.handleHttp(context, new IllegalArgumentException("Request body is required"));

            return false;
        }

        boolean hasValidField = false;

        for (String field : allowedFields)
        {
            if (json.containsKey(field))
            {
                hasValidField = true;

                break;
            }
        }

        if (!hasValidField)
        {
            String allowedFieldsList = String.join(", ", allowedFields);

            ExceptionUtil.handleHttp(context, new IllegalArgumentException(
                "At least one field must be provided: " + allowedFieldsList));

            return false;
        }

        for (String field : allowedFields)
        {
            if (json.containsKey(field))
            {
                Object value = json.getValue(field);

                if (value == null)
                {
                    ExceptionUtil.handleHttp(context, new IllegalArgumentException(field + " cannot be null"));

                    return false;
                }

                if (value instanceof String && ((String) value).trim().isEmpty())
                {
                    ExceptionUtil.handleHttp(context, new IllegalArgumentException(field + " cannot be empty"));

                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Validate numeric range for update fields
     *
     * @param context Routing context
     * @param json Request JSON
     * @param field Field name to validate
     * @param min Minimum value (inclusive)
     * @param max Maximum value (inclusive)
     * @return true if validation passes, false otherwise (response already sent)
     */
    public static boolean validateNumericRange(RoutingContext context, JsonObject json, String field, int min, int max)
    {
        if (json.containsKey(field))
        {
            Integer value = json.getInteger(field);

            if (value != null && (value < min || value > max))
            {
                ExceptionUtil.handleHttp(context, new IllegalArgumentException(
                    field + " must be between " + min + " and " + max));

                return false;
            }
        }

        return true;
    }

    /**
     * Validate UUID path parameter
     *
     * @param context Routing context
     * @param uuidValue UUID string to validate
     * @param parameterName Name of the parameter for error messages
     * @return true if valid UUID, false otherwise (response already sent)
     */
    public static boolean validatePathParameterUUID(RoutingContext context, String uuidValue, String parameterName)
    {
        if (uuidValue == null || uuidValue.trim().isEmpty())
        {
            ExceptionUtil.handleHttp(context, new IllegalArgumentException(parameterName + " is required"),
                parameterName + " path parameter is required");

            return false;
        }

        try
        {
            UUID.fromString(uuidValue);

            return true;
        }
        catch (IllegalArgumentException exception)
        {
            ExceptionUtil.handleHttp(context, new IllegalArgumentException("Invalid UUID format"),
                parameterName + " must be a valid UUID");

            return false;
        }
    }

    /**
     * Validate timeout range (0-600 seconds)
     *
     * @param ctx Routing context
     * @param timeoutSeconds Timeout value to validate
     * @return true if valid timeout range, false otherwise (response already sent)
     */
    public static boolean validateTimeoutRange(RoutingContext ctx, Integer timeoutSeconds)
    {
        if (timeoutSeconds != null && (timeoutSeconds < 0 || timeoutSeconds > 600))
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid timeout range"),
                "Timeout must be between 0 and 600 seconds");

            return false;
        }

        return true;
    }

    /**
     * Validate retry count range (0-5)
     *
     * @param ctx Routing context
     * @param retryCount Retry count value to validate
     * @return true if valid retry count range, false otherwise (response already sent)
     */
    public static boolean validateRetryCountRange(RoutingContext ctx, Integer retryCount)
    {
        if (retryCount != null && (retryCount < 0 || retryCount > 5))
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid retry count"),
                "Retry count must be between 0 and 5");

            return false;
        }

        return true;
    }

    // ========================================
    // NESTED STATIC CLASSES FOR DOMAIN-SPECIFIC VALIDATIONS
    // ========================================

    /**
     * User - User-related validations
     */
    public static class User
    {

        /**
         * Validate basic user fields for creation
         *
         * @param ctx routing context
         * @param userData user data to validate
         * @return true if validation passes, false otherwise
         */
        public static boolean validateCreate(RoutingContext ctx, JsonObject userData)
        {
            if (!validateRequiredFields(ctx, userData, "username", "password"))
            {
                return false;
            }

            String username = userData.getString("username");

            String password = userData.getString("password");

            if (!validateUsernameFormat(ctx, username))
            {
                return false;
            }

            if (!validatePasswordStrength(ctx, password))
            {
                return false;
            }

            return true;
        }

        /**
         * Validate username format (private helper method)
         *
         * @param ctx routing context
         * @param username username to validate
         * @return true if validation passes, false otherwise
         */
        private static boolean validateUsernameFormat(RoutingContext ctx, String username)
        {
            if (username != null)
            {
                if (username.length() < 8)
                {
                    ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Username too short"),
                        "Username must be at least 8 characters long");

                    return false;
                }

                if (username.length() > 100)
                {
                    ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Username too long"),
                        "Username must be 100 characters or less");

                    return false;
                }
            }

            return true;
        }

        /**
         * Validate password strength (private helper method)
         *
         * @param ctx routing context
         * @param password password to validate
         * @return true if validation passes, false otherwise
         */
        private static boolean validatePasswordStrength(RoutingContext ctx, String password)
        {
            if (password != null)
            {
                if (password.length() < 8)
                {
                    ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Password too short"),
                        "Password must be at least 8 characters long");

                    return false;
                }

                if (password.length() > 100)
                {
                    ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Password too long"),
                        "Password must be 100 characters or less");

                    return false;
                }
            }

            return true;
        }

        /**
         * Validate user update fields
         *
         * @param ctx routing context
         * @param requestBody request body to validate
         * @return true if validation passes, false otherwise
         */
        public static boolean validateUpdate(RoutingContext ctx, JsonObject requestBody)
        {
            if (!validateUpdateFields(ctx, requestBody, "username", "password", "is_active"))
            {
                return false;
            }

            if (requestBody.containsKey("username"))
            {
                String username = requestBody.getString("username");

                if (!validateUsernameFormat(ctx, username))
                {
                    return false;
                }
            }

            if (requestBody.containsKey("password"))
            {
                String password = requestBody.getString("password");

                if (!validatePasswordStrength(ctx, password))
                {
                    return false;
                }
            }

            return true;
        }

        /**
         * Validate user authentication fields
         *
         * @param ctx routing context
         * @param authData authentication data to validate
         * @return true if validation passes, false otherwise
         */
        public static boolean validateAuthentication(RoutingContext ctx, JsonObject authData)
        {
            return validateRequiredFields(ctx, authData, "username", "password");
        }

    }

    /**
     * Device - Device-related validations
     */
    public static class Device
    {

        /**
         * Validate device update fields
         *
         * @param ctx routing context
         * @param requestBody request body to validate
         * @return true if validation passes, false otherwise
         */
        public static boolean validateUpdate(RoutingContext ctx, JsonObject requestBody)
        {
            if (!validateUpdateFields(ctx, requestBody, "device_name", "port",
                "polling_interval_seconds", "timeout_seconds", "retry_count",
                "alert_threshold_cpu", "alert_threshold_memory", "alert_threshold_disk"))
            {
                return false;
            }

            if (!validateNumericRange(ctx, requestBody, "port", 1, 65535))
            {
                return false;
            }

            if (!validateNumericRange(ctx, requestBody, "polling_interval_seconds", 60, 86400))
            {
                return false;
            }

            if (!validateTimeoutRange(ctx, requestBody.getInteger("timeout_seconds")))
            {
                return false;
            }

            if (!validateRetryCountRange(ctx, requestBody.getInteger("retry_count")))
            {
                return false;
            }

            if (!validatePercentageRange(ctx, requestBody.getInteger("alert_threshold_cpu"), "alert_threshold_cpu"))
            {
                return false;
            }

            if (!validatePercentageRange(ctx, requestBody.getInteger("alert_threshold_memory"), "alert_threshold_memory"))
            {
                return false;
            }

            if (!validatePercentageRange(ctx, requestBody.getInteger("alert_threshold_disk"), "alert_threshold_disk"))
            {
                return false;
            }

            return true;
        }

    }

    /**
     * Credential - Credential profile validations
     */
    public static class Credential
    {

        /**
         * Validate credential profile creation
         *
         * @param ctx routing context
         * @param credentialData credential data to validate
         * @return true if validation passes, false otherwise
         */
        public static boolean validateCreate(RoutingContext ctx, JsonObject credentialData)
        {
            return validateRequiredFields(ctx, credentialData, "profile_name", "username", "password");
        }

        /**
         * Validate credential profile update
         *
         * @param ctx routing context
         * @param requestBody request body to validate
         * @return true if validation passes, false otherwise
         */
        public static boolean validateUpdate(RoutingContext ctx, JsonObject requestBody)
        {
            if (!validateUpdateFields(ctx, requestBody, "profile_name", "username", "password"))
            {
                return false;
            }

            if (!validateStringLength(ctx, requestBody.getString("profile_name"), 100, "profile_name"))
            {
                return false;
            }

            if (!validateStringLength(ctx, requestBody.getString("username"), 100, "username"))
            {
                return false;
            }

            if (!validateStringLength(ctx, requestBody.getString("password"), 500, "password"))
            {
                return false;
            }

            return true;
        }

    }

    /**
     * DiscoveryProfile - Discovery profile validations
     */
    public static class DiscoveryProfile
    {

        /**
         * Validate discovery profile creation
         *
         * @param ctx routing context
         * @param profileData profile data to validate
         * @return true if validation passes, false otherwise
         */
        public static boolean validateCreate(RoutingContext ctx, JsonObject profileData)
        {
            if (!validateRequiredFields(ctx, profileData,
                "discovery_name", "ip_address", "device_type_id", "credential_profile_ids", "protocol"))
            {
                return false;
            }

            if (!validateCredentialProfileIds(ctx, profileData))
            {
                return false;
            }

            if (!validatePortRange(ctx, profileData.getInteger("port")))
            {
                return false;
            }

            String ipAddress = profileData.getString("ip_address");

            Boolean isRange = profileData.getBoolean("is_range", false);

            return validateIPFormat(ctx, ipAddress, isRange);
        }

        /**
         * Validate credential profile IDs array
         *
         * @param ctx routing context
         * @param profileData profile data containing credential_profile_ids
         * @return true if validation passes, false otherwise
         */
        private static boolean validateCredentialProfileIds(RoutingContext ctx, JsonObject profileData)
        {
            Object credentialProfileIdsObj = profileData.getValue("credential_profile_ids");

            if (!(credentialProfileIdsObj instanceof JsonArray))
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid credential_profile_ids format"),
                    "credential_profile_ids must be an array of UUID strings");

                return false;
            }

            JsonArray credentialProfileIds = (JsonArray) credentialProfileIdsObj;

            if (credentialProfileIds.isEmpty())
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Empty credential_profile_ids array"),
                    "credential_profile_ids array must contain at least one credential profile ID");

                return false;
            }

            if (credentialProfileIds.size() > 10)
            {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Too many credential profiles"),
                    "credential_profile_ids array cannot contain more than 10 credential profiles");

                return false;
            }

            return true;
        }

        /**
         * Validate IP address format
         *
         * @param ctx routing context
         * @param ipAddress IP address to validate
         * @param isRange whether this is an IP range
         * @return true if validation passes, false otherwise
         */
        private static boolean validateIPFormat(RoutingContext ctx, String ipAddress, Boolean isRange)
        {
            try
            {
                IPRangeUtil.parseIPRange(ipAddress, isRange != null && isRange);

                return true;
            }
            catch (IllegalArgumentException exception)
            {
                ExceptionUtil.handleHttp(ctx, exception, "Invalid IP address format: " + exception.getMessage());

                return false;
            }
        }

    }

}

