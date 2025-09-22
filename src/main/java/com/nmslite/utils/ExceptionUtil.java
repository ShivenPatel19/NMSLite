package com.nmslite.utils;

import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExceptionUtil - Generic Exception Handling Utility
 * 
 * Provides consistent error handling across all verticles and APIs
 * - HTTP response formatting
 * - Event Bus error handling
 * - Success response formatting
 * - Extensible status code mapping
 */
public class ExceptionUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(ExceptionUtil.class);
    
    // ========================================
    // HTTP EXCEPTION HANDLING
    // ========================================
    
    /**
     * Handle HTTP exceptions with default message
     * @param context Routing context
     * @param cause Exception cause
     */
    public static void handleHttp(RoutingContext context, Throwable cause) {
        handleHttp(context, cause, "Operation failed");
    }
    
    /**
     * Handle HTTP exceptions with custom default message
     * @param context Routing context
     * @param cause Exception cause
     * @param defaultMessage Default error message if cause message is null
     */
    public static void handleHttp(RoutingContext context, Throwable cause, String defaultMessage) {
        int statusCode = getStatusCode(cause);
        String message = getMessage(cause, defaultMessage);
        
        JsonObject errorResponse = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("status_code", statusCode)
            .put("timestamp", System.currentTimeMillis());
        
        context.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(errorResponse.encode());
                
        logger.error("HTTP Error [{}]: {}", statusCode, message, cause);
    }
    
    // ========================================
    // EVENT BUS EXCEPTION HANDLING
    // ========================================
    
    /**
     * Handle Event Bus exceptions with default message
     * @param message Event bus message
     * @param cause Exception cause
     */
    public static void handleEventBus(Message<?> message, Throwable cause) {
        handleEventBus(message, cause, "Service operation failed");
    }
    
    /**
     * Handle Event Bus exceptions with custom default message
     * @param message Event bus message
     * @param cause Exception cause
     * @param defaultMessage Default error message if cause message is null
     */
    public static void handleEventBus(Message<?> message, Throwable cause, String defaultMessage) {
        int code = getStatusCode(cause);
        String msg = getMessage(cause, defaultMessage);
        
        JsonObject errorResponse = new JsonObject()
            .put("success", false)
            .put("error", msg)
            .put("status_code", code)
            .put("timestamp", System.currentTimeMillis());
        
        message.fail(code, errorResponse.encode());
        logger.error("EventBus Error [{}]: {}", code, msg, cause);
    }
    
    // ========================================
    // SUCCESS RESPONSE HANDLING
    // ========================================
    
    /**
     * Handle successful HTTP responses
     * @param context Routing context
     * @param result Success result data
     */
    public static void handleSuccess(RoutingContext context, Object result) {
        JsonObject successResponse = new JsonObject()
            .put("success", true)
            .put("data", result)
            .put("timestamp", System.currentTimeMillis());
        
        context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(successResponse.encode());
    }
    
    /**
     * Handle successful HTTP responses with custom status code
     * @param context Routing context
     * @param result Success result data
     * @param statusCode HTTP status code (e.g., 201 for created)
     */
    public static void handleSuccess(RoutingContext context, Object result, int statusCode) {
        JsonObject successResponse = new JsonObject()
            .put("success", true)
            .put("data", result)
            .put("timestamp", System.currentTimeMillis());
        
        context.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(successResponse.encode());
    }
    
    // ========================================
    // UTILITY METHODS
    // ========================================
    
    /**
     * Map exception types to HTTP status codes
     * Extensible - add new mappings by modifying this method
     * @param cause Exception cause
     * @return HTTP status code
     */
    private static int getStatusCode(Throwable cause) {
        if (cause == null) return 500;
        
        String message = cause.getMessage();
        if (message == null) return 500;
        
        String lowerMessage = message.toLowerCase();
        
        // 400 - Bad Request
        if (lowerMessage.contains("validation") || 
            lowerMessage.contains("invalid") || 
            lowerMessage.contains("required") ||
            lowerMessage.contains("missing") ||
            lowerMessage.contains("malformed")) {
            return 400;
        }
        
        // 401 - Unauthorized
        if (lowerMessage.contains("unauthorized") || 
            lowerMessage.contains("authentication") ||
            lowerMessage.contains("login")) {
            return 401;
        }
        
        // 403 - Forbidden
        if (lowerMessage.contains("forbidden") || 
            lowerMessage.contains("access denied") ||
            lowerMessage.contains("permission")) {
            return 403;
        }
        
        // 404 - Not Found
        if (lowerMessage.contains("not found") || 
            lowerMessage.contains("does not exist") ||
            lowerMessage.contains("no such")) {
            return 404;
        }
        
        // 409 - Conflict
        if (lowerMessage.contains("already exists") || 
            lowerMessage.contains("duplicate") ||
            lowerMessage.contains("conflict")) {
            return 409;
        }
        
        // 503 - Service Unavailable
        if (lowerMessage.contains("timeout") || 
            lowerMessage.contains("unreachable") ||
            lowerMessage.contains("connection") ||
            lowerMessage.contains("service unavailable")) {
            return 503;
        }
        
        // 500 - Internal Server Error (default)
        return 500;
    }
    
    /**
     * Extract meaningful error message from exception
     * @param cause Exception cause
     * @param defaultMessage Default message if cause message is null
     * @return Error message
     */
    private static String getMessage(Throwable cause, String defaultMessage) {
        if (cause == null) return defaultMessage;
        
        String message = cause.getMessage();
        return (message != null && !message.trim().isEmpty()) ? message : defaultMessage;
    }
    
    // ========================================
    // VALIDATION HELPERS
    // ========================================
    
    /**
     * Validate required fields in JSON request
     * @param context Routing context
     * @param json Request JSON
     * @param requiredFields Array of required field names
     * @return true if all fields present, false otherwise (response already sent)
     */
    public static boolean validateRequiredFields(RoutingContext context, JsonObject json, String... requiredFields) {
        for (String field : requiredFields) {
            if (!json.containsKey(field) || json.getValue(field) == null) {
                handleHttp(context, new IllegalArgumentException("Missing required field: " + field));
                return false;
            }
        }
        return true;
    }

    /**
     * Validate update request - ensures at least one valid field is provided and validates field constraints
     * @param context Routing context
     * @param json Request JSON
     * @param allowedFields Array of allowed field names for update
     * @return true if validation passes, false otherwise (response already sent)
     */
    public static boolean validateUpdateFields(RoutingContext context, JsonObject json, String... allowedFields) {
        if (json == null || json.isEmpty()) {
            handleHttp(context, new IllegalArgumentException("Request body is required"));
            return false;
        }

        // Check if at least one allowed field is provided
        boolean hasValidField = false;
        for (String field : allowedFields) {
            if (json.containsKey(field)) {
                hasValidField = true;
                break;
            }
        }

        if (!hasValidField) {
            String allowedFieldsList = String.join(", ", allowedFields);
            handleHttp(context, new IllegalArgumentException(
                "At least one field must be provided: " + allowedFieldsList));
            return false;
        }

        // Validate that provided fields are not null or empty strings
        for (String field : allowedFields) {
            if (json.containsKey(field)) {
                Object value = json.getValue(field);
                if (value == null) {
                    handleHttp(context, new IllegalArgumentException(field + " cannot be null"));
                    return false;
                }
                // For string fields, check if empty
                if (value instanceof String && ((String) value).trim().isEmpty()) {
                    handleHttp(context, new IllegalArgumentException(field + " cannot be empty"));
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Validate numeric range for update fields
     * @param context Routing context
     * @param json Request JSON
     * @param field Field name to validate
     * @param min Minimum value (inclusive)
     * @param max Maximum value (inclusive)
     * @return true if validation passes, false otherwise (response already sent)
     */
    public static boolean validateNumericRange(RoutingContext context, JsonObject json, String field, int min, int max) {
        if (json.containsKey(field)) {
            Integer value = json.getInteger(field);
            if (value != null && (value < min || value > max)) {
                handleHttp(context, new IllegalArgumentException(
                    field + " must be between " + min + " and " + max));
                return false;
            }
        }
        return true;
    }

    /**
     * Validate decimal range for update fields
     * @param context Routing context
     * @param json Request JSON
     * @param field Field name to validate
     * @param min Minimum value (inclusive)
     * @param max Maximum value (inclusive)
     * @return true if validation passes, false otherwise (response already sent)
     */
    public static boolean validateDecimalRange(RoutingContext context, JsonObject json, String field, double min, double max) {
        if (json.containsKey(field)) {
            Double value = json.getDouble(field);
            if (value != null && (value < min || value > max)) {
                handleHttp(context, new IllegalArgumentException(
                    field + " must be between " + min + " and " + max));
                return false;
            }
        }
        return true;
    }
}
