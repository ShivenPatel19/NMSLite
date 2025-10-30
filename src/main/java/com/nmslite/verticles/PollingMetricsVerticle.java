/*
 * METRIC POLLING FUNCTIONALITY
 *
 * PollingMetricsVerticle provides continuous device monitoring capabilities.
 *
 * Features:
 * - Periodic polling of active devices (60-second cycle)
 * - Availability pre-filtering (from AvailabilityVerticle cache)
 * - GoEngine metrics collection for "up" devices
 * - Inline auto-disable (fire-and-forget pattern)
 * - Thread-safe concurrent processing
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

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.TimeUnit;

import java.util.stream.Collectors;

/**
 * PollingMetricsVerticle - Continuous Device Monitoring
 
 * Responsibilities:
 * - Periodic polling of active devices (60-second cycle)
 * - Availability pre-filtering (from AvailabilityVerticle cache)
 * - GoEngine metrics collection for "up" devices
 * - Inline auto-disable (fire-and-forget pattern)
 * - Thread-safe concurrent processing with ConcurrentHashMap
 
 * Architecture:
 * - Single-stage processing (auto-disable happens inline)
 * - Fire-and-forget batch execution (matches AvailabilityVerticle)
 * - Parallel batch processing with worker pool
 * - Immediate cache removal for disabled devices
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

    // In-memory device cache (thread-safe for concurrent access)
    // Key: device_id, Value: DevicePolling (persistent data + runtime state)
    // ConcurrentHashMap enables safe concurrent access from event bus handlers and polling cycles
    private ConcurrentHashMap<String, DevicePolling> deviceCache;

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

            this.deviceService = DeviceService.createProxy();

            this.metricsService = MetricsService.createProxy();

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
            // ConcurrentHashMap for thread-safe concurrent access
            deviceCache = new ConcurrentHashMap<>();

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
     * Execute polling cycle (single-stage with fire-and-forget pattern).

     * Flow:
     * 1. Filter devices due for polling (using aligned scheduling)
     * 2. Submit all batches for processing (fire-and-forget)
     * 3. Each batch independently:
     *    - Checks availability from cache
     *    - Executes GoEngine for "up" devices
     *    - Updates device state (failures, schedule)
     *    - Auto-disables if threshold reached (inline)
     *    - Stores metrics in database
     * 4. Return immediately (batches process in background)

     * Auto-disable happens inline during batch processing (no separate phase).
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

                        // Batch Processing: Process all due devices in batches
                        // Auto-disable happens inline during batch processing
                        executeBatchProcessing(dueDevices)
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

    /**
     * Execute batch processing for all due devices (fire-and-forget pattern).

     * Fire-and-forget pattern is optimal for polling because:
     * 1. Auto-disable happens inline during batch processing (no aggregation needed)
     * 2. Each batch is independent (no dependencies between batches)
     * 3. Metrics stored immediately as each batch completes (real-time updates)
     * 4. Next cycle doesn't depend on previous cycle completion
     * 5. Worker pool handles backpressure automatically

     * Matches AvailabilityVerticle's execution pattern for consistency.

     * Process flow per batch:
     * 1. Check availability from cache (populated by AvailabilityVerticle)
     * 2. Execute GoEngine for "up" devices
     * 3. Update device state (failures, schedule)
     * 4. Auto-disable if threshold reached (inline, fire-and-forget)
     * 5. Store metrics in database
     *
     * @param dueDevices List of devices due for polling
     * @return Future<Void> - completes immediately after submitting all batches
     */
    private Future<Void> executeBatchProcessing(List<DevicePolling> dueDevices)
    {
        try
        {
            logger.debug("Batch processing {} devices (fire-and-forget)", dueDevices.size());

            // Create parallel batch processor
            var processor = new PollingBatchProcessor(dueDevices, batchSize, pollingWorkerPool);

            // Fire-and-forget: Submit all batches and return immediately
            // Each batch processes independently in worker pool
            return processor.processAllBatchesFireAndForget()
                    .onSuccess(v ->
                            logger.debug("Batches submitted: {} devices in {} batches",
                                    dueDevices.size(), (dueDevices.size() + batchSize - 1) / batchSize))
                    .onFailure(cause ->
                            logger.error("Error submitting batches: {}", cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in executeBatchProcessing: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Process a single batch of devices (uses availability cache for pre-filtering).

     * Flow:
     * 1. Check device status in availability cache (populated by AvailabilityVerticle)
     * 2. If status = "up" → execute GoEngine
     * 3. If status = "down" or "unknown" → increment failure count, check auto-disable, skip GoEngine
     * 4. Update device state based on GoEngine results:
     *    - Success: reset failures + advance schedule
     *    - Failure: increment failures + check auto-disable

     * This method runs in a worker thread (called from processBatch).
     *
     * @param batch List of devices to process in this batch
     */
    private void processSingleBatchBlocking(List<DevicePolling> batch)
    {
        try
        {
            // Separate devices into "up" and "down" based on availability cache
            var upDevices = new ArrayList<DevicePolling>();

            for (var pd : batch)
            {
                // Check availability status from shared cache (stored as JsonObject)
                var availabilityJson = availabilityCache.get(pd.deviceId);

                String status = availabilityJson != null ? availabilityJson.getString("status") : "unknown";

                if ("up".equals(status))
                {
                    // Device is up - add to GoEngine execution list
                    upDevices.add(pd);
                }
                else
                {
                    // Device is down or unknown - increment failure count, skip GoEngine
                    pd.incrementFailures();

                    // Check if device should be auto-disabled (inline, immediate)
                    checkAndDisableIfNeeded(pd);

                    logger.debug("Device {} is {} (from availability cache), skipping GoEngine", pd.deviceId, status);
                }
            }

            logger.info("Availability check: {}/{} devices up, {}/{} down/unknown",
                    upDevices.size(), batch.size(), batch.size() - upDevices.size(), batch.size());

            // Execute GoEngine for devices that are "up"
            if (upDevices.isEmpty())
            {
                logger.debug("No devices are up in batch, skipping GoEngine");

                return;
            }

            // Direct blocking call to GoEngine (already in worker thread)
            var metricsResults = pollDeviceMetricsBatch(upDevices);

            // Process GoEngine results and update device state
            for (var pd : upDevices)
            {
                var success = metricsResults.getOrDefault(pd.deviceId, false);

                if (success)
                {
                    pd.resetFailures();

                    pd.advanceSchedule();
                }
                else
                {
                    pd.incrementFailures();

                    // Check if device should be auto-disabled (inline, immediate)
                    checkAndDisableIfNeeded(pd);
                }
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in processSingleBatchBlocking: {}", exception.getMessage());

            throw exception;
        }
    }

    /**
     * Check if device should be auto-disabled and disable it immediately.

     * Called after incrementing failures to check if threshold is reached.
     * Removes from cache immediately (thread-safe with ConcurrentHashMap),
     * then triggers async DB update (fire-and-forget).

     * Fire-and-forget pattern is used because:
     * 1. Cache removal is immediate (device won't be polled in next cycle)
     * 2. DB update is eventual consistency (not critical for real-time operation)
     * 3. Non-blocking (doesn't delay batch processing)
     * 4. Parallel updates (multiple devices can be disabled simultaneously)
     *
     * @param pd Device to check for auto-disable
     */
    private void checkAndDisableIfNeeded(DevicePolling pd)
    {
        try
        {
            if (pd.shouldAutoDisable(maxCyclesSkipped))
            {
                logger.warn("Device {} reached {} consecutive failures, auto-disabling", pd.deviceName, maxCyclesSkipped);

                // Remove from cache FIRST (thread-safe, immediate)
                // This ensures device won't be polled in next cycle
                deviceCache.remove(pd.deviceId);

                // THEN update database (async, fire-and-forget)
                // No need to wait for DB update - cache removal is sufficient
                deviceService.deviceDisableProvisioning(pd.deviceId)
                        .onSuccess(v ->
                                logger.info("Device {} auto-disabled successfully in database", pd.deviceName))
                        .onFailure(cause ->
                                logger.error("Failed to auto-disable device {} in database: {}", pd.deviceName, cause.getMessage()));
            }
        }
        catch (Exception exception)
        {
            logger.error("Error in checkAndDisableIfNeeded for device {}: {}", pd.deviceName, exception.getMessage());
        }
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

            // Create IP-to-device-ID lookup map for O(1) access when processing GoEngine streaming results
            // GoEngine returns results with IP address, we need to map back to device IDs
            // Without this map, we'd need O(n) linear search for each result line
            var deviceIdsByIp = new HashMap<String, String>();

            for (var pd : devices)
            {
                deviceIdsByIp.put(pd.address, pd.deviceId);
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

                        // Find the device ID by IP address
                        var deviceId = deviceIdsByIp.get(deviceAddress);

                        if (deviceId == null)
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
                            storeMetrics(deviceId, result);

                            results.put(deviceId, true);

                            successCount++;
                        }
                        else
                        {
                            results.put(deviceId, false);
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
     * Store metrics in database (fire-and-forget).

     * Transforms GoEngine response format to database schema format:
     * - GoEngine: {"cpu":{"usage_percent":15.2},"memory":{...},"disk":{...}}
     * - Database: {"cpu_usage_percent":15.2,"memory_usage_percent":...}

     * Includes cache check to prevent race conditions:
     * - If device was removed from cache (e.g., auto-disabled), skip metrics insertion
     * - Thread-safe with ConcurrentHashMap
     *
     * @param deviceId Device ID
     * @param goEngineResult GoEngine response JSON
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
     * Processes devices in parallel batches with availability check and GoEngine metrics collection.

     * Features:
     * - Parallel batch processing of devices
     * - Availability pre-filtering (from AvailabilityVerticle cache)
     * - GoEngine metrics collection for "up" devices
     * - Inline auto-disable (fire-and-forget)
     * - Fail-tolerant: continues with next batch on failure
     */
    private class PollingBatchProcessor extends ParallelBatchProcessor<DevicePolling>
    {
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
        }

        /**
         * Process a batch of devices (BLOCKING operation).

         * Executes the complete polling workflow for a batch:
         * 1. Check availability from cache (populated by AvailabilityVerticle)
         * 2. GoEngine metrics collection for "up" devices
         * 3. Update device state (failures, schedule)
         * 4. Auto-disable if threshold reached (inline, fire-and-forget)
         * 5. Store metrics in database

         * This method runs in a worker thread from the worker pool.
         *
         * @param batch List of devices to poll in this batch
         * @return JsonArray of results (empty for polling)
         */
        @Override
        protected JsonArray processBatch(List<DevicePolling> batch)
        {
            try
            {
                // Direct blocking call (already in worker thread)
                // Auto-disable happens inline during processing
                processSingleBatchBlocking(batch);

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

         * Marks all devices in the failed batch as failed and checks for auto-disable.
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

                // Check if device should be auto-disabled (inline, fire-and-forget)
                checkAndDisableIfNeeded(pd);
            }
        }
    }

    /**
     * Stop the verticle.
     * Graceful shutdown sequence:
     * 1. Cancel timer to prevent new cycles
     * 2. Wait 2 seconds for in-flight fire-and-forget tasks to complete
     * 3. Close worker pool
     * 4. Clear cache
     *
     * @param stopPromise promise completed once the verticle is stopped
     */
    @Override
    public void stop(Promise<Void> stopPromise)
    {
        try
        {
            logger.info("Stopping PollingMetricsVerticle - initiating graceful shutdown");

            // Step 1: Cancel polling cycle timer to prevent new cycles
            if (pollingTimerId != 0)
            {
                vertx.cancelTimer(pollingTimerId);

                logger.info("Polling cycle timer cancelled");
            }

            // Step 2: Wait 2 seconds for in-flight fire-and-forget tasks to complete
            vertx.setTimer(2000, timerId ->
            {
                try
                {
                    // Step 3: Close worker pool
                    if (pollingWorkerPool != null)
                    {
                        pollingWorkerPool.close();

                        logger.info("Polling worker pool closed");
                    }

                    // Step 4: Clear cache
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
                    logger.error("Error during graceful shutdown: {}", exception.getMessage());

                    stopPromise.fail(exception);
                }
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in stop: {}", exception.getMessage());

            stopPromise.fail(exception);
        }
    }
}
