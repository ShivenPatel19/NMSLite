package com.nmslite.utils;

import com.nmslite.Bootstrap;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.auth.JWTOptions;

import io.vertx.ext.auth.PubSecKeyOptions;

import io.vertx.ext.auth.jwt.JWTAuth;

import io.vertx.ext.auth.jwt.JWTAuthOptions;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;

import com.auth0.jwt.algorithms.Algorithm;

/**
 * JWT Utility class for token generation and validation
 * Handles JWT token creation, validation, and user authentication
 */
public class JWTUtil
{

    private static final Logger logger = LoggerFactory.getLogger(JWTUtil.class);

    // JWT Configuration
    private static final String JWT_SECRET = "NMSLite-Super-Secret-Key-2025";

    private static final String JWT_ALGORITHM = "HS256";

    private static final int TOKEN_EXPIRY_HOURS = 24; // 24 hours

    private final JWTAuth jwtAuth;

    /**
     * Constructor for JWTUtil
     */
    public JWTUtil()
    {
        // Configure JWT authentication
        var config = new JWTAuthOptions()
                .addPubSecKey(new PubSecKeyOptions()
                .setAlgorithm(JWT_ALGORITHM)
                .setBuffer(JWT_SECRET));

        this.jwtAuth = JWTAuth.create(Bootstrap.getVertxInstance(), config);

        logger.debug("JWT authentication configured with {} algorithm", JWT_ALGORITHM);
    }

    /**
     * Generate JWT token for authenticated user
     *
     * @param userId User ID
     * @param username Username
     * @param isActive User active status
     * @return JWT token string
     */
    public String generateToken(String userId, String username, boolean isActive)
    {
        try
        {
            var claims = new JsonObject()
                .put("user_id", userId)
                .put("username", username)
                .put("is_active", isActive)
                .put("iat", System.currentTimeMillis() / 1000) // Issued at
                .put("iss", "NMSLite"); // Issuer

            var options = new JWTOptions()
                .setExpiresInMinutes(TOKEN_EXPIRY_HOURS * 60) // Convert hours to minutes
                .setIssuer("NMSLite")
                .setSubject(userId);

            var token = jwtAuth.generateToken(claims, options);

            logger.debug("JWT token generated for user: {}", username);

            return token;

        }
        catch (Exception exception)
        {
            logger.error("Failed to generate JWT token for user: {}: {}", username, exception.getMessage());

            return null;
        }
    }
    
    /**
     * Validate JWT token and extract user information
     *
     * @param token JWT token string
     * @return JsonObject with user information if valid, null if invalid
     */
    public JsonObject validateToken(String token)
    {
        try
        {
            if (token == null || token.trim().isEmpty())
            {
                logger.debug("Empty or null JWT token provided");

                return null;
            }

            // Use Auth0 JWT library for validation
            try
            {
                var algorithm = Algorithm.HMAC256(JWT_SECRET);

                var verifier = JWT.require(algorithm)
                    .withIssuer("NMSLite")
                    .build();

                var decodedJWT = verifier.verify(token);

                // Extract user information from token

                return new JsonObject()
                    .put("user_id", decodedJWT.getClaim("user_id").asString())
                    .put("username", decodedJWT.getClaim("username").asString())
                    .put("is_active", decodedJWT.getClaim("is_active").asBoolean())
                    .put("iat", decodedJWT.getClaim("iat").asLong())
                    .put("exp", decodedJWT.getExpiresAt().getTime() / 1000);

            }
            catch (Exception exception)
            {
                logger.debug("JWT verification failed: {}", exception.getMessage());

                return null;
            }

        }
        catch (Exception exception)
        {
            logger.debug("Invalid JWT token: {}", exception.getMessage());

            return null;
        }
    }

    /**
     * Get token expiry time in hours
     *
     * @return Token expiry hours
     */
    public static int getTokenExpiryHours()
    {
        return TOKEN_EXPIRY_HOURS;
    }

}
