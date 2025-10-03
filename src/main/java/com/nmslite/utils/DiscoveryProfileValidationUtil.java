package com.nmslite.utils;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * DiscoveryProfileValidationUtil - Common validation methods for DiscoveryProfile-related operations
 * 
 * This utility class provides reusable validation methods for all DiscoveryProfile handlers:
 * - Discovery profile basic field validation
 * - IP format validation with range support
 * - Foreign key UUID validation
 * - String length validation
 * - Port range validation
 * - Update field validation
 * 
 * All methods return true if validation passes, false if validation fails
 * (with HTTP response already sent to client)
 */
public class DiscoveryProfileValidationUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryProfileValidationUtil.class);
    
    /**
     * Validate basic discovery profile fields for creation
     * Validates: discovery_name, ip_address, device_type_id, credential_profile_ids, protocol
     */
    public static boolean validateDiscoveryProfileBasicFields(RoutingContext ctx, JsonObject profileData) {
        // 1. Required fields validation
        if (!CommonValidationUtil.validateRequiredFields(ctx, profileData,
            "discovery_name", "ip_address", "device_type_id", "credential_profile_ids", "protocol")) {
            return false;
        }

        String discoveryName = profileData.getString("discovery_name");
        String ipAddress = profileData.getString("ip_address");
        Boolean isRange = profileData.getBoolean("is_range", false);
        String deviceTypeId = profileData.getString("device_type_id");
        Integer port = profileData.getInteger("port");
        String protocol = profileData.getString("protocol");

        // 2. Validate credential profile IDs array
        if (!validateCredentialProfileIds(ctx, profileData)) {
            return false;
        }

        // 3. Port range validation (database CHECK constraint)
        if (!CommonValidationUtil.validatePortRange(ctx, port)) {
            return false;
        }

        // 4. IP address format validation (business logic)
        if (!validateIPFormat(ctx, ipAddress, isRange)) {
            return false;
        }

        return true;
    }







    /**
     * Validate credential profile IDs array
     * Validates: array is not empty, contains valid UUIDs, no duplicates
     */
    public static boolean validateCredentialProfileIds(RoutingContext ctx, JsonObject profileData) {
        Object credentialProfileIdsObj = profileData.getValue("credential_profile_ids");

        // Check if it's an array
        if (!(credentialProfileIdsObj instanceof io.vertx.core.json.JsonArray)) {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid credential_profile_ids format"),
                "credential_profile_ids must be an array of UUID strings");
            return false;
        }

        io.vertx.core.json.JsonArray credentialProfileIds = (io.vertx.core.json.JsonArray) credentialProfileIdsObj;

        // Check if array is not empty
        if (credentialProfileIds.isEmpty()) {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Empty credential_profile_ids array"),
                "credential_profile_ids array must contain at least one credential profile ID");
            return false;
        }

        // Check if array has too many elements (reasonable limit)
        if (credentialProfileIds.size() > 10) {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Too many credential profiles"),
                "credential_profile_ids array cannot contain more than 10 credential profiles");
            return false;
        }

        // Validate each UUID and check for duplicates
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (int i = 0; i < credentialProfileIds.size(); i++) {
            Object idObj = credentialProfileIds.getValue(i);

            if (!(idObj instanceof String)) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid credential profile ID format"),
                    "All credential profile IDs must be UUID strings");
                return false;
            }

            String credentialId = (String) idObj;

            // Validate UUID format
            try {
                java.util.UUID.fromString(credentialId);
            } catch (IllegalArgumentException e) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid UUID format"),
                    "Invalid credential profile ID format: " + credentialId);
                return false;
            }

            // Check for duplicates
            if (seenIds.contains(credentialId)) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Duplicate credential profile ID"),
                    "credential_profile_ids array cannot contain duplicate IDs: " + credentialId);
                return false;
            }
            seenIds.add(credentialId);
        }

        return true;
    }

    /**
     * Validate IP address format based on is_range flag (business logic)
     */
    public static boolean validateIPFormat(RoutingContext ctx, String ipAddress, Boolean isRange) {
        if (!IPRangeUtil.validateIPFormat(ipAddress, isRange)) {
            String expectedFormat = isRange ? "IP range (e.g., '192.168.1.1-50')" : "single IP address (e.g., '192.168.1.100')";
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Invalid IP format"),
                "Invalid IP address format. Expected " + expectedFormat + " but got: " + ipAddress);
            return false;
        }
        return true;
    }




}
