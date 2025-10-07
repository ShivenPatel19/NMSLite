package com.nmslite.utils;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

/**
 * ResponseUtil - HTTP Response Utility
 *
 * Provides consistent success response formatting across all APIs
 * - Success response formatting with data
 * - Standardized JSON structure
 * - Timestamp inclusion
 */
public class ResponseUtil
{

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

}

