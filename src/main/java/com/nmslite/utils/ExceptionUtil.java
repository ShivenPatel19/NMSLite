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
public class ExceptionUtil
{

    private static final Logger logger = LoggerFactory.getLogger(ExceptionUtil.class);

    // ========================================
    // HTTP EXCEPTION HANDLING
    // ========================================

    /**
     * Handle HTTP exceptions with default message
     *
     * @param context Routing context
     * @param cause Exception cause
     */
    public static void handleHttp(RoutingContext context, Throwable cause)
    {
        handleHttp(context, cause, "Operation failed");
    }

    /**
     * Handle HTTP exceptions with custom default message
     *
     * @param context Routing context
     * @param cause Exception cause
     * @param defaultMessage Default error message if cause message is null
     */
    public static void handleHttp(RoutingContext context, Throwable cause, String defaultMessage)
    {
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
    // SUCCESS RESPONSE HANDLING
    // ========================================

    /**
     * Handle successful HTTP responses
     *
     * @param context Routing context
     * @param result Success result data
     */
    public static void handleSuccess(RoutingContext context, Object result)
    {
        JsonObject successResponse = new JsonObject()
            .put("success", true)
            .put("data", result)
            .put("timestamp", System.currentTimeMillis());

        context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(successResponse.encode());
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    /**
     * Map exception types to HTTP status codes
     * Extensible - add new mappings by modifying this method
     *
     * @param cause Exception cause
     * @return HTTP status code
     */
    private static int getStatusCode(Throwable cause)
    {
        if (cause == null)
        {
            return 500;
        }

        String message = cause.getMessage();

        if (message == null)
        {
            return 500;
        }

        String lowerMessage = message.toLowerCase();

        // 400 - Bad Request
        if (lowerMessage.contains("validation") ||
            lowerMessage.contains("invalid") ||
            lowerMessage.contains("required") ||
            lowerMessage.contains("missing") ||
            lowerMessage.contains("malformed"))
        {
            return 400;
        }

        // 401 - Unauthorized
        if (lowerMessage.contains("unauthorized") ||
            lowerMessage.contains("authentication") ||
            lowerMessage.contains("login"))
        {
            return 401;
        }

        // 403 - Forbidden
        if (lowerMessage.contains("forbidden") ||
            lowerMessage.contains("access denied") ||
            lowerMessage.contains("permission"))
        {
            return 403;
        }

        // 404 - Not Found
        if (lowerMessage.contains("not found") ||
            lowerMessage.contains("does not exist") ||
            lowerMessage.contains("no such"))
        {
            return 404;
        }

        // 409 - Conflict
        if (lowerMessage.contains("already exists") ||
            lowerMessage.contains("duplicate") ||
            lowerMessage.contains("conflict"))
        {
            return 409;
        }

        // 503 - Service Unavailable
        if (lowerMessage.contains("timeout") ||
            lowerMessage.contains("unreachable") ||
            lowerMessage.contains("connection") ||
            lowerMessage.contains("service unavailable"))
        {
            return 503;
        }

        // 500 - Internal Server Error (default)
        return 500;
    }

    /**
     * Extract meaningful error message from exception
     *
     * @param cause Exception cause
     * @param defaultMessage Default message if cause message is null
     * @return Error message
     */
    private static String getMessage(Throwable cause, String defaultMessage)
    {
        if (cause == null)
        {
            return defaultMessage;
        }

        String message = cause.getMessage();

        return (message != null && !message.trim().isEmpty()) ? message : defaultMessage;
    }

}
