package com.nmslite.handlers;

import com.nmslite.services.CredentialProfileService;
import com.nmslite.utils.ExceptionUtil;
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
public class CredentialHandler {

    private static final Logger logger = LoggerFactory.getLogger(CredentialHandler.class);
    private final CredentialProfileService credentialProfileService;

    public CredentialHandler(CredentialProfileService credentialProfileService) {
        this.credentialProfileService = credentialProfileService;
    }

    // ========================================
    // CREDENTIAL CRUD OPERATIONS
    // ========================================

    public void getCredentials(RoutingContext ctx) {
        credentialProfileService.credentialList(ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, new JsonObject().put("credentials", ar.result()));
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to get credentials");
            }
        });
    }



    public void createCredentials(RoutingContext ctx) {
        JsonObject requestBody = ctx.body().asJsonObject();
        
        // Validate required fields
        if (!ExceptionUtil.validateRequiredFields(ctx, requestBody,
            "profile_name", "username", "password")) {
            return; // Response already sent by validateRequiredFields
        }
        
        credentialProfileService.credentialCreate(requestBody, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to create credentials");
            }
        });
    }

    public void updateCredentials(RoutingContext ctx) {
        String credentialId = ctx.pathParam("id");
        JsonObject requestBody = ctx.body().asJsonObject();

        // Validate update fields - at least one field must be provided
        if (!ExceptionUtil.validateUpdateFields(ctx, requestBody, "profile_name", "username", "password")) {
            return; // Response already sent by validateUpdateFields
        }

        credentialProfileService.credentialUpdate(credentialId, requestBody, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to update credentials");
            }
        });
    }

    public void deleteCredentials(RoutingContext ctx) {
        String credentialId = ctx.pathParam("id");

        credentialProfileService.credentialDelete(credentialId, ar -> {
            if (ar.succeeded()) {
                ExceptionUtil.handleSuccess(ctx, ar.result());
            } else {
                ExceptionUtil.handleHttp(ctx, ar.cause(), "Failed to delete credentials");
            }
        });
    }
}
