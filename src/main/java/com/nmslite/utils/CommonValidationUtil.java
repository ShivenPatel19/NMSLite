package com.nmslite.utils;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * CommonValidationUtil - Common validation methods used across multiple validation utilities
 *
 * This utility class provides reusable validation methods that are used by multiple
 * component-specific validation utilities (DeviceValidationUtil, UserValidationUtil, etc.):
 *
 * - UUID format validation (single and multiple)
 * - Port range validation (1-65535)
 * - String length validation (generic)
 * - Request body validation (null/empty checks)
 * - Percentage range validation (0-100)
 *
 * All methods return true if validation passes, false if validation fails
 * (with HTTP response already sent to client)
 *
 * This reduces code duplication across component-specific validation utilities.
 */
public class CommonValidationUtil
{

    private static final Logger logger = LoggerFactory.getLogger(CommonValidationUtil.class);

    // ========================================
    // PORT RANGE VALIDATION
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

    // ========================================
    // STRING LENGTH VALIDATION
    // ========================================

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

    // ========================================
    // REQUEST BODY VALIDATION
    // ========================================

    /**
     * Validate request body is not null or empty
     *
     * @param ctx Routing context
     * @param requestBody JSON request body to validate
     * @return true if valid request body, false otherwise (response already sent)
     */
    public static boolean validateRequestBody(RoutingContext ctx, JsonObject requestBody)
    {
        if (requestBody == null)
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Request body is required"),
                "Request body cannot be null");

            return false;
        }

        if (requestBody.isEmpty())
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Request body is empty"),
                "Request body cannot be empty");

            return false;
        }

        return true;
    }

    // ========================================
    // PERCENTAGE RANGE VALIDATION
    // ========================================

    /**
     * Validate percentage range (0-100) - Standard single method for both integer and decimal
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

    // ========================================
    // REQUIRED FIELDS VALIDATION
    // ========================================

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

    // ========================================
    // UPDATE FIELDS VALIDATION
    // ========================================

    /**
     * Validate update request - ensures at least one valid field is provided and validates field constraints
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

        // Check if at least one allowed field is provided
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

        // Validate that provided fields are not null or empty strings
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

                // For string fields, check if empty
                if (value instanceof String && ((String) value).trim().isEmpty())
                {
                    ExceptionUtil.handleHttp(context, new IllegalArgumentException(field + " cannot be empty"));

                    return false;
                }
            }
        }

        return true;
    }

    // ========================================
    // NUMERIC RANGE VALIDATION
    // ========================================

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
     * Validate decimal range for update fields
     *
     * @param context Routing context
     * @param json Request JSON
     * @param field Field name to validate
     * @param min Minimum value (inclusive)
     * @param max Maximum value (inclusive)
     * @return true if validation passes, false otherwise (response already sent)
     */
    public static boolean validateDecimalRange(RoutingContext context, JsonObject json, String field, double min, double max)
    {
        if (json.containsKey(field))
        {
            Double value = json.getDouble(field);

            if (value != null && (value < min || value > max))
            {
                ExceptionUtil.handleHttp(context, new IllegalArgumentException(
                    field + " must be between " + min + " and " + max));

                return false;
            }
        }

        return true;
    }

    // ========================================
    // PATH PARAMETER VALIDATION
    // ========================================

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

    // ========================================
    // TIMEOUT AND RETRY VALIDATION
    // ========================================

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

}
