/*
 * METRIC POLLING FUNCTIONALITY
 *
 * PollingMetricsVerticle provides continuous device monitoring capabilities.
 *
 * Features:
 * - Periodic polling of active devices
 * - Batch fping for connectivity validation
 * - GoEngine metrics collection for alive devices
 * - Device availability tracking
 */

package com.nmslite.verticles;

import com.nmslite.core.NetworkConnectivity;
import com.nmslite.models.PollingDevice;

import com.nmslite.models.PollingResult;

import com.nmslite.services.DeviceService;

import com.nmslite.services.MetricsService;

import com.nmslite.services.AvailabilityService;

import com.nmslite.utils.PasswordUtil;

import com.nmslite.core.QueueBatchProcessor;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.io.*;

import java.time.Instant;

import java.time.LocalDateTime;

import java.time.format.DateTimeFormatter;

import java.util.*;

import java.util.concurrent.TimeUnit;

import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

/**
 * PollingMetricsVerticle - Continuous Device Monitoring

 * Responsibilities:
 * - Periodic polling of active devices
 * - Batch fping for connectivity validation
 * - GoEngine metrics collection for alive devices
 * - Device availability tracking
 */
public class PollingMetricsVerticle extends AbstractVerticle
{

    private static final Logger logger = LoggerFactory.getLogger(PollingMetricsVerticle.class);

    // Configuration
    private String goEnginePath;

    private int batchSize;

    private long pollingTimerId;

    private int blockingTimeoutGoEngine;

    private int defaultConnectionTimeoutSeconds;  // Default connection timeout for all devices

    // Polling cycle configuration
    private int cycleIntervalSeconds;        // How often scheduler checks for due devices

    private int maxCyclesSkipped;            // Auto-disable threshold

    private String failureLogPath;           // Failure log file path

    // Service proxies
    private DeviceService deviceService;

    private MetricsService metricsService;

    private AvailabilityService availabilityService;

    // In-memory device cache
    // Key: device_id, Value: PollingDevice (persistent data + runtime state)
    private HashMap<String, PollingDevice> deviceCache;

    /**
     * Start the verticle: load configuration, initialize service proxies, load devices into cache, and start polling.
     *
     * @param startPromise promise completed once the verticle is ready
     */
    @Override
    public void start(Promise<Void> startPromise)
    {
        try
        {
            logger.info("Starting PollingMetricsVerticle");

            // Load configuration from tools and polling sections
            var toolsConfig = config().getJsonObject("tools", new JsonObject());

            var pollingConfig = config().getJsonObject("polling", new JsonObject());

            goEnginePath = toolsConfig.getString("goengine.path", "./goengine/goengine");

            // Load polling configuration
            cycleIntervalSeconds = pollingConfig.getInteger("cycle.interval.seconds", 60);

            batchSize = pollingConfig.getInteger("batch.size", 50);

            maxCyclesSkipped = pollingConfig.getInteger("max.cycles.skipped", 5);

            failureLogPath = pollingConfig.getString("failure.log.path", "polling_failed/metrics_polling_failed.txt");

            blockingTimeoutGoEngine = pollingConfig.getInteger("blocking.timeout.goengine", 330);

            defaultConnectionTimeoutSeconds = pollingConfig.getInteger("connection.timeout.seconds", 10);

            // Initialize service proxies
            initializeServiceProxies();

            // Initialize CACHE (in-memory device store for fast lookups)
            deviceCache = new HashMap<>();

            // Load devices into cache
            loadDevicesIntoCache()
                .onSuccess(count ->
                {
                    logger.info("Device cache initialized: {} devices loaded (provisioned + monitoring enabled)", count);

                    setupEventBusConsumers();

                    // Starting polling scheduler
                    startPeriodicPolling();

                    logger.info("PollingMetricsVerticle started successfully");

                    startPromise.complete();
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to load devices into cache: {}", cause.getMessage());

                    startPromise.fail(cause);
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in start: {}", exception.getMessage());

            startPromise.fail(exception);
        }
    }

    /**
     * Initialize service proxies for database operations
     */
    private void initializeServiceProxies()
    {
        try
        {
            this.deviceService = DeviceService.createProxy();

            this.metricsService = MetricsService.createProxy();

            this.availabilityService = AvailabilityService.createProxy();
        }
        catch (Exception exception)
        {
            logger.error("Error in initializeServiceProxies: {}", exception.getMessage());
        }
    }

    /**
     * Load all eligible devices from database into in-memory cache.

     * Queries devices where:
     * - is_provisioned = true
     * - is_monitoring_enabled = true
     * - is_deleted = false

     * Joins with credential_profiles to get username and password.
     *
     * @return Future with count of devices loaded
     */
    private Future<Integer> loadDevicesIntoCache()
    {
        var promise = Promise.<Integer>promise();

        try
        {
            deviceService.deviceListProvisionedAndMonitoringEnabled()
                .compose(devices ->
                {
                    // avoid blocking event loop, if large number of devices to be cached
                    return vertx.executeBlocking(() ->
                    {
                        var count = 0;

                        for (var obj : devices)
                        {
                            var deviceData = (JsonObject) obj;

                            try
                            {
                                var pd = createPollingDeviceFromJson(deviceData);

                                deviceCache.put(pd.deviceId, pd);

                                count++;
                            }
                            catch (Exception exception)
                            {
                                logger.error("Failed to cache device {}: {}", deviceData.getString("device_name"), exception.getMessage());
                            }
                        }

                        return count;
                    });
                })
                .onSuccess(promise::complete)
                .onFailure(cause ->
                {
                    logger.error("Failed to query devices for cache: {}", cause.getMessage());

                    promise.fail(cause);
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in loadDevicesIntoCache: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Create PollingDevice from database JSON.

     * Maps database fields to PollingDevice model and computes runtime state.
     *
     * @param deviceData JSON from database query
     * @return PollingDevice instance
     */
    private PollingDevice createPollingDeviceFromJson(JsonObject deviceData)
    {
        try
        {
            var pd = new PollingDevice();

            // Identity
            pd.deviceId = deviceData.getString("device_id");

            pd.deviceName = deviceData.getString("device_name");

            // GoEngine required fields
            // Strip CIDR notation from IP address (e.g., "192.168.1.1/32" → "192.168.1.1")
            var ipWithCidr = deviceData.getString("ip_address");

            pd.address = ipWithCidr.contains("/") ? ipWithCidr.split("/")[0] : ipWithCidr;

            pd.deviceType = deviceData.getString("device_type");

            pd.username = deviceData.getString("username");

            // Decrypt password for GoEngine use
            pd.password = PasswordUtil.decryptPassword(deviceData.getString("password_encrypted"));

            if (pd.password == null)
            {
                logger.error("Failed to decrypt password for device {}", pd.deviceId);

                pd.password = ""; // Set empty password on decryption failure
            }

            pd.port = deviceData.getInteger("port");

            // Per-device config (from devices table, NOT config file)
            pd.timeoutSeconds = deviceData.getInteger("timeout_seconds");

            pd.pollingIntervalSeconds = deviceData.getInteger("polling_interval_seconds");

            // Global config (from config file, same for all devices)
            pd.connectionTimeoutSeconds = defaultConnectionTimeoutSeconds;

            // Timestamps (PostgresSQL returns timestamps without 'Z', need to append it for ISO-8601)
            var monitoringEnabledAtStr = deviceData.getString("monitoring_enabled_at");

            pd.monitoringEnabledAt = Instant.parse(monitoringEnabledAtStr + "Z");

            // Compute aligned next poll time
            pd.nextScheduledAt = computeAlignedNext(pd.monitoringEnabledAt, Instant.now(), pd.pollingIntervalSeconds);

            // Initialize runtime state
            pd.consecutiveFailures = 0;

            return pd;
        }
        catch (Exception exception)
        {
            logger.error("Error in createPollingDeviceFromJson: {}", exception.getMessage());

            return null;
        }
    }

    /**
     * Compute aligned next poll time from anchor.

     * This ensures fixed cadence without drift:
     * - Anchor: monitoring_enabled_at
     * - Next = anchor + ceil((now - anchor) / interval) * interval

     * Example:
     * - Anchor: 10:00:00 (device monitoring enabled)
     * - Interval: 600s (10 min)
     * - Now: 11:14:00 (current time)
     * - Elapsed: 4440s (1 hour 14 minutes)
     * - Cycles passed: 7 (4440 / 600 = 7.4)
     * - Next cycle: 8
     * - Next poll: 10:00:00 + (8 × 600s) = 10:00:00 + 4800s = 11:20:00

     * Timeline:
     * 10:00:00 → 10:10:00 → 10:20:00 → 10:30:00 → 10:40:00 → 10:50:00 → 11:00:00 → 11:10:00 → [11:20:00] ← Next
     * Cycle 0    Cycle 1    Cycle 2    Cycle 3    Cycle 4    Cycle 5    Cycle 6    Cycle 7    Cycle 8
     *                                                                                 ↑ Now (11:14:00)
     *
     * @param anchor monitoring_enabled_at timestamp
     * @param now Current time
     * @param intervalSeconds Polling interval
     * @return Next scheduled poll time
     */
    private Instant computeAlignedNext(Instant anchor, Instant now, long intervalSeconds)
    {
        try
        {
            var elapsedSeconds = now.getEpochSecond() - anchor.getEpochSecond();

            var cyclesPassed = elapsedSeconds / intervalSeconds;

            var nextCycle = cyclesPassed + 1;

            return anchor.plusSeconds(nextCycle * intervalSeconds);
        }
        catch (Exception exception)
        {
            logger.error("Error in computeAlignedNext: {}", exception.getMessage());

            return now.plusSeconds(intervalSeconds);
        }
    }

    private void setupEventBusConsumers()
    {
        try
        {
            // Cache update consumers -> to persist up-to-date device data in cache
            vertx.eventBus().consumer("device.monitoring.enabled", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceMonitoringEnabled(deviceId);
            });

            vertx.eventBus().consumer("device.monitoring.disabled", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceMonitoringDisabled(deviceId);
            });

            vertx.eventBus().consumer("device.config.updated", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceConfigUpdated(deviceId);
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in setupEventBusConsumers: {}", exception.getMessage());
        }
    }

    /**
     * Handle device monitoring enabled event.
     * Add device to cache.

     * Event is only published after successful database update,
     * so no validation checks are needed - just fetch and cache.
     *
     * @param deviceId Device ID to add to cache
     */
    private void onDeviceMonitoringEnabled(String deviceId)
    {
        try
        {
            logger.debug("Device monitoring enabled event: {}", deviceId);

            deviceService.deviceGetById(deviceId)
                    .onSuccess(deviceData ->
                    {
                        try
                        {
                            var pd = createPollingDeviceFromJson(deviceData);

                            deviceCache.put(pd.deviceId, pd);

                            logger.info("Device cache updated: {} added (total cached: {})", pd.deviceName, deviceCache.size());
                        }
                        catch (Exception exception)
                        {
                            logger.error("Failed to add device {} to cache: {}", deviceId, exception.getMessage());
                        }
                    })
                    .onFailure(cause ->
                            logger.error("Failed to fetch device {} for cache: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceMonitoringEnabled: {}", exception.getMessage());
        }
    }

    /**
     * Handle device monitoring disabled event.
     * Remove device from cache.
     *
     * @param deviceId Device ID to add to cache
     */
    private void onDeviceMonitoringDisabled(String deviceId)
    {
        try
        {
            logger.debug("Device monitoring disabled event: {}", deviceId);

            var removed = deviceCache.remove(deviceId);

            if (removed != null)
            {
                logger.info("Device cache updated: {} removed (total cached: {})", removed.deviceName, deviceCache.size());
            }
            else
            {
                logger.debug("Device {} not found in cache", deviceId);
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceMonitoringDisabled: {}", exception.getMessage());
        }
    }

    /**
     * Handle device config updated event.
     * Reload device from database and update cache.
     *
     * @param deviceId Device ID to add to cache
     */
    private void onDeviceConfigUpdated(String deviceId)
    {
        try
        {
            logger.debug("Device config updated event: {}", deviceId);

            deviceService.deviceGetById(deviceId)
                    .onSuccess(deviceData ->
                    {
                        try
                        {
                            var pd = createPollingDeviceFromJson(deviceData);

                            deviceCache.put(pd.deviceId, pd);  // simply overwrite existing entry

                            logger.info("Device cache updated: {} config refreshed (total cached: {})", pd.deviceName, deviceCache.size());
                        }
                        catch (Exception exception)
                        {
                            logger.error("Failed to update device {} in cache: {}", deviceId, exception.getMessage());
                        }
                    })
                    .onFailure(cause ->
                            logger.error("Failed to fetch device {} for cache update: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceConfigUpdated: {}", exception.getMessage());
        }
    }

    private void startPeriodicPolling()
    {
        try
        {
            pollingTimerId = vertx.setPeriodic(cycleIntervalSeconds * 1000L, timerId -> executePollingCycle());

            logger.info("Periodic polling started with {} second interval", cycleIntervalSeconds);
        }
        catch (Exception exception)
        {
            logger.error("Error in startPeriodicPolling: {}", exception.getMessage());
        }
    }

    /**
     * Execute 4-phase polling cycle:

     * Phase 1: Batch Processing
     *   - Filter devices due for polling (using aligned scheduling)
     *   - Process in batches with fping + GoEngine
     *   - Track failures

     * Phase 2: Retry Failed Devices
     *   - Retry devices that failed in Phase 1 (1 retry attempt for all devices)
     *   - Uses batch processing for retry attempt

     * Phase 3: Log Exhausted Failures
     *   - Log devices that failed after retry to file

     * Phase 4: Auto-Disable
     *   - Disable monitoring for devices exceeding max_cycles_skipped
     */
    private void executePollingCycle()
    {
        try
        {
            var startTime = System.currentTimeMillis();

            var now = Instant.now();

            var totalCachedDevices = deviceCache.size();

            // Get due devices for polling from cache (moved to worker thread to avoid blocking)
            vertx.executeBlocking(() -> deviceCache.values().stream().filter(pd -> pd.isDue(now)).collect(Collectors.toList()))
                    .onSuccess(dueDevices ->
                    {
                        if (dueDevices.isEmpty())
                        {
                            logger.debug("Polling cycle: 0 devices due (total cached: {})", totalCachedDevices);

                            return;
                        }

                        logger.info("Polling cycle: {} devices due for polling (total cached: {})", dueDevices.size(), totalCachedDevices);

                        // Phase 1: Batch Processing
                        // Phase 2: Retry Failed Devices, for current batch only
                        // Phase 3: Log Exhausted Failures, for current batch only
                        executeBatchProcessing(dueDevices)
                                .compose(this::executeRetryFailures)
                                .compose(this::executeLogFailures)
                                .compose(v ->
                                {
                                    // Phase 4: Auto-Disable
                                    return executePhaseAutoDisable();
                                })
                                .onComplete(result ->
                                {
                                    var duration = System.currentTimeMillis() - startTime;

                                    if (result.succeeded())
                                    {
                                        logger.info("Polling cycle completed successfully in {}ms", duration);
                                    }
                                    else
                                    {
                                        logger.error("Polling cycle failed in {}ms: {}", duration, result.cause().getMessage());
                                    }
                                });
                    })
                    .onFailure(cause ->
                            logger.error("Failed to filter due devices from cache: {}", cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in executePollingCycle: {}", exception.getMessage());
        }
    }

    // ========== 4-PHASE POLLING CYCLE IMPLEMENTATION ==========

    /**
     * Phase 1: Batch Processing

     * Process devices in batches:
     * 1. Batch fping connectivity check
     * 2. For alive devices: GoEngine metrics collection
     * 3. For dead devices: Record connectivity failure
     * 4. Update device schedules and failure counters

     * Uses QueueBatchProcessor for sequential batch processing.
     *
     * @param dueDevices List of devices due for polling
     * @return Future with list of failed devices (for retry)
     */
    private Future<List<PollingDevice>> executeBatchProcessing(List<PollingDevice> dueDevices)
    {
        var promise = Promise.<List<PollingDevice>>promise();

        try
        {
            logger.debug("Phase 1: Batch processing {} devices", dueDevices.size());

            // Use QueueBatchProcessor for sequential batch processing
            var processor = new PollingBatchProcessor(dueDevices);

            var batchPromise = Promise.<JsonArray>promise();

            processor.processNext(batchPromise);

            batchPromise.future()
                .onSuccess(results ->
                {
                    var failedDevices = processor.getFailedDevices();

                    logger.info("Phase 1 completed: {}/{} devices processed successfully",
                        dueDevices.size() - failedDevices.size(), dueDevices.size());

                    promise.complete(failedDevices);
                })
                .onFailure(promise::fail);
        }
        catch (Exception exception)
        {
            logger.error("Error in executeBatchProcessing: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Process a single batch of devices (OPTIMIZED - in-place processing)

     * Optimizations:
     * 1. Process devices in-place using PollingResult enum for state tracking
     * 2. Single device map created once and reused (not recreated)
     * 3. No intermediate JsonArrays or Lists - direct device object manipulation
     * 4. Collect failures at the end in one pass using filter
     * 5. Clear state machine: NOT_PROCESSED → CONNECTIVITY_FAILED/SUCCESS/GOENGINE_FAILED

     * Flow:
     * 1. Reset all devices to NOT_PROCESSED state
     * 2. Perform connectivity checks (fping + port)
     * 3. Mark unreachable devices as CONNECTIVITY_FAILED
     * 4. Execute GoEngine for reachable devices
     * 5. Mark GoEngine results as SUCCESS or GOENGINE_FAILED
     * 6. Return all failed devices in one pass
     */
    private Future<List<PollingDevice>> processSingleBatch(List<PollingDevice> batch)
    {
        try
        {
            // Reset polling results for all devices in batch
            batch.forEach(PollingDevice::resetPollingResult);

            // Create device map ONCE for O(1) lookups throughout the method
            var deviceMap = batch.stream().collect(toMap(d -> d.deviceId, d -> d));

            // Step 1: Perform connectivity checks (fping + port check)
            return performConnectivityChecksForPolling(batch)
            .compose(connectivityResults ->
            {
                var reachableDevices = connectivityResults.getJsonArray("reachable");

                var unreachableDevices = connectivityResults.getJsonArray("unreachable");

                logger.debug("Connectivity check: {}/{} devices reachable", reachableDevices.size(), batch.size());

                // Step 2: Mark unreachable devices as CONNECTIVITY_FAILED
                // Note: This loop runs on event loop but is fast (O(n) with O(1) HashMap lookups)
                // For batch sizes < 1000, this is safe (< 100ms)
                // For larger batches, consider moving to executeBlocking
                for (var i = 0; i < unreachableDevices.size(); i++)
                {
                    var deviceId = unreachableDevices.getString(i);

                    var pd = deviceMap.get(deviceId);

                    if (pd != null)
                    {
                        pd.pollingResult = PollingResult.CONNECTIVITY_FAILED;

                        pd.incrementFailures();

                        updateDeviceAvailability(pd.deviceId, false);
                    }
                }

                // Step 3: Execute GoEngine for reachable devices
                if (reachableDevices.isEmpty())
                {
                    logger.debug("No reachable devices in batch, skipping GoEngine");

                    return Future.succeededFuture(
                        batch.stream().filter(PollingDevice::hasFailed).toList()
                    );
                }

                return executeGoEngineForReachableDevices(batch, reachableDevices)
                    .map(metricsResults ->
                    {
                        // Step 4: Process GoEngine results and mark device states
                        for (var i = 0; i < reachableDevices.size(); i++)
                        {
                            var deviceId = reachableDevices.getString(i);

                            var pd = deviceMap.get(deviceId);

                            if (pd != null)
                            {
                                var success = metricsResults.getOrDefault(deviceId, false);

                                if (success)
                                {
                                    pd.pollingResult = PollingResult.SUCCESS;

                                    pd.resetFailures();

                                    pd.advanceSchedule();
                                }
                                else
                                {
                                    pd.pollingResult = PollingResult.GOENGINE_FAILED;

                                    pd.incrementFailures();
                                }
                            }
                        }

                        // Return all failed devices in one pass (CONNECTIVITY_FAILED + GOENGINE_FAILED)
                        return batch.stream().filter(PollingDevice::hasFailed).toList();
                    });
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in processSingleBatch: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Execute GoEngine metrics collection for reachable devices (similar to DiscoveryVerticle)

     * Uses executeBlocking to run GoEngine process and stream results.
     * Only processes devices that passed connectivity checks.
     *
     * @param allDevices All devices in the batch
     * @param reachableDeviceIds JsonArray of device IDs that passed connectivity checks
     * @return Future<Map<String, Boolean>> - Map of device_id -> success status
     */
    private Future<Map<String, Boolean>> executeGoEngineForReachableDevices(
        List<PollingDevice> allDevices, JsonArray reachableDeviceIds)
    {
        var promise = Promise.<Map<String, Boolean>>promise();

        try
        {
            // Filter to only reachable devices
            var reachableDevices = allDevices.stream().filter(pd -> {
                    for (var i = 0; i < reachableDeviceIds.size(); i++)
                    {
                        if (reachableDeviceIds.getString(i).equals(pd.deviceId))
                        {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());

            vertx.executeBlocking(() -> pollDeviceMetricsBatch(reachableDevices), false)
            .onSuccess(promise::complete)
            .onFailure(cause ->
            {
                logger.error("GoEngine metrics execution failed: {}", cause.getMessage());

                promise.fail(cause);
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in executeGoEngineForReachableDevices: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Poll metrics for multiple devices in a batch using GoEngine streaming output

     * GoEngine processes devices in parallel and outputs results as they become available.
     * Each result is a separate JSON line on stdout.

     * This method runs in a worker thread (called from executeBlocking).
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
            var devicesArray = new JsonArray();

            for (var pd : devices)
            {
                devicesArray.add(pd.toGoEngineJson());
            }

            // Execute GoEngine with batch input
            var pb = new ProcessBuilder(
                goEnginePath,
                "--mode", "metrics",
                "--devices", devicesArray.encode()
            );

            var process = pb.start();

            // Create a map for quick device lookup by IP address
            var devicesByIp = new HashMap<String, PollingDevice>();

            for (var pd : devices)
            {
                devicesByIp.put(pd.address, pd);
            }

            // Read streaming output line by line and process results as they arrive
            var errorOutput = new StringBuilder();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 var errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream())))
            {
                String line;

                while ((line = reader.readLine()) != null)
                {
                    try
                    {
                        // Parse JSON result for this device
                        var result = new JsonObject(line);

                        var deviceAddress = result.getString("device_address");

                        var success = result.getBoolean("success", false);

                        // Find the device by IP address
                        var pd = devicesByIp.get(deviceAddress);

                        if (success)
                        {
                            // Store metrics and update availability
                            storeMetrics(pd.deviceId, result);

                            updateDeviceAvailability(pd.deviceId, true);

                            results.put(pd.deviceId, true);
                        }
                        else
                        {
                            updateDeviceAvailability(pd.deviceId, false);

                            results.put(pd.deviceId, false);
                        }

                    }
                    catch (Exception exception)
                    {
                        logger.error("Failed to parse GoEngine result line {}: {}", line, exception.getMessage());
                    }
                }

                // Read any error output
                String errLine;

                while ((errLine = errorReader.readLine()) != null)
                {
                    errorOutput.append(errLine).append("\n");
                }
            }

            // Wait for process to complete (timeout handled by Vert.x blocking timeout)
            var finished = process.waitFor(blockingTimeoutGoEngine, TimeUnit.SECONDS);

            if (!finished)
            {
                process.destroyForcibly();

                logger.warn("GoEngine batch timeout after {} seconds", blockingTimeoutGoEngine);

                return results;
            }

            var exitCode = process.exitValue();

            if (exitCode != 0)
            {
                logger.warn("GoEngine batch exited with code: {}", exitCode);

                if (!errorOutput.isEmpty())
                {
                    logger.warn("GoEngine stderr: {}", errorOutput.toString().trim());
                }
            }

            logger.info("Batch poll completed: {}/{} devices successful",
                results.values().stream().filter(v -> v).count(), devices.size());

        }
        catch (Exception exception)
        {
            logger.error("Failed to execute GoEngine batch poll: {}", exception.getMessage());
        }

        return results;
    }

    /**
     * Phase 2: Retry Failed Devices (BATCH MODE)

     * Retry devices that failed in Phase 1 using batch processing.
     * Uses the same batch approach as Phase 1 for consistency and efficiency.
     * All devices get exactly 1 retry attempt.
     *
     * @param failedDevices Devices that failed in Phase 1
     * @return Future with list of exhausted devices (failed after retry)
     */
    private Future<List<PollingDevice>> executeRetryFailures(List<PollingDevice> failedDevices)
    {
        var promise = Promise.<List<PollingDevice>>promise();

        if (failedDevices.isEmpty())
        {
            logger.info("Phase 2: No failed devices to retry");

            promise.complete(new ArrayList<>());

            return promise.future();
        }

        logger.info("Phase 2: Retrying {} failed devices (1 retry attempt)", failedDevices.size());

        vertx.executeBlocking(() ->
        {
            var exhaustedDevices = new ArrayList<PollingDevice>();

            // Perform 1 retry attempt for all failed devices using batch processing
            var retryResults = pollDeviceMetricsBatch(failedDevices);

            for (var pd : failedDevices)
            {
                var success = retryResults.getOrDefault(pd.deviceId, false);

                if (success)
                {
                    pd.resetFailures();

                    pd.advanceSchedule();
                }
                else
                {
                    exhaustedDevices.add(pd);
                }
            }

            return exhaustedDevices;

        }, false)
        .onSuccess(exhaustedDevices ->
        {
            var retriedSuccessfully = failedDevices.size() - exhaustedDevices.size();

            logger.info("Phase 2 completed: {}/{} devices recovered on retry", retriedSuccessfully, failedDevices.size());

            promise.complete(exhaustedDevices);
        })
        .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Phase 3: Log Exhausted Failures

     * Log devices that failed after exhausting all retries to file.
     *
     * @param exhaustedDevices Devices that failed after all retries
     * @return Future<Void>
     */
    private Future<Void> executeLogFailures(List<PollingDevice> exhaustedDevices)
    {
        var promise = Promise.<Void>promise();

        if (exhaustedDevices.isEmpty())
        {
            logger.info("Phase 3: No exhausted failures to log");

            promise.complete();

            return promise.future();
        }

        logger.info("Phase 3: Logging {} exhausted failures to {}", exhaustedDevices.size(), failureLogPath);

        vertx.executeBlocking(() ->
        {
            var logFile = new File(failureLogPath);

            logFile.getParentFile().mkdirs(); // Create directory if needed

            try (var fw = new FileWriter(logFile, true);
                 var bw = new BufferedWriter(fw);
                 var out = new PrintWriter(bw))
            {
                // Human-readable timestamp: "2025-10-11 11:30:45"
                var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                var timestamp = LocalDateTime.now().format(formatter);

                for (var pd : exhaustedDevices)
                {
                    var logEntry = String.format("%s | %s | %s | %s | failures=%d",
                        timestamp, pd.deviceId, pd.deviceName, pd.address, pd.consecutiveFailures);

                    out.println(logEntry);
                }
            }

            return null;

        }, false)
        .onSuccess(result ->
        {
            logger.info("Phase 3 completed: {} failures logged", exhaustedDevices.size());

            promise.complete();
        })
        .onFailure(cause ->
        {
            logger.error("Failed to write failure log: {}", cause.getMessage());

            promise.fail(cause);
        });

        return promise.future();
    }

    /**
     * Phase 4: Auto-Disable

     * Disable monitoring for devices that have exceeded max_cycles_skipped consecutive failures.
     *
     * @return Future<Void>
     */
    private Future<Void> executePhaseAutoDisable()
    {
        var promise = Promise.<Void>promise();

        // Filter devices to auto-disable (moved to worker thread to avoid blocking)
        vertx.executeBlocking(() ->
                        deviceCache.values().stream()
                            .filter(pd -> pd.shouldAutoDisable(maxCyclesSkipped))
                            .collect(Collectors.toList()))
        .onSuccess(devicesToDisable ->
        {
            if (devicesToDisable.isEmpty())
            {
                logger.info("Phase 4: No devices to auto-disable");

                promise.complete();

                return;
            }

            logger.warn("Phase 4: Auto-disabling {} devices due to {} consecutive failures", devicesToDisable.size(), maxCyclesSkipped);

            // Disable devices sequentially
            disableDevicesSequentially(devicesToDisable, 0, promise);
        })
        .onFailure(cause ->
        {
            logger.error("Failed to filter devices for auto-disable: {}", cause.getMessage());

            promise.fail(cause);
        });

        return promise.future();
    }

    /**
     * Disable devices sequentially
     */
    private void disableDevicesSequentially(List<PollingDevice> devices, int index, Promise<Void> promise)
    {
        try
        {
            if (index >= devices.size())
            {
                promise.complete();
                return;
            }

            var pd = devices.get(index);

            logger.warn("Auto-disabling device {} due to consecutive failures", pd.deviceName);

            deviceService.deviceDisableMonitoring(pd.deviceId)
                .onSuccess(result ->
                {
                    // Remove from cache (will be removed by event handler too, but this is immediate)
                    deviceCache.remove(pd.deviceId);

                    // Continue with next device
                    disableDevicesSequentially(devices, index + 1, promise);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to disable monitoring for device {}: {}", pd.deviceName, cause.getMessage());

                    // Continue with next device even on failure
                    disableDevicesSequentially(devices, index + 1, promise);
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in disableDevicesSequentially: {}", exception.getMessage());

            promise.fail(exception);
        }
    }
    
    /**
     * Store metrics in database

     * Transforms GoEngine response format to database schema format:
     * GoEngine: {"cpu":{"usage_percent":15.2},"memory":{...},"disk":{...}}
     * Database: {"cpu_usage_percent":15.2,"memory_usage_percent":...}
     */
    private void storeMetrics(String deviceId, JsonObject goEngineResult)
    {
        try
        {
            // Extract nested metrics from GoEngine response
            var cpu = goEngineResult.getJsonObject("cpu");

            var memory = goEngineResult.getJsonObject("memory");

            var disk = goEngineResult.getJsonObject("disk");

            // Transform to database schema format
            var metricsData = new JsonObject()
                .put("device_id", deviceId)
                .put("cpu_usage_percent", cpu.getDouble("usage_percent"))
                .put("memory_usage_percent", memory.getDouble("usage_percent"))
                .put("memory_total_bytes", memory.getLong("total_bytes"))
                .put("memory_used_bytes", memory.getLong("used_bytes"))
                .put("memory_free_bytes", memory.getLong("free_bytes"))
                .put("disk_usage_percent", disk.getDouble("usage_percent"))
                .put("disk_total_bytes", disk.getLong("total_bytes"))
                .put("disk_used_bytes", disk.getLong("used_bytes"))
                .put("disk_free_bytes", disk.getLong("free_bytes"));

            metricsService.metricsCreate(metricsData)
                .onFailure(cause ->
                        logger.error("Failed to store metrics for device {}: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in storeMetrics: {}", exception.getMessage());
        }
    }

    /**
     * Update device availability status
     */
    private void updateDeviceAvailability(String deviceId, boolean isAvailable)
    {
        try
        {
            var status = isAvailable ? "UP" : "DOWN";

            availabilityService.availabilityUpdateDeviceStatus(deviceId, status)
                .onFailure(cause ->
                        logger.error("Failed to record availability for device {}: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in updateDeviceAvailability: {}", exception.getMessage());
        }
    }

    /**
     * Perform connectivity checks for polling devices (similar to DiscoveryVerticle)

     * Connectivity check process (OPTIMIZED):
     * 1. BATCH fping check for all devices (single fping process)
     * 2. BATCH port check for devices that passed fping (parallel checks)
     * 3. Device is considered reachable only if both checks pass
     *
     * @param devices List of devices to check
     * @return Future<JsonObject> with "reachable" and "unreachable" device IDs
     */
    private Future<JsonObject> performConnectivityChecksForPolling(List<PollingDevice> devices)
    {
        // Convert devices to IP list
        var ipList = devices.stream().map(pd -> pd.address).collect(Collectors.toList());

        // Step 1: Batch fping check (single fping process for all IPs) - wrapped in executeBlocking
        return vertx.executeBlocking(() -> NetworkConnectivity.batchFpingCheck(ipList, config()))
            .compose(fpingResults ->
            {
                // Filter IPs that successfully passed fping
                var pingAliveIps = ipList.stream().filter(ip -> fpingResults.getOrDefault(ip, false)).toList();

                if (pingAliveIps.isEmpty())
                {
                    // All devices failed fping, no need for port check
                    var unreachableDeviceIds = new JsonArray();

                    devices.forEach(pd -> unreachableDeviceIds.add(pd.deviceId));

                    var result = new JsonObject()
                        .put("reachable", new JsonArray())
                        .put("unreachable", unreachableDeviceIds)
                        .put("total_checked", devices.size());

                    return Future.succeededFuture(result);
                }

                // Step 2: Individual port checks for each device (devices have different ports)
                // Batch all port checks in a single executeBlocking call
                return vertx.executeBlocking(() ->
                {
                    var portCheckResults = new ArrayList<JsonObject>();

                    for (var pd : devices)
                    {
                        // Only check port for devices that passed fping
                        if (!fpingResults.getOrDefault(pd.address, false))
                        {
                            continue;
                        }

                        var portOpen = NetworkConnectivity.portCheck(pd.address, pd.port, config());

                        portCheckResults.add(new JsonObject()
                            .put("device_id", pd.deviceId)
                            .put("address", pd.address)
                            .put("port", pd.port)
                            .put("port_open", portOpen));
                    }

                    return portCheckResults;
                })

                    .map(portCheckResults ->
                    {
                        var reachableDeviceIds = new JsonArray();

                        var unreachableDeviceIds = new JsonArray();

                        // Build a map for O(1) lookup instead of O(n) nested loop
                        var portResultsMap = new HashMap<String, Boolean>();

                        for (var portResult : portCheckResults)
                        {
                            var deviceId = portResult.getString("device_id");

                            var portOpen = portResult.getBoolean("port_open", false);

                            portResultsMap.put(deviceId, portOpen);
                        }

                        // Classify devices based on both fping and port check results
                        for (var pd : devices)
                        {
                            var pingAlive = fpingResults.getOrDefault(pd.address, false);

                            if (!pingAlive)
                            {
                                unreachableDeviceIds.add(pd.deviceId);

                                continue;
                            }

                            // O(1) lookup instead of O(n) nested loop
                            var portOpen = portResultsMap.getOrDefault(pd.deviceId, false);

                            if (portOpen)
                            {
                                reachableDeviceIds.add(pd.deviceId);
                            }
                            else
                            {
                                unreachableDeviceIds.add(pd.deviceId);
                            }
                        }

                        logger.debug("Connectivity checks: {}/{} devices reachable", reachableDeviceIds.size(), devices.size());

                        return new JsonObject()
                            .put("reachable", reachableDeviceIds)
                            .put("unreachable", unreachableDeviceIds)
                            .put("total_checked", devices.size());
                    });
            });
    }

    /**
     * Polling batch processor using QueueBatchProcessor.

     * Extends the generic QueueBatchProcessor to handle polling-specific batch processing.
     * Processes devices in batches with fping, port check, and GoEngine metrics collection.

     * Features:
     * - Sequential batch processing of devices
     * - Connectivity pre-filtering (fping + port check)
     * - GoEngine metrics collection for alive devices
     * - Fail-tolerant: continues with next batch on failure
     * - Tracks failed devices for retry phase
     */
    private class PollingBatchProcessor extends QueueBatchProcessor<PollingDevice>
    {
        private final List<PollingDevice> failedDevices;

        /**
         * Constructor for PollingBatchProcessor.

         * Initializes the processor with devices to poll.
         *
         * @param devices List of devices to poll in batches
         */
        public PollingBatchProcessor(List<PollingDevice> devices)
        {
            super(devices, batchSize);

            this.failedDevices = new ArrayList<>();
        }

        /**
         * Process a batch of devices.

         * Executes the complete polling workflow for a batch:
         * 1. Batch fping connectivity check
         * 2. Port reachability check for alive devices
         * 3. GoEngine metrics collection for reachable devices
         * 4. Update device schedules and failure counters
         *
         * @param batch List of devices to poll in this batch
         * @return Future containing JsonArray of results (empty for polling)
         */
        @Override
        protected Future<JsonArray> processBatch(List<PollingDevice> batch)
        {
            return processSingleBatch(batch)
                .map(batchFailures ->
                {
                    failedDevices.addAll(batchFailures);

                    return new JsonArray();
                });
        }

        /**
         * Handle batch processing failure.

         * Marks all devices in the failed batch as failed and adds them to the failed devices list.
         * The batch processor will continue with the next batch (fail-tolerant behavior).
         *
         * @param batch The batch of devices that failed to process
         * @param cause The exception that caused the failure
         */
        @Override
        protected void handleBatchFailure(List<PollingDevice> batch, Throwable cause)
        {
            logger.warn("Batch processing failed for {} devices: {}", batch.size(), cause.getMessage());

            for (PollingDevice pd : batch)
            {
                pd.incrementFailures();

                failedDevices.add(pd);
            }
        }

        /**
         * Get the list of devices that failed during batch processing.
         *
         * @return List of failed devices
         */
        public List<PollingDevice> getFailedDevices()
        {
            return failedDevices;
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
        try
        {
            logger.info("Stopping PollingMetricsVerticle");

            if (pollingTimerId != 0)
            {
                vertx.cancelTimer(pollingTimerId);
            }

            // Clear cache
            if (deviceCache != null)
            {
                deviceCache.clear();
            }

            stopPromise.complete();
        }
        catch (Exception exception)
        {
            logger.error("Error in stop: {}", exception.getMessage());

            stopPromise.fail(exception);
        }
    }
}
