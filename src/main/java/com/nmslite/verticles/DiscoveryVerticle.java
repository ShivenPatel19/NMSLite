package com.nmslite.verticles;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DiscoveryVerticle - Device Discovery Workflow
 * 
 * Responsibilities:
 * - Single device discovery and validation
 * - Port scanning for device connectivity
 * - GoEngine integration for SSH/WinRM discovery
 * - Device provisioning to database
 * - Real-time status updates via WebSocket
 */
public class DiscoveryVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryVerticle.class);
    
    private String goEnginePath;
    private String fpingPath;
    private int timeoutSeconds;
    private int fpingTimeoutSeconds;
    private int goengineTimeoutSeconds;
    
    // Discovery state tracking
    private final AtomicInteger activeDiscoveries = new AtomicInteger(0);
    private volatile JsonObject currentDiscoveryStatus = new JsonObject()
        .put("status", "idle")
        .put("active_discoveries", 0);

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("🔍 Starting DiscoveryVerticle - Device Discovery");

        // Load configuration
        goEnginePath = config().getString("goengine.path", "./goengine/goengine");
        fpingPath = config().getString("fping.path", "fping");
        timeoutSeconds = config().getInteger("timeout.seconds", 30);
        fpingTimeoutSeconds = config().getInteger("blocking.timeout.fping", 60);
        goengineTimeoutSeconds = config().getInteger("blocking.timeout.goengine", 120);

        logger.info("🔧 GoEngine path: {}", goEnginePath);
        logger.info("🔧 fping path: {}", fpingPath);
        logger.info("🔧 Timeout: {} seconds", timeoutSeconds);
        logger.info("🕐 fping blocking timeout: {} seconds", fpingTimeoutSeconds);
        logger.info("🕐 GoEngine blocking timeout: {} seconds", goengineTimeoutSeconds);

        setupEventBusConsumers();
        startPromise.complete();
    }

    private void setupEventBusConsumers() {
        // Handle single device credential validation
        vertx.eventBus().consumer("discovery.validate_device", message -> {
            JsonObject request = (JsonObject) message.body();
            handleDeviceValidation(message, request);
        });

        // Handle discovery and auto-provision
        vertx.eventBus().consumer("discovery.validate_and_provision", message -> {
            JsonObject request = (JsonObject) message.body();
            handleDiscoveryAndProvision(message, request);
        });

        // Handle validation-only (no provisioning)
        vertx.eventBus().consumer("discovery.validate_only", message -> {
            JsonObject request = (JsonObject) message.body();
            handleValidationOnly(message, request);
        });

        // Handle discovery status requests
        vertx.eventBus().consumer("discovery.status", message -> {
            message.reply(currentDiscoveryStatus.copy());
        });

        // Handle device restoration
        vertx.eventBus().consumer("discovery.restore_device", message -> {
            JsonObject request = (JsonObject) message.body();
            handleDeviceRestoration(message, request);
        });

        // Handle force creation (ignore deleted devices)
        vertx.eventBus().consumer("discovery.force_create", message -> {
            JsonObject request = (JsonObject) message.body();
            handleForceDiscoveryAndProvision(message, request);
        });

        // Handle single device discovery (main discovery endpoint)
        vertx.eventBus().consumer("discovery.single_device", message -> {
            JsonObject request = (JsonObject) message.body();
            handleSingleDeviceDiscovery(message, request);
        });

        // Handle profile-based discovery (enterprise workflow)
        vertx.eventBus().consumer("discovery.profile_based", message -> {
            JsonObject request = (JsonObject) message.body();
            handleProfileBasedDiscovery(message, request);
        });

        logger.info("📡 Discovery event bus consumers setup complete");
    }

    private void handleDeviceValidation(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        try {
            // Validate required fields
            String address = request.getString("address");
            String deviceType = request.getString("device_type");
            String username = request.getString("username");
            String password = request.getString("password");
            Integer port = request.getInteger("port");

            if (address == null || deviceType == null || username == null || password == null || port == null) {
                message.fail(400, "Missing required fields: address, device_type, username, password, port");
                return;
            }

            // Update discovery status
            int validationId = activeDiscoveries.incrementAndGet();
            currentDiscoveryStatus = new JsonObject()
                .put("status", "validating")
                .put("validation_id", validationId)
                .put("device_address", address)
                .put("start_time", System.currentTimeMillis());

            logger.info("🔍 Starting device validation #{} for {}:{} ({})", validationId, address, port, deviceType);

            // Execute validation in blocking thread
            vertx.<JsonObject>executeBlocking(promise -> {
                try {
                    JsonObject result = validateSingleDevice(address, deviceType, username, password, port);
                    promise.complete(result);
                } catch (Exception e) {
                    logger.error("Device validation failed", e);
                    promise.fail(e);
                }
            }, false, result -> {
                activeDiscoveries.decrementAndGet();

                if (result.succeeded()) {
                    JsonObject validationResult = result.result();

                    // Update discovery status to completed
                    currentDiscoveryStatus = new JsonObject()
                        .put("status", "completed")
                        .put("validation_id", validationId)
                        .put("result", validationResult);

                    logger.info("✅ Device validation #{} completed: {}", validationId,
                        validationResult.getBoolean("success") ? "SUCCESS" : "FAILED");

                    message.reply(validationResult);
                } else {
                    logger.error("Device validation failed", result.cause());
                    currentDiscoveryStatus = new JsonObject()
                        .put("status", "failed")
                        .put("error", result.cause().getMessage());

                    JsonObject errorResult = new JsonObject()
                        .put("success", false)
                        .put("address", address)
                        .put("error", "Validation failed: " + result.cause().getMessage())
                        .put("timestamp", System.currentTimeMillis());

                    message.reply(errorResult);
                }
            });

        } catch (Exception e) {
            logger.error("Failed to start device validation", e);
            message.fail(500, "Failed to start device validation: " + e.getMessage());
        }
    }

    private void handleDiscoveryAndProvision(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        try {
            // Validate required fields
            String address = request.getString("address");
            String deviceType = request.getString("device_type");
            String username = request.getString("username");
            String password = request.getString("password");
            Integer port = request.getInteger("port");
            String deviceName = request.getString("device_name", address); // Default to IP if no name provided

            if (address == null || deviceType == null || username == null || password == null || port == null) {
                message.fail(400, "Missing required fields: address, device_type, username, password, port");
                return;
            }

            // STEP 1: Check Discovery Profile first (highest priority)
            logger.info("🔍 Step 1: Checking if discovery profile exists for IP {}", address);

            vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "discovery_profile_by_ip")
                .put("params", new JsonObject().put("ip_address", address)), discoveryReply -> {

                if (discoveryReply.succeeded()) {
                    JsonObject discoveryResult = (JsonObject) discoveryReply.result().body();
                    JsonArray existingProfiles = discoveryResult.getJsonArray("profiles", new JsonArray());

                    if (!existingProfiles.isEmpty()) {
                        // Discovery profile already exists - suggest edit
                        JsonObject existingProfile = existingProfiles.getJsonObject(0);
                        String profileId = existingProfile.getString("profile_id");
                        String discoveryName = existingProfile.getString("discovery_name");

                        logger.warn("❌ Discovery profile for IP {} already exists: {}", address, discoveryName);

                        JsonObject errorResponse = new JsonObject()
                            .put("success", false)
                            .put("address", address)
                            .put("error", "Discovery profile for IP " + address + " already exists")
                            .put("existing_profile_id", profileId)
                            .put("existing_profile_name", discoveryName)
                            .put("suggestion", "Edit existing discovery profile: '" + discoveryName + "'")
                            .put("action_required", "edit_discovery_profile")
                            .put("provisioned", false)
                            .put("timestamp", System.currentTimeMillis());

                        message.reply(errorResponse);
                        return;
                    }

                    // STEP 2: No discovery profile exists, check device table
                    logger.info("✅ No discovery profile found for IP {}, checking device table", address);
                    checkDeviceTableForIP(message, address, deviceType, username, password, port, deviceName);

                } else {
                    logger.error("Failed to check discovery profile", discoveryReply.cause());
                    message.fail(500, "Failed to check discovery profile: " + discoveryReply.cause().getMessage());
                }
            });

        } catch (Exception e) {
            logger.error("Failed to start discovery and provision", e);
            message.fail(500, "Failed to start discovery and provision: " + e.getMessage());
        }
    }

    private void proceedWithDiscoveryAndProvision(io.vertx.core.eventbus.Message<Object> message, String address, String deviceType, String username, String password, int port, String deviceName) {
        try {
            // Update discovery status
            int validationId = activeDiscoveries.incrementAndGet();
            currentDiscoveryStatus = new JsonObject()
                .put("status", "discovering_and_provisioning")
                .put("validation_id", validationId)
                .put("device_address", address)
                .put("start_time", System.currentTimeMillis());

            logger.info("🔍 Starting discovery & provision #{} for {}:{} ({})", validationId, address, port, deviceType);

            // Execute validation in blocking thread
            vertx.<JsonObject>executeBlocking(promise -> {
                try {
                    JsonObject result = validateSingleDevice(address, deviceType, username, password, port);
                    promise.complete(result);
                } catch (Exception e) {
                    logger.error("Device validation failed", e);
                    promise.fail(e);
                }
            }, false, result -> {
                activeDiscoveries.decrementAndGet();

                if (result.succeeded()) {
                    JsonObject validationResult = result.result();
                    boolean discoverySuccess = validationResult.getBoolean("success", false);

                    if (discoverySuccess) {
                        // Discovery successful - now provision to devices table
                        logger.info("✅ Discovery successful for {} - proceeding with provisioning", address);

                        // Encrypt the password before storing
                        String encryptedPassword = com.nmslite.utils.PasswordUtil.encryptPassword(password);

                        JsonObject deviceData = new JsonObject()
                            .put("device_name", deviceName)
                            .put("ip_address", address)
                            .put("device_type", deviceType)
                            .put("port", port)
                            .put("username", username)
                            .put("password_encrypted", encryptedPassword)
                            .put("is_monitoring_enabled", true);

                        // Insert into devices table
                        vertx.eventBus().request("db.insert", new JsonObject()
                            .put("operation", "device")
                            .put("params", deviceData), dbReply -> {

                            if (dbReply.succeeded()) {
                                JsonObject dbResult = (JsonObject) dbReply.result().body();
                                String deviceId = dbResult.getString("device_id");

                                logger.info("🎯 Device provisioned successfully: {} (ID: {})", address, deviceId);

                                // Update discovery status to completed
                                currentDiscoveryStatus = new JsonObject()
                                    .put("status", "provisioned")
                                    .put("validation_id", validationId)
                                    .put("device_id", deviceId)
                                    .put("result", validationResult);

                                // Return success with device ID
                                JsonObject response = validationResult.copy()
                                    .put("provisioned", true)
                                    .put("device_id", deviceId)
                                    .put("device_name", deviceName);

                                message.reply(response);

                            } else {
                                logger.error("Failed to provision device to database", dbReply.cause());

                                // Return discovery success but provisioning failure
                                JsonObject response = validationResult.copy()
                                    .put("provisioned", false)
                                    .put("provision_error", "Database error: " + dbReply.cause().getMessage());

                                message.reply(response);
                            }
                        });

                    } else {
                        // Discovery failed - don't provision
                        logger.warn("❌ Discovery failed for {} - not provisioning", address);

                        currentDiscoveryStatus = new JsonObject()
                            .put("status", "discovery_failed")
                            .put("validation_id", validationId)
                            .put("result", validationResult);

                        JsonObject response = validationResult.copy()
                            .put("provisioned", false)
                            .put("provision_error", "Discovery failed - device not accessible");

                        message.reply(response);
                    }

                } else {
                    logger.error("Device validation failed", result.cause());
                    currentDiscoveryStatus = new JsonObject()
                        .put("status", "failed")
                        .put("error", result.cause().getMessage());

                    JsonObject errorResult = new JsonObject()
                        .put("success", false)
                        .put("address", address)
                        .put("error", "Validation failed: " + result.cause().getMessage())
                        .put("provisioned", false)
                        .put("timestamp", System.currentTimeMillis());

                    message.reply(errorResult);
                }
            });

        } catch (Exception e) {
            logger.error("Failed to start discovery and provision", e);
            message.fail(500, "Failed to start discovery and provision: " + e.getMessage());
        }
    }

    private JsonObject validateSingleDevice(String address, String deviceType, String username, String password, int port) {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("🔍 Validating device: {}:{} with user: {}", address, port, username);

            // Step 1: Check if device is reachable (ping)
            if (!isDeviceReachable(address)) {
                return new JsonObject()
                    .put("success", false)
                    .put("address", address)
                    .put("error", "Device unreachable - ping failed")
                    .put("duration_ms", System.currentTimeMillis() - startTime)
                    .put("timestamp", System.currentTimeMillis());
            }

            // Step 2: Check if port is open
            if (!executePortCheck(address, port)) {
                return new JsonObject()
                    .put("success", false)
                    .put("address", address)
                    .put("error", "Port " + port + " is closed or filtered")
                    .put("duration_ms", System.currentTimeMillis() - startTime)
                    .put("timestamp", System.currentTimeMillis());
            }

            // Step 3: Test credentials with GoEngine
            JsonObject goEngineDevice = new JsonObject()
                .put("address", address)
                .put("device_type", deviceType)
                .put("username", username)
                .put("password", password)
                .put("port", port)
                .put("timeout", timeoutSeconds + "s");

            JsonObject result = executeGoEngineSingle(goEngineDevice);
            if (result != null) {
                // Add validation metadata
                result.put("duration_ms", System.currentTimeMillis() - startTime);
                result.put("validation_type", "credential_test");
                return result;
            } else {
                return new JsonObject()
                    .put("success", false)
                    .put("address", address)
                    .put("error", "GoEngine validation failed - no result returned")
                    .put("duration_ms", System.currentTimeMillis() - startTime)
                    .put("timestamp", System.currentTimeMillis());
            }

        } catch (Exception e) {
            logger.error("Device validation exception", e);
            return new JsonObject()
                .put("success", false)
                .put("address", address)
                .put("error", "Validation exception: " + e.getMessage())
                .put("duration_ms", System.currentTimeMillis() - startTime)
                .put("timestamp", System.currentTimeMillis());
        }
    }

    private boolean isDeviceReachable(String address) {
        try {
            // Simple ping check using fping
            ProcessBuilder pb = new ProcessBuilder(fpingPath, "-c", "1", "-t", "3000", address);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            logger.warn("Ping check failed for {}: {}", address, e.getMessage());
            return false;
        }
    }

    private boolean executePortCheck(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 3000); // 3 second timeout
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private JsonObject executeGoEngineSingle(JsonObject device) {
        try {
            // Prepare GoEngine v5.0.0 command for single device discovery
            JsonArray targetsArray = new JsonArray().add(device);
            String requestId = "DISC_" + System.currentTimeMillis();

            List<String> command = Arrays.asList(
                goEnginePath,
                "--mode", "discovery",
                "--targets", targetsArray.encode(),  // v5.0.0: Use --targets for discovery
                "--request-id", requestId,
                "--timeout", "5m" // 5 minute timeout for discovery
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            // Read results from stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonObject result = new JsonObject(line);
                        String deviceAddress = result.getString("device_address");
                        if (deviceAddress.equals(device.getString("address"))) {
                            return result;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to parse GoEngine result: {}", line);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("GoEngine exited with code: {}", exitCode);
            }

        } catch (Exception e) {
            logger.error("GoEngine execution failed", e);
            return new JsonObject()
                .put("success", false)
                .put("device_address", device.getString("address"))
                .put("error", "GoEngine execution failed: " + e.getMessage())
                .put("timestamp", System.currentTimeMillis());
        }

        // If no result found, return failure
        return new JsonObject()
            .put("success", false)
            .put("device_address", device.getString("address"))
            .put("error", "No result returned from GoEngine")
            .put("timestamp", System.currentTimeMillis());
    }

    private void handleDeviceRestoration(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        try {
            String deviceId = request.getString("device_id");

            if (deviceId == null) {
                message.fail(400, "Missing required field: device_id");
                return;
            }

            logger.info("🔄 Restoring deleted device: {}", deviceId);

            vertx.eventBus().request("db.update", new JsonObject()
                .put("operation", "restore_device")
                .put("params", new JsonObject().put("device_id", deviceId)), restoreReply -> {

                if (restoreReply.succeeded()) {
                    JsonObject restoreResult = (JsonObject) restoreReply.result().body();

                    logger.info("✅ Device restored successfully: {}", deviceId);

                    JsonObject response = new JsonObject()
                        .put("success", true)
                        .put("device_id", deviceId)
                        .put("action", "restored")
                        .put("message", "Device restored and monitoring enabled")
                        .put("timestamp", System.currentTimeMillis());

                    message.reply(response);
                } else {
                    logger.error("Failed to restore device", restoreReply.cause());
                    message.fail(500, "Failed to restore device: " + restoreReply.cause().getMessage());
                }
            });

        } catch (Exception e) {
            logger.error("Failed to handle device restoration", e);
            message.fail(500, "Failed to handle device restoration: " + e.getMessage());
        }
    }

    private void checkDeviceTableForIP(io.vertx.core.eventbus.Message<Object> message, String address, String deviceType, String username, String password, int port, String deviceName) {
        logger.info("🔍 Step 2: Checking device table for IP {}", address);

        vertx.eventBus().request("db.query", new JsonObject()
            .put("operation", "device_by_ip")
            .put("params", new JsonObject()
                .put("ip_address", address)
                .put("include_deleted", true)), deviceReply -> {

            if (deviceReply.succeeded()) {
                JsonObject deviceResult = (JsonObject) deviceReply.result().body();
                JsonArray existingDevices = deviceResult.getJsonArray("devices", new JsonArray());
                boolean hasActive = deviceResult.getBoolean("has_active", false);
                boolean hasDeleted = deviceResult.getBoolean("has_deleted", false);

                if (hasActive) {
                    // Active device exists - find corresponding discovery profile
                    JsonObject activeDevice = existingDevices.stream()
                        .map(JsonObject.class::cast)
                        .filter(d -> !d.getBoolean("is_deleted"))
                        .findFirst()
                        .orElse(new JsonObject());

                    String existingDeviceId = activeDevice.getString("device_id");
                    String existingDeviceName = activeDevice.getString("device_name");

                    // Find corresponding discovery profile
                    findDiscoveryProfileForDevice(message, address, existingDeviceId, existingDeviceName);
                    return;
                }

                if (hasDeleted && !hasActive) {
                    // Only deleted devices exist - offer restoration option
                    JsonObject deletedDevice = existingDevices.stream()
                        .map(JsonObject.class::cast)
                        .filter(d -> d.getBoolean("is_deleted"))
                        .findFirst()
                        .orElse(new JsonObject());

                    logger.info("⚠️ Previously deleted device {} found - offering restoration or new discovery", address);

                    JsonObject warningResponse = new JsonObject()
                        .put("success", false)
                        .put("address", address)
                        .put("warning", "Device with IP " + address + " was previously deleted")
                        .put("deleted_device_id", deletedDevice.getString("device_id"))
                        .put("deleted_device_name", deletedDevice.getString("device_name"))
                        .put("deleted_at", deletedDevice.getString("deleted_at"))
                        .put("deleted_by", deletedDevice.getString("deleted_by"))
                        .put("is_deleted", true)
                        .put("options", new JsonArray()
                            .add("restore_existing")
                            .add("create_new"))
                        .put("action_required", "choose_restoration_option")
                        .put("provisioned", false)
                        .put("timestamp", System.currentTimeMillis());

                    message.reply(warningResponse);
                    return;
                }

                // STEP 3: No devices exist, proceed with discovery
                logger.info("✅ Device {} not found anywhere, proceeding with discovery", address);
                proceedWithDiscoveryAndProvision(message, address, deviceType, username, password, port, deviceName);

            } else {
                logger.error("Failed to check device table", deviceReply.cause());
                message.fail(500, "Failed to check device table: " + deviceReply.cause().getMessage());
            }
        });
    }

    private void findDiscoveryProfileForDevice(io.vertx.core.eventbus.Message<Object> message, String address, String deviceId, String deviceName) {
        logger.info("🔍 Finding discovery profile for active device {} ({})", deviceName, deviceId);

        vertx.eventBus().request("db.query", new JsonObject()
            .put("operation", "discovery_profile_by_ip")
            .put("params", new JsonObject().put("ip_address", address)), profileReply -> {

            if (profileReply.succeeded()) {
                JsonObject profileResult = (JsonObject) profileReply.result().body();
                JsonArray profiles = profileResult.getJsonArray("profiles", new JsonArray());

                if (!profiles.isEmpty()) {
                    // Found corresponding discovery profile
                    JsonObject profile = profiles.getJsonObject(0);
                    String profileId = profile.getString("profile_id");
                    String discoveryName = profile.getString("discovery_name");

                    logger.warn("❌ Active device {} exists with corresponding discovery profile: {}", address, discoveryName);

                    JsonObject errorResponse = new JsonObject()
                        .put("success", false)
                        .put("address", address)
                        .put("error", "Device with IP " + address + " is already provisioned and active")
                        .put("existing_device_id", deviceId)
                        .put("existing_device_name", deviceName)
                        .put("discovery_profile_id", profileId)
                        .put("discovery_profile_name", discoveryName)
                        .put("suggestion", "To modify device credentials, edit discovery profile: '" + discoveryName + "' then re-run discovery")
                        .put("action_required", "edit_discovery_profile_then_rediscover")
                        .put("is_active", true)
                        .put("provisioned", true)
                        .put("timestamp", System.currentTimeMillis());

                    message.reply(errorResponse);
                } else {
                    // Active device exists but no discovery profile (orphaned device)
                    logger.warn("⚠️ Active device {} exists but no discovery profile found (orphaned device)", address);

                    JsonObject warningResponse = new JsonObject()
                        .put("success", false)
                        .put("address", address)
                        .put("warning", "Device with IP " + address + " is active but has no discovery profile")
                        .put("existing_device_id", deviceId)
                        .put("existing_device_name", deviceName)
                        .put("suggestion", "Create discovery profile first, then manage device through discovery workflow")
                        .put("action_required", "create_discovery_profile_for_existing_device")
                        .put("is_active", true)
                        .put("is_orphaned", true)
                        .put("provisioned", true)
                        .put("timestamp", System.currentTimeMillis());

                    message.reply(warningResponse);
                }
            } else {
                logger.error("Failed to find discovery profile for device", profileReply.cause());
                message.fail(500, "Failed to find discovery profile: " + profileReply.cause().getMessage());
            }
        });
    }

    private void handleForceDiscoveryAndProvision(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        try {
            // Validate required fields
            String address = request.getString("address");
            String deviceType = request.getString("device_type");
            String username = request.getString("username");
            String password = request.getString("password");
            Integer port = request.getInteger("port");
            String deviceName = request.getString("device_name", address);

            if (address == null || deviceType == null || username == null || password == null || port == null) {
                message.fail(400, "Missing required fields: address, device_type, username, password, port");
                return;
            }

            logger.info("🚀 Force creating new device (ignoring deleted devices): {}", address);

            // Proceed directly with discovery and provision (skip duplicate check)
            proceedWithDiscoveryAndProvision(message, address, deviceType, username, password, port, deviceName);

        } catch (Exception e) {
            logger.error("Failed to handle force discovery and provision", e);
            message.fail(500, "Failed to handle force discovery and provision: " + e.getMessage());
        }
    }

    /**
     * Handle profile-based discovery - enterprise workflow
     * Uses discovery profile to get device configuration and credentials
     */
    private void handleProfileBasedDiscovery(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        try {
            String discoveryProfileId = request.getString("discovery_profile_id");

            if (discoveryProfileId == null) {
                message.fail(400, "Missing required field: discovery_profile_id");
                return;
            }

            logger.info("🔍 Profile-based discovery request for profile: {}", discoveryProfileId);

            // Step 1: Get discovery profile with credentials
            vertx.eventBus().request("db.query", new JsonObject()
                .put("operation", "get_discovery_and_credentials")
                .put("params", new JsonObject().put("profile_id", discoveryProfileId)), profileReply -> {

                if (profileReply.succeeded()) {
                    JsonObject profileResult = (JsonObject) profileReply.result().body();

                    if (profileResult.containsKey("error")) {
                        message.fail(404, "Discovery profile not found: " + discoveryProfileId);
                        return;
                    }

                    // Extract discovery profile data
                    String discoveryName = profileResult.getString("discovery_name");
                    String ipAddress = profileResult.getString("ip_address");
                    String deviceType = profileResult.getString("device_type_name");
                    String username = profileResult.getString("username");
                    String password = profileResult.getString("password");
                    Integer port = profileResult.getInteger("port");

                    logger.info("✅ Retrieved discovery profile '{}' for {}:{} ({})",
                        discoveryName, ipAddress, port, deviceType);

                    // Step 2: Execute discovery with profile data
                    executeProfileBasedDiscovery(message, discoveryProfileId, ipAddress, deviceType,
                        username, password, port, discoveryName);

                } else {
                    logger.error("Failed to get discovery profile", profileReply.cause());
                    message.fail(500, "Failed to get discovery profile: " + profileReply.cause().getMessage());
                }
            });

        } catch (Exception e) {
            logger.error("Profile-based discovery exception", e);
            message.fail(500, "Discovery exception: " + e.getMessage());
        }
    }

    /**
     * Handle single device discovery - main discovery endpoint
     * Uses enterprise rules to check for conflicts before discovery
     */
    private void handleSingleDeviceDiscovery(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        try {
            String address = request.getString("address");
            String deviceType = request.getString("device_type");
            String username = request.getString("username");
            String password = request.getString("password");
            int port = request.getInteger("port");
            String deviceName = request.getString("device_name", address); // Use IP as default name

            logger.info("🔍 Single device discovery request for: {}:{} ({})", address, port, deviceType);

            // Use enterprise discovery rules (check for conflicts first)
            handleDiscoveryAndProvision(message, request);

        } catch (Exception e) {
            logger.error("Failed to handle single device discovery", e);
            message.fail(500, "Failed to handle single device discovery: " + e.getMessage());
        }
    }

    /**
     * Execute discovery using profile data
     */
    private void executeProfileBasedDiscovery(io.vertx.core.eventbus.Message<Object> message,
            String discoveryProfileId, String address, String deviceType, String username,
            String password, int port, String discoveryName) {

        try {
            logger.info("🔍 Starting profile-based discovery for {}:{} using profile '{}'",
                address, port, discoveryName);

            // Update discovery status
            int validationId = activeDiscoveries.incrementAndGet();
            currentDiscoveryStatus = new JsonObject()
                .put("status", "validating")
                .put("validation_id", validationId)
                .put("device_address", address)
                .put("discovery_profile_id", discoveryProfileId)
                .put("start_time", System.currentTimeMillis());

            // Execute validation in blocking thread
            vertx.<JsonObject>executeBlocking(promise -> {
                try {
                    JsonObject result = validateSingleDevice(address, deviceType, username, password, port);
                    promise.complete(result);
                } catch (Exception e) {
                    logger.error("Profile-based device validation failed", e);
                    promise.fail(e);
                }
            }, false, result -> {
                activeDiscoveries.decrementAndGet();

                if (result.succeeded()) {
                    JsonObject discoveryResult = result.result();
                    boolean discoverySuccess = discoveryResult.getBoolean("success", false);

                    if (discoverySuccess) {
                        // Discovery successful - now provision to devices table
                        logger.info("✅ Profile-based discovery successful for {} - proceeding with provisioning", address);

                        // Encrypt the password before storing
                        String encryptedPassword = com.nmslite.utils.PasswordUtil.encryptPassword(password);

                        JsonObject deviceData = new JsonObject()
                            .put("device_name", discoveryName)  // Use discovery name as device name
                            .put("ip_address", address)
                            .put("device_type", deviceType)
                            .put("port", port)
                            .put("username", username)
                            .put("password_encrypted", encryptedPassword)
                            .put("discovery_profile_id", discoveryProfileId)  // Link to discovery profile
                            .put("is_monitoring_enabled", true);

                        // Insert into devices table
                        vertx.eventBus().request("db.insert", new JsonObject()
                            .put("operation", "device")
                            .put("params", deviceData), dbReply -> {

                            if (dbReply.succeeded()) {
                                JsonObject dbResult = (JsonObject) dbReply.result().body();
                                String deviceId = dbResult.getString("device_id");

                                logger.info("🎯 Device provisioned successfully from profile '{}': {} (ID: {})",
                                    discoveryName, address, deviceId);

                                // Return success response
                                JsonObject response = new JsonObject()
                                    .put("success", true)
                                    .put("device_address", address)
                                    .put("discovery_profile_id", discoveryProfileId)
                                    .put("device_id", deviceId)
                                    .put("device_name", discoveryName)
                                    .put("provisioned", true)
                                    .put("timestamp", System.currentTimeMillis())
                                    .put("duration_ms", System.currentTimeMillis() - currentDiscoveryStatus.getLong("start_time"));

                                message.reply(response);

                            } else {
                                logger.error("Failed to provision device from profile", dbReply.cause());

                                JsonObject errorResponse = new JsonObject()
                                    .put("success", false)
                                    .put("device_address", address)
                                    .put("discovery_profile_id", discoveryProfileId)
                                    .put("provisioned", false)
                                    .put("provision_error", "Database error: " + dbReply.cause().getMessage())
                                    .put("timestamp", System.currentTimeMillis());

                                message.reply(errorResponse);
                            }
                        });

                    } else {
                        // Discovery failed
                        logger.warn("❌ Profile-based discovery failed for {} - not provisioning", address);

                        JsonObject errorResponse = new JsonObject()
                            .put("success", false)
                            .put("device_address", address)
                            .put("discovery_profile_id", discoveryProfileId)
                            .put("error", discoveryResult.getString("error", "Discovery validation failed"))
                            .put("provisioned", false)
                            .put("provision_error", "Discovery failed - device not accessible")
                            .put("timestamp", System.currentTimeMillis())
                            .put("duration_ms", discoveryResult.getLong("duration_ms", 0L));

                        message.reply(errorResponse);
                    }

                } else {
                    logger.error("Profile-based discovery validation exception", result.cause());

                    JsonObject errorResponse = new JsonObject()
                        .put("success", false)
                        .put("device_address", address)
                        .put("discovery_profile_id", discoveryProfileId)
                        .put("error", "Validation exception: " + result.cause().getMessage())
                        .put("provisioned", false)
                        .put("timestamp", System.currentTimeMillis());

                    message.reply(errorResponse);
                }
            });

        } catch (Exception e) {
            logger.error("Profile-based discovery execution exception", e);
            message.fail(500, "Discovery execution exception: " + e.getMessage());
        }
    }

    // ========================================
    // VALIDATION-ONLY OPERATION
    // ========================================

    private void handleValidationOnly(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        String address = request.getString("address");
        String deviceType = request.getString("device_type");
        String username = request.getString("username");
        String password = request.getString("password");
        Integer port = request.getInteger("port", 22);

        logger.info("🔍 Starting validation-only for device: {} ({}:{})", address, deviceType, port);

        // Step 1: Network connectivity check
        checkNetworkConnectivity(address, port)
            .compose(connectivityResult -> {
                if (!connectivityResult.getBoolean("success")) {
                    return Future.succeededFuture(new JsonObject()
                        .put("success", false)
                        .put("stage", "connectivity")
                        .put("error", "Network connectivity failed")
                        .put("details", connectivityResult));
                }

                // Step 2: Credential validation using GoEngine
                return validateCredentialsWithGoEngine(address, deviceType, username, password, port);
            })
            .onSuccess(result -> {
                logger.info("✅ Validation-only completed for {}: {}", address, result.getBoolean("success"));
                message.reply(result);
            })
            .onFailure(cause -> {
                logger.error("❌ Validation-only failed for {}: {}", address, cause.getMessage());
                message.reply(new JsonObject()
                    .put("success", false)
                    .put("stage", "validation")
                    .put("error", cause.getMessage()));
            });
    }

    private Future<JsonObject> validateCredentialsWithGoEngine(String address, String deviceType,
                                                              String username, String password, Integer port) {
        Promise<JsonObject> promise = Promise.promise();

        String requestId = "validate-" + System.currentTimeMillis();

        // Create GoEngine command for validation
        List<String> command = new ArrayList<>();
        command.add(goEnginePath);
        command.add("--mode");
        command.add("discovery");
        command.add("--targets");
        command.add(String.format("%s:%d", address, port));
        command.add("--request-id");
        command.add(requestId);
        command.add("--timeout");
        command.add(String.valueOf(timeoutSeconds));

        // Create input JSON for GoEngine
        JsonObject deviceInput = new JsonObject()
            .put("address", address)
            .put("device_type", deviceType)
            .put("username", username)
            .put("password", password)
            .put("port", port);

        logger.info("🚀 Executing GoEngine validation: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();

            // Send input to GoEngine
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream())) {
                writer.write(deviceInput.encode() + "\n");
                writer.flush();
            }

            // Read GoEngine output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    // Try to parse each line as JSON
                    try {
                        JsonObject result = new JsonObject(line);
                        if (result.containsKey("success")) {
                            promise.complete(result);
                            return promise.future();
                        }
                    } catch (Exception ignored) {
                        // Not JSON, continue reading
                    }
                }
            }

            // Wait for process completion
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                promise.complete(new JsonObject()
                    .put("success", true)
                    .put("message", "Validation completed successfully")
                    .put("output", output.toString()));
            } else {
                promise.fail("GoEngine validation failed with exit code: " + exitCode);
            }

        } catch (Exception e) {
            logger.error("Failed to execute GoEngine validation", e);
            promise.fail("GoEngine execution failed: " + e.getMessage());
        }

        return promise.future();
    }

    /**
     * Check network connectivity to a device using socket connection
     */
    private Future<JsonObject> checkNetworkConnectivity(String address, Integer port) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(address, port), 5000); // 5 second timeout
                socket.close();

                blockingPromise.complete(new JsonObject()
                    .put("success", true)
                    .put("message", "Network connectivity successful"));

            } catch (Exception e) {
                blockingPromise.complete(new JsonObject()
                    .put("success", false)
                    .put("error", "Network connectivity failed: " + e.getMessage()));
            }
        }, promise);

        return promise.future();
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("🛑 Stopping DiscoveryVerticle");
        stopPromise.complete();
    }
}
