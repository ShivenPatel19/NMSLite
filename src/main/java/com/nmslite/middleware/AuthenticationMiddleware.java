package com.nmslite.middleware;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.JWTUtil;

import io.vertx.core.Handler;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * Authentication Middleware for JWT token validation.
 * Protects API endpoints by validating JWT tokens and checking user status.
 */
public class AuthenticationMiddleware
{

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationMiddleware.class);

    private final JWTUtil jwtUtil;

    /**
     * Constructs an AuthenticationMiddleware with the provided JWT utility.
     *
     * @param jwtUtil JWT utility for token validation
     */
    public AuthenticationMiddleware(JWTUtil jwtUtil)
    {
        this.jwtUtil = jwtUtil;
    }

    /**
     * Creates an authentication handler for protecting routes.
     * Validates JWT token from Authorization header, checks user status, and stores user info in context.
     *
     * @return Handler for JWT authentication
     */
    public Handler<RoutingContext> requireAuthentication()
    {
        return ctx ->
        {
            try
            {
                // Extract token from Authorization header
                var authHeader = ctx.request().getHeader("Authorization");

                if (authHeader == null || authHeader.trim().isEmpty())
                {
                    logger.debug("Missing Authorization header for {}", ctx.request().path());

                    handleUnauthorized(ctx, "Missing Authorization header");

                    return;
                }

                // Validate token format
                if (!authHeader.startsWith("Bearer "))
                {
                    logger.debug("Invalid Authorization header format for {}", ctx.request().path());

                    handleUnauthorized(ctx, "Invalid Authorization header format. Use 'Bearer <token>'");

                    return;
                }

                var token = authHeader.substring(7); // Remove "Bearer " prefix

                // Validate JWT token
                var userInfo = jwtUtil.validateToken(token);

                if (userInfo == null)
                {
                    logger.debug("Invalid JWT token for {}", ctx.request().path());

                    handleUnauthorized(ctx, "Invalid or expired JWT token");

                    return;
                }

                // Check if user is active
                var isActive = userInfo.getBoolean("is_active", false);

                if (!isActive)
                {
                    logger.debug("Inactive user attempted access: {}", userInfo.getString("username"));

                    handleUnauthorized(ctx, "User account is inactive");

                    return;
                }

                // Continue to next handler
                ctx.next();

            }
            catch (Exception exception)
            {
                logger.error("Error in requireAuthentication: {}", exception.getMessage());

                ExceptionUtil.handleHttp(ctx, exception, "Authentication failed");
            }
        };
    }

    /**
     * Handles unauthorized access by sending a 401 response with error details.
     *
     * @param ctx Routing context
     * @param message Error message to include in response
     */
    private void handleUnauthorized(RoutingContext ctx, String message)
    {
        try
        {
            var errorResponse = new JsonObject()
                .put("success", false)
                .put("error", "Unauthorized")
                .put("message", message)
                .put("status_code", 401)
                .put("timestamp", System.currentTimeMillis());

            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(errorResponse.encode());
        }
        catch (Exception exception)
        {
            logger.error("Error in handleUnauthorized: {}", exception.getMessage());
        }
    }

}
