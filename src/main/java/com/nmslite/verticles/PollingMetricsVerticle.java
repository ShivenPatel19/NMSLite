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

import com.nmslite.models.PollingDevice;

import com.nmslite.services.DeviceService;

import com.nmslite.services.MetricsService;

import com.nmslite.services.AvailabilityService;

import com.nmslite.utils.PasswordUtil;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.io.*;

import java.time.Instant;

import java.util.*;

import java.util.concurrent.ConcurrentHashMap;

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
public class PollingMetricsVerticle extends AbstractVerticle
{

    private static final Logger logger = LoggerFactory.getLogger(PollingMetricsVerticle.class);

    // Configuration
    private String goEnginePath;

    private String fpingPath;

    private int intervalSeconds;

    private int networkTimeoutSeconds;

    private int batchSize;

    private long pollingTimerId;

    private int blockingTimeoutGoEngine;

    private int goEngineProcessTimeout;

    // NEW: Polling cycle configuration
    private int cycleIntervalSeconds;        // How often scheduler checks for due devices

    private int maxCyclesSkipped;            // Auto-disable threshold

    private String failureLogPath;           // Failure log file path

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

    // NEW: In-memory device cache
    // Key: device_id, Value: PollingDevice (persistent data + runtime state)
    private ConcurrentHashMap<String, PollingDevice> deviceCache;

    /**
     * Start the verticle: load configuration, initialize service proxies, load devices into cache, and start polling.
     *
     * @param startPromise promise completed once the verticle is ready
     */
    @Override
    public void start(Promise<Void> startPromise)
    {
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

        // NEW: Load polling cycle configuration
        cycleIntervalSeconds = pollingConfig.getJsonObject("cycle", new JsonObject())
                                           .getJsonObject("interval", new JsonObject())
                                           .getInteger("seconds", 300);

        maxCyclesSkipped = pollingConfig.getJsonObject("max", new JsonObject())
                                       .getJsonObject("cycles", new JsonObject())
                                       .getInteger("skipped", 5);

        failureLogPath = pollingConfig.getJsonObject("failure", new JsonObject())
                                      .getJsonObject("log", new JsonObject())
                                      .getString("path", "polling_failed/metrics_polling_failed.txt");

        logger.info("🔧 GoEngine path: {}", goEnginePath);

        logger.info("🔧 fping path: {}", fpingPath);

        logger.info("🔧 Polling cycle interval: {} seconds", cycleIntervalSeconds);

        logger.info("🔧 Max cycles skipped (auto-disable): {}", maxCyclesSkipped);

        logger.info("🔧 Batch size: {}", batchSize);

        logger.info("🔧 GoEngine process timeout: {} seconds", goEngineProcessTimeout);

        logger.info("⏱️ GoEngine blocking timeout: {} seconds", blockingTimeoutGoEngine);

        // Initialize service proxies
        initializeServiceProxies();

        // NEW: Initialize cache
        deviceCache = new ConcurrentHashMap<>();

        // NEW: Load devices into cache (DatabaseVerticle is already deployed sequentially before this)
        loadDevicesIntoCache()
            .onSuccess(count ->
            {
                logger.info("✅ Loaded {} devices into cache", count);

                setupEventBusConsumers();

                startPeriodicPolling();

                logger.info("🚀 PollingMetricsVerticle started successfully");

                startPromise.complete();
            })
            .onFailure(cause ->
            {
                logger.error("❌ Failed to load devices into cache", cause);

                startPromise.fail(cause);
            });
    }

    /**
     * Initialize service proxies for database operations
     */
    private void initializeServiceProxies()
    {
        this.deviceService = DeviceService.createProxy(vertx);

        this.metricsService = MetricsService.createProxy(vertx);

        this.availabilityService = AvailabilityService.createProxy(vertx);

        logger.info("📡 Service proxies initialized for database operations");
    }

    /**
     * Load all eligible devices from database into in-memory cache.
     *
     * Queries devices where:
     * - is_provisioned = true
     * - is_monitoring_enabled = true
     * - is_deleted = false
     *
     * Joins with credential_profiles to get username and password.
     *
     * @return Future with count of devices loaded
     */
    private Future<Integer> loadDevicesIntoCache()
    {
        Promise<Integer> promise = Promise.promise();

        logger.info("📦 Loading devices into cache...");

        deviceService.deviceListProvisionedAndMonitoringEnabled(ar ->
        {
            if (ar.succeeded())
            {
                JsonArray devices = ar.result();

                int count = 0;

                for (Object obj : devices)
                {
                    JsonObject deviceData = (JsonObject) obj;

                    try
                    {
                        PollingDevice pd = createPollingDeviceFromJson(deviceData);

                        deviceCache.put(pd.deviceId, pd);

                        count++;

                        logger.info("📦 Cached device: {} ({}) | Interval: {}s | Next poll: {}",
                                   pd.deviceName, pd.address, pd.pollingIntervalSeconds, pd.nextScheduledAt);
                    }
                    catch (Exception exception)
                    {
                        logger.error("❌ Failed to cache device: {}",
                                   deviceData.getString("device_name"), exception);
                    }
                }

                logger.info("✅ Successfully cached {} devices", count);

                promise.complete(count);
            }
            else
            {
                logger.error("❌ Failed to query devices for cache", ar.cause());

                promise.fail(ar.cause());
            }
        });

        return promise.future();
    }

    /**
     * Create PollingDevice from database JSON.
     *
     * Maps database fields to PollingDevice model and computes runtime state.
     *
     * @param deviceData JSON from database query
     * @return PollingDevice instance
     */
    private PollingDevice createPollingDeviceFromJson(JsonObject deviceData)
    {
        PollingDevice pd = new PollingDevice();

        // Identity
        pd.deviceId = deviceData.getString("device_id");

        pd.deviceName = deviceData.getString("device_name");

        // GoEngine required fields
        // Strip CIDR notation from IP address (e.g., "192.168.1.1/32" → "192.168.1.1")
        String ipWithCidr = deviceData.getString("ip_address");

        pd.address = ipWithCidr.contains("/") ? ipWithCidr.split("/")[0] : ipWithCidr;

        // Convert "server_linux" → "server linux" for GoEngine
        pd.deviceType = deviceData.getString("device_type").replace("_", " ");

        pd.username = deviceData.getString("username");

        // Decrypt password for GoEngine use
        pd.password = PasswordUtil.decryptPassword(deviceData.getString("password_encrypted"));

        pd.port = deviceData.getInteger("port");

        // Per-device config (from devices table, NOT config file)
        pd.timeoutSeconds = deviceData.getInteger("timeout_seconds");

        pd.retryCount = deviceData.getInteger("retry_count");

        pd.pollingIntervalSeconds = deviceData.getInteger("polling_interval_seconds");

        // Timestamps (PostgreSQL returns timestamps without 'Z', need to append it for ISO-8601)
        String monitoringEnabledAtStr = deviceData.getString("monitoring_enabled_at");

        pd.monitoringEnabledAt = monitoringEnabledAtStr != null
            ? Instant.parse(monitoringEnabledAtStr + "Z")
            : Instant.parse(deviceData.getString("created_at") + "Z");

        String lastPolledAtStr = deviceData.getString("last_polled_at");

        pd.lastPolledAt = lastPolledAtStr != null
            ? Instant.parse(lastPolledAtStr + "Z")
            : null;

        // Compute aligned next poll time
        pd.nextScheduledAt = computeAlignedNext(pd.monitoringEnabledAt, Instant.now(),
                                                pd.pollingIntervalSeconds);

        // Initialize runtime state
        pd.consecutiveFailures = 0;

        return pd;
    }

    /**
     * Compute aligned next poll time from anchor.
     *
     * This ensures fixed cadence without drift:
     * - Anchor: monitoring_enabled_at
     * - Next = anchor + ceil((now - anchor) / interval) * interval
     *
     * Example:
     * - Anchor: 10:00:00
     * - Interval: 600s (10 min)
     * - Now: 10:07:00
     * - Elapsed: 420s
     * - Cycles passed: 0 (420 / 600 = 0.7)
     * - Next cycle: 1
     * - Next poll: 10:00:00 + 600s = 10:10:00
     *
     * @param anchor monitoring_enabled_at timestamp
     * @param now Current time
     * @param intervalSeconds Polling interval
     * @return Next scheduled poll time
     */
    private Instant computeAlignedNext(Instant anchor, Instant now, long intervalSeconds)
    {
        long elapsedSeconds = now.getEpochSecond() - anchor.getEpochSecond();

        long cyclesPassed = elapsedSeconds / intervalSeconds;

        long nextCycle = cyclesPassed + 1;

        return anchor.plusSeconds(nextCycle * intervalSeconds);
    }

    // ========== 4-PHASE POLLING CYCLE IMPLEMENTATION ==========

    /**
     * Phase 1: Batch Processing
     *
     * Process devices in batches:
     * 1. Batch fping connectivity check
     * 2. For alive devices: GoEngine metrics collection
     * 3. For dead devices: Record connectivity failure
     * 4. Update device schedules and failure counters
     *
     * @param dueDevices List of devices due for polling
     * @param now Current timestamp
     * @return Future with list of failed devices (for retry)
     */
    private Future<List<PollingDevice>> executePhaseBatchProcessing(List<PollingDevice> dueDevices, Instant now)
    {
        Promise<List<PollingDevice>> promise = Promise.promise();

        logger.info("📦 Phase 1: Batch Processing - {} devices", dueDevices.size());

        List<PollingDevice> failedDevices = new ArrayList<>();

        // Process devices in batches
        List<List<PollingDevice>> batches = partitionIntoBatches(dueDevices, batchSize);

        logger.info("📦 Processing {} batches (batch size: {})", batches.size(), batchSize);

        // Process batches sequentially
        processBatchesSequentially(batches, 0, failedDevices, promise);

        return promise.future();
    }

    /**
     * Partition devices into batches
     */
    private List<List<PollingDevice>> partitionIntoBatches(List<PollingDevice> devices, int batchSize)
    {
        List<List<PollingDevice>> batches = new ArrayList<>();

        for (int i = 0; i < devices.size(); i += batchSize)
        {
            batches.add(devices.subList(i, Math.min(i + batchSize, devices.size())));
        }

        return batches;
    }

    /**
     * Process batches sequentially (one after another)
     */
    private void processBatchesSequentially(List<List<PollingDevice>> batches, int batchIndex,
                                           List<PollingDevice> failedDevices, Promise<List<PollingDevice>> promise)
    {
        if (batchIndex >= batches.size())
        {
            // All batches processed
            promise.complete(failedDevices);

            return;
        }

        List<PollingDevice> batch = batches.get(batchIndex);

        logger.info("📦 Processing batch {}/{} ({} devices)", batchIndex + 1, batches.size(), batch.size());

        processSingleBatch(batch)
            .onSuccess(batchFailures ->
            {
                failedDevices.addAll(batchFailures);

                // Process next batch
                processBatchesSequentially(batches, batchIndex + 1, failedDevices, promise);
            })
            .onFailure(cause ->
            {
                logger.error("❌ Batch {} failed", batchIndex + 1, cause);

                // Mark all devices in this batch as failed and continue
                failedDevices.addAll(batch);

                processBatchesSequentially(batches, batchIndex + 1, failedDevices, promise);
            });
    }

    /**
     * Process a single batch of devices
     */
    private Future<List<PollingDevice>> processSingleBatch(List<PollingDevice> batch)
    {
        Promise<List<PollingDevice>> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise ->
        {
            try
            {
                List<PollingDevice> batchFailures = new ArrayList<>();

                // Step 1: Batch fping connectivity check (ICMP)
                List<String> ips = batch.stream().map(pd -> pd.address).collect(Collectors.toList());

                Map<String, Boolean> connectivityResults = executeBatchFping(ips);

                // Step 2: Port reachability check (TCP) for devices that passed fping
                List<PollingDevice> pingAliveDevices = batch.stream()
                    .filter(pd -> connectivityResults.getOrDefault(pd.address, false))
                    .collect(Collectors.toList());

                Map<String, Boolean> portCheckResults = executeBatchPortCheck(pingAliveDevices);

                // Step 3: Separate alive and dead devices (both ICMP and TCP must pass)
                List<PollingDevice> aliveDevices = new ArrayList<>();

                List<PollingDevice> deadDevices = new ArrayList<>();

                for (PollingDevice pd : batch)
                {
                    boolean pingAlive = connectivityResults.getOrDefault(pd.address, false);

                    boolean portOpen = portCheckResults.getOrDefault(pd.deviceId, false);

                    if (pingAlive && portOpen)
                    {
                        aliveDevices.add(pd);
                    }
                    else
                    {
                        deadDevices.add(pd);

                        batchFailures.add(pd);

                        if (!pingAlive)
                        {
                            logger.debug("❌ Device {} failed ICMP ping", pd.deviceName);
                        }
                        else
                        {
                            logger.debug("❌ Device {} port {} not reachable", pd.deviceName, pd.port);
                        }
                    }
                }

                logger.info("📊 Batch connectivity: {} alive (ICMP+TCP), {} dead", aliveDevices.size(), deadDevices.size());

                // Step 3: Process dead devices (record connectivity failure)
                for (PollingDevice pd : deadDevices)
                {
                    pd.incrementFailures();

                    logger.debug("❌ Device {} unreachable (failures: {})", pd.deviceName, pd.consecutiveFailures);
                }

                // Step 4: Process alive devices (GoEngine metrics in batch with streaming results)
                if (!aliveDevices.isEmpty())
                {
                    Map<String, Boolean> metricsResults = pollDeviceMetricsBatch(aliveDevices);

                    // Process results for each device
                    for (PollingDevice pd : aliveDevices)
                    {
                        boolean success = metricsResults.getOrDefault(pd.deviceId, false);

                        if (success)
                        {
                            pd.resetFailures();

                            pd.advanceSchedule();

                            logger.debug("✅ Device {} polled successfully", pd.deviceName);
                        }
                        else
                        {
                            pd.incrementFailures();

                            batchFailures.add(pd);

                            logger.debug("❌ Device {} metrics failed (failures: {})", pd.deviceName, pd.consecutiveFailures);
                        }
                    }
                }

                blockingPromise.complete(batchFailures);

            }
            catch (Exception exception)
            {
                logger.error("❌ Batch processing failed", exception);

                blockingPromise.fail(exception);
            }
        }, false, result ->
        {
            if (result.succeeded())
            {
                promise.complete((List<PollingDevice>) result.result());
            }
            else
            {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    /**
     * Poll metrics for multiple devices in a batch using GoEngine streaming output
     *
     * GoEngine processes devices in parallel and outputs results as they become available.
     * Each result is a separate JSON line on stdout.
     *
     * @param devices List of devices to poll
     * @return Map of device_id -> success status
     */
    private Map<String, Boolean> pollDeviceMetricsBatch(List<PollingDevice> devices)
    {
        Map<String, Boolean> results = new HashMap<>();

        // Initialize all devices as failed (will be updated on success)
        for (PollingDevice pd : devices)
        {
            results.put(pd.deviceId, false);
        }

        if (devices.isEmpty())
        {
            return results;
        }

        try
        {
            // Build GoEngine JSON input array
            JsonArray devicesArray = new JsonArray();

            for (PollingDevice pd : devices)
            {
                devicesArray.add(pd.toGoEngineJson());
            }

            // Generate request ID
            String requestId = "POLL_BATCH_" + System.currentTimeMillis();

            logger.debug("🚀 Starting GoEngine batch poll for {} devices", devices.size());

            // Execute GoEngine with batch input
            ProcessBuilder pb = new ProcessBuilder(
                goEnginePath,
                "--mode", "metrics",
                "--devices", devicesArray.encode(),
                "--request-id", requestId
            );

            Process process = pb.start();

            // Create a map for quick device lookup by IP address
            Map<String, PollingDevice> devicesByIp = new HashMap<>();

            for (PollingDevice pd : devices)
            {
                devicesByIp.put(pd.address, pd);
            }

            // Read streaming output line by line and process results as they arrive
            StringBuilder errorOutput = new StringBuilder();

            int processedCount = 0;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream())))
            {
                String line;

                while ((line = reader.readLine()) != null)
                {
                    try
                    {
                        // Parse JSON result for this device
                        JsonObject result = new JsonObject(line);

                        String deviceAddress = result.getString("device_address");

                        boolean success = result.getBoolean("success", false);

                        // Find the device by IP address
                        PollingDevice pd = devicesByIp.get(deviceAddress);

                        if (pd == null)
                        {
                            logger.warn("⚠️ Received result for unknown device: {}", deviceAddress);

                            continue;
                        }

                        processedCount++;

                        if (success)
                        {
                            // Store metrics and update availability
                            storeMetrics(pd.deviceId, result);

                            updateDeviceAvailability(pd.deviceId, true);

                            updateLastPolledAt(pd.deviceId);

                            results.put(pd.deviceId, true);

                            logger.debug("✅ [{}/{}] Device {} metrics collected", processedCount, devices.size(), pd.deviceName);
                        }
                        else
                        {
                            String error = result.getString("error", "Unknown error");

                            updateDeviceAvailability(pd.deviceId, false);

                            results.put(pd.deviceId, false);

                            logger.debug("❌ [{}/{}] Device {} failed: {}", processedCount, devices.size(), pd.deviceName, error);
                        }

                    }
                    catch (Exception exception)
                    {
                        logger.error("❌ Failed to parse GoEngine result line: {}", line, exception);
                    }
                }

                // Read any error output
                String errLine;

                while ((errLine = errorReader.readLine()) != null)
                {
                    errorOutput.append(errLine).append("\n");
                }
            }

            // Wait for process to complete
            boolean finished = process.waitFor(goEngineProcessTimeout, TimeUnit.SECONDS);

            if (!finished)
            {
                process.destroyForcibly();

                logger.warn("⏱️ GoEngine batch timeout after {} seconds", goEngineProcessTimeout);

                return results;
            }

            int exitCode = process.exitValue();

            if (exitCode != 0)
            {
                logger.warn("❌ GoEngine batch exited with code: {}", exitCode);

                if (errorOutput.length() > 0)
                {
                    logger.warn("❌ GoEngine stderr: {}", errorOutput.toString().trim());
                }
            }

            logger.info("✅ Batch poll completed: {}/{} devices processed successfully",
                results.values().stream().filter(v -> v).count(), devices.size());

        }
        catch (Exception exception)
        {
            logger.error("❌ Failed to execute GoEngine batch poll", exception);
        }

        return results;
    }

    /**
     * Phase 2: Retry Failed Devices (BATCH MODE)
     *
     * Retry devices that failed in Phase 1 using batch processing.
     * Uses the same batch approach as Phase 1 for consistency and efficiency.
     *
     * @param failedDevices Devices that failed in Phase 1
     * @param now Current timestamp
     * @return Future with list of exhausted devices (failed after all retries)
     */
    private Future<List<PollingDevice>> executePhaseRetryFailures(List<PollingDevice> failedDevices, Instant now)
    {
        Promise<List<PollingDevice>> promise = Promise.promise();

        if (failedDevices.isEmpty())
        {
            logger.info("✅ Phase 2: No failures to retry");

            promise.complete(new ArrayList<>());

            return promise.future();
        }

        logger.info("🔄 Phase 2: Retrying {} failed devices using batch mode", failedDevices.size());

        vertx.executeBlocking(blockingPromise ->
        {
            List<PollingDevice> exhaustedDevices = new ArrayList<>();

            // Retry using batch method (same as Phase 1)
            // Note: We do one retry attempt for all devices in batch mode
            // Individual retry_count is handled by consecutive_failures tracking
            Map<String, Boolean> retryResults = pollDeviceMetricsBatch(failedDevices);

            for (PollingDevice pd : failedDevices)
            {
                boolean success = retryResults.getOrDefault(pd.deviceId, false);

                if (success)
                {
                    pd.resetFailures();

                    pd.advanceSchedule();

                    logger.info("✅ Device {} succeeded on retry", pd.deviceName);
                }
                else
                {
                    exhaustedDevices.add(pd);

                    logger.warn("❌ Device {} failed retry attempt", pd.deviceName);
                }
            }

            blockingPromise.complete(exhaustedDevices);

        }, false, result ->
        {
            if (result.succeeded())
            {
                promise.complete((List<PollingDevice>) result.result());
            }
            else
            {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    /**
     * Phase 3: Log Exhausted Failures
     *
     * Log devices that failed after exhausting all retries to file.
     *
     * @param exhaustedDevices Devices that failed after all retries
     * @return Future<Void>
     */
    private Future<Void> executePhaseLogFailures(List<PollingDevice> exhaustedDevices)
    {
        Promise<Void> promise = Promise.promise();

        if (exhaustedDevices.isEmpty())
        {
            logger.info("✅ Phase 3: No exhausted failures to log");

            promise.complete();

            return promise.future();
        }

        logger.info("📝 Phase 3: Logging {} exhausted failures", exhaustedDevices.size());

        vertx.executeBlocking(blockingPromise ->
        {
            try
            {
                File logFile = new File(failureLogPath);

                logFile.getParentFile().mkdirs(); // Create directory if needed

                try (FileWriter fw = new FileWriter(logFile, true);
                     BufferedWriter bw = new BufferedWriter(fw);
                     PrintWriter out = new PrintWriter(bw))
                {
                    String timestamp = Instant.now().toString();

                    for (PollingDevice pd : exhaustedDevices)
                    {
                        String logEntry = String.format("%s | %s | %s | %s | failures=%d",
                            timestamp, pd.deviceId, pd.deviceName, pd.address, pd.consecutiveFailures);

                        out.println(logEntry);
                    }
                }

                logger.info("✅ Logged {} failures to {}", exhaustedDevices.size(), failureLogPath);

                blockingPromise.complete();

            }
            catch (IOException exception)
            {
                logger.error("❌ Failed to write failure log", exception);

                blockingPromise.fail(exception);
            }
        }, false, result ->
        {
            if (result.succeeded())
            {
                promise.complete();
            }
            else
            {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    /**
     * Phase 4: Auto-Disable
     *
     * Disable monitoring for devices that have exceeded max_cycles_skipped consecutive failures.
     *
     * @return Future<Void>
     */
    private Future<Void> executePhaseAutoDisable()
    {
        Promise<Void> promise = Promise.promise();

        logger.info("🔍 Phase 4: Checking for devices to auto-disable");

        List<PollingDevice> devicesToDisable = deviceCache.values().stream()
            .filter(pd -> pd.shouldAutoDisable(maxCyclesSkipped))
            .collect(Collectors.toList());

        if (devicesToDisable.isEmpty())
        {
            logger.info("✅ Phase 4: No devices to auto-disable");

            promise.complete();

            return promise.future();
        }

        logger.warn("⚠️ Phase 4: Auto-disabling {} devices", devicesToDisable.size());

        // Disable devices sequentially
        disableDevicesSequentially(devicesToDisable, 0, promise);

        return promise.future();
    }

    /**
     * Disable devices sequentially
     */
    private void disableDevicesSequentially(List<PollingDevice> devices, int index, Promise<Void> promise)
    {
        if (index >= devices.size())
        {
            promise.complete();
            return;
        }

        PollingDevice pd = devices.get(index);

        logger.warn("🚫 Auto-disabling device: {} (consecutive failures: {})", pd.deviceName, pd.consecutiveFailures);

        deviceService.deviceDisableMonitoring(pd.deviceId, ar ->
        {
            if (ar.succeeded())
            {
                logger.info("✅ Device {} monitoring disabled", pd.deviceName);

                // Remove from cache (will be removed by event handler too, but this is immediate)
                deviceCache.remove(pd.deviceId);
            }
            else
            {
                logger.error("❌ Failed to disable monitoring for device: {}", pd.deviceName, ar.cause());
            }

            // Continue with next device
            disableDevicesSequentially(devices, index + 1, promise);
        });
    }

    // ========== HELPER METHODS ==========

    /**
     * Update last_polled_at timestamp in database
     */
    private void updateLastPolledAt(String deviceId)
    {
        // This will be called after successful metrics collection
        // We can update the database asynchronously (fire and forget)
        vertx.eventBus().send("device.update.last_polled", new JsonObject().put("device_id", deviceId));
    }

    /**
     * Store metrics in database
     *
     * Transforms GoEngine response format to database schema format:
     * GoEngine: {"cpu":{"usage_percent":15.2},"memory":{...},"disk":{...},"duration_ms":582}
     * Database: {"cpu_usage_percent":15.2,"memory_usage_percent":...,"duration_ms":582}
     */
    private void storeMetrics(String deviceId, JsonObject goEngineResult)
    {
        // Extract nested metrics from GoEngine response
        JsonObject cpu = goEngineResult.getJsonObject("cpu");

        JsonObject memory = goEngineResult.getJsonObject("memory");

        JsonObject disk = goEngineResult.getJsonObject("disk");

        // Transform to database schema format
        JsonObject metricsData = new JsonObject()
            .put("device_id", deviceId)
            .put("duration_ms", goEngineResult.getInteger("duration_ms"))
            .put("cpu_usage_percent", cpu.getDouble("usage_percent"))
            .put("memory_usage_percent", memory.getDouble("usage_percent"))
            .put("memory_total_bytes", memory.getLong("total_bytes"))
            .put("memory_used_bytes", memory.getLong("used_bytes"))
            .put("memory_free_bytes", memory.getLong("free_bytes"))
            .put("disk_usage_percent", disk.getDouble("usage_percent"))
            .put("disk_total_bytes", disk.getLong("total_bytes"))
            .put("disk_used_bytes", disk.getLong("used_bytes"))
            .put("disk_free_bytes", disk.getLong("free_bytes"));

        metricsService.metricsCreate(metricsData, ar ->
        {
            if (ar.succeeded())
            {
                logger.info("✅ Metrics stored for device: {}", deviceId);
            }
            else
            {
                logger.error("❌ Failed to store metrics for device: {}", deviceId, ar.cause());
            }
        });
    }

    /**
     * Update device availability status
     */
    private void updateDeviceAvailability(String deviceId, boolean isAvailable)
    {
        String status = isAvailable ? "UP" : "DOWN";

        Long responseTime = isAvailable ? 100L : null; // Simple response time

        availabilityService.availabilityUpdateDeviceStatus(deviceId, status, responseTime, ar ->
        {
            if (ar.failed())
            {
                logger.error("❌ Failed to record availability for device: {}", deviceId, ar.cause());
            }
        });
    }

    private void setupEventBusConsumers()
    {
        // Handle provision requests
        vertx.eventBus().consumer("provision.start", message ->
        {
            JsonObject request = (JsonObject) message.body();

            handleProvisionStart(message, request);
        });

        // Handle polling status requests
        vertx.eventBus().consumer("polling.status", message ->
        {
            message.reply(pollingStatus.copy());
        });

        // NEW: Cache update consumers
        vertx.eventBus().consumer("device.monitoring.enabled", msg ->
        {
            JsonObject data = (JsonObject) msg.body();

            String deviceId = data.getString("device_id");

            onDeviceMonitoringEnabled(deviceId);
        });

        vertx.eventBus().consumer("device.monitoring.disabled", msg ->
        {
            JsonObject data = (JsonObject) msg.body();

            String deviceId = data.getString("device_id");

            onDeviceMonitoringDisabled(deviceId);
        });

        vertx.eventBus().consumer("device.config.updated", msg ->
        {
            JsonObject data = (JsonObject) msg.body();

            String deviceId = data.getString("device_id");

            onDeviceConfigUpdated(deviceId);
        });

        logger.info("📡 Polling event bus consumers setup complete");
    }

    /**
     * Handle device monitoring enabled event.
     * Fetch device from database and add to cache.
     */
    private void onDeviceMonitoringEnabled(String deviceId)
    {
        logger.info("📥 Device monitoring enabled event: {}", deviceId);

        deviceService.deviceGetById(deviceId, ar ->
        {
            if (ar.succeeded())
            {
                JsonObject deviceData = ar.result();

                if (!deviceData.getBoolean("found", false))
                {
                    logger.warn("⚠️ Device {} not found", deviceId);

                    return;
                }

                if (!deviceData.getBoolean("is_monitoring_enabled", false))
                {
                    logger.warn("⚠️ Device {} is not monitoring enabled", deviceId);

                    return;
                }

                try
                {
                    PollingDevice pd = createPollingDeviceFromJson(deviceData);

                    deviceCache.put(pd.deviceId, pd);

                    logger.info("✅ Device {} added to cache | Interval: {}s | Next poll: {}",
                              pd.deviceName, pd.pollingIntervalSeconds, pd.nextScheduledAt);
                }
                catch (Exception exception)
                {
                    logger.error("❌ Failed to add device {} to cache", deviceId, exception);
                }
            }
            else
            {
                logger.error("❌ Failed to fetch device {} for cache", deviceId, ar.cause());
            }
        });
    }

    /**
     * Handle device monitoring disabled event.
     * Remove device from cache.
     */
    private void onDeviceMonitoringDisabled(String deviceId)
    {
        logger.info("📤 Device monitoring disabled event: {}", deviceId);

        PollingDevice removed = deviceCache.remove(deviceId);

        if (removed != null)
        {
            logger.info("✅ Device {} removed from cache", removed.deviceName);
        }
        else
        {
            logger.warn("⚠️ Device {} not found in cache", deviceId);
        }
    }

    /**
     * Handle device config updated event.
     * Reload device from database and update cache.
     */
    private void onDeviceConfigUpdated(String deviceId)
    {
        logger.info("🔄 Device config updated event: {}", deviceId);

        deviceService.deviceGetById(deviceId, ar ->
        {
            if (ar.succeeded())
            {
                JsonObject deviceData = ar.result();

                if (!deviceData.getBoolean("found", false))
                {
                    logger.warn("⚠️ Device {} not found", deviceId);

                    return;
                }

                try
                {
                    PollingDevice pd = createPollingDeviceFromJson(deviceData);

                    deviceCache.put(pd.deviceId, pd);

                    logger.info("✅ Device {} updated in cache - next poll: {}",
                              pd.deviceName, pd.nextScheduledAt);
                }
                catch (Exception exception)
                {
                    logger.error("❌ Failed to update device {} in cache", deviceId, exception);
                }
            }
            else
            {
                logger.error("❌ Failed to fetch device {} for cache update", deviceId, ar.cause());
            }
        });
    }

    private void startPeriodicPolling()
    {
        // Use cycleIntervalSeconds (default 300s = 5 min) instead of intervalSeconds
        pollingTimerId = vertx.setPeriodic(cycleIntervalSeconds * 1000L, timerId ->
        {
            if (!isPolling)
            {
                executePollingCycle();
            }
            else
            {
                logger.debug("⏳ Skipping polling cycle - previous cycle still running");
            }
        });

        logger.info("⏰ Periodic polling started - cycle interval: {} seconds", cycleIntervalSeconds);
    }

    /**
     * Execute 4-phase polling cycle:
     *
     * Phase 1: Batch Processing
     *   - Filter devices due for polling (using aligned scheduling)
     *   - Process in batches with fping + GoEngine
     *   - Track failures
     *
     * Phase 2: Retry Failed Devices
     *   - Retry devices that failed in Phase 1
     *   - Use per-device retry_count
     *
     * Phase 3: Log Exhausted Failures
     *   - Log devices that failed after all retries to file
     *
     * Phase 4: Auto-Disable
     *   - Disable monitoring for devices exceeding max_cycles_skipped
     */
    private void executePollingCycle()
    {
        isPolling = true;

        long startTime = System.currentTimeMillis();

        Instant now = Instant.now();

        logger.debug("🔄 Polling cycle check at {}", now);

        // Get devices due for polling from cache
        List<PollingDevice> dueDevices = deviceCache.values().stream()
            .filter(pd -> pd.isDue(now))
            .collect(Collectors.toList());

        // Log all devices and their schedules for debugging
        if (logger.isDebugEnabled())
        {
            deviceCache.values().forEach(pd ->
            {
                boolean isDue = pd.isDue(now);

                logger.debug("  Device: {} | Next: {} | Due: {}",
                    pd.deviceName, pd.nextScheduledAt, isDue ? "YES" : "NO");
            });
        }

        if (dueDevices.isEmpty())
        {
            logger.debug("📭 No devices due for polling at this time (total devices in cache: {})",
                deviceCache.size());

            isPolling = false;

            return;
        }

        logger.info("📊 Found {} devices due for polling at {}", dueDevices.size(), now);

        dueDevices.forEach(pd -> logger.info("  ✓ Device due: {} (next scheduled: {})",
            pd.deviceName, pd.nextScheduledAt));

        // Phase 1: Batch Processing
        executePhaseBatchProcessing(dueDevices, now)
            .compose(failedDevices ->
            {
                // Phase 2: Retry Failed Devices
                return executePhaseRetryFailures(failedDevices, now);
            })
            .compose(exhaustedDevices ->
            {
                // Phase 3: Log Exhausted Failures
                return executePhaseLogFailures(exhaustedDevices);
            })
            .compose(v ->
            {
                // Phase 4: Auto-Disable
                return executePhaseAutoDisable();
            })
            .onComplete(result ->
            {
                isPolling = false;

                long duration = System.currentTimeMillis() - startTime;

                if (result.succeeded())
                {
                    logger.info("✅ Polling cycle completed in {}ms", duration);
                }
                else
                {
                    logger.error("❌ Polling cycle failed in {}ms", duration, result.cause());
                }
            });
    }

    // ========================================
    // BATCH CONNECTIVITY CHECKS (fping + port)
    // ========================================

    /**
     * Execute batch fping check for multiple IPs (EFFICIENT - single fping process)
     *
     * Uses fping's batch mode: writes all IPs to stdin and reads results from stdout.
     * This is much more efficient than running individual fping processes.
     *
     * @param ips List of IP addresses to check
     * @return Map of IP -> reachability status
     */
    private Map<String, Boolean> executeBatchFping(List<String> ips)
    {
        Map<String, Boolean> results = new HashMap<>();

        try
        {
            List<String> command = Arrays.asList(
                fpingPath, "-a", "-q", "-t", String.valueOf(networkTimeoutSeconds * 1000) // -a: show alive, -q: quiet, -t: timeout in ms
            );

            ProcessBuilder pb = new ProcessBuilder(command);

            Process process = pb.start();

            // Write IPs to stdin and close it
            try (var writer = process.outputWriter())
            {
                for (String ip : ips)
                {
                    writer.write(ip + "\n");

                    results.put(ip, false); // Default to dead
                }
            } // Writer is closed here, signaling EOF to fping

            // Wait for process to complete with timeout
            boolean finished = process.waitFor(networkTimeoutSeconds + 10, TimeUnit.SECONDS);

            if (!finished)
            {
                process.destroyForcibly();

                logger.warn("fping process timed out after {} seconds", networkTimeoutSeconds + 10);

                return results; // Return all as dead
            }

            // Read alive IPs from stdout (after process completes)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
            {
                String line;

                while ((line = reader.readLine()) != null)
                {
                    String aliveIp = line.trim();

                    if (results.containsKey(aliveIp))
                    {
                        results.put(aliveIp, true);
                    }
                }
            }

        }
        catch (Exception exception)
        {
            logger.error("fping execution failed during polling", exception);

            // Fallback: mark all as dead
            for (String ip : ips)
            {
                results.put(ip, false);
            }
        }

        return results;
    }

    /**
     * Execute batch port connectivity check using Java Socket
     *
     * Checks if the service port (SSH 22 or WinRM 5985) is reachable.
     * Uses parallel streams for concurrent port checks with timeout.
     *
     * @param devices List of devices to check
     * @return Map of device_id -> port reachability status
     */
    private Map<String, Boolean> executeBatchPortCheck(List<PollingDevice> devices)
    {
        Map<String, Boolean> results = new HashMap<>();

        if (devices.isEmpty())
        {
            return results;
        }

        // Port check timeout (shorter than GoEngine timeout)
        int portCheckTimeoutMs = 3000; // 3 seconds

        // Use parallel stream for concurrent port checks
        devices.parallelStream().forEach(pd ->
        {
            boolean portOpen = false;

            try
            {
                // Try to connect to the port
                try (java.net.Socket socket = new java.net.Socket())
                {
                    socket.connect(
                        new java.net.InetSocketAddress(pd.address, pd.port),
                        portCheckTimeoutMs
                    );

                    portOpen = true;

                    logger.debug("✅ Port check passed: {}:{}", pd.address, pd.port);
                }
            }
            catch (java.net.SocketTimeoutException exception)
            {
                logger.debug("⏱️ Port check timeout: {}:{}", pd.address, pd.port);
            }
            catch (java.io.IOException exception)
            {
                logger.debug("❌ Port check failed: {}:{} - {}", pd.address, pd.port, exception.getMessage());
            }

            synchronized (results)
            {
                results.put(pd.deviceId, portOpen);
            }
        });

        long successCount = results.values().stream().filter(v -> v).count();

        logger.info("📊 Port check results: {}/{} ports reachable", successCount, devices.size());

        return results;
    }

    // ========================================
    // EVENT BUS HANDLERS (Provisioning)
    // ========================================

    private void handleProvisionStart(io.vertx.core.eventbus.Message<Object> message, JsonObject request)
    {
        // Handle manual provision requests
        String profileId = request.getString("profile_id");

        if (profileId == null)
        {
            message.fail(400, "profile_id is required");

            return;
        }

        vertx.<JsonObject>executeBlocking(promise ->
        {
            // Get discovery profile and credentials
            vertx.eventBus().request("db.query", new JsonObject()
                    .put("operation", "get_discovery_and_credentials")
                    .put("params", new JsonObject().put("profile_id", profileId)))
                .onSuccess(reply ->
                {
                    JsonObject deviceConfig = (JsonObject) reply.body();

                    // Execute provision (similar to discovery but for single device)
                    executeProvision(deviceConfig, promise);
                })
                .onFailure(promise::fail);
        }, false, result ->
        {
            if (result.succeeded())
            {
                message.reply(result.result());
            }
            else
            {
                message.fail(500, "Provision failed: " + result.cause().getMessage());
            }
        });
    }

    private void executeProvision(JsonObject deviceConfig, Promise<JsonObject> promise)
    {
        try
        {
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

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
            {
                String line;

                while ((line = reader.readLine()) != null)
                {
                    try
                    {
                        result = new JsonObject(line);

                        break; // Only one device
                    }
                    catch (Exception exception)
                    {
                        logger.warn("Failed to parse provision result: {}", line);
                    }
                }
            }

            process.waitFor();

            if (result != null)
            {
                promise.complete(result);
            }
            else
            {
                promise.fail("No result received from GoEngine");
            }

        }
        catch (Exception exception)
        {
            logger.error("Provision execution failed", exception);

            promise.fail(exception);
        }
    }

    /**
     * Stop the verticle: cancel periodic polling and clear device cache.
     *
     * @param stopPromise promise completed once the verticle is stopped
     */
    @Override
    public void stop(Promise<Void> stopPromise)
    {
        logger.info("🛑 Stopping PollingMetricsVerticle");

        if (pollingTimerId != 0)
        {
            vertx.cancelTimer(pollingTimerId);

            logger.info("⏰ Periodic polling stopped");
        }

        // NEW: Clear cache
        if (deviceCache != null)
        {
            int cacheSize = deviceCache.size();

            deviceCache.clear();

            logger.info("🗑️ Device cache cleared ({} devices)", cacheSize);
        }

        stopPromise.complete();
    }
}
