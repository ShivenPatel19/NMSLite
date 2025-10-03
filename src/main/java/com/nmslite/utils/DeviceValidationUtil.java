package com.nmslite.utils;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * DeviceValidationUtil - Common validation methods for Device-related operations
 * 
 * This utility class provides reusable validation methods for all Device handlers:
 * - Device basic field validation
 * - Monitoring configuration validation
 * - String length validation
 * - UUID format validation
 * - Database constraint validation
 * 
 * All methods return true if validation passes, false if validation fails
 * (with HTTP response already sent to client)
 */
public class DeviceValidationUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(DeviceValidationUtil.class);
    
    /**
     * Validate basic device fields for creation
     * Validates: device_name, ip_address, device_type, port, protocol, username, password
     */
    public static boolean validateDeviceBasicFields(RoutingContext ctx, JsonObject deviceData) {
        // 1. Required fields validation (except device_name which can be derived from host_name)
        if (!CommonValidationUtil.validateRequiredFields(ctx, deviceData,
            "ip_address", "device_type", "port", "protocol", "username", "password")) {
            return false;
        }

        // 2. Device name validation - either device_name or host_name must be provided
        String deviceName = deviceData.getString("device_name");
        String hostName = deviceData.getString("host_name");

        if ((deviceName == null || deviceName.trim().isEmpty()) &&
            (hostName == null || hostName.trim().isEmpty())) {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Device name required"),
                "Either device_name or host_name must be provided");
            return false;
        }

        String ipAddress = deviceData.getString("ip_address");
        String deviceType = deviceData.getString("device_type");
        Integer port = deviceData.getInteger("port");
        String protocol = deviceData.getString("protocol");
        String username = deviceData.getString("username");
        String password = deviceData.getString("password");

        // 3. Port range validation (database CHECK constraint)
        if (!CommonValidationUtil.validatePortRange(ctx, port)) {
            return false;
        }

        // 4. Device name length validation (if provided)
        if (deviceName != null && deviceName.length() > 100) {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Device name too long"),
                "Device name must be at most 100 characters");
            return false;
        }

        // 5. Host name length validation (if provided)
        if (hostName != null && hostName.length() > 100) {
            ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("Host name too long"),
                "Host name must be at most 100 characters");
            return false;
        }

        return true;
    }





    /**
     * Validate monitoring configuration fields
     * Validates: timeout_seconds, retry_count, alert thresholds
     */
    public static boolean validateMonitoringConfig(RoutingContext ctx, JsonObject deviceData) {
        Integer timeoutSeconds = deviceData.getInteger("timeout_seconds");
        Integer retryCount = deviceData.getInteger("retry_count");
        Integer cpuThreshold = deviceData.getInteger("alert_threshold_cpu");
        Integer memoryThreshold = deviceData.getInteger("alert_threshold_memory");
        Integer diskThreshold = deviceData.getInteger("alert_threshold_disk");

        // Timeout validation (database CHECK constraint: timeout_seconds BETWEEN 0 AND 600)
        if (!CommonValidationUtil.validateTimeoutRange(ctx, timeoutSeconds)) {
            return false;
        }

        // Retry count validation (database CHECK constraint: retry_count BETWEEN 0 AND 5)
        if (!CommonValidationUtil.validateRetryCountRange(ctx, retryCount)) {
            return false;
        }

        // Threshold validation (database CHECK constraints: BETWEEN 0 AND 100)
        if (!CommonValidationUtil.validatePercentageRange(ctx, cpuThreshold, "CPU threshold") ||
            !CommonValidationUtil.validatePercentageRange(ctx, memoryThreshold, "Memory threshold") ||
            !CommonValidationUtil.validatePercentageRange(ctx, diskThreshold, "Disk threshold")) {
            return false;
        }

        return true;
    }







    /**
     * Validate monitoring configuration update fields
     * Used for updateDeviceMonitoringConfig endpoint
     */
    public static boolean validateMonitoringConfigUpdate(RoutingContext ctx, JsonObject requestBody) {
        // Validate update fields - at least one monitoring field must be provided
        if (!CommonValidationUtil.validateUpdateFields(ctx, requestBody,
            "polling_interval_seconds", "alert_threshold_cpu", "alert_threshold_memory", "alert_threshold_disk")) {
            return false;
        }

        // Validate threshold ranges (0-100) if provided
        if (!CommonValidationUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_cpu", 0.0, 100.0) ||
            !CommonValidationUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_memory", 0.0, 100.0) ||
            !CommonValidationUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_disk", 0.0, 100.0)) {
            return false;
        }

        // Validate polling interval (positive integer) if provided
        if (requestBody.containsKey("polling_interval_seconds")) {
            Integer pollingInterval = requestBody.getInteger("polling_interval_seconds");
            if (pollingInterval != null && pollingInterval <= 0) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("polling_interval_seconds must be positive"));
                return false;
            }
        }

        return true;
    }

    /**
     * Validate device update fields
     * Used for updateDeviceConfig endpoint
     */
    public static boolean validateDeviceUpdate(RoutingContext ctx, JsonObject requestBody) {
        // 1. Validate update fields - at least one field must be provided
        String[] allowedFields = {
            "device_name", "port", "polling_interval_seconds", "timeout_seconds", "retry_count",
            "alert_threshold_cpu", "alert_threshold_memory", "alert_threshold_disk"
        };

        if (!CommonValidationUtil.validateUpdateFields(ctx, requestBody, allowedFields)) {
            return false;
        }

        // 2. Field-specific validation (if fields are provided)

        // Device name length validation
        if (requestBody.containsKey("device_name")) {
            String deviceName = requestBody.getString("device_name");
            if (!CommonValidationUtil.validateStringLength(ctx, deviceName, 100, "device_name")) {
                return false;
            }
        }

        // Port range validation
        if (!CommonValidationUtil.validatePortRange(ctx, requestBody.getInteger("port"))) {
            return false;
        }

        // Timeout validation
        if (!CommonValidationUtil.validateTimeoutRange(ctx, requestBody.getInteger("timeout_seconds"))) {
            return false;
        }

        // Retry count validation
        if (!CommonValidationUtil.validateRetryCountRange(ctx, requestBody.getInteger("retry_count"))) {
            return false;
        }

        // Alert threshold validations
        if (!CommonValidationUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_cpu", 0.0, 100.0) ||
            !CommonValidationUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_memory", 0.0, 100.0) ||
            !CommonValidationUtil.validateDecimalRange(ctx, requestBody, "alert_threshold_disk", 0.0, 100.0)) {
            return false;
        }

        // Polling interval validation (positive integer)
        if (requestBody.containsKey("polling_interval_seconds")) {
            Integer pollingInterval = requestBody.getInteger("polling_interval_seconds");
            if (pollingInterval != null && pollingInterval <= 0) {
                ExceptionUtil.handleHttp(ctx, new IllegalArgumentException("polling_interval_seconds must be positive"));
                return false;
            }
        }

        return true;
    }
}
