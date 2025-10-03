package com.nmslite.utils;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;

import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

/**
 * JWT Utility class for token generation and validation
 * Handles JWT token creation, validation, and user authentication
 */
public class JWTUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(JWTUtil.class);
    
    // JWT Configuration
    private static final String JWT_SECRET = "NMSLite-Super-Secret-Key-2025";
    private static final String JWT_ALGORITHM = "HS256";
    private static final int TOKEN_EXPIRY_HOURS = 24; // 24 hours
    
    private final JWTAuth jwtAuth;
    
    public JWTUtil(Vertx vertx) {
        // Configure JWT authentication
        JWTAuthOptions config = new JWTAuthOptions()
            .addPubSecKey(new PubSecKeyOptions()
                .setAlgorithm(JWT_ALGORITHM)
                .setBuffer(JWT_SECRET));
        
        this.jwtAuth = JWTAuth.create(vertx, config);
        logger.info("🔐 JWT authentication configured with {} algorithm", JWT_ALGORITHM);
    }
    
    /**
     * Generate JWT token for authenticated user
     * @param userId User ID
     * @param username Username
     * @param isActive User active status
     * @return JWT token string
     */
    public String generateToken(String userId, String username, boolean isActive) {
        try {
            JsonObject claims = new JsonObject()
                .put("user_id", userId)
                .put("username", username)
                .put("is_active", isActive)
                .put("iat", System.currentTimeMillis() / 1000) // Issued at
                .put("iss", "NMSLite"); // Issuer
            
            JWTOptions options = new JWTOptions()
                .setExpiresInMinutes(TOKEN_EXPIRY_HOURS * 60) // Convert hours to minutes
                .setIssuer("NMSLite")
                .setSubject(userId);
            
            String token = jwtAuth.generateToken(claims, options);
            logger.info("🔑 JWT token generated for user: {} (expires in {} hours)", username, TOKEN_EXPIRY_HOURS);
            return token;
            
        } catch (Exception e) {
            logger.error("❌ Failed to generate JWT token for user: {}", username, e);
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }
    
    /**
     * Validate JWT token and extract user information
     * @param token JWT token string
     * @return JsonObject with user information if valid, null if invalid
     */
    public JsonObject validateToken(String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                logger.warn("⚠️ Empty or null JWT token provided");
                return null;
            }

            logger.debug("🔍 Validating JWT token: {}", token.substring(0, Math.min(20, token.length())) + "...");

            // Use Auth0 JWT library for validation
            try {
                Algorithm algorithm = Algorithm.HMAC256(JWT_SECRET);
                JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer("NMSLite")
                    .build();

                DecodedJWT decodedJWT = verifier.verify(token);

                // Extract user information from token
                JsonObject userInfo = new JsonObject()
                    .put("user_id", decodedJWT.getClaim("user_id").asString())
                    .put("username", decodedJWT.getClaim("username").asString())
                    .put("is_active", decodedJWT.getClaim("is_active").asBoolean())
                    .put("iat", decodedJWT.getClaim("iat").asLong())
                    .put("exp", decodedJWT.getExpiresAt().getTime() / 1000);

                logger.debug("✅ JWT token validated for user: {}", userInfo.getString("username"));
                return userInfo;

            } catch (JWTVerificationException e) {
                logger.warn("❌ JWT verification failed: {}", e.getMessage());
                return null;
            }

        } catch (Exception e) {
            logger.warn("❌ Invalid JWT token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get token expiry time in hours
     * @return Token expiry hours
     */
    public static int getTokenExpiryHours() {
        return TOKEN_EXPIRY_HOURS;
    }
}
