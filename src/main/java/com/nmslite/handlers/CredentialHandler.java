package com.nmslite.handlers;

import com.nmslite.services.CredentialProfileService;

import com.nmslite.utils.ExceptionUtil;

import com.nmslite.utils.ValidationUtil;

import com.nmslite.utils.ResponseUtil;


import io.vertx.core.json.JsonObject;

import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * CredentialHandler - Handles all credential profile HTTP requests

 * This handler manages:
 * - Credential profile CRUD operations
 * - Credential validation
 * - Protocol management

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

    /**
     * Get all credential profiles.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void getCredentials(RoutingContext ctx)
    {
        try
        {
            credentialProfileService.credentialList()
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, new JsonObject().put("credentials", result)))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to get credentials"));
        }
        catch (Exception exception)
        {
            logger.error("Error in getCredentials handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to get credentials");
        }
    }

    /**
     * Create a new credential profile.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void createCredentials(RoutingContext ctx)
    {
        try
        {
            var requestBody = ctx.body().asJsonObject();

            if (!ValidationUtil.Credential.validateCreate(ctx, requestBody))
            {
                return; // Validation failed, response already sent
            }

            credentialProfileService.credentialCreate(requestBody)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to create credentials"));
        }
        catch (Exception exception)
        {
            logger.error("Error in createCredentials handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to create credentials");
        }
    }

    /**
     * Update an existing credential profile.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void updateCredentials(RoutingContext ctx)
    {
        try
        {
            var credentialId = ctx.pathParam("id");

            var requestBody = ctx.body().asJsonObject();

            // 1. Validate path parameter
            if (!ValidationUtil.validatePathParameterUUID(ctx, credentialId, "Credential ID"))
            {
                return; // Validation failed, response already sent
            }

            // 2. Validate credential update fields
            if (!ValidationUtil.Credential.validateUpdate(ctx, requestBody))
            {
                return; // Validation failed, response already sent
            }

            credentialProfileService.credentialUpdate(credentialId, requestBody)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to update credentials"));
        }
        catch (Exception exception)
        {
            logger.error("Error in updateCredentials handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to update credentials");
        }
    }

    /**
     * Delete a credential profile.
     *
     * @param ctx routing context containing the HTTP request and response
     */
    public void deleteCredentials(RoutingContext ctx)
    {
        try
        {
            var credentialId = ctx.pathParam("id");

            // ===== PATH PARAMETER VALIDATION =====
            if (!ValidationUtil.validatePathParameterUUID(ctx, credentialId, "Credential ID"))
            {
                return; // Validation failed, response already sent
            }

            credentialProfileService.credentialDelete(credentialId)
                .onSuccess(result ->
                        ResponseUtil.handleSuccess(ctx, result))
                .onFailure(cause ->
                        ExceptionUtil.handleHttp(ctx, cause, "Failed to delete credentials"));
        }
        catch (Exception exception)
        {
            logger.error("Error in deleteCredentials handler: {}", exception.getMessage());

            ExceptionUtil.handleHttp(ctx, exception, "Failed to delete credentials");
        }
    }
}
