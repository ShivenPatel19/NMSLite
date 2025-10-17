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

import com.nmslite.Bootstrap;

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

    // File write synchronization lock to prevent concurrent writes to failure log
    private final Object fileWriteLock = new Object();

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
            var toolsConfig = Bootstrap.getConfig().getJsonObject("tools", new JsonObject());

            var pollingConfig = Bootstrap.getConfig().getJsonObject("polling", new JsonObject());

            // HOCON parses dotted keys as nested objects: goengine.path becomes goengine -> path
            goEnginePath = toolsConfig.getJsonObject("goengine", new JsonObject())
                    .getString("path", "./goengine/goengine");

            // Load polling configuration - HOCON parses dotted keys as nested objects
            cycleIntervalSeconds = pollingConfig.getJsonObject("cycle", new JsonObject())
                    .getJsonObject("interval", new JsonObject())
                    .getInteger("seconds", 60);

            batchSize = pollingConfig.getJsonObject("batch", new JsonObject())
                    .getInteger("size", 50);

            maxCyclesSkipped = pollingConfig.getJsonObject("max", new JsonObject())
                    .getJsonObject("cycles", new JsonObject())
                    .getInteger("skipped", 5);

            failureLogPath = pollingConfig.getJsonObject("failure", new JsonObject())
                    .getJsonObject("log", new JsonObject())
                    .getString("path", "polling_failed/metrics_polling_failed.txt");

            blockingTimeoutGoEngine = pollingConfig.getJsonObject("blocking", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("goengine", 330);

            defaultConnectionTimeoutSeconds = pollingConfig.getJsonObject("connection", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 10);

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

                                if (pd != null)
                                {
                                    deviceCache.put(pd.deviceId, pd);

                                    count++;
                                }
                                else
                                {
                                    logger.error("Failed to create PollingDevice from JSON for device {}", deviceData.getString("device_name"));
                                }
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

            vertx.eventBus().consumer("device.deleted", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceDeleted(deviceId);
            });

            vertx.eventBus().consumer("device.restored", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceRestored(deviceId);
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

                            if (pd != null)
                            {
                                deviceCache.put(pd.deviceId, pd);

                                logger.info("Device cache updated: {} added (total cached: {})", pd.deviceName, deviceCache.size());
                            }
                            else
                            {
                                logger.error("Failed to create PollingDevice from JSON for device {}", deviceId);
                            }
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
     * @param deviceId Device ID to remove from cache
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
     * @param deviceId Device ID to update in cache
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

                            if (pd != null)
                            {
                                deviceCache.put(pd.deviceId, pd);  // simply overwrite existing entry

                                logger.info("Device cache updated: {} config refreshed (total cached: {})", pd.deviceName, deviceCache.size());
                            }
                            else
                            {
                                logger.error("Failed to create PollingDevice from JSON for device {}", deviceId);
                            }
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

    /**
     * Handle device deleted event.
     * Remove device from cache, delete all metrics, and reset availability to 0/unknown.
     *
     * @param deviceId Device ID that was deleted
     */
    private void onDeviceDeleted(String deviceId)
    {
        try
        {
            logger.debug("Device deleted event: {}", deviceId);

            // Step 1: Remove from cache
            var removed = deviceCache.remove(deviceId);

            if (removed != null)
            {
                logger.info("Device cache updated: {} removed due to deletion (total cached: {})",
                    removed.deviceName, deviceCache.size());
            }

            // Step 2: Delete all metrics for this device
            metricsService.metricsDeleteAllByDevice(deviceId)
                .onSuccess(result ->
                    logger.info("Deleted all metrics for device: {}", deviceId))
                .onFailure(cause ->
                    logger.error("Failed to delete metrics for device {}: {}", deviceId, cause.getMessage()));

            // Step 3: Reset availability to 0% and unknown (keep row for future polling)
            availabilityService.availabilityResetDevice(deviceId)
                .onSuccess(result ->
                    logger.info("Reset availability to 0%/unknown for device: {}", deviceId))
                .onFailure(cause ->
                    logger.error("Failed to reset availability for device {}: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceDeleted: {}", exception.getMessage());
        }
    }

    /**
     * Handle device restored event.
     * Add device back to cache if monitoring is enabled.
     *
     * @param deviceId Device ID that was restored
     */
    private void onDeviceRestored(String deviceId)
    {
        try
        {
            logger.debug("Device restored event: {}", deviceId);

            // Fetch device and add to cache only if monitoring is enabled
            deviceService.deviceGetById(deviceId)
                .onSuccess(deviceData ->
                {
                    try
                    {
                        var isMonitoringEnabled = deviceData.getBoolean("is_monitoring_enabled", false);

                        if (!isMonitoringEnabled)
                        {
                            logger.info("Device {} restored but monitoring not enabled, skipping cache add", deviceId);

                            return;
                        }

                        var pd = createPollingDeviceFromJson(deviceData);

                        if (pd != null)
                        {
                            deviceCache.put(pd.deviceId, pd);

                            logger.info("Device cache updated: {} restored and added (total cached: {})",
                                pd.deviceName, deviceCache.size());
                        }
                        else
                        {
                            logger.error("Failed to create PollingDevice from JSON for restored device {}", deviceId);
                        }
                    }
                    catch (Exception exception)
                    {
                        logger.error("Failed to add restored device {} to cache: {}", deviceId, exception.getMessage());
                    }
                })
                .onFailure(cause ->
                    logger.error("Failed to fetch restored device {} for cache: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceRestored: {}", exception.getMessage());
        }
    }

    private void startPeriodicPolling()
    {
        try
        {
            // Schedule periodic execution every cycleIntervalSeconds
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
     *   - Track failures across all batches

     * Phase 2: Retry Failed Devices
     *   - Retry ALL devices that failed in Phase 1 (1 retry attempt)
     *   - Sends all failed devices to GoEngine in a single call

     * Phase 3: Log Exhausted Failures
     *   - Log devices that reached max consecutive failures to file

     * Phase 4: Auto-Disable
     *   - Disable monitoring for devices that reached or exceeded max_cycles_skipped
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
                            logger.info("Polling cycle: 0 devices due (total cached: {})", totalCachedDevices);

                            return;
                        }

                        logger.info("Polling cycle: {} devices due for polling (total cached: {})", dueDevices.size(), totalCachedDevices);

                        // Phase 1: Batch Processing (processes all due devices in batches)
                        // Phase 2: Retry Failed Devices (retries ALL accumulated failures from Phase 1)
                        // Phase 3: Log Exhausted Failures (logs devices that reached max failures)
                        // Phase 4: Auto-Disable (disables devices that reached/exceeded max failures)
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

                    // Update availability for devices that succeeded in Phase 1 (will not be retried)
                    // Failed devices will have their availability updated after retry phase
                    updateAvailabilityForSuccessfulDevices(dueDevices, failedDevices);

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

                var reachableCount = reachableDevices.size();

                var unreachableCount = unreachableDevices.size();

                var batchSize = batch.size();

                logger.info("Connectivity check: {}/{} devices reachable, {}/{} unreachable",
                    reachableCount, batchSize, unreachableCount, batchSize);

                // Step 2: Mark unreachable devices as CONNECTIVITY_FAILED
                // Availability will be updated centrally after final result is determined
                for (var i = 0; i < unreachableDevices.size(); i++)
                {
                    var deviceId = unreachableDevices.getString(i);

                    var pd = deviceMap.get(deviceId);

                    if (pd != null)
                    {
                        pd.pollingResult = PollingResult.CONNECTIVITY_FAILED;

                        pd.incrementFailures();
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

        var deviceCount = devices.size();

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

            // Execute GoEngine - only mode flag, data passed via stdin
            // Use absolute path for binary, set working directory for config file
            var goEngineBinary = new File(goEnginePath).getAbsolutePath();

            var pb = new ProcessBuilder(goEngineBinary, "--mode", "metrics");

            pb.directory(new File("./goengine"));

            var process = pb.start();

            // Create a map for quick device lookup by IP address
            var devicesByIp = new HashMap<String, PollingDevice>();

            for (var pd : devices)
            {
                devicesByIp.put(pd.address, pd);
            }

            var errorOutput = new StringBuilder();

            // Write devices array to stdin
            try (var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream())))
            {
                writer.write(devicesArray.encode());

                writer.flush();
            }
            catch (Exception exception)
            {
                logger.error("Failed to write devices array to GoEngine stdin: {}", exception.getMessage());
            }

            var successCount = 0;

            // Read streaming output line by line and process results as they arrive
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

                        // Extract device address from nested device_info object
                        var deviceInfo = result.getJsonObject("device_info");

                        if (deviceInfo == null)
                        {
                            logger.error("GoEngine result missing device_info: {}", line);

                            continue;
                        }

                        var deviceAddress = deviceInfo.getString("address");

                        if (deviceAddress == null)
                        {
                            logger.error("GoEngine result missing device address: {}", line);

                            continue;
                        }

                        // Find the device by IP address
                        var pd = devicesByIp.get(deviceAddress);

                        if (pd == null)
                        {
                            logger.error("No matching device found for address {}", deviceAddress);

                            continue;
                        }

                        // Check if metrics collection was successful (no error field present)
                        // GoEngine returns "error" field only when collection fails
                        var success = !result.containsKey("error");

                        if (success)
                        {
                            // Store metrics (availability will be updated centrally after final result is determined)
                            storeMetrics(pd.deviceId, result);

                            results.put(pd.deviceId, true);

                            successCount++;
                        }
                        else
                        {
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

            // Log with pre-calculated count (no stream operations on event loop)
            logger.info("Batch poll completed: {}/{} devices successful", successCount, deviceCount);

        }
        catch (Exception exception)
        {
            logger.error("Failed to execute GoEngine batch poll: {}", exception.getMessage());
        }

        return results;
    }

    /**
     * Phase 2: Retry Failed Devices

     * Retry ALL devices that failed in Phase 1 (across all batches).
     * Sends all failed devices to GoEngine in a single call.
     * All devices get exactly 1 retry attempt.
     *
     * @param failedDevices ALL devices that failed in Phase 1 (accumulated across all batches)
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

            // Update availability for all retried devices based on final result (after retry)
            updateAvailabilityForRetriedDevices(failedDevices, exhaustedDevices);

            promise.complete(exhaustedDevices);
        })
        .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Phase 3: Log Exhausted Failures

     * Log devices that reached max consecutive failures (about to be auto-disabled) to file.
     * Scans entire cache for devices where consecutiveFailures == maxCyclesSkipped (one-time logging).
     *
     * @param exhaustedDevices Devices that failed after all retries (unused, kept for chain compatibility)
     * @return Future<Void>
     */
    private Future<Void> executeLogFailures(List<PollingDevice> exhaustedDevices)
    {
        var promise = Promise.<Void>promise();

        // Scan entire cache for devices that JUST reached max failures (one-time logging)
        // Use executeBlocking to avoid blocking event loop with cache iteration
        vertx.executeBlocking(() ->
                        deviceCache.values().stream()
                            .filter(pd -> pd.consecutiveFailures == maxCyclesSkipped)
                            .collect(Collectors.toList()))
        .compose(devicesToLog ->
        {
            if (devicesToLog.isEmpty())
            {
                logger.info("Phase 3: No devices reached max failures threshold ({}), skipping log", maxCyclesSkipped);

                return Future.succeededFuture(0);
            }

            logger.info("Phase 3: Logging {} devices that reached max failures ({}) to {}",
                devicesToLog.size(), maxCyclesSkipped, failureLogPath);

            return vertx.executeBlocking(() ->
            {
                // Synchronize file writes to prevent concurrent access from multiple polling cycles
                synchronized (fileWriteLock)
                {
                    try
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

                            for (var pd : devicesToLog)
                            {
                                var logEntry = String.format("%s | %s | %s | %s", timestamp, pd.deviceId, pd.deviceName, pd.address);

                                out.println(logEntry);
                            }
                        }

                        return devicesToLog.size();
                    }
                    catch (Exception exception)
                    {
                        logger.error("Error writing failure log: {}", exception.getMessage());

                        return 0;
                    }
                }

            }, false);
        })
        .onSuccess(count ->
        {
            logger.info("Phase 3 completed: {} devices logged to failure file", count);

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

     * Removes device from cache FIRST (synchronous) to prevent race condition,
     * then updates database (async). This ensures the device won't be polled
     * in the next cycle before the database update completes.
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

            // Remove from cache FIRST (synchronous, immediate) to prevent race condition
            // This ensures the device won't be polled in the next cycle before DB update completes
            deviceCache.remove(pd.deviceId);

            // THEN update database (async, non-blocking)
            deviceService.deviceDisableMonitoring(pd.deviceId)
                .onSuccess(result ->
                {
                    logger.info("Device {} monitoring disabled successfully in database", pd.deviceName);

                    // Continue with next device
                    disableDevicesSequentially(devices, index + 1, promise);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to disable monitoring for device {} in database: {}", pd.deviceName, cause.getMessage());

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

     * Includes cache check to prevent race conditions in overlapping cycles.
     * If device was removed from cache by a concurrent cycle, skip metrics insertion.
     */
    private void storeMetrics(String deviceId, JsonObject goEngineResult)
    {
        try
        {
            // Check if device is still in cache before inserting metrics
            // Prevents race condition: device removed by concurrent cycle while this cycle is still processing
            if (!deviceCache.containsKey(deviceId))
            {
                logger.debug("Device {} removed from cache during polling, skipping metrics insert", deviceId);

                return;
            }

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

     * Includes cache check to prevent race conditions in overlapping cycles.
     * If device was removed from cache by a concurrent cycle, skip availability update.
     */
    private void updateDeviceAvailability(String deviceId, boolean isAvailable)
    {
        try
        {
            // Check if device is still in cache before updating availability
            // Prevents race condition: device removed by concurrent cycle while this cycle is still processing
            if (!deviceCache.containsKey(deviceId))
            {
                logger.debug("Device {} removed from cache during polling, skipping availability update", deviceId);

                return;
            }

            var status = isAvailable ? "UP" : "DOWN";

            availabilityService.availabilityUpdateDeviceStatus(deviceId, status)
                .onFailure(cause ->
                {
                    // Device might have been deleted during polling cycle - this is expected
                    if (cause.getMessage() != null && cause.getMessage().contains("Device not found"))
                    {
                        logger.debug("Device {} was deleted during polling cycle, skipping availability update", deviceId);
                    }
                    else
                    {
                        logger.error("Failed to record availability for device {}: {}", deviceId, cause.getMessage());
                    }
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in updateDeviceAvailability: {}", exception.getMessage());
        }
    }

    /**
     * Update availability for devices that succeeded in Phase 1 (will not be retried)
     *
     * @param allDevices All devices that were processed in Phase 1
     * @param failedDevices Devices that failed in Phase 1 (will be retried, so skip them)
     */
    private void updateAvailabilityForSuccessfulDevices(List<PollingDevice> allDevices, List<PollingDevice> failedDevices)
    {
        try
        {
            // Create a set of failed device IDs for O(1) lookup
            var failedDeviceIds = failedDevices.stream()
                .map(pd -> pd.deviceId)
                .collect(Collectors.toSet());

            // Update availability for devices that succeeded (not in failed list)
            for (var pd : allDevices)
            {
                if (!failedDeviceIds.contains(pd.deviceId))
                {
                    // Device succeeded in Phase 1, update availability as UP
                    updateDeviceAvailability(pd.deviceId, true);
                }
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in updateAvailabilityForSuccessfulDevices: {}", exception.getMessage());
        }
    }

    /**
     * Update availability for devices that were retried in Phase 2 based on final result
     *
     * @param retriedDevices All devices that were retried in Phase 2
     * @param exhaustedDevices Devices that failed even after retry
     */
    private void updateAvailabilityForRetriedDevices(List<PollingDevice> retriedDevices, List<PollingDevice> exhaustedDevices)
    {
        try
        {
            // Create a set of exhausted device IDs for O(1) lookup
            var exhaustedDeviceIds = exhaustedDevices.stream()
                .map(pd -> pd.deviceId)
                .collect(Collectors.toSet());

            // Update availability for all retried devices based on final result
            for (var pd : retriedDevices)
            {
                if (exhaustedDeviceIds.contains(pd.deviceId))
                {
                    // Device failed even after retry, update availability as DOWN
                    updateDeviceAvailability(pd.deviceId, false);
                }
                else
                {
                    // Device succeeded on retry, update availability as UP
                    updateDeviceAvailability(pd.deviceId, true);
                }
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in updateAvailabilityForRetriedDevices: {}", exception.getMessage());
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
        return vertx.executeBlocking(() -> NetworkConnectivity.batchFpingCheck(ipList, Bootstrap.getConfig()))
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

                        var portOpen = NetworkConnectivity.portCheck(pd.address, pd.port, Bootstrap.getConfig());

                        portCheckResults.add(new JsonObject()
                            .put("device_id", pd.deviceId)
                            .put("address", pd.address)
                            .put("port", pd.port)
                            .put("port_open", portOpen));
                    }

                    return portCheckResults;
                }).map(portCheckResults ->
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
