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

import com.nmslite.models.DevicePolling;

import com.nmslite.services.DeviceService;

import com.nmslite.services.MetricsService;

import com.nmslite.utils.PasswordUtil;

import com.nmslite.core.ParallelBatchProcessor;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.core.WorkerExecutor;

import io.vertx.core.shareddata.LocalMap;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.io.*;

import java.time.Instant;

import java.util.*;

import java.util.concurrent.TimeUnit;

import java.util.stream.Collectors;

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

    private DeviceService deviceService;

    private MetricsService metricsService;

    // Worker pool for blocking operations (GoEngine only - fping/port check done by AvailabilityVerticle)
    private WorkerExecutor pollingWorkerPool;

    // Shared availability cache name (same as AvailabilityVerticle)
    private static final String AVAILABILITY_CACHE_NAME = "availability-cache";

    // Shared availability cache (read-only access from AvailabilityVerticle)
    // Key: device_id, Value: JsonObject with fields: device_id, address, port, status
    // Note: LocalMap only supports immutable types (String, JsonObject, etc.), not custom POJOs
    private LocalMap<String, JsonObject> availabilityCache;

    // In-memory device cache
    // Key: device_id, Value: DevicePolling (persistent data + runtime state)
    private HashMap<String, DevicePolling> deviceCache;

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

            // Note: requireNonNull() only on first call to validate Bootstrap.getConfig() is not null
            // Bootstrap.getConfig() can return null if config retrieval from shared data fails
            // Once validated, subsequent calls in same method are safe to use directly
            var toolsConfig = Objects.requireNonNull(Bootstrap.getConfig()).getJsonObject("tools", new JsonObject());

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

            blockingTimeoutGoEngine = pollingConfig.getJsonObject("blocking", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("goengine", 300);

            defaultConnectionTimeoutSeconds = pollingConfig.getJsonObject("connection", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 10);

            int workerPoolSize = pollingConfig.getJsonObject("worker", new JsonObject())
                    .getJsonObject("pool", new JsonObject())
                    .getInteger("size", 10);

            int workerPoolTimeoutSeconds = pollingConfig.getJsonObject("worker", new JsonObject())
                    .getJsonObject("pool", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 300);

            initializeServiceProxies();

            // Create dedicated worker pool for polling operations
            pollingWorkerPool = vertx.createSharedWorkerExecutor(
                    "polling-worker-pool",
                    workerPoolSize,
                    workerPoolTimeoutSeconds,
                    TimeUnit.SECONDS);

            logger.info("Polling worker pool created: {} workers, {}s timeout", workerPoolSize, workerPoolTimeoutSeconds);

            // Access shared availability cache (populated by AvailabilityVerticle)
            // Note: AvailabilityVerticle MUST be deployed BEFORE this verticle (see VerticleDeployer)
            availabilityCache = vertx.sharedData().getLocalMap(AVAILABILITY_CACHE_NAME);

            if (availabilityCache.isEmpty())
            {
                logger.warn("Availability cache is empty - AvailabilityVerticle may not have started yet or no devices are provisioned");
            }
            else
            {
                logger.info("Accessing shared availability cache: {} devices", availabilityCache.size());
            }

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
        }
        catch (Exception exception)
        {
            logger.error("Error in initializeServiceProxies: {}", exception.getMessage());
        }
    }

    /**
     * Load all eligible devices from database into in-memory cache.

     * Queries devices where:
     * - is_provisioned = true (monitoring enabled)
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
            deviceService.deviceListByProvisioned(true)
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
                                    logger.error("Failed to create DevicePolling from JSON for device {}", deviceData.getString("device_name"));
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
     * Create DevicePolling from database JSON.

     * Maps database fields to DevicePolling model and computes runtime state.
     *
     * @param deviceData JSON from database query
     * @return DevicePolling instance
     */
    private DevicePolling createPollingDeviceFromJson(JsonObject deviceData)
    {
        try
        {
            var pd = new DevicePolling();

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

            // Compute aligned next poll time using created_at as anchor
            // (created_at is when device was first created, used for aligned scheduling)
            var createdAtStr = deviceData.getString("created_at");

            var createdAt = Instant.parse(createdAtStr + "Z");

            pd.nextScheduledAt = computeAlignedNext(createdAt, Instant.now(), pd.pollingIntervalSeconds);

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
     * - Anchor: created_at (when device was first created)
     * - Next = anchor + ceil((now - anchor) / interval) * interval

     * Example:
     * - Anchor: 10:00:00 (device created)
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
     * @param anchor created_at timestamp (device creation time)
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
            vertx.eventBus().consumer("device.provision.enabled", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceProvisionEnabled(deviceId);
            });

            vertx.eventBus().consumer("device.provision.disabled", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceProvisionDisabled(deviceId);
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

            // Credential profile updated - UPDATE port for all devices using this profile
            vertx.eventBus().consumer("credential.profile.updated", msg ->
            {
                var data = (JsonObject) msg.body();

                var profileId = data.getString("credential_profile_id");

                onCredentialProfileUpdated(profileId);
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in setupEventBusConsumers: {}", exception.getMessage());
        }
    }

    /**
     * Handle device provision enabled event.
     * Add device to cache.

     * Event is only published after successful database update,
     * so no validation checks are needed - just fetch and cache.
     *
     * @param deviceId Device ID to add to cache
     */
    private void onDeviceProvisionEnabled(String deviceId)
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
                                logger.error("Failed to create DevicePolling from JSON for device {}", deviceId);
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
            logger.error("Error in onDeviceProvisionEnabled: {}", exception.getMessage());
        }
    }

    /**
     * Handle device provision disabled event.
     * Remove device from cache.
     *
     * @param deviceId Device ID to remove from cache
     */
    private void onDeviceProvisionDisabled(String deviceId)
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
            logger.error("Error in onDeviceProvisionDisabled: {}", exception.getMessage());
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
                                logger.error("Failed to create DevicePolling from JSON for device {}", deviceId);
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

            // Note: Availability continues to be tracked by AvailabilityVerticle (10s cycle)
            // No need to reset availability here
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

            // Fetch device and add to cache only if provisioned (monitoring enabled)
            deviceService.deviceGetById(deviceId)
                .onSuccess(deviceData ->
                {
                    try
                    {
                        var isProvisioned = deviceData.getBoolean("is_provisioned", false);

                        if (!isProvisioned)
                        {
                            logger.info("Device {} restored but not provisioned (monitoring disabled), skipping cache add", deviceId);

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
                            logger.error("Failed to create DevicePolling from JSON for restored device {}", deviceId);
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

    /**
     * Handle credential profile updated event.
     * Reload all devices using this credential profile to get updated port.
     *
     * @param profileId Credential profile ID
     */
    private void onCredentialProfileUpdated(String profileId)
    {
        try
        {
            logger.info("Credential profile updated: {}", profileId);

            // Find all devices using this credential profile and reload their data
            deviceService.deviceListByCredentialProfile(profileId)
                    .onSuccess(devices ->
                    {
                        for (int i = 0; i < devices.size(); i++)
                        {
                            var deviceData = devices.getJsonObject(i);

                            var deviceId = deviceData.getString("device_id");

                            // Only update if device is in cache (provisioned)
                            if (deviceCache.containsKey(deviceId))
                            {
                                var pd = createPollingDeviceFromJson(deviceData);

                                if (pd != null)
                                {
                                    // Preserve runtime state
                                    var oldPd = deviceCache.get(deviceId);

                                    pd.nextScheduledAt = oldPd.nextScheduledAt;

                                    pd.consecutiveFailures = oldPd.consecutiveFailures;

                                    deviceCache.put(deviceId, pd);

                                    logger.debug("Updated device {} with new port: {}", deviceId, pd.port);
                                }
                            }
                        }

                        logger.info("Updated {} devices for credential profile {}", devices.size(), profileId);
                    })
                    .onFailure(cause ->
                            logger.error("Failed to reload devices for credential profile {}: {}", profileId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onCredentialProfileUpdated: {}", exception.getMessage());
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
     * Execute 2-phase polling cycle:

     * Phase 1: Batch Processing
     *   - Filter devices due for polling (using aligned scheduling)
     *   - Process in batches with fping + GoEngine
     *   - Update device availability status immediately (both success and failure)
     *   - Track failures across all batches

     * Phase 2: Auto-Disable
     *   - Disable monitoring for devices that reached or exceeded max_cycles_skipped (5 consecutive failures)
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

                        logger.info("New Polling cycle: {} devices due for polling (total cached: {})", dueDevices.size(), totalCachedDevices);

                        // Phase 1: Batch Processing (processes all due devices in batches, updates availability immediately)
                        // Phase 2: Auto-Disable (disables devices that reached/exceeded max failures)
                        executeBatchProcessing(dueDevices)
                                .compose(v ->
                                {
                                    // Phase 2: Auto-Disable
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

    // ========== 2-PHASE POLLING CYCLE IMPLEMENTATION ==========

    /**
     * Phase 1: Batch Processing

     * Process devices in batches:
     * 1. Batch fping connectivity check
     * 2. For alive devices: GoEngine metrics collection
     * 3. For dead devices: Record connectivity failure
     * 4. Update device schedules and failure counters
     * 5. Update device availability status immediately (both success and failure)

     * Uses QueueBatchProcessor for sequential batch processing.
     *
     * @param dueDevices List of devices due for polling
     * @return Future<Void> - completes when all devices are processed and availability updated
     */
    private Future<Void> executeBatchProcessing(List<DevicePolling> dueDevices)
    {
        var promise = Promise.<Void>promise();

        try
        {
            logger.debug("Phase 1: Batch processing {} devices", dueDevices.size());

            // Use ParallelBatchProcessor with completion tracking
            // Each batch processes its results IMMEDIATELY when it completes (no waiting for other batches)
            // Completion handler triggers Phase 2 when ALL batches done
            var processor = new PollingBatchProcessor(dueDevices, batchSize, pollingWorkerPool);

            processor.processAllBatchesWithCompletion(v ->
            {
                // ✅ This runs when ALL batches complete
                // ✅ Each batch already stored metrics immediately upon completion
                // ✅ Now trigger Phase 2 (auto-disable)

                var failedDevices = processor.getFailedDevices();

                logger.info("Phase 1 completed: {}/{} devices processed successfully, {} failed",
                        dueDevices.size() - failedDevices.size(), dueDevices.size(), failedDevices.size());

                // Note: Availability updates are now handled by AvailabilityVerticle (10s cycle)
                // PollingMetricsVerticle only focuses on metrics collection

                promise.complete();
            })
            .onFailure(promise::fail);

            // Method returns immediately after submitting all batches
            logger.debug("All batches submitted, processing in background");
        }
        catch (Exception exception)
        {
            logger.error("Error in executeBatchProcessing: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Process a single batch of devices (OPTIMIZED - uses availability cache)

     * Flow:
     * 1. Check device status in availability cache (populated by AvailabilityVerticle)
     * 2. If status = "up" → execute GoEngine
     * 3. If status = "down" or "unknown" → increment failure count, skip GoEngine
     * 4. Update device state based on GoEngine results (reset failures + advance schedule on success)
     * 5. Return all failed devices in one pass
     */
    private Future<List<DevicePolling>> processSingleBatch(List<DevicePolling> batch)
    {
        try
        {
            // Track failed devices locally
            var failedDevices = new ArrayList<DevicePolling>();

            // Separate devices into "up" and "down" based on availability cache
            var upDevices = new JsonArray();

            for (var pd : batch)
            {
                // Check availability status from shared cache (stored as JsonObject)
                var availabilityJson = availabilityCache.get(pd.deviceId);

                String status = availabilityJson != null ? availabilityJson.getString("status") : "unknown";

                if ("up".equals(status))
                {
                    // Device is up - add to GoEngine execution list
                    upDevices.add(pd.deviceId);
                }
                else
                {
                    // Device is down or unknown - increment failure count, skip GoEngine
                    pd.incrementFailures();

                    failedDevices.add(pd);

                    logger.debug("Device {} is {} (from availability cache), skipping GoEngine",
                            pd.deviceId, status);
                }
            }

            logger.info("Availability check: {}/{} devices up, {}/{} down/unknown",
                    upDevices.size(), batch.size(), failedDevices.size(), batch.size());

            // Execute GoEngine for devices that are "up"
            if (upDevices.isEmpty())
            {
                logger.debug("No devices are up in batch, skipping GoEngine");

                return Future.succeededFuture(failedDevices);
            }

            return executeGoEngineForReachableDevices(batch, upDevices)
                    .map(metricsResults ->
                    {
                        // Process GoEngine results and update device state
                        for (var i = 0; i < upDevices.size(); i++)
                        {
                            var deviceId = upDevices.getString(i);

                            var pd = batch.stream()
                                    .filter(d -> d.deviceId.equals(deviceId))
                                    .findFirst()
                                    .orElse(null);

                            if (pd != null)
                            {
                                var success = metricsResults.getOrDefault(deviceId, false);

                                if (success)
                                {
                                    pd.resetFailures();

                                    pd.advanceSchedule();
                                }
                                else
                                {
                                    pd.incrementFailures();

                                    failedDevices.add(pd);
                                }
                            }
                        }

                        // Return all failed devices (availability failures + GoEngine failures)
                        return failedDevices;
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
    private Future<Map<String, Boolean>> executeGoEngineForReachableDevices(List<DevicePolling> allDevices, JsonArray reachableDeviceIds)
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
    private Map<String, Boolean> pollDeviceMetricsBatch(List<DevicePolling> devices)
    {
        Map<String, Boolean> results = new HashMap<>();

        var deviceCount = devices.size();

        // Initialize all devices as failed (will be updated on success)
        for (DevicePolling pd : devices)
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

            // Create IP-to-device lookup map for O(1) access when processing GoEngine streaming results
            // GoEngine returns results with IP address, we need to map back to DevicePolling objects
            // Without this map, we'd need O(n) linear search for each result line
            var devicesByIp = new HashMap<String, DevicePolling>();

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

            // Wait for process to complete with timeout
            var finished = process.waitFor(blockingTimeoutGoEngine, TimeUnit.SECONDS);

            if (!finished)
            {
                process.destroyForcibly();

                logger.warn("GoEngine batch timeout after {} seconds - process killed", blockingTimeoutGoEngine);

                return results;
            }

            // Read streaming output line by line and process results after process completes
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
     * Phase 2: Auto-Disable

     * Disable monitoring for devices that have reached or exceeded max_cycles_skipped (5) consecutive failures.
     * Devices are automatically disabled to prevent continuous polling of unreachable devices.
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
                logger.info("Phase 2: No devices to auto-disable");

                promise.complete();

                return;
            }

            logger.warn("Phase 2: Auto-disabling {} devices due to {} consecutive failures", devicesToDisable.size(), maxCyclesSkipped);

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
    private void disableDevicesSequentially(List<DevicePolling> devices, int index, Promise<Void> promise)
    {
        try
        {
            if (index >= devices.size())
            {
                promise.complete();

                return;
            }

            var pd = devices.get(index);

            logger.warn("Auto-disabling device {} due to {} consecutive failures", pd.deviceName, maxCyclesSkipped);

            // Remove from cache FIRST (synchronous, immediate) to prevent race condition
            // This ensures the device won't be polled in the next cycle before DB update completes
            deviceCache.remove(pd.deviceId);

            // THEN update database (async, non-blocking)
            deviceService.deviceDisableProvisioning(pd.deviceId)
                .onSuccess(result ->
                {
                    logger.info("Device {} provisioning disabled successfully in database", pd.deviceName);

                    // Continue with next device
                    disableDevicesSequentially(devices, index + 1, promise);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to auto-disable device {}: {}", pd.deviceName, cause.getMessage());

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
     * Polling batch processor using ParallelBatchProcessor.

     * Extends the generic ParallelBatchProcessor to handle polling-specific batch processing.
     * Processes devices in parallel batches with fping, port check, and GoEngine metrics collection.

     * Features:
     * - Parallel batch processing of devices
     * - Connectivity pre-filtering (fping + port check)
     * - GoEngine metrics collection for alive devices
     * - Fail-tolerant: continues with next batch on failure
     * - Tracks failed devices for retry phase
     */
    private class PollingBatchProcessor extends ParallelBatchProcessor<DevicePolling>
    {
        private final List<DevicePolling> failedDevices;

        /**
         * Constructor for PollingBatchProcessor.

         * Initializes the processor with devices to poll.
         *
         * @param devices List of devices to poll in batches
         * @param batchSize Maximum devices per batch
         * @param workerExecutor Worker executor for parallel processing
         */
        public PollingBatchProcessor(List<DevicePolling> devices, int batchSize, WorkerExecutor workerExecutor)
        {
            super(devices, batchSize, workerExecutor);

            this.failedDevices = Collections.synchronizedList(new ArrayList<>());
        }

        /**
         * Process a batch of devices (BLOCKING operation).

         * Executes the complete polling workflow for a batch:
         * 1. Batch fping connectivity check
         * 2. Port reachability check for alive devices
         * 3. GoEngine metrics collection for reachable devices
         * 4. Update device schedules and failure counters
         *
         * @param batch List of devices to poll in this batch
         * @return JsonArray of results (empty for polling)
         */
        @Override
        protected JsonArray processBatch(List<DevicePolling> batch)
        {
            try
            {
                // Process batch using async method, then block and wait for result
                // (safe in worker thread)
                var batchFailures = processSingleBatch(batch)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get();

                // Add failed devices to synchronized list
                failedDevices.addAll(batchFailures);

                return new JsonArray();
            }
            catch (Exception exception)
            {
                logger.error("Error processing polling batch: {}", exception.getMessage());

                return new JsonArray();
            }
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
        protected void handleBatchFailure(List<DevicePolling> batch, Throwable cause)
        {
            logger.warn("Batch processing failed for {} devices: {}", batch.size(), cause.getMessage());

            for (DevicePolling pd : batch)
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
        public List<DevicePolling> getFailedDevices()
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

                logger.info("Polling cycle timer cancelled");
            }

            // Close worker pool
            if (pollingWorkerPool != null)
            {
                pollingWorkerPool.close();

                logger.info("Polling worker pool closed");
            }

            // Clear cache
            if (deviceCache != null)
            {
                deviceCache.clear();

                logger.info("Device cache cleared");
            }

            logger.info("PollingMetricsVerticle stopped successfully");

            stopPromise.complete();
        }
        catch (Exception exception)
        {
            logger.error("Error in stop: {}", exception.getMessage());

            stopPromise.fail(exception);
        }
    }
}
