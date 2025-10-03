package com.nmslite.middleware;

import com.nmslite.utils.JWTUtil;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authentication Middleware for JWT token validation
 * Protects API endpoints by validating JWT tokens
 */
public class AuthenticationMiddleware {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationMiddleware.class);
    
    private final JWTUtil jwtUtil;
    
    public AuthenticationMiddleware(JWTUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }
    
    /**
     * Create authentication handler for protecting routes
     * @return Handler for JWT authentication
     */
    public Handler<RoutingContext> requireAuthentication() {
        return ctx -> {
            try {
                // Extract token from Authorization header
                String authHeader = ctx.request().getHeader("Authorization");
                
                if (authHeader == null || authHeader.trim().isEmpty()) {
                    logger.warn("🚫 Missing Authorization header for {}", ctx.request().path());
                    handleUnauthorized(ctx, "Missing Authorization header");
                    return;
                }
                
                // Validate token format
                if (!authHeader.startsWith("Bearer ")) {
                    logger.warn("🚫 Invalid Authorization header format for {}", ctx.request().path());
                    handleUnauthorized(ctx, "Invalid Authorization header format. Use 'Bearer <token>'");
                    return;
                }
                
                String token = authHeader.substring(7); // Remove "Bearer " prefix

                logger.debug("🔍 Extracted token for validation: {}", token.substring(0, Math.min(20, token.length())) + "...");

                // Validate JWT token
                JsonObject userInfo = jwtUtil.validateToken(token);
                if (userInfo == null) {
                    logger.warn("🚫 Invalid JWT token for {}", ctx.request().path());
                    handleUnauthorized(ctx, "Invalid or expired JWT token");
                    return;
                }
                
                // Check if user is active
                boolean isActive = userInfo.getBoolean("is_active", false);
                if (!isActive) {
                    logger.warn("🚫 Inactive user attempted access: {}", userInfo.getString("username"));
                    handleUnauthorized(ctx, "User account is inactive");
                    return;
                }
                
                // Store user information in context for use in handlers
                ctx.put("user", userInfo);
                ctx.put("user_id", userInfo.getString("user_id"));
                ctx.put("username", userInfo.getString("username"));
                
                logger.debug("✅ Authentication successful for user: {} accessing {}", 
                           userInfo.getString("username"), ctx.request().path());
                
                // Continue to next handler
                ctx.next();
                
            } catch (Exception e) {
                logger.error("❌ Authentication middleware error for {}: {}", 
                           ctx.request().path(), e.getMessage());
                handleUnauthorized(ctx, "Authentication failed");
            }
        };
    }
    
    /**
     * Handle unauthorized access
     * @param ctx Routing context
     * @param message Error message
     */
    private void handleUnauthorized(RoutingContext ctx, String message) {
        JsonObject errorResponse = new JsonObject()
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
}
