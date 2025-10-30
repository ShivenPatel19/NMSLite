/*
 * AVAILABILITY MONITORING FUNCTIONALITY
 *
 * AvailabilityVerticle provides fast device availability checking.
 *
 * Features:
 * - Periodic availability checks (10-second cycle)
 * - Batch fping for connectivity validation
 * - Port reachability checks
 * - Device availability status tracking
 * - Event bus communication with PollingMetricsVerticle
 */

package com.nmslite.verticles;

import com.nmslite.Bootstrap;

import com.nmslite.core.NetworkConnectivity;

import com.nmslite.core.ParallelBatchProcessor;

import com.nmslite.services.DeviceService;

import com.nmslite.services.AvailabilityService;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.WorkerExecutor;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.core.shareddata.LocalMap;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.*;

import java.util.concurrent.TimeUnit;

/**
 * AvailabilityVerticle - Fast Device Availability Monitoring

 * Responsibilities:
 * - Periodic availability checks (10-second cycle)
 * - Batch fping for connectivity validation
 * - Port reachability checks
 * - Device availability status tracking
 * - Event bus communication with PollingMetricsVerticle
 */
public class AvailabilityVerticle extends AbstractVerticle
{

    private static final Logger logger = LoggerFactory.getLogger(AvailabilityVerticle.class);

    // Configuration
    private int cycleIntervalSeconds;

    private int batchSize;

    private long availabilityTimerId;

    // Service proxies
    private DeviceService deviceService;

    private AvailabilityService availabilityService;

    // Worker pool for blocking operations (fping, port check)
    private WorkerExecutor availabilityWorkerPool;

    // Shared availability cache name (accessible by other verticles via SharedData)
    private static final String AVAILABILITY_CACHE_NAME = "availability-cache";

    // Key: device_id, Value: JsonObject with fields (device_id, address, port, status)
    // Thread-safe: Vert.x LocalMap shared across verticles
    // Note: LocalMap only supports immutable types (String, JsonObject, etc.), not custom POJOs!
    private LocalMap<String, JsonObject> availabilityCache;

    /**
     * Start the verticle: load configuration, initialize service proxies, load availability cache, and start cycle.
     *
     * @param startPromise promise completed once the verticle is ready
     */
    @Override
    public void start(Promise<Void> startPromise)
    {
        try
        {
            logger.info("Starting AvailabilityVerticle");

            // Load configuration
            var config = Objects.requireNonNull(Bootstrap.getConfig());

            var availabilityConfig = config.getJsonObject("availability", new JsonObject());

            cycleIntervalSeconds = availabilityConfig.getJsonObject("cycle", new JsonObject())
                    .getJsonObject("interval", new JsonObject())
                    .getInteger("seconds", 10);

            batchSize = availabilityConfig.getJsonObject("batch", new JsonObject())
                    .getInteger("size", 50);

            var workerPoolSize = availabilityConfig.getJsonObject("worker", new JsonObject())
                    .getJsonObject("pool", new JsonObject())
                    .getInteger("size", 10);

            var workerPoolTimeoutSeconds = availabilityConfig.getJsonObject("worker", new JsonObject())
                    .getJsonObject("pool", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 600);

            logger.info("Availability configuration: cycle={}s, batch={}, workers={}", cycleIntervalSeconds, batchSize, workerPoolSize);

            // Initialize service proxies
            deviceService = DeviceService.createProxy();

            availabilityService = AvailabilityService.createProxy();

            // Create dedicated worker pool for availability checks
            availabilityWorkerPool = vertx.createSharedWorkerExecutor(
                    "availability-worker-pool",
                    workerPoolSize,
                    workerPoolTimeoutSeconds,
                    TimeUnit.SECONDS);

            logger.info("Availability worker pool created: {} workers, {}s timeout", workerPoolSize, workerPoolTimeoutSeconds);

            // Initialize availability cache using SharedData LocalMap (shared across verticles)
            availabilityCache = vertx.sharedData().getLocalMap(AVAILABILITY_CACHE_NAME);

            logger.info("Availability cache initialized using SharedData LocalMap: {}", AVAILABILITY_CACHE_NAME);

            // Load availability cache from database
            loadAvailabilityCache()
                    .onSuccess(count ->
                    {
                        logger.info("Availability cache initialized: {} devices loaded", count);

                        // Register event bus handlers
                        setupEventBusConsumers();

                        // Start availability cycle
                        startAvailabilityCycle();

                        logger.info("AvailabilityVerticle started successfully");

                        startPromise.complete();
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to start AvailabilityVerticle: {}", cause.getMessage());

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
     * Load availability cache from database.
     * Loads ALL devices (regardless of provisioning status) and their current availability status.
     *
     * @return Future containing count of devices loaded
     */
    private Future<Integer> loadAvailabilityCache()
    {
        try
        {
            // Load ALL devices (regardless of is_provisioned flag)
            return deviceService.deviceListAll()
                    .compose(devices ->
                    {
                        // Load availability status for all devices
                        return availabilityService.availabilityGetAll()
                                .map(availabilityResults ->
                                {
                                    // Create map of device_id -> status for quick lookup
                                    var statusMap = new HashMap<String, String>();

                                    for (int i = 0; i < availabilityResults.size(); i++)
                                    {
                                        var row = availabilityResults.getJsonObject(i);

                                        var deviceId = row.getString("device_id");

                                        var status = row.getString("status", "unknown");

                                        statusMap.put(deviceId, status.toLowerCase());
                                    }

                                    // Create JsonObject entries for cache
                                    for (int i = 0; i < devices.size(); i++)
                                    {
                                        var deviceData = devices.getJsonObject(i);

                                        var deviceId = deviceData.getString("device_id");

                                        // Strip CIDR notation from IP address
                                        var ipWithCidr = deviceData.getString("ip_address");

                                        var address = ipWithCidr.contains("/") ? ipWithCidr.split("/")[0] : ipWithCidr;

                                        var port = deviceData.getInteger("port");

                                        var status = statusMap.getOrDefault(deviceId, "unknown");

                                        // Store directly as JsonObject (no POJO conversion)
                                        var deviceJson = new JsonObject()
                                                .put("device_id", deviceId)
                                                .put("address", address)
                                                .put("port", port)
                                                .put("status", status);

                                        availabilityCache.put(deviceId, deviceJson);
                                    }

                                    return availabilityCache.size();
                                });
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in loadAvailabilityCache: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Register event bus consumers for device lifecycle events.
     */
    private void setupEventBusConsumers()
    {
        try
        {
            // Device provisioned (monitoring enabled) - ADD to cache
            vertx.eventBus().consumer("device.provision.enabled", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceProvisionEnabled(deviceId);
            });
            
            // Device deleted - REMOVE from cache
            vertx.eventBus().consumer("device.deleted", msg ->
            {
                var data = (JsonObject) msg.body();

                var deviceId = data.getString("device_id");

                onDeviceDeleted(deviceId);
            });

            // Device restored - ADD back to cache
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

            logger.info("Event bus consumers registered");
        }
        catch (Exception exception)
        {
            logger.error("Error in setupEventBusConsumers: {}", exception.getMessage());
        }
    }

    /**
     * Start availability cycle with periodic timer.
     */
    private void startAvailabilityCycle()
    {
        try
        {
            availabilityTimerId = vertx.setPeriodic(cycleIntervalSeconds * 1000L, timerId -> executeAvailabilityCycle());

            logger.debug("Availability cycle started with {} second interval", cycleIntervalSeconds);
        }
        catch (Exception exception)
        {
            logger.error("Error in startAvailabilityCycle: {}", exception.getMessage());
        }
    }

    /**
     * Execute availability cycle: check all devices and update status.
     */
    private void executeAvailabilityCycle()
    {
        try
        {
            var startTime = System.currentTimeMillis();

            var allDevices = new ArrayList<>(availabilityCache.values());

            if (allDevices.isEmpty())
            {
                logger.info("Availability cycle: No devices to check");

                return;
            }

            logger.info("Availability cycle: Checking {} devices", allDevices.size());

            // Process in batches (fire-and-forget: batches process in background)
            processAvailabilityBatches(allDevices)
                    .onComplete(result ->
                    {
                        var duration = System.currentTimeMillis() - startTime;

                        if (result.succeeded())
                        {
                            logger.info("Availability batches submitted in {}ms (processing in background)", duration);
                        }
                        else
                        {
                            logger.error("Availability batch submission failed in {}ms: {}", duration, result.cause().getMessage());
                        }
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in executeAvailabilityCycle: {}", exception.getMessage());
        }
    }

    /**
     * Process availability batches in parallel using ParallelBatchProcessor (fire-and-forget pattern).

     * Fire-and-forget pattern is optimal for availability checks because:
     * 1. No results need to be collected (side effects only: cache/DB updates)
     * 2. Each batch is independent (no aggregation needed)
     * 3. Real-time updates preferred (faster cache updates = better accuracy)
     * 4. Next cycle (10s) doesn't depend on previous cycle completion
     * 5. No user waiting for response (background periodic task)

     * Flow:
     * - Submit all batches to WorkerExecutor
     * - Return immediately (don't wait for batches)
     * - Each batch updates cache/DB as it completes
     * - Faster cache updates = more accurate availability data for PollingMetricsVerticle
     *
     * @param allDevices List of all devices to check (as JsonObject)
     * @return Future that completes immediately after submitting all batches
     */
    private Future<Void> processAvailabilityBatches(List<JsonObject> allDevices)
    {
        try
        {
            // Create parallel batch processor
            var processor = new AvailabilityBatchProcessor(allDevices, batchSize, availabilityWorkerPool);

            // Fire-and-forget: Submit all batches and return immediately
            // Each batch will update cache/DB as it completes (no waiting for all batches)
            return processor.processAllBatchesFireAndForget()
                    .onSuccess(v -> logger.debug("Availability batches submitted: {} devices in {} batches", allDevices.size(), (allDevices.size() + batchSize - 1) / batchSize))
                    .onFailure(cause -> logger.error("Error submitting availability batches: {}", cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in processAvailabilityBatches: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Inner class: Parallel batch processor for availability checks.

     * Extends ParallelBatchProcessor to process device availability checks in parallel.
     */
    private class AvailabilityBatchProcessor extends ParallelBatchProcessor<JsonObject>
    {

        /**
         * Constructor for AvailabilityBatchProcessor.
         *
         * @param devices List of devices to check (as JsonObject)
         * @param batchSize Maximum devices per batch
         * @param workerExecutor Worker executor for parallel processing
         */
        public AvailabilityBatchProcessor(List<JsonObject> devices, int batchSize, WorkerExecutor workerExecutor)
        {
            super(devices, batchSize, workerExecutor);
        }

        /**
         * Process a single batch of devices (BLOCKING operation).

         * Performs connectivity checks and updates device availability status.
         *
         * @param batch List of devices in this batch (as JsonObject)
         * @return JsonArray of results (empty for availability checks)
         */
        @Override
        protected JsonArray processBatch(List<JsonObject> batch)
        {
            try
            {
                // Perform connectivity checks
                var results = performConnectivityChecks(batch);

                // Update cache and database
                for (var deviceJson : batch)
                {
                    var deviceId = deviceJson.getString("device_id");

                    boolean isUp = results.getOrDefault(deviceId, false);

                    String newStatus = isUp ? "up" : "down";

                    // Update cache (SharedData LocalMap - accessible by PollingMetricsVerticle)
                    var updatedJson = new JsonObject()
                            .put("device_id", deviceId)
                            .put("address", deviceJson.getString("address"))
                            .put("port", deviceJson.getInteger("port"))
                            .put("status", newStatus);

                    availabilityCache.put(deviceId, updatedJson);

                    // Update database (async, fire-and-forget)
                    updateDeviceAvailability(deviceId, isUp);
                }

                return new JsonArray();  // Empty results for availability checks
            }
            catch (Exception exception)
            {
                logger.error("Error processing availability batch: {}", exception.getMessage());

                return new JsonArray();
            }
        }

        /**
         * Handle batch processing failure.

         * When a batch fails (WorkerExecutor exception, timeout, etc.), we mark all devices
         * in the batch as "down" to maintain consistency with performConnectivityChecks()
         * error handling and prevent false positives.

         * This is a SAFE DEFAULT behavior:
         * - Better to skip polling than to poll unreachable devices
         * - Next cycle (10s) will correct the status if devices are actually up
         * - Prevents wasting resources on potentially dead devices

         * @param batch The batch that failed to process (as JsonObject)
         * @param cause The exception that caused the failure
         */
        @Override
        protected void handleBatchFailure(List<JsonObject> batch, Throwable cause)
        {
            logger.error("Availability batch failed for {} devices: {}", batch.size(), cause.getMessage());

            // Mark all devices as "down" (safe default - assume unreachable on batch failure)
            for (var deviceJson : batch)
            {
                try
                {
                    var deviceId = deviceJson.getString("device_id");

                    var address = deviceJson.getString("address");

                    var port = deviceJson.getInteger("port");

                    // Update cache with "down" status
                    var updatedJson = new JsonObject()
                            .put("device_id", deviceId)
                            .put("address", address)
                            .put("port", port)
                            .put("status", "down");

                    availabilityCache.put(deviceId, updatedJson);

                    // Update database (async, fire-and-forget)
                    updateDeviceAvailability(deviceId, false);

                    logger.debug("Marked device {} as 'down' due to batch failure", deviceId);
                }
                catch (Exception exception)
                {
                    logger.error("Error updating device status in handleBatchFailure: {}", exception.getMessage());
                }
            }

            logger.warn("Marked {} devices as 'down' due to batch failure (will retry in next cycle)", batch.size());
        }
    }

    /**
     * Perform connectivity checks (fping + port check) for a batch of devices.
     *
     * @param batch List of devices to check (as JsonObject)
     * @return Map of deviceId -> isUp
     */
    private Map<String, Boolean> performConnectivityChecks(List<JsonObject> batch)
    {
        try
        {
            var results = new HashMap<String, Boolean>();

            // Step 1: Batch fping check
            var ipList = batch.stream().map(json -> json.getString("address")).toList();

            var fpingResults = NetworkConnectivity.batchFpingCheck(ipList);

            // Step 2: Port check for devices that passed fping
            var pingAliveDevices = batch.stream()
                    .filter(json -> fpingResults.getOrDefault(json.getString("address"), false))
                    .toList();

            if (pingAliveDevices.isEmpty())
            {
                // All failed fping
                batch.forEach(json -> results.put(json.getString("device_id"), false));

                return results;
            }

            // Step 3: Mark devices that failed fping as down
            batch.stream()
                    .filter(json -> !fpingResults.getOrDefault(json.getString("address"), false))
                    .forEach(json -> results.put(json.getString("device_id"), false));

            // Step 4: Batch port check (parallel)
            for (var deviceJson : pingAliveDevices)
            {
                var address = deviceJson.getString("address");

                var port = deviceJson.getInteger("port");

                boolean portOpen = NetworkConnectivity.portCheck(address, port);

                results.put(deviceJson.getString("device_id"), portOpen);
            }

            return results;
        }
        catch (Exception exception)
        {
            logger.error("Error in performConnectivityChecks: {}", exception.getMessage());

            // Return all devices as down on error
            var results = new HashMap<String, Boolean>();

            batch.forEach(json -> results.put(json.getString("device_id"), false));

            return results;
        }
    }

    /**
     * Update device availability in database.
     *
     * @param deviceId Device ID
     * @param isUp Whether device is up
     */
    private void updateDeviceAvailability(String deviceId, boolean isUp)
    {
        try
        {
            String status = isUp ? "up" : "down";

            availabilityService.availabilityUpdate(deviceId, status)
                    .onSuccess(result ->
                    {
                        if (result.getBoolean("success", false))
                        {
                            logger.debug("Updated availability for device {}: {}", deviceId, status);
                        }
                        else
                        {
                            logger.warn("Failed to update availability for device {}: {}", deviceId, result.getString("message"));
                        }
                    })
                    .onFailure(cause -> logger.error("Failed to update availability for device {}: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in updateDeviceAvailability: {}", exception.getMessage());
        }
    }

    /**
     * Handle device provision enabled event.
     *
     * @param deviceId Device ID
     */
    private void onDeviceProvisionEnabled(String deviceId)
    {
        try
        {
            logger.info("Device provisioned: {}", deviceId);

            // Load device data and add to cache
            deviceService.deviceGetById(deviceId)
                    .onSuccess(deviceData ->
                    {
                        var ipWithCidr = deviceData.getString("ip_address");

                        var address = ipWithCidr.contains("/") ? ipWithCidr.split("/")[0] : ipWithCidr;

                        var port = deviceData.getInteger("port");

                        // Store directly as JsonObject (no POJO conversion)
                        var deviceJson = new JsonObject()
                                .put("device_id", deviceId)
                                .put("address", address)
                                .put("port", port)
                                .put("status", "unknown");

                        availabilityCache.put(deviceId, deviceJson);

                        logger.info("Added device to availability cache: {}", deviceId);
                    })
                    .onFailure(cause -> logger.error("Failed to load device {}: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceProvisionEnabled: {}", exception.getMessage());
        }
    }



    /**
     * Handle device deleted event.
     *
     * @param deviceId Device ID
     */
    private void onDeviceDeleted(String deviceId)
    {
        try
        {
            logger.info("Device deleted: {}", deviceId);

            availabilityCache.remove(deviceId);

            logger.info("Removed device from availability cache: {}", deviceId);
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceDeleted: {}", exception.getMessage());
        }
    }

    /**
     * Handle device restored event.
     *
     * @param deviceId Device ID
     */
    private void onDeviceRestored(String deviceId)
    {
        try
        {
            logger.info("Device restored: {}", deviceId);

            // Load device data and add to cache
            deviceService.deviceGetById(deviceId)
                    .onSuccess(deviceData ->
                    {
                        var ipWithCidr = deviceData.getString("ip_address");

                        var address = ipWithCidr.contains("/") ? ipWithCidr.split("/")[0] : ipWithCidr;

                        var port = deviceData.getInteger("port");

                        // Store directly as JsonObject
                        var deviceJson = new JsonObject()
                                .put("device_id", deviceId)
                                .put("address", address)
                                .put("port", port)
                                .put("status", "unknown");

                        availabilityCache.put(deviceId, deviceJson);

                        logger.info("Added device to availability cache: {}", deviceId);
                    })
                    .onFailure(cause -> logger.error("Failed to load device {}: {}", deviceId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onDeviceRestored: {}", exception.getMessage());
        }
    }

    /**
     * Handle credential profile updated event.
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
                            if (availabilityCache.containsKey(deviceId))
                            {
                                var ipWithCidr = deviceData.getString("ip_address");

                                var address = ipWithCidr.contains("/") ? ipWithCidr.split("/")[0] : ipWithCidr;

                                var port = deviceData.getInteger("port");

                                // Get current status from cache
                                var oldDeviceJson = availabilityCache.get(deviceId);

                                var oldStatus = oldDeviceJson != null ? oldDeviceJson.getString("status") : "unknown";

                                // Create updated JsonObject with new port
                                var updatedJson = new JsonObject()
                                        .put("device_id", deviceId)
                                        .put("address", address)
                                        .put("port", port)
                                        .put("status", oldStatus);

                                availabilityCache.put(deviceId, updatedJson);

                                logger.debug("Updated device {} with new port: {}", deviceId, port);
                            }
                        }

                        logger.info("Updated {} devices for credential profile {}", devices.size(), profileId);
                    })
                    .onFailure(cause -> logger.error("Failed to reload devices for credential profile {}: {}", profileId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in onCredentialProfileUpdated: {}", exception.getMessage());
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
            logger.info("Stopping AvailabilityVerticle - initiating graceful shutdown");

            // Step 1: Cancel availability cycle timer to prevent new cycles
            if (availabilityTimerId > 0)
            {
                vertx.cancelTimer(availabilityTimerId);

                logger.info("Availability cycle timer cancelled");
            }

            // Step 2: Wait 2 seconds for in-flight fire-and-forget tasks to complete
            vertx.setTimer(2000, timerId ->
            {
                try
                {
                    // Step 3: Close worker pool
                    if (availabilityWorkerPool != null)
                    {
                        availabilityWorkerPool.close();

                        logger.info("Availability worker pool closed");
                    }

                    // Step 4: Clear shared availability cache (SharedData LocalMap)
                    if (availabilityCache != null)
                    {
                        availabilityCache.clear();

                        logger.info("Availability cache cleared");
                    }

                    logger.info("AvailabilityVerticle stopped successfully");

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




