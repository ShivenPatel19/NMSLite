package com.nmslite.utils;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * ResponseUtil - HTTP Response Utility

 * Provides consistent HTTP response formatting across all APIs
 * - Success response formatting with data
 * - Failure/error response formatting with error messages
 * - Standardized JSON structure
 * - Timestamp inclusion
 * - Automatic status code handling
 */
public class ResponseUtil
{

    private static final Logger logger = LoggerFactory.getLogger(ResponseUtil.class);

    /**
     * Handle successful HTTP responses
     *
     * @param context Routing context
     * @param result Success result data
     */
    public static void handleSuccess(RoutingContext context, Object result)
    {
        var successResponse = new JsonObject()
            .put("success", true)
            .put("data", result)
            .put("timestamp", System.currentTimeMillis());

        context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(successResponse.encode());
    }

    /**
     * Handle failed HTTP responses with default message
     *
     * @param context Routing context
     * @param cause Exception/error cause
     */
    public static void  handleFailure(RoutingContext context, Throwable cause)
    {
        handleFailure(context, cause, "Operation failed");
    }

    /**
     * Handle failed HTTP responses with custom default message
     *
     * @param context Routing context
     * @param cause Exception/error cause
     * @param defaultMessage Default error message if cause message is null
     */
    public static void handleFailure(RoutingContext context, Throwable cause, String defaultMessage)
    {
        var message = getMessage(cause, defaultMessage);

        var errorResponse = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("timestamp", System.currentTimeMillis());

        context.response()
            .setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(errorResponse.encode());

        logger.error("HTTP Error: {}", message);
    }

    /**
     * Extract meaningful error message from exception
     * Combines default message (context) with exception message (specific error) when both are available
     *
     * @param cause Exception cause
     * @param defaultMessage Default message providing context
     * @return Error message (combined or default only)
     */
    private static String getMessage(Throwable cause, String defaultMessage)
    {
        if (cause == null)
        {
            return defaultMessage;
        }

        var exceptionMessage = cause.getMessage();

        // If exception has a message, combine it with default message for better context
        if (exceptionMessage != null && !exceptionMessage.trim().isEmpty())
        {
            return defaultMessage + ": " + exceptionMessage;
        }

        return defaultMessage;
    }

}

