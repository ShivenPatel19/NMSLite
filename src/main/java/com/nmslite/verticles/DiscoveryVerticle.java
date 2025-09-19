package com.nmslite.verticles;

import com.nmslite.services.DeviceService;
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

    // Service proxies
    private DeviceService deviceService;



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

        // Initialize service proxies
        this.deviceService = DeviceService.createProxy(vertx);
        logger.info("🔧 Service proxies initialized");

        setupEventBusConsumers();
        startPromise.complete();
    }

    private void setupEventBusConsumers() {
        // Handle validation-only (no provisioning)
        vertx.eventBus().consumer("discovery.validate_only", message -> {
            JsonObject request = (JsonObject) message.body();
            handleValidationOnly(message, request);
        });



        logger.info("📡 Discovery event bus consumers setup complete");
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

        // Create target JSON for GoEngine (v5.0.0 format)
        JsonArray targets = new JsonArray()
            .add(new JsonObject()
                .put("address", address)
                .put("device_type", deviceType)
                .put("username", username)
                .put("password", password)
                .put("port", port));

        // Create GoEngine command for validation (correct v5.0.0 format)
        List<String> command = new ArrayList<>();
        command.add(goEnginePath);
        command.add("--mode");
        command.add("discovery");
        command.add("--targets");
        command.add(targets.encode());  // JSON array as string
        command.add("--request-id");
        command.add(requestId);

        logger.info("🚀 Executing GoEngine validation: {}", String.join(" ", command));
        logger.info("📋 Targets JSON: {}", targets.encode());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();

            // No stdin input needed for GoEngine v5.0.0 - everything is in command line

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
