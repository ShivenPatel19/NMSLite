package com.nmslite.handlers;

import com.nmslite.services.CredentialProfileService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.CredentialValidationUtil;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * CredentialHandler - Handles all credential profile HTTP requests
 *
 * This handler manages:
 * - Credential profile CRUD operations
 * - Credential validation
 * - Protocol management
 *
 * Uses CredentialService for all database operations via ProxyGen
 */
public class CredentialHandler
{

    private static final Logger logger = LoggerFactory.getLogger(CredentialHandler.class);

    private final CredentialProfileService credentialProfileService;

    /**
     * Constructor for CredentialHandler.
     *
     * @param credentialProfileService service proxy for credential profile database operations
     */
    public CredentialHandler(CredentialProfileService credentialProfileService)
    {
        this.credentialProfileService = credentialProfileService;
    }

    // ========================================
    // CREDENTIAL CRUD OPERATIONS
    // ========================================

    /**
     * Get all credential profiles.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getCredentials(RoutingContext ctx)
    {
        credentialProfileService.credentialList(ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, new JsonObject().put("credentials", ar.result()));
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get credentials");
            }
        });
    }

    /**
     * Create a new credential profile.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void createCredentials(RoutingContext ctx)
    {
        JsonObject requestBody = ctx.body().asJsonObject();

        // ===== COMPREHENSIVE HANDLER VALIDATION =====
        // Using common validation methods to reduce code redundancy
        if (!CredentialValidationUtil.validateCredentialBasicFields(ctx, requestBody))
        {
            return; // Validation failed, response already sent
        }

        credentialProfileService.credentialCreate(requestBody, ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to create credentials");
            }
        });
    }

    /**
     * Update an existing credential profile.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void updateCredentials(RoutingContext ctx)
    {
        String credentialId = ctx.pathParam("id");

        JsonObject requestBody = ctx.body().asJsonObject();

        // ===== COMPREHENSIVE HANDLER VALIDATION =====
        // Using common validation methods to reduce code redundancy

        // 1. Validate path parameter
        if (credentialId == null || credentialId.trim().isEmpty())
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Credential ID is required"),
                "Credential ID path parameter is required");

            return;
        }

        try
        {
            java.util.UUID.fromString(credentialId);
        }
        catch (IllegalArgumentException exception)
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid UUID format"),
                "Credential ID must be a valid UUID");

            return;
        }

        // 2. Validate credential update fields
        if (!CredentialValidationUtil.validateCredentialUpdate(ctx, requestBody))
        {
            return; // Validation failed, response already sent
        }

        credentialProfileService.credentialUpdate(credentialId, requestBody, ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update credentials");
            }
        });
    }

    /**
     * Delete a credential profile.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void deleteCredentials(RoutingContext ctx)
    {
        String credentialId = ctx.pathParam("id");

        // ===== PATH PARAMETER VALIDATION =====
        if (credentialId == null || credentialId.trim().isEmpty())
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Credential ID is required"),
                "Credential ID path parameter is required");

            return;
        }

        try
        {
            java.util.UUID.fromString(credentialId);
        }
        catch (IllegalArgumentException exception)
        {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid UUID format"),
                "Credential ID must be a valid UUID");

            return;
        }

        credentialProfileService.credentialDelete(credentialId, ar ->
        {
            if (ar.succeeded())
            {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            }
            else
            {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete credentials");
            }
        });
    }
}
