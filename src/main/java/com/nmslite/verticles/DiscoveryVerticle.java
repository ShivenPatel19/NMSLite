package com.nmslite.verticles;

import com.nmslite.Bootstrap;
import com.nmslite.core.NetworkConnectivity;

import com.nmslite.services.DeviceService;

import com.nmslite.services.DiscoveryProfileService;

import com.nmslite.utils.PasswordUtil;

import com.nmslite.utils.ValidationUtil;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.eventbus.Message;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.io.BufferedReader;

import java.io.BufferedWriter;

import java.io.InputStreamReader;

import java.io.OutputStreamWriter;

import java.io.File;

import java.util.*;

import java.util.concurrent.TimeUnit;

import com.nmslite.core.QueueBatchProcessor;

import java.util.stream.Collectors;

/**
 * DiscoveryVerticle - Device Discovery Workflow
 
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

    private int timeoutSeconds;

    private int connectionTimeoutSeconds;

    private int discoveryBatchSize;

    private int blockingTimeoutGoEngine;

    // Service proxies
    private DeviceService deviceService;

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
        try
        {
            logger.info("Starting DiscoveryVerticle");

            // Load configuration from tools and discovery sections
            var toolsConfig = Bootstrap.getConfig().getJsonObject("tools", new JsonObject());

            var discoveryConfig = Bootstrap.getConfig().getJsonObject("discovery", new JsonObject());

            var goEngineConfig = discoveryConfig.getJsonObject("goengine", new JsonObject());

            // HOCON parses dotted keys as nested objects: goengine.path becomes goengine -> path
            goEnginePath = toolsConfig.getJsonObject("goengine", new JsonObject())
                    .getString("path", "./goengine/goengine");

            // GoEngine configuration parameters - HOCON parses dotted keys as nested objects
            timeoutSeconds = goEngineConfig.getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 30);

            connectionTimeoutSeconds = goEngineConfig.getJsonObject("connection", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 10);

            discoveryBatchSize = discoveryConfig.getJsonObject("batch", new JsonObject())
                    .getInteger("size", 100);

            blockingTimeoutGoEngine = discoveryConfig.getJsonObject("blocking", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("goengine", 120);

            // Initialize service proxies
            this.deviceService = DeviceService.createProxy();

            this.discoveryProfileService = DiscoveryProfileService.createProxy();

            setupEventBusConsumers();

            startPromise.complete();
        }
        catch (Exception exception)
        {
            logger.error("Error in start: {}", exception.getMessage());

            startPromise.fail(exception);
        }
    }

    /**
     * Register discovery-related event bus consumers.
     * Currently subscribes to "discovery.test_profile" to run a test discovery for a profile.
     */
    private void setupEventBusConsumers()
    {
        try
        {
            // Handle test discovery from profile
            vertx.eventBus().consumer("discovery.test_profile", message ->
            {
                var request = (JsonObject) message.body();

                handleTestDiscoveryFromProfile(message, request);
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in setupEventBusConsumers: {}", exception.getMessage());
        }
    }

    /**
     * Handles test discovery from an existing discovery profile.
     
     * This method orchestrates the complete discovery workflow:
     * 1. Fetches discovery profile and associated credentials from database
     * 2. Parses IP targets (single IP or IP range) from profile configuration
     * 3. Filters out already discovered devices to avoid duplicates
     * 4. Executes GoEngine discovery in sequential batches for network efficiency
     * 5. Processes results and creates device entries in database
     *
     * @param message Event bus message to reply with discovery results
     * @param request JsonObject containing profile_id field
     
     * Expected request format:
     * {
     *   "profile_id": "uuid-of-discovery-profile"
     * }
     
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
        try
        {
            var profileId = request.getString("profile_id");

            logger.debug("Starting test discovery for profile: {}", profileId);

            // Get discovery profile data
            getDiscoveryProfileWithCredentials(profileId)
                .compose(this::parseDiscoveryTargetsForTest)
                .compose(this::checkExistingDevicesAndFilter)
                .compose(this::executeSequentialBatchDiscovery)
                .compose(this::processTestDiscoveryResults)
                .onSuccess(result ->
                {
                    logger.info("Test discovery completed for profile {}: {} discovered, {} failed, {} existing",
                               profileId,
                               result.getInteger("devices_discovered", 0),
                               result.getInteger("devices_failed", 0),
                               result.getInteger("devices_existing", 0));

                    message.reply(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Test discovery failed for profile {}: {}", profileId, cause.getMessage());

                    message.reply(new JsonObject()
                        .put("success", false)
                        .put("error", cause.getMessage()));
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in handleTestDiscoveryFromProfile: {}", exception.getMessage());

            message.reply(new JsonObject()
                .put("success", false)
                .put("error", exception.getMessage()));
        }
    }

    /**
     * Retrieves discovery profile data along with associated credential profiles.
     
     * Fetches the discovery profile by ID and enriches it with credential profile data
     * needed for device authentication during discovery. This creates a complete
     * profile object containing all information required for GoEngine execution.
     *
     * @param profileId UUID of the discovery profile to retrieve
     * @return Future<JsonObject> containing complete profile data with credentials
     
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
        var promise = Promise.<JsonObject>promise();

        try
        {
            // Get discovery profile with encrypted credentials using DiscoveryProfileService
            discoveryProfileService.discoveryGetById(profileId)
                .onSuccess(profileResponse ->
                {
                    if (!profileResponse.getBoolean("found", false))
                    {
                        promise.fail("Discovery profile not found: " + profileId);

                        return;
                    }

                    // Get credential_profiles array (includes encrypted passwords)
                    var credentialProfiles = profileResponse.getJsonArray("credential_profiles");

                    // Decrypt passwords locally (no additional database call needed)
                    var decryptedCredentials = new JsonArray();

                    for (var i = 0; i < credentialProfiles.size(); i++)
                    {
                        var credential = credentialProfiles.getJsonObject(i);

                        var encryptedPassword = credential.getString("password_encrypted");

                        var decryptedPassword = PasswordUtil.decryptPassword(encryptedPassword);

                        if (decryptedPassword == null)
                        {
                            logger.error("Failed to decrypt password for credential profile: {}",
                                credential.getString("credential_profile_id"));

                            promise.fail(new Exception("Failed to decrypt credential password"));

                            return;
                        }

                        // Create credential object with decrypted password for GoEngine
                        var decryptedCredential = new JsonObject()
                            .put("credential_profile_id", credential.getString("credential_profile_id"))
                            .put("profile_name", credential.getString("profile_name"))
                            .put("username", credential.getString("username"))
                            .put("password", decryptedPassword);

                        decryptedCredentials.add(decryptedCredential);
                    }

                    // Replace credential_profiles with decrypted credentials
                    profileResponse.remove("credential_profiles"); // Remove encrypted version to avoid duplication

                    profileResponse.put("credentials", decryptedCredentials);

                    promise.complete(profileResponse);
                })
                .onFailure(cause -> promise.fail("Failed to get discovery profile: " + cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in getDiscoveryProfileWithCredentials: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Parses IP targets from discovery profile configuration.

     * Converts the IP address specification from the profile into a list of individual
     * IP addresses for discovery. Handles both single IP addresses and IP ranges.
     * Adds target IP list and count to the profile data.

     * Uses executeBlocking() to avoid blocking the event loop for large IP ranges.
     * While IP range parsing is typically fast (< 1ms for 254 IPs), using executeBlocking()
     * ensures consistency with other blocking operations and future-proofs for larger ranges.

     * @param profile JsonObject containing discovery profile data with ip_address and is_range fields
     * @return Future<JsonObject> profile data enriched with target_ips array and total_targets count

     * Input profile must contain:
     * - ip_address: "192.168.1.10" (single) or "192.168.1.1-50" (range)
     * - is_range: boolean indicating if ip_address is a range specification

     * Output adds:
     * - target_ips: ["192.168.1.1", "192.168.1.2", ...] array of individual IPs
     * - total_targets: integer count of IPs to discover
     */
    private Future<JsonObject> parseDiscoveryTargetsForTest(JsonObject profile)
    {
        var promise = Promise.<JsonObject>promise();

        try
        {
            // Execute IP range parsing in worker thread to avoid blocking event loop
            vertx.executeBlocking(() ->
            {
                var ipAddress = profile.getString("ip_address");

                var isRange = profile.getBoolean("is_range", false);

                var targetIps = new JsonArray();

                // Parse both single IP and ranges
                var ipList = parseIPRange(ipAddress, isRange);

                for (var ip : ipList)
                {
                    targetIps.add(ip);
                }

                logger.debug("Discovery targets: {} ({} {})", ipAddress, targetIps.size(),
                           isRange ? "IPs from range" : "single IP");

                // Create result with profile data and target IPs
                return profile.copy()
                    .put("target_ips", targetIps)
                    .put("total_targets", targetIps.size());

            }, false)
            .onSuccess(promise::complete)
            .onFailure(cause ->
            {
                logger.error("Failed to parse discovery targets: {}", cause.getMessage());

                promise.fail("Failed to parse discovery targets: " + cause.getMessage());
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in parseDiscoveryTargetsForTest: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }



    /**
     * Filters out IP addresses that already have devices in the database.
     
     * Checks each target IP against the device table to identify which IPs already
     * have discovered devices. This prevents duplicate device creation and optimizes
     * discovery by only processing new IPs. Separates IPs into existing and new
     * target lists for efficient processing.
     *
     * @param profileData JsonObject containing target_ips array from previous step
     * @return Future<JsonObject> profile data with existing_devices and new_targets arrays
     
     * Input requires:
     * - target_ips: array of IP addresses to check
    
     * Output adds:
     * - existing_devices: array of JsonObjects for IPs that already have devices
     * - new_targets: array of IP strings that need discovery
     * - existing_count: integer count of existing devices found
     * - new_count: integer count of new IPs to discover
     */
    private Future<JsonObject> checkExistingDevicesAndFilter(JsonObject profileData)
    {
        var promise = Promise.<JsonObject>promise();

        try
        {
            var targetIps = profileData.getJsonArray("target_ips");

            var existingDevices = new JsonArray();

            var newTargets = new JsonArray();

            if (targetIps.isEmpty())
            {
                promise.complete(profileData.put("existing_devices", existingDevices).put("new_targets", newTargets));

                return promise.future();
            }

        // Check which IPs already exist in devices table using DeviceService
        var deviceCheckFutures = new ArrayList<Future<JsonObject>>();

        for (var ipObj : targetIps)
        {
            var ip = (String) ipObj;

            var devicePromise = Promise.<JsonObject>promise();

            // Check with includeDeleted = true to get all devices including soft deleted ones
            deviceService.deviceFindByIp(ip, true)
                .onSuccess(deviceResult ->
                {

                    if (deviceResult.getBoolean("found", false))
                    {
                        // Device exists - deviceResult contains the device data directly
                        var isProvisioned = deviceResult.getBoolean("is_provisioned", false);

                        var isMonitoring = deviceResult.getBoolean("is_monitoring_enabled", false);

                        var isDeleted = deviceResult.getBoolean("is_deleted", false);

                        String status;

                        String message;

                        var proceedWithDiscovery = false;

                        if (isDeleted)
                        {
                            // isDeleted = true, device was soft deleted - user must restore it
                            status = "soft_deleted";

                            message = "Device was previously deleted. Please restore it using the restore API instead of rediscovering";
                        }
                        else if (!isProvisioned && !isMonitoring)
                        {
                            // isProvisioned = false, isMonitored = false, isDeleted = false
                            status = "available_for_provision";

                            message = "Device already exists and is available for provision";
                        }
                        else if (isProvisioned && isMonitoring)
                        {
                            // isProvisioned = true, isMonitored = true, isDeleted = false
                            status = "being_monitored";

                            message = "Device already available and is being monitored";
                        }
                        else if (isProvisioned)
                        {
                            // isProvisioned = true, isMonitored = false, isDeleted = false
                            status = "monitoring_disabled";

                            message = "Device already available, and monitoring is disabled";
                        }
                        else
                        {
                            status = "unknown_state";

                            message = "Device in unknown state";
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
                })
                .onFailure(cause ->
                {
                    // Error checking device, assume it doesn't exist
                    devicePromise.complete(new JsonObject()
                        .put("ip_address", ip)
                        .put("exists", false)
                        .put("proceed_with_discovery", true));
                });

            deviceCheckFutures.add(devicePromise.future());
        }

            // Wait for all device checks to complete
            Future.all(deviceCheckFutures)
                .onSuccess(compositeFuture ->
                {
                    // Process results
                    for (var i = 0; i < compositeFuture.size(); i++)
                    {
                        var deviceCheck = compositeFuture.<JsonObject>resultAt(i);

                        var ip = deviceCheck.getString("ip_address");

                        if (deviceCheck.getBoolean("exists", false))
                        {
                            // Device exists - check if we should proceed with discovery
                            if (deviceCheck.getBoolean("proceed_with_discovery", false))
                            {
                                // Soft deleted device - proceed with discovery
                                newTargets.add(ip);

                                logger.debug("IP {} has soft deleted device, proceeding with new discovery", ip);
                            }
                            else
                            {
                                // Device exists and should not be rediscovered
                                existingDevices.add(new JsonObject()
                                    .put("ip_address", ip)
                                    .put("status", deviceCheck.getString("status"))
                                    .put("message", deviceCheck.getString("message"))
                                    .put("is_provisioned", deviceCheck.getBoolean("is_provisioned"))
                                    .put("is_monitoring_enabled", deviceCheck.getBoolean("is_monitoring_enabled"))
                                    .put("is_deleted", deviceCheck.getBoolean("is_deleted", false)));

                                logger.debug("IP {} already exists: {}", ip, deviceCheck.getString("message"));
                            }
                        }
                        else
                        {
                            // Device doesn't exist - proceed with discovery
                            newTargets.add(ip);
                        }
                    }

                    logger.info("Device check: {} existing (skipped), {} new targets for discovery",
                               existingDevices.size(), newTargets.size());

                    promise.complete(profileData
                        .put("existing_devices", existingDevices)
                        .put("new_targets", newTargets));
                })
                .onFailure(promise::fail);
        }
        catch (Exception exception)
        {
            logger.error("Error in checkExistingDevicesAndFilter: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Executes discovery in sequential batches for network efficiency and reliability.
     
     * Processes new target IPs in configurable batch sizes to avoid overwhelming the
     * network and GoEngine. Uses sequential processing to maintain system stability
     * and provide better error handling. Each batch is processed completely before
     * moving to the next batch. Accumulates results from all batches.
     *
     * @param profileData JsonObject containing new_targets array and profile configuration
     * @return Future<JsonObject> profile data with discovery_results array containing all batch results
     
     * Input requires:
     * - new_targets: array of IP strings to discover
     * - All profile data (credentials, device_type, port, protocol)
     
     * Output adds:
     * - discovery_results: array of discovery result objects from GoEngine
     * - total_processed: integer count of IPs processed
     * - batches_completed: integer count of batches processed
     
     * Batch processing configuration:
     * - Default batch size: 50 IPs per batch
     * - Sequential execution: waits for each batch to complete
     * - Error handling: continues with next batch if one fails
     */
    private Future<JsonObject> executeSequentialBatchDiscovery(JsonObject profileData)
    {
        var promise = Promise.<JsonObject>promise();

        try
        {
            var newTargets = profileData.getJsonArray("new_targets");

            if (newTargets.isEmpty())
            {
                logger.debug("No new targets for discovery, skipping GoEngine execution");

                promise.complete(profileData.put("discovery_results", new JsonArray()));

                return promise.future();
            }

            var totalTargets = newTargets.size();

            var totalBatches = (int) Math.ceil((double) totalTargets / discoveryBatchSize);

            logger.info("Starting sequential batch discovery for {} targets in {} batches",
                       totalTargets, totalBatches);

            // Use QueueBatchProcessor for sequential batch discovery
            var processor = new DiscoveryBatchProcessor(profileData, newTargets);

            var arrayPromise = Promise.<JsonArray>promise();

            processor.processNext(arrayPromise);

            return arrayPromise.future().map(results -> profileData.put("discovery_results", results));
        }
        catch (Exception exception)
        {
            logger.error("Error in executeSequentialBatchDiscovery: {}", exception.getMessage());

            promise.fail(exception);

            return promise.future();
        }
    }

    /**
     * Executes GoEngine discovery with pre-connectivity checks and credential iteration.
     
     * Performs a two-stage discovery process:
     * 1. Connectivity checks using fping and port scanning to identify reachable targets
     * 2. GoEngine discovery execution only on reachable IPs for efficiency
     
     * This method optimizes discovery by filtering unreachable IPs early, reducing
     * GoEngine execution time and improving overall discovery performance. Uses
     * NetworkConnectivityUtil for fast connectivity validation.
     *
     * @param profileData JsonObject containing discovery profile with credentials and configuration
     * @param targetIps JsonArray of IP address strings to discover
     * @return Future<JsonArray> containing discovery results for all IPs (successful and failed)
     
     * Input profileData requires:
     * - credentials: array of credential objects with username/password
     * - device_type_name: string device type for GoEngine
     * - port: integer port number for connectivity checks
     * - protocol: string protocol (ssh/winrm)
     
     * Output JsonArray contains:
     * - Successful discoveries: full device information from GoEngine
     * - Failed discoveries: error objects with ip_address, success=false, error message
     * - Unreachable IPs: connectivity failure objects with appropriate error messages
     */
    private Future<JsonArray> executeGoEngineDiscovery(JsonObject profileData, JsonArray targetIps)
    {
        var promise = Promise.<JsonArray>promise();

        try
        {
            if (targetIps.isEmpty())
            {
                promise.complete(new JsonArray());

                return promise.future();
            }

            // Step 1: Perform connectivity checks first
            performConnectivityChecks(targetIps, profileData)
                .compose(connectivityResults ->
                {
                    var reachableIPs = connectivityResults.getJsonArray("reachable");

                    var unreachableIPs = connectivityResults.getJsonArray("unreachable");

                    logger.debug("Connectivity check: {}/{} IPs reachable", reachableIPs.size(), targetIps.size());

                    if (reachableIPs.isEmpty())
                    {
                        logger.debug("No reachable IPs found, skipping GoEngine discovery");

                        // Create failed results inline
                        var failedResults = new JsonArray();

                        for (var i = 0; i < targetIps.size(); i++)
                        {
                            var ipAddress = targetIps.getString(i);

                            var failedResult = new JsonObject()
                                .put("ip_address", ipAddress)
                                .put("success", false)
                                .put("error", "No connectivity")
                                .put("timestamp", System.currentTimeMillis());

                            failedResults.add(failedResult);
                        }

                        return Future.succeededFuture(failedResults);
                    }

                    // Step 2: Proceed with GoEngine discovery for reachable IPs only

                    return executeGoEngineForReachableIPs(profileData, reachableIPs, unreachableIPs);
                })
                .onSuccess(promise::complete)
                .onFailure(promise::fail);
        }
        catch (Exception exception)
        {
            logger.error("Error in executeGoEngineDiscovery: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Performs fast connectivity checks on all target IPs using BATCH fping and port scanning.
     
     * Executes efficient batch connectivity validation to quickly identify which IPs are
     * reachable before attempting GoEngine discovery. Uses NetworkConnectivityUtil batch methods
     * for optimal performance. This pre-filtering step significantly improves discovery performance
     * by avoiding timeouts on unreachable IPs.
     *
     * @param targetIps JsonArray of IP address strings to check
     * @param profileData JsonObject containing port number for port connectivity checks
     * @return Future<JsonObject> containing reachable and unreachable IP arrays
     
     * Input requirements:
     * - targetIps: array of IP address strings
     * - profileData.port: integer port number to check (e.g., 22 for SSH, 5985 for WinRM)
     
     * Output JsonObject structure:
     * {
     *   "reachable": ["192.168.1.10", "192.168.1.15"],
     *   "unreachable": ["192.168.1.11", "192.168.1.12"]
     * }
     
     * Connectivity check process (OPTIMIZED):
     * 1. BATCH fping check for all IPs (single fping process)
     * 2. BATCH port check for IPs that passed fping (parallel checks)
     * 3. IP is considered reachable only if both checks pass
     */
    private Future<JsonObject> performConnectivityChecks(JsonArray targetIps, JsonObject profileData)
    {
        var promise = Promise.<JsonObject>promise();

        try
        {
            // Convert JsonArray to List<String>
            var ipList = new ArrayList<String>();

            for (var i = 0; i < targetIps.size(); i++)
            {
                ipList.add(targetIps.getString(i));
            }

            var port = profileData.getInteger("port", 22);

            // Step 1: Batch fping check (single fping process for all IPs) - wrapped in executeBlocking
            vertx.executeBlocking(() -> NetworkConnectivity.batchFpingCheck(ipList, Bootstrap.getConfig()))
                .compose(fpingResults ->
                {
                    // Filter IPs that passed fping
                    var pingAliveIps = ipList.stream()
                        .filter(ip -> fpingResults.getOrDefault(ip, false))
                        .collect(Collectors.toList());

                    if (pingAliveIps.isEmpty())
                    {
                        // All IPs failed fping, no need for port check
                        var unreachableIPs = new JsonArray();

                        ipList.forEach(unreachableIPs::add);

                        var result = new JsonObject()
                            .put("reachable", new JsonArray())
                            .put("unreachable", unreachableIPs)
                            .put("total_checked", ipList.size());

                        return Future.succeededFuture(result);
                    }

                    // Step 2: Batch port check (parallel checks for alive IPs only) - wrapped in executeBlocking
                    return vertx.executeBlocking(() -> NetworkConnectivity.batchPortCheck(pingAliveIps, port, Bootstrap.getConfig()))
                        .map(portResults ->
                        {
                            var reachableIPs = new JsonArray();

                            var unreachableIPs = new JsonArray();

                            // Classify IPs based on both fping and port check results
                            for (var ip : ipList)
                            {
                                var pingAlive = fpingResults.getOrDefault(ip, false);

                                var portOpen = portResults.getOrDefault(ip, false);

                                if (pingAlive && portOpen)
                                {
                                    reachableIPs.add(ip);
                                }
                                else
                                {
                                    unreachableIPs.add(ip);
                                }
                            }

                            logger.debug("Connectivity checks: {}/{} IPs reachable", reachableIPs.size(), ipList.size());

                            return new JsonObject()
                                .put("reachable", reachableIPs)
                                .put("unreachable", unreachableIPs)
                                .put("total_checked", ipList.size());
                        });
                })
                .onSuccess(promise::complete)
                .onFailure(promise::fail);
        }
        catch (Exception exception)
        {
            logger.error("Error in performConnectivityChecks: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Execute GoEngine discovery for reachable IPs and create results including unreachable ones
     */
    private Future<JsonArray> executeGoEngineForReachableIPs(JsonObject profileData, JsonArray reachableIPs,
                                                             JsonArray unreachableIPs)
    {
        var promise = Promise.<JsonArray>promise();

        try
        {
            vertx.executeBlocking(() ->
                    {
                        // Create GoEngine discovery request format for reachable IPs only
                        var discoveryRequest = createDiscoveryRequest(profileData, reachableIPs);

                        // Prepare GoEngine command - only mode flag, data passed via stdin
                        // Use absolute path for binary, set working directory for config file
                        var goEngineBinary = new File(goEnginePath).getAbsolutePath();

                        var pb = new ProcessBuilder(goEngineBinary, "--mode", "discovery");

                        pb.directory(new File("./goengine"));

                        var process = pb.start();

                        var results = new JsonArray();

                        var errorOutput = new StringBuilder();

                        // Write discovery request to stdin
                        try (var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream())))
                        {
                            writer.write(discoveryRequest.encode());

                            writer.flush();
                        }
                        catch (Exception exception)
                        {
                            logger.error("Failed to write discovery request to GoEngine stdin: {}", exception.getMessage());
                        }

                        // Read results from stdout and stderr simultaneously
                        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                             var errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream())))
                        {
                            String line;

                            while ((line = reader.readLine()) != null)
                            {
                                try
                                {
                                    var result = new JsonObject(line);

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

                            // Read any error output
                            String errLine;

                            while ((errLine = errorReader.readLine()) != null)
                            {
                                errorOutput.append(errLine).append("\n");
                            }
                        }

                        // Process timeout handled by Vert.x blocking timeout (blockingTimeoutGoEngine)
                        var finished = process.waitFor(blockingTimeoutGoEngine, TimeUnit.SECONDS);

                        if (!finished)
                        {
                            process.destroyForcibly();

                            throw new Exception("GoEngine discovery process timed out after " + blockingTimeoutGoEngine + " seconds");
                        }

                        var exitCode = process.exitValue();

                        if (exitCode != 0)
                        {
                            logger.warn("GoEngine discovery exited with code: {}", exitCode);

                            if (!errorOutput.isEmpty())
                            {
                                logger.warn("GoEngine stderr: {}", errorOutput.toString().trim());
                            }
                        }

                        logger.debug("GoEngine discovery completed with exit code: {}, {} results", exitCode, results.size());

                        // Combine GoEngine results with unreachable IPs
                        var finalResults = new JsonArray();

                        // Add GoEngine results
                        for (var obj : results)
                        {
                            finalResults.add(obj);
                        }

                        // Add failed results for unreachable IPs
                        for (var i = 0; i < unreachableIPs.size(); i++)
                        {
                            var unreachableIP = unreachableIPs.getString(i);

                            var failedResult = new JsonObject()
                                .put("ip_address", unreachableIP)
                                .put("success", false)
                                .put("error", "Device unreachable - connectivity check failed")
                                .put("timestamp", System.currentTimeMillis());

                            finalResults.add(failedResult);
                        }

                        return finalResults;

                        }, false)
                        .onSuccess(promise::complete)
                        .onFailure(cause ->
                        {
                            logger.error("GoEngine discovery execution failed: {}", cause.getMessage());

                            promise.fail(cause);
                        });
        }
        catch (Exception exception)
        {
            logger.error("Error in executeGoEngineForReachableIPs: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

    /**
     * Create GoEngine discovery request format with credential iteration.
     *
     * @param profileData JsonObject containing discovery profile configuration
     * @param targetIps JsonArray of IP addresses to discover
     * @return JsonObject in GoEngine discovery format
     */
    private JsonObject createDiscoveryRequest(JsonObject profileData, JsonArray targetIps)
    {
        try
        {
            // Convert target IPs to string array
            var targetIpArray = new JsonArray();

            for (var ip : targetIps)
            {
                targetIpArray.add(ip.toString());
            }

            // Convert credentials to GoEngine format
            var credentials = new JsonArray();

            var profileCredentials = profileData.getJsonArray("credentials");

            for (var credObj : profileCredentials)
            {
                var credential = (JsonObject) credObj;

                var goEngineCredential = new JsonObject()
                    .put("credential_id", credential.getString("credential_profile_id"))
                    .put("username", credential.getString("username"))
                    .put("password", credential.getString("password"));

                credentials.add(goEngineCredential);
            }

            // Get device type (already in GoEngine format from database)
            var deviceTypeName = profileData.getString("device_type_name");

            // Create discovery_config
            var discoveryConfig = new JsonObject()
                .put("device_type", deviceTypeName)
                .put("port", profileData.getInteger("port"))
                .put("timeout_seconds", timeoutSeconds)
                .put("connection_timeout", connectionTimeoutSeconds);

            // Create and return the discovery request (NEW FORMAT - no wrapper)
            return new JsonObject()
                .put("target_ips", targetIpArray)
                .put("credentials", credentials)
                .put("discovery_config", discoveryConfig);
        }
        catch (Exception exception)
        {
            logger.error("Error in createDiscoveryRequest: {}", exception.getMessage());

            return new JsonObject();
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
        var promise = Promise.<JsonObject>promise();

        try
        {
            var discoveryResults = profileData.getJsonArray("discovery_results", new JsonArray());

            var existingDevices = profileData.getJsonArray("existing_devices", new JsonArray());

            var successfulDevices = new JsonArray();

            var failedDevices = new JsonArray();

            // Separate successful and failed discoveries
            for (var obj : discoveryResults)
            {
                var result = (JsonObject) obj;

                if (result.getBoolean("success", false))
                {
                    successfulDevices.add(result);
                }
                else
                {
                    failedDevices.add(result);
                }
            }

            logger.info("Discovery results: {} successful, {} failed, {} existing",
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
                    promise.complete(createTestDiscoveryResponse(profileData, createdDevices.size(), failedDevices.size(),
                        existingDevices.size(), createdDevices, failedDevices, existingDevices)))
                .onFailure(promise::fail);
        }
        catch (Exception exception)
        {
            logger.error("Error in processTestDiscoveryResults: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonArray>promise();

        try
        {
            if (successfulDiscoveries.isEmpty())
            {
                promise.complete(new JsonArray());

                return promise.future();
            }

            var createdDevices = new JsonArray();

            var deviceCreationFutures = new ArrayList<Future<JsonObject>>();

            for (var obj : successfulDiscoveries)
            {
                var discovery = (JsonObject) obj;

                var deviceFuture = createSingleDeviceFromTestDiscovery(discovery);

                deviceCreationFutures.add(deviceFuture);
            }

            // Wait for all device creations to complete
            Future.all(deviceCreationFutures)
                .onSuccess(compositeFuture ->
                {
                    for (var future : deviceCreationFutures)
                    {
                        if (future.result() != null)
                        {
                            createdDevices.add(future.result());
                        }
                    }

                    promise.complete(createdDevices);
                })
                .onFailure(promise::fail);
        }
        catch (Exception exception)
        {
            logger.error("Error in createDevicesFromTestDiscoveries: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            // Prepare device data for DeviceService.deviceCreateFromDiscovery
            var deviceData = new JsonObject()
                .put("device_name", discovery.getString("hostname", discovery.getString("ip_address")))
                .put("ip_address", discovery.getString("ip_address"))
                .put("device_type", discovery.getString("device_type"))
                .put("port", discovery.getInteger("port", 22))
                .put("protocol", discovery.getString("device_type").contains("windows") ? "winrm" : "ssh")
                .put("credential_profile_id", discovery.getString("credential_id")) // ONLY the successful credential ID
                .put("host_name", discovery.getString("hostname", discovery.getString("ip_address")));

            deviceService.deviceCreateFromDiscovery(deviceData)
                .onSuccess(result ->
                {

                    // Return device info for response
                    promise.complete(new JsonObject()
                        .put("ip_address", deviceData.getString("ip_address"))
                        .put("hostname", deviceData.getString("host_name"))
                        .put("device_id", result.getString("device_id"))
                        .put("device_name", deviceData.getString("device_name"))
                        .put("success", true));
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to create device from test discovery: {}", cause.getMessage());

                    promise.complete(null); // Return null for failed creation
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in createSingleDeviceFromTestDiscovery: {}", exception.getMessage());

            promise.complete(null);
        }

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
        try
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
        catch (Exception exception)
        {
            logger.error("Error in createTestDiscoveryResponse: {}", exception.getMessage());

            return new JsonObject()
                .put("success", false)
                .put("error", exception.getMessage());
        }
    }

    /**
     * Discovery batch processor using QueueBatchProcessor.
     
     * Extends the generic QueueBatchProcessor to handle discovery-specific batch processing.
     * Uses GoEngine's built-in credential iteration (tests multiple credentials per IP until one succeeds).
     
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
        }

        /**
         * Process a batch of IP addresses using GoEngine discovery.
         
         * Converts the batch of IP strings to JsonArray and executes GoEngine discovery.
         * GoEngine handles credential iteration internally for each IP.
         *
         * @param batch List of IP addresses to discover in this batch
         * @return Future containing JsonArray of discovery results
         */
        @Override
        protected Future<JsonArray> processBatch(List<String> batch)
        {
            try
            {
                var ipArray = new JsonArray(batch);

                return executeGoEngineDiscovery(profileData, ipArray);
            }
            catch (Exception exception)
            {
                logger.error("Error in processBatch: {}", exception.getMessage());

                return Future.failedFuture(exception);
            }
        }

        /**
         * Handle batch processing failure.
         
         * Logs the failed IPs for debugging. The batch processor will continue
         * with the next batch (fail-tolerant behavior).
         *
         * @param batch The batch of IPs that failed to process
         * @param cause The exception that caused the failure
         */
        @Override
        protected void handleBatchFailure(List<String> batch, Throwable cause)
        {
            try
            {
                logger.warn("Failed to discover {} IPs in batch: {}", batch.size(), cause.getMessage());
            }
            catch (Exception exception)
            {
                logger.error("Error in handleBatchFailure: {}", exception.getMessage());
            }
        }
    }

    /**
     * Parse IP address or range into a list of individual IP addresses.

     * Supports:
     * - Single IP addresses: "192.168.1.100"
     * - IP ranges in same subnet: "192.168.1.1-50" (expands to 192.168.1.1 through 192.168.1.50)
     *
     * @param ipAddress The IP address or range string
     * @param isRange Flag indicating if this should be treated as a range
     * @return List of individual IP addresses, or empty list if invalid
     */
    private List<String> parseIPRange(String ipAddress, boolean isRange)
    {
        try
        {
            if (ipAddress == null || ipAddress.trim().isEmpty())
            {
                logger.error("IP address cannot be null or empty");

                return new ArrayList<>();
            }

            ipAddress = ipAddress.trim();

            List<String> ipList;

            if (!isRange)
            {
                // Single IP address - validate format using ValidationUtil
                if (!ValidationUtil.isValidSingleIP(ipAddress))
                {
                    logger.error("Invalid IP address format: {}", ipAddress);

                    return new ArrayList<>();
                }

                ipList = new ArrayList<>();

                ipList.add(ipAddress);
            }
            else
            {
                // IP range - validate and expand using ValidationUtil
                if (!ValidationUtil.isValidIPRange(ipAddress))
                {
                    logger.error("Invalid IP range format: {}. Expected format: '192.168.1.1-50'", ipAddress);

                    return new ArrayList<>();
                }

                ipList = expandIPRange(ipAddress);
            }

            return ipList;
        }
        catch (Exception exception)
        {
            logger.error("Error in parseIPRange: {}", exception.getMessage());

            return new ArrayList<>();
        }
    }

    /**
     * Expand an IP range into individual IP addresses.
     *
     * @param ipRange The IP range string (e.g., "192.168.1.1-50")
     * @return List of individual IP addresses, or empty list if invalid
     */
    private List<String> expandIPRange(String ipRange)
    {
        var ipList = new ArrayList<String>();

        try
        {
            var parts = ipRange.split("-");

            var baseIP = parts[0];

            var endOctet = Integer.parseInt(parts[1]);

            // Extract IP base and start octet
            var ipParts = baseIP.split("\\.");

            var ipBase = ipParts[0] + "." + ipParts[1] + "." + ipParts[2] + ".";

            var startOctet = Integer.parseInt(ipParts[3]);

            // Generate IP addresses in range
            for (var i = startOctet; i <= endOctet; i++)
            {
                ipList.add(ipBase + i);
            }

        }
        catch (Exception exception)
        {
            logger.error("Failed to expand IP range {}: {}", ipRange, exception.getMessage());

            return new ArrayList<>();
        }

        return ipList;
    }

    /**
     * Stop the verticle and perform any required cleanup.
     *
     * @param stopPromise completed when shutdown work is finished
     */
    @Override
    public void stop(Promise<Void> stopPromise)
    {
        try
        {
            logger.info("Stopping DiscoveryVerticle");

            stopPromise.complete();
        }
        catch (Exception exception)
        {
            logger.error("Error in stop: {}", exception.getMessage());

            stopPromise.fail(exception);
        }
    }
}