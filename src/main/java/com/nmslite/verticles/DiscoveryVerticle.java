package com.nmslite.verticles;

import com.nmslite.services.DeviceService;

import com.nmslite.services.DeviceTypeService;

import com.nmslite.services.CredentialProfileService;

import com.nmslite.services.DiscoveryProfileService;

import com.nmslite.utils.IPRangeUtil;

import com.nmslite.utils.NetworkConnectivityUtil;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.eventbus.Message;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.io.BufferedReader;

import java.io.InputStreamReader;

import java.util.*;

import java.util.concurrent.ConcurrentLinkedQueue;

import java.util.concurrent.TimeUnit;

import com.nmslite.core.QueueBatchProcessor;

import java.util.stream.Collectors;

/**
 * DiscoveryVerticle - Device Discovery Workflow
 *
 * Responsibilities:
 * - Single device discovery and validation
 * - Port scanning for device connectivity
 * - GoEngine integration for SSH/WinRM discovery
 * - Device provisioning to database
 * - Discovery results via API responses
 */
public class DiscoveryVerticle extends AbstractVerticle
{

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryVerticle.class);

    private String goEnginePath;

    private String fpingPath;

    private int timeoutSeconds;

    private int connectionTimeoutSeconds;

    private int retryCount;

    private int fpingTimeoutSeconds;

    private int discoveryBatchSize;

    private int blockingTimeoutGoEngine;

    // Service proxies
    private DeviceService deviceService;

    private DeviceTypeService deviceTypeService;

    private CredentialProfileService credentialProfileService;

    private DiscoveryProfileService discoveryProfileService;

    /**
     * Start the verticle: load configuration, initialize service proxies, and register event bus consumers.
     * Loads GoEngine and fping settings from config, then calls setupEventBusConsumers().
     *
     * @param startPromise promise completed once the verticle is ready
     */
    @Override
    public void start(Promise<Void> startPromise)
    {
        logger.info("🔍 Starting DiscoveryVerticle - Device Discovery");

        // Load configuration from tools and discovery sections
        JsonObject toolsConfig = config().getJsonObject("tools", new JsonObject());

        JsonObject discoveryConfig = config().getJsonObject("discovery", new JsonObject());

        JsonObject goEngineConfig = discoveryConfig.getJsonObject("goengine", new JsonObject());

        goEnginePath = toolsConfig.getString("goengine.path", "./goengine/goengine");

        fpingPath = toolsConfig.getString("fping.path", "fping");

        // GoEngine v7.0.0 configuration parameters
        timeoutSeconds = goEngineConfig.getInteger("timeout.seconds", 30);

        connectionTimeoutSeconds = goEngineConfig.getInteger("connection.timeout.seconds", 10);

        retryCount = goEngineConfig.getInteger("retry.count", 2);

        fpingTimeoutSeconds = toolsConfig.getInteger("fping.batch.blocking.timeout.seconds", 180);

        discoveryBatchSize = discoveryConfig.getInteger("batch.size", 100);

        blockingTimeoutGoEngine = discoveryConfig.getInteger("blocking.timeout.goengine", 120);

        logger.info("🔧 GoEngine path: {}", goEnginePath);

        logger.info("🔧 fping path: {}", fpingPath);

        logger.info("🔧 GoEngine v7.0.0 - Timeout per IP: {} seconds", timeoutSeconds);

        logger.info("🔧 GoEngine v7.0.0 - Connection timeout: {} seconds", connectionTimeoutSeconds);

        logger.info("🔧 Java retry count: {}", retryCount);

        logger.info("🕐 fping batch blocking timeout: {} seconds", fpingTimeoutSeconds);

        logger.info("📦 Discovery batch size: {} IPs", discoveryBatchSize);

        logger.info("⏱️ GoEngine blocking timeout: {} seconds", blockingTimeoutGoEngine);

        // Initialize service proxies
        this.deviceService = DeviceService.createProxy(vertx);

        this.deviceTypeService = DeviceTypeService.createProxy(vertx);

        this.credentialProfileService = CredentialProfileService.createProxy(vertx);

        this.discoveryProfileService = DiscoveryProfileService.createProxy(vertx);

        logger.info("🔧 Service proxies initialized");

        setupEventBusConsumers();

        startPromise.complete();
    }

    /**
     * Register discovery-related event bus consumers.
     * Currently subscribes to "discovery.test_profile" to run a test discovery for a profile.
     */
    private void setupEventBusConsumers()
    {
        // Handle test discovery from profile
        vertx.eventBus().consumer("discovery.test_profile", message ->
        {
            JsonObject request = (JsonObject) message.body();

            handleTestDiscoveryFromProfile(message, request);
        });

        logger.info("📡 Discovery event bus consumers setup complete");
    }

    /**
     * Handles test discovery from an existing discovery profile.
     *
     * This method orchestrates the complete discovery workflow:
     * 1. Fetches discovery profile and associated credentials from database
     * 2. Parses IP targets (single IP or IP range) from profile configuration
     * 3. Filters out already discovered devices to avoid duplicates
     * 4. Executes GoEngine discovery in sequential batches for network efficiency
     * 5. Processes results and creates device entries in database
     *
     * @param message Event bus message to reply with discovery results
     * @param request JsonObject containing profile_id field
     *
     * Expected request format:
     * {
     *   "profile_id": "uuid-of-discovery-profile"
     * }
     *
     * Response format:
     * {
     *   "success": true,
     *   "devices_discovered": 2,
     *   "devices_failed": 1,
     *   "devices_existing": 0,
     *   "created_devices": [...],
     *   "failed_devices": [...],
     *   "existing_devices": [...]
     * }
     */
    private void handleTestDiscoveryFromProfile(Message<Object> message, JsonObject request)
    {
        String profileId = request.getString("profile_id");

        logger.info("🧪 Starting test discovery for profile: {}", profileId);

        // Get discovery profile data
        getDiscoveryProfileWithCredentials(profileId)
            .compose(this::parseDiscoveryTargetsForTest)
            .compose(this::checkExistingDevicesAndFilter)
            .compose(this::executeSequentialBatchDiscovery)
            .compose(this::processTestDiscoveryResults)
            .onSuccess(result ->
            {
                logger.info("✅ Test discovery completed for profile {}: {} discovered, {} failed, {} existing",
                           profileId,
                           result.getInteger("devices_discovered", 0),
                           result.getInteger("devices_failed", 0),
                           result.getInteger("devices_existing", 0));

                message.reply(result);
            })
            .onFailure(cause ->
            {
                logger.error("❌ Test discovery failed for profile {}: {}", profileId, cause.getMessage());

                message.reply(new JsonObject()
                    .put("success", false)
                    .put("error", cause.getMessage()));
            });
    }

    /**
     * Retrieves discovery profile data along with associated credential profiles.
     *
     * Fetches the discovery profile by ID and enriches it with credential profile data
     * needed for device authentication during discovery. This creates a complete
     * profile object containing all information required for GoEngine execution.
     *
     * @param profileId UUID of the discovery profile to retrieve
     * @return Future<JsonObject> containing complete profile data with credentials
     *
     * Returned JsonObject structure:
     * {
     *   "profile_id": "uuid",
     *   "discovery_name": "Profile Name",
     *   "ip_address": "192.168.1.1" or "192.168.1.1-50",
     *   "is_range": true/false,
     *   "device_type_name": "server linux",
     *   "port": 22,
     *   "protocol": "ssh",
     *   "credentials": [
     *     {
     *       "credential_profile_id": "uuid",
     *       "username": "admin",
     *       "password_encrypted": "encrypted_password"
     *     }
     *   ]
     * }
     */
    private Future<JsonObject> getDiscoveryProfileWithCredentials(String profileId)
    {
        Promise<JsonObject> promise = Promise.promise();

        // Get discovery profile using DiscoveryProfileService
        discoveryProfileService.discoveryGetById(profileId, profileResult ->
        {
            if (profileResult.failed())
            {
                promise.fail("Failed to get discovery profile: " + profileResult.cause().getMessage());

                return;
            }

            JsonObject profileResponse = profileResult.result();

            if (!profileResponse.getBoolean("found", false))
            {
                promise.fail("Discovery profile not found: " + profileId);

                return;
            }

            JsonObject profileData = profileResponse;

            JsonArray credentialIds = profileData.getJsonArray("credential_profile_ids");

            // Get credential profiles using CredentialProfileService
            credentialProfileService.credentialGetByIds(credentialIds, credentialResult ->
            {
                if (credentialResult.failed())
                {
                    promise.fail("Failed to get credentials: " + credentialResult.cause().getMessage());

                    return;
                }

                JsonObject credentialResponse = credentialResult.result();

                if (!credentialResponse.getBoolean("success", false))
                {
                    promise.fail("Failed to get credential profiles");

                    return;
                }

                JsonObject dataObject = credentialResponse.getJsonObject("data");

                JsonArray credentials = dataObject.getJsonArray("credentials");

                profileData.put("credentials", credentials);

                promise.complete(profileData);
            });
        });

        return promise.future();
    }

    /**
     * Parses IP targets from discovery profile configuration.
     *
     * Converts the IP address specification from the profile into a list of individual
     * IP addresses for discovery. Handles both single IP addresses and IP ranges using
     * the IPRangeUtil utility. Adds target IP list and count to the profile data.
     *
     * Uses executeBlocking() to avoid blocking the event loop for large IP ranges.
     * While IP range parsing is typically fast (< 1ms for 254 IPs), using executeBlocking()
     * ensures consistency with other blocking operations and future-proofs for larger ranges.
     *
     * @param profile JsonObject containing discovery profile data with ip_address and is_range fields
     * @return Future<JsonObject> profile data enriched with target_ips array and total_targets count
     *
     * Input profile must contain:
     * - ip_address: "192.168.1.10" (single) or "192.168.1.1-50" (range)
     * - is_range: boolean indicating if ip_address is a range specification
     *
     * Output adds:
     * - target_ips: ["192.168.1.1", "192.168.1.2", ...] array of individual IPs
     * - total_targets: integer count of IPs to discover
     */
    private Future<JsonObject> parseDiscoveryTargetsForTest(JsonObject profile)
    {
        Promise<JsonObject> promise = Promise.promise();

        // Execute IP range parsing in worker thread to avoid blocking event loop
        vertx.executeBlocking(blockingPromise ->
        {
            try
            {
                String ipAddress = profile.getString("ip_address");

                boolean isRange = profile.getBoolean("is_range", false);

                JsonArray targetIps = new JsonArray();

                // Use IPRangeUtil for parsing both single IP and ranges
                List<String> ipList = IPRangeUtil.parseIPRange(ipAddress, isRange);

                for (String ip : ipList)
                {
                    targetIps.add(ip);
                }

                logger.info("📋 Discovery targets: {} ({} {})", ipAddress, targetIps.size(),
                           isRange ? "IPs from range" : "single IP");

                // Create result with profile data and target IPs
                JsonObject result = profile.copy()
                    .put("target_ips", targetIps)
                    .put("total_targets", targetIps.size());

                blockingPromise.complete(result);

            }
            catch (Exception exception)
            {
                logger.error("❌ Failed to parse discovery targets: {}", exception.getMessage());

                blockingPromise.fail("Failed to parse discovery targets: " + exception.getMessage());
            }

        }, false, promise);

        return promise.future();
    }



    /**
     * Filters out IP addresses that already have devices in the database.
     *
     * Checks each target IP against the device table to identify which IPs already
     * have discovered devices. This prevents duplicate device creation and optimizes
     * discovery by only processing new IPs. Separates IPs into existing and new
     * target lists for efficient processing.
     *
     * @param profileData JsonObject containing target_ips array from previous step
     * @return Future<JsonObject> profile data with existing_devices and new_targets arrays
     *
     * Input requires:
     * - target_ips: array of IP addresses to check
     *
     * Output adds:
     * - existing_devices: array of JsonObjects for IPs that already have devices
     * - new_targets: array of IP strings that need discovery
     * - existing_count: integer count of existing devices found
     * - new_count: integer count of new IPs to discover
     */
    private Future<JsonObject> checkExistingDevicesAndFilter(JsonObject profileData)
    {
        Promise<JsonObject> promise = Promise.promise();

        JsonArray targetIps = profileData.getJsonArray("target_ips");

        JsonArray existingDevices = new JsonArray();

        JsonArray newTargets = new JsonArray();

        if (targetIps.isEmpty())
        {
            promise.complete(profileData.put("existing_devices", existingDevices).put("new_targets", newTargets));

            return promise.future();
        }

        // Check which IPs already exist in devices table using DeviceService
        List<Future<JsonObject>> deviceCheckFutures = new ArrayList<>();

        for (Object ipObj : targetIps)
        {
            String ip = (String) ipObj;

            Promise<JsonObject> devicePromise = Promise.promise();

            // Check with includeDeleted = true to get all devices including soft deleted ones
            deviceService.deviceFindByIp(ip, true, result ->
            {
                if (result.succeeded())
                {
                    JsonObject deviceResult = result.result();

                    logger.info("🔍 Device lookup for IP {}: {}", ip, deviceResult.encodePrettily());

                    if (deviceResult.getBoolean("found", false))
                    {
                        // Device exists - deviceResult contains the device data directly
                        boolean isProvisioned = deviceResult.getBoolean("is_provisioned", false);

                        boolean isMonitoring = deviceResult.getBoolean("is_monitoring_enabled", false);

                        boolean isDeleted = deviceResult.getBoolean("is_deleted", false);

                        String status;

                        String message;

                        boolean proceedWithDiscovery = false;

                        if (isDeleted)
                        {
                            // isDeleted = true, create a new entry for the same IP as old same IP is soft deleted
                            status = "soft_deleted";

                            message = "Device was soft deleted, proceeding with new discovery";

                            proceedWithDiscovery = true;
                        }
                        else if (!isProvisioned && !isMonitoring)
                        {
                            // isProvisioned = false, isMonitored = false, isDeleted = false
                            status = "available_for_provision";

                            message = "Device already exists and is available for provision";

                            proceedWithDiscovery = false;
                        }
                        else if (isProvisioned && isMonitoring)
                        {
                            // isProvisioned = true, isMonitored = true, isDeleted = false
                            status = "being_monitored";

                            message = "Device already available and is being monitored";

                            proceedWithDiscovery = false;
                        }
                        else if (isProvisioned && !isMonitoring)
                        {
                            // isProvisioned = true, isMonitored = false, isDeleted = false
                            status = "monitoring_disabled";

                            message = "Device already available, and monitoring is disabled";

                            proceedWithDiscovery = false;
                        }
                        else
                        {
                            status = "unknown_state";

                            message = "Device in unknown state";

                            proceedWithDiscovery = false;
                        }

                        devicePromise.complete(new JsonObject()
                            .put("ip_address", ip)
                            .put("exists", true)
                            .put("status", status)
                            .put("message", message)
                            .put("proceed_with_discovery", proceedWithDiscovery)
                            .put("is_provisioned", isProvisioned)
                            .put("is_monitoring_enabled", isMonitoring)
                            .put("is_deleted", isDeleted));
                    }
                    else
                    {
                        // Device doesn't exist
                        devicePromise.complete(new JsonObject()
                            .put("ip_address", ip)
                            .put("exists", false)
                            .put("proceed_with_discovery", true));
                    }
                }
                else
                {
                    // Error checking device, assume it doesn't exist
                    devicePromise.complete(new JsonObject()
                        .put("ip_address", ip)
                        .put("exists", false)
                        .put("proceed_with_discovery", true));
                }
            });

            deviceCheckFutures.add(devicePromise.future());
        }

        // Wait for all device checks to complete
        Future.all(deviceCheckFutures)
            .onSuccess(compositeFuture ->
            {
                Set<String> existingIps = new HashSet<>();

                // Process results
                for (int i = 0; i < compositeFuture.size(); i++)
                {
                    JsonObject deviceCheck = compositeFuture.resultAt(i);

                    String ip = deviceCheck.getString("ip_address");

                    if (deviceCheck.getBoolean("exists", false))
                    {
                        // Device exists - check if we should proceed with discovery
                        if (deviceCheck.getBoolean("proceed_with_discovery", false))
                        {
                            // Soft deleted device - proceed with discovery
                            newTargets.add(ip);

                            logger.info("📋 IP {} has soft deleted device, proceeding with new discovery", ip);
                        }
                        else
                        {
                            // Device exists and should not be rediscovered
                            existingIps.add(ip);

                            existingDevices.add(new JsonObject()
                                .put("ip_address", ip)
                                .put("status", deviceCheck.getString("status"))
                                .put("message", deviceCheck.getString("message"))
                                .put("is_provisioned", deviceCheck.getBoolean("is_provisioned"))
                                .put("is_monitoring_enabled", deviceCheck.getBoolean("is_monitoring_enabled"))
                                .put("is_deleted", deviceCheck.getBoolean("is_deleted", false)));

                            logger.info("📋 IP {} already exists: {}", ip, deviceCheck.getString("message"));
                        }
                    }
                    else
                    {
                        // Device doesn't exist - proceed with discovery
                        newTargets.add(ip);
                    }
                }

                logger.info("📊 Device check: {} existing (skipped), {} new targets for discovery",
                           existingDevices.size(), newTargets.size());

                promise.complete(profileData
                    .put("existing_devices", existingDevices)
                    .put("new_targets", newTargets));
            })
            .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Executes discovery in sequential batches for network efficiency and reliability.
     *
     * Processes new target IPs in configurable batch sizes to avoid overwhelming the
     * network and GoEngine. Uses sequential processing to maintain system stability
     * and provide better error handling. Each batch is processed completely before
     * moving to the next batch. Accumulates results from all batches.
     *
     * @param profileData JsonObject containing new_targets array and profile configuration
     * @return Future<JsonObject> profile data with discovery_results array containing all batch results
     *
     * Input requires:
     * - new_targets: array of IP strings to discover
     * - All profile data (credentials, device_type, port, protocol)
     *
     * Output adds:
     * - discovery_results: array of discovery result objects from GoEngine
     * - total_processed: integer count of IPs processed
     * - batches_completed: integer count of batches processed
     *
     * Batch processing configuration:
     * - Default batch size: 50 IPs per batch
     * - Sequential execution: waits for each batch to complete
     * - Error handling: continues with next batch if one fails
     */
    private Future<JsonObject> executeSequentialBatchDiscovery(JsonObject profileData)
    {
        Promise<JsonObject> promise = Promise.promise();

        JsonArray newTargets = profileData.getJsonArray("new_targets");

        if (newTargets.isEmpty())
        {
            logger.info("📋 No new targets for discovery, skipping GoEngine execution");

            promise.complete(profileData.put("discovery_results", new JsonArray()));

            return promise.future();
        }

        logger.info("🚀 Starting sequential batch discovery for {} targets", newTargets.size());

        int totalTargets = newTargets.size();

        int totalBatches = (int) Math.ceil((double) totalTargets / discoveryBatchSize);

        logger.info("📦 Will process {} targets in {} batches of max {} IPs (GoEngine credential iteration)",
                   totalTargets, totalBatches, discoveryBatchSize);

        // Use QueueBatchProcessor for sequential batch discovery
        DiscoveryBatchProcessor processor = new DiscoveryBatchProcessor(profileData, newTargets);

        Promise<JsonArray> arrayPromise = Promise.promise();

        processor.processNext(arrayPromise);

        return arrayPromise.future().map(results -> profileData.put("discovery_results", results));
    }

    /**
     * Executes GoEngine discovery with pre-connectivity checks and credential iteration.
     *
     * Performs a two-stage discovery process:
     * 1. Connectivity checks using fping and port scanning to identify reachable targets
     * 2. GoEngine discovery execution only on reachable IPs for efficiency
     *
     * This method optimizes discovery by filtering unreachable IPs early, reducing
     * GoEngine execution time and improving overall discovery performance. Uses
     * NetworkConnectivityUtil for fast connectivity validation.
     *
     * @param profileData JsonObject containing discovery profile with credentials and configuration
     * @param targetIps JsonArray of IP address strings to discover
     * @return Future<JsonArray> containing discovery results for all IPs (successful and failed)
     *
     * Input profileData requires:
     * - credentials: array of credential objects with username/password
     * - device_type_name: string device type for GoEngine
     * - port: integer port number for connectivity checks
     * - protocol: string protocol (ssh/winrm)
     *
     * Output JsonArray contains:
     * - Successful discoveries: full device information from GoEngine
     * - Failed discoveries: error objects with ip_address, success=false, error message
     * - Unreachable IPs: connectivity failure objects with appropriate error messages
     */
    private Future<JsonArray> executeGoEngineDiscovery(JsonObject profileData, JsonArray targetIps)
    {
        Promise<JsonArray> promise = Promise.promise();

        if (targetIps.isEmpty())
        {
            promise.complete(new JsonArray());

            return promise.future();
        }

        String requestId = "DISC_" + System.currentTimeMillis();

        String batchId = "batch-" + System.currentTimeMillis();

        logger.info("🚀 Starting discovery for {} IPs with connectivity pre-check", targetIps.size());

        // Step 1: Perform connectivity checks first
        performConnectivityChecks(targetIps, profileData)
            .compose(connectivityResults ->
            {
                JsonArray reachableIPs = connectivityResults.getJsonArray("reachable");

                JsonArray unreachableIPs = connectivityResults.getJsonArray("unreachable");

                logger.info("🏓 Connectivity check completed: {}/{} IPs reachable",
                           reachableIPs.size(), targetIps.size());

                if (reachableIPs.isEmpty())
                {
                    logger.info("❌ No reachable IPs found, skipping GoEngine discovery");

                    // Create failed results inline
                    JsonArray failedResults = new JsonArray();

                    for (int i = 0; i < targetIps.size(); i++)
                    {
                        String ipAddress = targetIps.getString(i);

                        JsonObject failedResult = new JsonObject()
                            .put("ip_address", ipAddress)
                            .put("success", false)
                            .put("error", "No connectivity")
                            .put("timestamp", System.currentTimeMillis());

                        failedResults.add(failedResult);
                    }

                    return Future.succeededFuture(failedResults);
                }

                // Step 2: Proceed with GoEngine discovery for reachable IPs only
                logger.info("🚀 Executing GoEngine discovery for {} reachable IPs with request ID: {}",
                           reachableIPs.size(), requestId);

                return executeGoEngineForReachableIPs(profileData, reachableIPs, unreachableIPs, batchId, requestId);
            })
            .onSuccess(promise::complete)
            .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Performs fast connectivity checks on all target IPs using BATCH fping and port scanning.
     *
     * Executes efficient batch connectivity validation to quickly identify which IPs are
     * reachable before attempting GoEngine discovery. Uses NetworkConnectivityUtil batch methods
     * for optimal performance. This pre-filtering step significantly improves discovery performance
     * by avoiding timeouts on unreachable IPs.
     *
     * @param targetIps JsonArray of IP address strings to check
     * @param profileData JsonObject containing port number for port connectivity checks
     * @return Future<JsonObject> containing reachable and unreachable IP arrays
     *
     * Input requirements:
     * - targetIps: array of IP address strings
     * - profileData.port: integer port number to check (e.g., 22 for SSH, 5985 for WinRM)
     *
     * Output JsonObject structure:
     * {
     *   "reachable": ["192.168.1.10", "192.168.1.15"],
     *   "unreachable": ["192.168.1.11", "192.168.1.12"]
     * }
     *
     * Connectivity check process (OPTIMIZED):
     * 1. BATCH fping check for all IPs (single fping process)
     * 2. BATCH port check for IPs that passed fping (parallel checks)
     * 3. IP is considered reachable only if both checks pass
     */
    private Future<JsonObject> performConnectivityChecks(JsonArray targetIps, JsonObject profileData)
    {
        Promise<JsonObject> promise = Promise.promise();

        // Convert JsonArray to List<String>
        List<String> ipList = new ArrayList<>();

        for (int i = 0; i < targetIps.size(); i++)
        {
            ipList.add(targetIps.getString(i));
        }

        Integer port = profileData.getInteger("port", 22);

        logger.debug("🔍 Starting batch connectivity checks for {} IPs", ipList.size());

        // Step 1: Batch fping check (single fping process for all IPs)
        NetworkConnectivityUtil.batchFpingCheck(vertx, ipList, config())
            .compose(fpingResults ->
            {
                // Filter IPs that passed fping
                List<String> pingAliveIps = ipList.stream()
                    .filter(ip -> fpingResults.getOrDefault(ip, false))
                    .collect(Collectors.toList());

                logger.debug("📊 fping results: {}/{} IPs alive", pingAliveIps.size(), ipList.size());

                if (pingAliveIps.isEmpty())
                {
                    // All IPs failed fping, no need for port check
                    JsonArray unreachableIPs = new JsonArray();

                    ipList.forEach(unreachableIPs::add);

                    JsonObject result = new JsonObject()
                        .put("reachable", new JsonArray())
                        .put("unreachable", unreachableIPs)
                        .put("total_checked", ipList.size());

                    return Future.succeededFuture(result);
                }

                // Step 2: Batch port check (parallel checks for alive IPs only)
                return NetworkConnectivityUtil.batchPortCheck(vertx, pingAliveIps, port, config())
                    .map(portResults ->
                    {
                        JsonArray reachableIPs = new JsonArray();

                        JsonArray unreachableIPs = new JsonArray();

                        // Classify IPs based on both fping and port check results
                        for (String ip : ipList)
                        {
                            boolean pingAlive = fpingResults.getOrDefault(ip, false);

                            boolean portOpen = portResults.getOrDefault(ip, false);

                            if (pingAlive && portOpen)
                            {
                                reachableIPs.add(ip);
                            }
                            else
                            {
                                unreachableIPs.add(ip);
                            }
                        }

                        logger.info("✅ Connectivity checks complete: {}/{} IPs reachable (ICMP+TCP)",
                            reachableIPs.size(), ipList.size());

                        return new JsonObject()
                            .put("reachable", reachableIPs)
                            .put("unreachable", unreachableIPs)
                            .put("total_checked", ipList.size());
                    });
            })
            .onSuccess(promise::complete)
            .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Execute GoEngine discovery for reachable IPs and create results including unreachable ones
     */
    private Future<JsonArray> executeGoEngineForReachableIPs(JsonObject profileData, JsonArray reachableIPs,
                                                             JsonArray unreachableIPs, String batchId, String requestId)
    {
        Promise<JsonArray> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise ->
        {
            try
            {
                // Create GoEngine discovery_request format for reachable IPs only
                JsonObject discoveryRequest = createDiscoveryRequest(profileData, reachableIPs, batchId);

                // Prepare GoEngine command
                List<String> command = Arrays.asList(
                    goEnginePath,
                    "--mode", "discovery",
                    "--targets", discoveryRequest.encode(),
                    "--request-id", requestId
                );

                logger.info("📋 GoEngine discovery request: {}", discoveryRequest.encodePrettily());

                ProcessBuilder pb = new ProcessBuilder(command);

                Process process = pb.start();

                JsonArray results = new JsonArray();

                // Read results from stdout line by line
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
                {
                    String line;

                    while ((line = reader.readLine()) != null)
                    {
                        try
                        {
                            JsonObject result = new JsonObject(line);

                            if (result.containsKey("ip_address"))
                            {  // uses ip_address instead of device_address
                                results.add(result);
                            }
                        }
                        catch (Exception exception)
                        {
                            logger.warn("Failed to parse GoEngine result line: {}", line);
                        }
                    }
                }

                // Process timeout handled by Vert.x blocking timeout (blockingTimeoutGoEngine)
                boolean finished = process.waitFor(blockingTimeoutGoEngine, TimeUnit.SECONDS);

                if (!finished)
                {
                    process.destroyForcibly();

                    throw new RuntimeException("GoEngine discovery process timed out after " + blockingTimeoutGoEngine + " seconds");
                }

                int exitCode = process.exitValue();

                logger.info("🏁 GoEngine v7.0.0 discovery completed with exit code: {}, {} results", exitCode, results.size());

                // Combine GoEngine results with unreachable IPs
                JsonArray finalResults = new JsonArray();

                // Add GoEngine results
                for (Object obj : results)
                {
                    finalResults.add(obj);
                }

                // Add failed results for unreachable IPs
                for (int i = 0; i < unreachableIPs.size(); i++)
                {
                    String unreachableIP = unreachableIPs.getString(i);

                    JsonObject failedResult = new JsonObject()
                        .put("ip_address", unreachableIP)
                        .put("success", false)
                        .put("error", "Device unreachable - connectivity check failed")
                        .put("timestamp", System.currentTimeMillis());

                    finalResults.add(failedResult);
                }

                blockingPromise.complete(finalResults);

            }
            catch (Exception exception)
            {
                logger.error("GoEngine discovery execution failed", exception);

                blockingPromise.fail(exception);
            }
        }, false, promise);

        return promise.future();
    }

    /**
     * Create GoEngine discovery_request format with credential iteration
     */
    private JsonObject createDiscoveryRequest(JsonObject profileData, JsonArray targetIps, String batchId)
    {
        // Convert target IPs to string array
        JsonArray targetIpArray = new JsonArray();

        for (Object ip : targetIps)
        {
            targetIpArray.add(ip.toString());
        }

        // Convert credentials to GoEngine format
        JsonArray credentials = new JsonArray();

        JsonArray profileCredentials = profileData.getJsonArray("credentials");

        for (Object credObj : profileCredentials)
        {
            JsonObject credential = (JsonObject) credObj;

            JsonObject goEngineCredential = new JsonObject()
                .put("credential_id", credential.getString("credential_profile_id"))
                .put("username", credential.getString("username"))
                .put("password", credential.getString("password_encrypted")); // Password is stored encrypted

            credentials.add(goEngineCredential);
        }

        // Map device type from database format to GoEngine format
        String deviceTypeName = profileData.getString("device_type_name");

        String goEngineDeviceType = mapDeviceTypeToGoEngine(deviceTypeName);

        // Create discovery_config
        JsonObject discoveryConfig = new JsonObject()
            .put("device_type", goEngineDeviceType)
            .put("port", profileData.getInteger("port"))
            .put("protocol", profileData.getString("protocol"))
            .put("timeout_seconds", timeoutSeconds)
            .put("connection_timeout", connectionTimeoutSeconds);
            // Note: retry_count handled by Java, not passed to GoEngine v7.0.0

        // Create the complete discovery_request
        JsonObject discoveryRequest = new JsonObject()
            .put("discovery_request", new JsonObject()
                .put("batch_id", batchId)
                .put("target_ips", targetIpArray)
                .put("credentials", credentials)
                .put("discovery_config", discoveryConfig)
            );

        return discoveryRequest;
    }

    /**
     * Map database device type to GoEngine device type format
     */
    private String mapDeviceTypeToGoEngine(String dbDeviceType)
    {
        if (dbDeviceType == null)
        {
            return "server linux"; // Default fallback
        }

        // Convert database format (server_linux) to GoEngine format (server linux)
        return dbDeviceType.replace("_", " ");
    }

    /**
     * Determine protocol based on device type and port
     */
    private String determineProtocol(String deviceType, Integer port)
    {
        if (deviceType.contains("windows"))
        {
            return "WinRM";
        }
        else
        {
            return "SSH";
        }
    }

    /**
     * Post-process discovery results: split success/failure, create devices for successes,
     * and build the final API response including existing devices.
     *
     * @param profileData profile and discovery context containing discovery_results and existing_devices
     * @return Future resolving to API response JSON summarizing created, failed, and existing devices
     */
    private Future<JsonObject> processTestDiscoveryResults(JsonObject profileData)
    {
        Promise<JsonObject> promise = Promise.promise();

        JsonArray discoveryResults = profileData.getJsonArray("discovery_results", new JsonArray());

        JsonArray existingDevices = profileData.getJsonArray("existing_devices", new JsonArray());

        JsonArray successfulDevices = new JsonArray();

        JsonArray failedDevices = new JsonArray();

        // Separate successful and failed discoveries
        for (Object obj : discoveryResults)
        {
            JsonObject result = (JsonObject) obj;

            if (result.getBoolean("success", false))
            {
                successfulDevices.add(result);
            }
            else
            {
                failedDevices.add(result);
            }
        }

        logger.info("📊 Discovery results: {} successful, {} failed, {} existing",
                   successfulDevices.size(), failedDevices.size(), existingDevices.size());

        if (successfulDevices.isEmpty())
        {
            // No successful discoveries to create devices
            promise.complete(createTestDiscoveryResponse(profileData, 0, failedDevices.size(), existingDevices.size(),
                new JsonArray(), failedDevices, existingDevices));

            return promise.future();
        }

        // Create devices from successful discoveries
        createDevicesFromTestDiscoveries(successfulDevices)
            .onSuccess(createdDevices ->
            {
                promise.complete(createTestDiscoveryResponse(profileData, createdDevices.size(), failedDevices.size(),
                    existingDevices.size(), createdDevices, failedDevices, existingDevices));
            })
            .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Create device records for each successful discovery result.
     *
     * @param successfulDiscoveries array of successful GoEngine results
     * @return Future resolving to a JsonArray of created device summaries
     */
    private Future<JsonArray> createDevicesFromTestDiscoveries(JsonArray successfulDiscoveries)
    {
        Promise<JsonArray> promise = Promise.promise();

        if (successfulDiscoveries.isEmpty())
        {
            promise.complete(new JsonArray());

            return promise.future();
        }

        JsonArray createdDevices = new JsonArray();

        List<Future<JsonObject>> deviceCreationFutures = new ArrayList<>();

        for (Object obj : successfulDiscoveries)
        {
            JsonObject discovery = (JsonObject) obj;

            Future<JsonObject> deviceFuture = createSingleDeviceFromTestDiscovery(discovery);

            deviceCreationFutures.add(deviceFuture);
        }

        // Wait for all device creations to complete
        Future.all(deviceCreationFutures)
            .onSuccess(compositeFuture ->
            {
                for (Future<JsonObject> future : deviceCreationFutures)
                {
                    if (future.result() != null)
                    {
                        createdDevices.add(future.result());
                    }
                }

                promise.complete(createdDevices);
            })
            .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Create a single device from a successful discovery result using DeviceService.
     *
     * @param discovery JsonObject from GoEngine containing ip_address, hostname, device_type, port, credential_id
     * @return Future resolving to created device summary or null if creation failed
     */
    private Future<JsonObject> createSingleDeviceFromTestDiscovery(JsonObject discovery)
    {
        Promise<JsonObject> promise = Promise.promise();

        // Prepare device data for DeviceService.deviceCreateFromDiscovery
        JsonObject deviceData = new JsonObject()
            .put("device_name", discovery.getString("hostname", discovery.getString("ip_address")))
            .put("ip_address", discovery.getString("ip_address"))
            .put("device_type", discovery.getString("device_type"))
            .put("port", discovery.getInteger("port", 22))
            .put("protocol", discovery.getString("device_type").contains("windows") ? "winrm" : "ssh")
            .put("credential_profile_id", discovery.getString("credential_id")) // ONLY the successful credential ID
            .put("host_name", discovery.getString("hostname", discovery.getString("ip_address")));

        logger.info("🔧 Creating device from test discovery: {}", deviceData.getString("ip_address"));

        deviceService.deviceCreateFromDiscovery(deviceData, ar ->
        {
            if (ar.succeeded())
            {
                JsonObject result = ar.result();

                logger.info("✅ Device created from test discovery: {}", result.getString("device_id"));

                // Return device info for response
                promise.complete(new JsonObject()
                    .put("ip_address", deviceData.getString("ip_address"))
                    .put("hostname", deviceData.getString("host_name"))
                    .put("device_id", result.getString("device_id"))
                    .put("device_name", deviceData.getString("device_name"))
                    .put("success", true));
            }
            else
            {
                logger.error("❌ Failed to create device from test discovery: {}", ar.cause().getMessage());

                promise.complete(null); // Return null for failed creation
            }
        });

        return promise.future();
    }

    /**
     * Build the final test discovery response payload for API consumers.
     *
     * @param profileData source profile info (id, name, total_targets)
     * @param devicesCreated number of devices successfully created
     * @param devicesFailed number of failed discoveries
     * @param devicesExisting number of pre-existing devices skipped
     * @param createdDevices details of created devices
     * @param failedDevices details of failed results
     * @param existingDevices details of existing devices
     * @return JsonObject response summarizing the test discovery run
     */
    private JsonObject createTestDiscoveryResponse(JsonObject profileData, int devicesCreated, int devicesFailed,
                                                  int devicesExisting, JsonArray createdDevices, JsonArray failedDevices,
                                                  JsonArray existingDevices)
    {
        return new JsonObject()
            .put("success", true)
            .put("profile_id", profileData.getString("profile_id"))
            .put("discovery_name", profileData.getString("discovery_name"))
            .put("total_targets", profileData.getInteger("total_targets", 0))
            .put("devices_discovered", devicesCreated)
            .put("devices_failed", devicesFailed)
            .put("devices_existing", devicesExisting)
            .put("discovered_devices", createdDevices)
            .put("failed_devices", failedDevices)
            .put("existing_devices", existingDevices)
            .put("message", String.format("Test discovery completed: %d discovered, %d failed, %d existing",
                                        devicesCreated, devicesFailed, devicesExisting));
    }

    /**
     * Discovery batch processor using QueueBatchProcessor.
     *
     * Extends the generic QueueBatchProcessor to handle discovery-specific batch processing.
     * Uses GoEngine's built-in credential iteration (tests multiple credentials per IP until one succeeds).
     *
     * Features:
     * - Sequential batch processing of IP addresses
     * - GoEngine credential iteration for each IP
     * - Fail-tolerant: continues with next batch on failure
     * - Memory efficient: only current batch in memory
     */
    private class DiscoveryBatchProcessor extends QueueBatchProcessor<String>
    {
        private final JsonObject profileData;

        /**
         * Constructor for DiscoveryBatchProcessor.
         *
         * Initializes the processor with profile data and target IPs.
         * Converts JsonArray to List for QueueBatchProcessor.
         *
         * @param profileData JsonObject containing discovery profile configuration
         * @param allTargetIPs JsonArray of IP addresses to discover
         */
        public DiscoveryBatchProcessor(JsonObject profileData, JsonArray allTargetIPs)
        {
            super(allTargetIPs.getList(), discoveryBatchSize);

            this.profileData = profileData;

            JsonArray credentials = profileData.getJsonArray("credentials");

            logger.info("📋 Discovery batch processor initialized: {} credentials, {} IPs",

                credentials.size(), getTotalItems());
        }

        /**
         * Process a batch of IP addresses using GoEngine discovery.
         *
         * Converts the batch of IP strings to JsonArray and executes GoEngine discovery.
         * GoEngine handles credential iteration internally for each IP.
         *
         * @param batch List of IP addresses to discover in this batch
         * @return Future containing JsonArray of discovery results
         */
        @Override
        protected Future<JsonArray> processBatch(List<String> batch)
        {
            JsonArray ipArray = new JsonArray(batch);

            logger.info("🔄 Executing GoEngine discovery for {} IPs (credential iteration)", batch.size());

            return executeGoEngineDiscovery(profileData, ipArray);
        }

        /**
         * Handle batch processing failure.
         *
         * Logs the failed IPs for debugging. The batch processor will continue
         * with the next batch (fail-tolerant behavior).
         *
         * @param batch The batch of IPs that failed to process
         * @param cause The exception that caused the failure
         */
        @Override
        protected void handleBatchFailure(List<String> batch, Throwable cause)
        {
            logger.warn("⚠️ Failed to discover {} IPs in batch: {}", batch.size(), cause.getMessage());

            logger.debug("Failed IPs: {}", batch);
        }
    }

    /**
     * Stop the verticle and perform any required cleanup.
     *
     * @param stopPromise completed when shutdown work is finished
     */
    @Override
    public void stop(Promise<Void> stopPromise)
    {
        logger.info("🛑 Stopping DiscoveryVerticle");

        stopPromise.complete();
    }
}
