/*
 * METRIC POLLING FUNCTIONALITY - NOW ENABLED
 *
 * PollingMetricsVerticle provides continuous device monitoring capabilities.
 * Can be enabled/disabled via configuration: polling.enabled = true/false
 *
 * Features:
 * - Periodic polling of active devices
 * - Batch fping for connectivity validation
 * - GoEngine metrics collection for alive devices
 * - Device availability tracking
 * - Real-time metrics updates to UI
 */

package com.nmslite.verticles;
import com.nmslite.services.DeviceService;
import com.nmslite.services.MetricsService;
import com.nmslite.services.AvailabilityService;
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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * PollingMetricsVerticle - Continuous Device Monitoring
 *
 * Responsibilities:
 * - Periodic polling of active devices
 * - Batch fping for connectivity validation
 * - GoEngine metrics collection for alive devices
 * - Device availability tracking
 * - Real-time metrics updates to UI
 */
public class PollingMetricsVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(PollingMetricsVerticle.class);

    private String goEnginePath;
    private String fpingPath;
    private int intervalSeconds;
    private int networkTimeoutSeconds;
    private int batchSize;
    private long pollingTimerId;
    private int blockingTimeoutGoEngine;
    private int goEngineProcessTimeout;

    // Service proxies
    private DeviceService deviceService;
    private MetricsService metricsService;
    private AvailabilityService availabilityService;

    // Polling state
    private volatile boolean isPolling = false;
    private volatile JsonObject pollingStatus = new JsonObject()
        .put("status", "idle")
        .put("last_poll_time", 0)
        .put("devices_polled", 0);

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("📈 Starting PollingMetricsVerticle - Continuous Monitoring");

        // Load configuration from tools and polling sections
        JsonObject toolsConfig = config().getJsonObject("tools", new JsonObject());
        JsonObject pollingConfig = config().getJsonObject("polling", new JsonObject());

        goEnginePath = toolsConfig.getString("goengine.path", "./goengine/goengine");
        fpingPath = toolsConfig.getString("fping.path", "fping");
        // Use device-specific timeout from device.defaults.connection.timeout.seconds
        JsonObject deviceDefaults = config().getJsonObject("device", new JsonObject())
                                           .getJsonObject("defaults", new JsonObject());
        networkTimeoutSeconds = deviceDefaults.getInteger("connection.timeout.seconds", 15);
        intervalSeconds = pollingConfig.getInteger("system.cycle.interval.seconds", 60);
        batchSize = pollingConfig.getInteger("batch.size", 50);
        blockingTimeoutGoEngine = pollingConfig.getInteger("blocking.timeout.goengine", 330);
        goEngineProcessTimeout = pollingConfig.getJsonObject("goengine", new JsonObject())
                                             .getInteger("process.timeout.seconds", 300);

        logger.info("🔧 GoEngine path: {}", goEnginePath);
        logger.info("🔧 fping path: {}", fpingPath);
        logger.info("🔧 Polling interval: {} seconds", intervalSeconds);
        logger.info("🔧 Device connection timeout: {} seconds (from device.defaults)", networkTimeoutSeconds);
        logger.info("🔧 Batch size: {}", batchSize);
        logger.info("🔧 GoEngine process timeout: {} seconds", goEngineProcessTimeout);
        logger.info("⏱️ GoEngine blocking timeout: {} seconds", blockingTimeoutGoEngine);

        // Initialize service proxies
        initializeServiceProxies();

        setupEventBusConsumers();
        startPeriodicPolling();

        logger.info("🚀 PollingMetricsVerticle started successfully");
        startPromise.complete();
    }

    /**
     * Initialize service proxies for database operations
     */
    private void initializeServiceProxies() {
        this.deviceService = DeviceService.createProxy(vertx);
        this.metricsService = MetricsService.createProxy(vertx);
        this.availabilityService = AvailabilityService.createProxy(vertx);

        logger.info("📡 Service proxies initialized for database operations");
    }

    private void setupEventBusConsumers() {
        // Handle provision requests
        vertx.eventBus().consumer("provision.start", message -> {
            JsonObject request = (JsonObject) message.body();
            handleProvisionStart(message, request);
        });

        // Handle polling status requests
        vertx.eventBus().consumer("polling.status", message -> {
            message.reply(pollingStatus.copy());
        });

        logger.info("📡 Polling event bus consumers setup complete");
    }

    private void startPeriodicPolling() {
        pollingTimerId = vertx.setPeriodic(intervalSeconds * 1000L, timerId -> {
            if (!isPolling) {
                executePollingCycle();
            } else {
                logger.debug("⏳ Skipping polling cycle - previous cycle still running");
            }
        });

        logger.info("⏰ Periodic polling started - interval: {} seconds", intervalSeconds);
    }

    private void executePollingCycle() {
        isPolling = true;
        long startTime = System.currentTimeMillis();

        logger.debug("🔄 Starting polling cycle");

        vertx.<JsonObject>executeBlocking(promise -> {
            try {
                // Get devices ready for polling
                getDevicesForPolling()
                    .onSuccess(devices -> {
                        if (devices.isEmpty()) {
                            logger.debug("📭 No devices available for polling");
                            promise.complete(new JsonObject()
                                .put("devices_polled", 0)
                                .put("message", "No devices available"));
                            return;
                        }

                        logger.debug("📊 Polling {} devices", devices.size());

                        // Execute smart batch polling
                        executeSmartBatchPolling(devices, promise);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get devices for polling", cause);
                        promise.fail(cause);
                    });

            } catch (Exception e) {
                logger.error("Polling cycle failed", e);
                promise.fail(e);
            }
        }, false, result -> {
            isPolling = false;
            long duration = System.currentTimeMillis() - startTime;

            if (result.succeeded()) {
                JsonObject summary = (JsonObject) result.result();
                pollingStatus = new JsonObject()
                    .put("status", "completed")
                    .put("last_poll_time", startTime)
                    .put("duration_ms", duration)
                    .put("devices_polled", summary.getInteger("devices_polled", 0));

                logger.debug("✅ Polling cycle completed in {}ms", duration);
            } else {
                pollingStatus = new JsonObject()
                    .put("status", "failed")
                    .put("last_poll_time", startTime)
                    .put("duration_ms", duration)
                    .put("error", result.cause().getMessage());

                logger.error("❌ Polling cycle failed in {}ms", duration, result.cause());
            }
        });
    }

    private Future<List<JsonObject>> getDevicesForPolling() {
        Promise<List<JsonObject>> promise = Promise.promise();

        deviceService.deviceListProvisionedAndMonitoringEnabled(ar -> {
            if (ar.succeeded()) {
                JsonArray devices = ar.result();
                List<JsonObject> deviceList = devices.stream()
                    .map(obj -> (JsonObject) obj)
                    .collect(Collectors.toList());
                promise.complete(deviceList);
            } else {
                logger.error("❌ Failed to get devices for polling", ar.cause());
                promise.fail(ar.cause());
            }
        });

        return promise.future();
    }

    private void executeSmartBatchPolling(List<JsonObject> devices, Promise<JsonObject> promise) {
        try {
            // Step 1: Extract IP addresses for connectivity check
            List<String> deviceIps = devices.stream()
                .map(device -> device.getString("ip_address"))
                .collect(Collectors.toList());

            logger.debug("🔍 Checking connectivity for {} devices", deviceIps.size());

            // Step 2: Execute batch fping
            Map<String, Boolean> connectivityResults = executeBatchFping(deviceIps);

            List<JsonObject> aliveDevices = new ArrayList<>();
            List<JsonObject> deadDevices = new ArrayList<>();

            for (JsonObject device : devices) {
                String ip = device.getString("ip_address");
                boolean isAlive = connectivityResults.getOrDefault(ip, false);

                if (isAlive) {
                    aliveDevices.add(device);
                } else {
                    deadDevices.add(device);
                }
            }

            logger.debug("📊 Connectivity results - {} alive, {} dead", aliveDevices.size(), deadDevices.size());

            // Step 3: Process dead devices (store connectivity failures)
            for (JsonObject deadDevice : deadDevices) {
                storeConnectivityFailure(deadDevice);
                updateDeviceAvailability(deadDevice.getString("device_id"), false);
            }

            // Step 4: Process alive devices (GoEngine metrics collection)
            if (!aliveDevices.isEmpty()) {
                executeGoEngineMetrics(aliveDevices, promise);
            } else {
                promise.complete(new JsonObject()
                    .put("devices_polled", devices.size())
                    .put("alive_devices", 0)
                    .put("dead_devices", deadDevices.size()));
            }

        } catch (Exception e) {
            logger.error("Smart batch polling failed", e);
            promise.fail(e);
        }
    }

    private Map<String, Boolean> executeBatchFping(List<String> ips) {
        Map<String, Boolean> results = new HashMap<>();

        try {
            List<String> command = Arrays.asList(
                fpingPath, "-a", "-q", "-t", String.valueOf(networkTimeoutSeconds * 1000) // -a: show alive, -q: quiet, -t: timeout in ms
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            // Write IPs to stdin
            try (var writer = process.outputWriter()) {
                for (String ip : ips) {
                    writer.write(ip + "\n");
                    results.put(ip, false); // Default to dead
                }
            }

            // Read alive IPs from stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String aliveIp = line.trim();
                    if (results.containsKey(aliveIp)) {
                        results.put(aliveIp, true);
                    }
                }
            }

            // Add process timeout for fping
            boolean finished = process.waitFor(networkTimeoutSeconds + 10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("fping process timed out after {} seconds", networkTimeoutSeconds + 10);
            }

        } catch (Exception e) {
            logger.error("fping execution failed during polling", e);
            // Fallback: mark all as dead
            for (String ip : ips) {
                results.put(ip, false);
            }
        }

        return results;
    }

    private void executeGoEngineMetrics(List<JsonObject> aliveDevices, Promise<JsonObject> promise) {
        try {
            // Group devices by platform and credentials for efficient batching
            Map<String, List<JsonObject>> deviceGroups = groupDevicesForBatching(aliveDevices);

            int totalProcessed = 0;
            int totalSuccessful = 0;
            int totalFailed = 0;

            for (Map.Entry<String, List<JsonObject>> entry : deviceGroups.entrySet()) {
                String groupKey = entry.getKey();
                List<JsonObject> groupDevices = entry.getValue();

                logger.debug("🚀 Processing device group '{}' with {} devices", groupKey, groupDevices.size());

                // Execute GoEngine for this group
                Map<String, JsonObject> groupResults = executeGoEngineForGroup(groupDevices);

                // Process results
                for (JsonObject device : groupDevices) {
                    String ip = device.getString("ip_address");
                    String deviceId = device.getString("device_id");
                    JsonObject result = groupResults.get(ip);

                    totalProcessed++;

                    if (result != null && result.getBoolean("success", false)) {
                        totalSuccessful++;
                        storeMetricsSuccess(deviceId, result);
                        updateDeviceAvailability(deviceId, true);
                    } else {
                        totalFailed++;
                        // Only update availability for failed metrics (no error metrics stored)
                        updateDeviceAvailability(deviceId, false);
                    }
                }
            }

            promise.complete(new JsonObject()
                .put("devices_polled", totalProcessed)
                .put("successful", totalSuccessful)
                .put("failed", totalFailed));

        } catch (Exception e) {
            logger.error("GoEngine metrics execution failed", e);
            promise.fail(e);
        }
    }

    private Map<String, List<JsonObject>> groupDevicesForBatching(List<JsonObject> devices) {
        Map<String, List<JsonObject>> groups = new HashMap<>();

        for (JsonObject device : devices) {
            String typeName = device.getString("device_type");
            String username = device.getString("username");

            // Create group key based on device type and credentials
            String groupKey = typeName + ":" + username;

            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(device);
        }

        return groups;
    }

    // Device types are stored directly in GoEngine format in database

    private Map<String, JsonObject> executeGoEngineForGroup(List<JsonObject> devices) {
        Map<String, JsonObject> results = new HashMap<>();

        try {
            // Prepare GoEngine devices array
            JsonArray goEngineDevices = new JsonArray();

            for (JsonObject device : devices) {
                JsonObject goEngineDevice = new JsonObject()
                    .put("address", device.getString("ip_address"))
                    .put("device_type", device.getString("device_type")) // Direct from database
                    .put("username", device.getString("username"))
                    .put("password", device.getString("password_encrypted")) // Should decrypt
                    .put("port", device.getInteger("port"))
                    .put("timeout", networkTimeoutSeconds + "s");

                goEngineDevices.add(goEngineDevice);
            }

            // Execute GoEngine
            String requestId = "METRICS_" + System.currentTimeMillis();

            List<String> command = Arrays.asList(
                goEnginePath,
                "--mode", "metrics",
                "--devices", goEngineDevices.encode(),
                "--request-id", requestId,
                "--timeout", "5m" // 5 minute timeout for metrics
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
                        results.put(deviceAddress, result);
                    } catch (Exception e) {
                        logger.warn("Failed to parse GoEngine metrics result: {}", line);
                    }
                }
            }

            // Add process timeout for GoEngine metrics
            boolean finished = process.waitFor(goEngineProcessTimeout, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("GoEngine metrics process timed out after " + goEngineProcessTimeout + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("GoEngine metrics exited with code: {}", exitCode);
            }

        } catch (Exception e) {
            logger.error("GoEngine metrics execution failed", e);

            // Create failure results for all devices
            for (JsonObject device : devices) {
                String ip = device.getString("ip_address");
                JsonObject failureResult = new JsonObject()
                    .put("device_address", ip)
                    .put("success", false)
                    .put("error", "GoEngine execution failed: " + e.getMessage())
                    .put("timestamp", System.currentTimeMillis());
                results.put(ip, failureResult);
            }
        }

        return results;
    }

    private void storeConnectivityFailure(JsonObject device) {
        JsonObject metricsData = new JsonObject()
            .put("device_id", device.getString("device_id"))
            .put("success", false)
            .put("duration_ms", 0)
            .put("error_message", "Device unreachable - ping failed");

        metricsService.metricsCreate(metricsData, ar -> {
            if (ar.succeeded()) {
                logger.debug("📊 Connectivity failure metrics stored for device: {}",
                           device.getString("device_name"));
            } else {
                logger.error("❌ Failed to store connectivity failure metrics for device: {}",
                           device.getString("device_name"), ar.cause());
            }
        });
    }

    private void storeMetricsSuccess(String deviceId, JsonObject result) {
        JsonObject cpu = result.getJsonObject("cpu", new JsonObject());
        JsonObject memory = result.getJsonObject("memory", new JsonObject());
        JsonObject disk = result.getJsonObject("disk", new JsonObject());

        JsonObject metricsData = new JsonObject()
            .put("device_id", deviceId)
            .put("success", true)
            .put("duration_ms", result.getInteger("duration_ms", 0))
            .put("cpu_usage_percent", cpu.getDouble("usage_percent", 0.0))
            .put("memory_usage_percent", memory.getDouble("usage_percent", 0.0))
            .put("memory_total_bytes", memory.getLong("total_bytes", 0L))
            .put("memory_used_bytes", memory.getLong("used_bytes", 0L))
            .put("memory_free_bytes", memory.getLong("free_bytes", 0L))
            .put("disk_usage_percent", disk.getDouble("usage_percent", 0.0))
            .put("disk_total_bytes", disk.getLong("total_bytes", 0L))
            .put("disk_used_bytes", disk.getLong("used_bytes", 0L))
            .put("disk_free_bytes", disk.getLong("free_bytes", 0L));

        metricsService.metricsCreate(metricsData, ar -> {
            if (ar.succeeded()) {
                logger.debug("📊 Metrics stored successfully for device: {}", deviceId);
            } else {
                logger.error("❌ Failed to store metrics for device: {}", deviceId, ar.cause());
            }
        });
    }

    // Removed storeMetricsFailure() - metrics table only stores successful metrics
    // Failed metrics are handled by availability tracking instead

    private void updateDeviceAvailability(String deviceId, boolean success) {
        String status = success ? "up" : "down";
        Long responseTime = success ? System.currentTimeMillis() % 1000 : null; // Simple response time simulation

        availabilityService.availabilityUpdateDeviceStatus(deviceId, status, responseTime, ar -> {
            if (ar.succeeded()) {
                logger.debug("📊 Availability updated for device: {} - Status: {}", deviceId, status);
            } else {
                logger.error("❌ Failed to update availability for device: {}", deviceId, ar.cause());
            }
        });
    }

    private void handleProvisionStart(io.vertx.core.eventbus.Message<Object> message, JsonObject request) {
        // Handle manual provision requests
        String profileId = request.getString("profile_id");

        if (profileId == null) {
            message.fail(400, "profile_id is required");
            return;
        }

        vertx.<JsonObject>executeBlocking(promise -> {
            // Get discovery profile and credentials
            vertx.eventBus().request("db.query", new JsonObject()
                    .put("operation", "get_discovery_and_credentials")
                    .put("params", new JsonObject().put("profile_id", profileId)))
                .onSuccess(reply -> {
                    JsonObject deviceConfig = (JsonObject) reply.body();

                    // Execute provision (similar to discovery but for single device)
                    executeProvision(deviceConfig, promise);
                })
                .onFailure(promise::fail);
        }, false, result -> {
            if (result.succeeded()) {
                message.reply(result.result());
            } else {
                message.fail(500, "Provision failed: " + result.cause().getMessage());
            }
        });
    }

    private void executeProvision(JsonObject deviceConfig, Promise<JsonObject> promise) {
        try {
            JsonObject goEngineDevice = new JsonObject()
                .put("address", deviceConfig.getString("ip_address"))
                .put("device_type", deviceConfig.getString("device_type")) // Direct from database
                .put("username", deviceConfig.getString("username"))
                .put("password", deviceConfig.getString("password_encrypted"))
                .put("port", deviceConfig.getInteger("port"))
                .put("timeout", networkTimeoutSeconds + "s");

            JsonArray devices = new JsonArray().add(goEngineDevice);

            String requestId = "PROVISION_" + System.currentTimeMillis();

            List<String> command = Arrays.asList(
                goEnginePath,
                "--mode", "discovery",
                "--targets", devices.encode(),
                "--request-id", requestId,
                "--timeout", "2m" // 2 minute timeout for provision
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            JsonObject result = null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        result = new JsonObject(line);
                        break; // Only one device
                    } catch (Exception e) {
                        logger.warn("Failed to parse provision result: {}", line);
                    }
                }
            }

            process.waitFor();

            if (result != null) {
                promise.complete(result);
            } else {
                promise.fail("No result received from GoEngine");
            }

        } catch (Exception e) {
            logger.error("Provision execution failed", e);
            promise.fail(e);
        }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("🛑 Stopping PollingMetricsVerticle");

        if (pollingTimerId != 0) {
            vertx.cancelTimer(pollingTimerId);
            logger.info("⏰ Periodic polling stopped");
        }

        stopPromise.complete();
    }
}
