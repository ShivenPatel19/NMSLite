package com.nmslite.utils;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * ExceptionUtil - Generic Exception Handling Utility

 * Provides consistent error handling across all verticles and APIs
 * - HTTP response formatting
 * - Event Bus error handling
 * - Success response formatting
 * - Extensible status code mapping
 */
public class ExceptionUtil
{

    private static final Logger logger = LoggerFactory.getLogger(ExceptionUtil.class);

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
        var message = getMessage(cause, defaultMessage);

        var errorResponse = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("timestamp", System.currentTimeMillis());

        context.response()
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

        // If no exception message, use default message only
        return defaultMessage;
    }

}
