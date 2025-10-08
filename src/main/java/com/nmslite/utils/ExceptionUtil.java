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
        String message = getMessage(cause, defaultMessage);

        JsonObject errorResponse = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("timestamp", System.currentTimeMillis());

        context.response()
            .putHeader("Content-Type", "application/json")
            .end(errorResponse.encode());

        logger.error("HTTP Error: {}", message, cause);
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
