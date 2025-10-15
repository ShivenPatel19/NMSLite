package com.nmslite.core;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.io.BufferedReader;

import java.io.InputStreamReader;

import java.net.InetSocketAddress;

import java.net.Socket;

import java.util.HashMap;

import java.util.List;

import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.TimeUnit;

/**
 * NetworkConnectivity - Synchronous batch connectivity checks for Discovery and Polling

 * Provides optimized batch methods for pre-checking device reachability:
 * - Batch fping check: Single fping process for multiple IPs (efficient)
 * - Batch port check: Parallel port checks for multiple IPs (fast)

 * IMPORTANT: All methods are BLOCKING and should be called from within executeBlocking() in verticles.
 * This class does NOT use executeBlocking internally - that responsibility belongs to the caller.

 * Used by both DiscoveryVerticle and PollingMetricsVerticle for pre-filtering
 * devices before sending to GoEngine.
 */
public class NetworkConnectivity
{

    private static final Logger logger = LoggerFactory.getLogger(NetworkConnectivity.class);

    /**
     * Batch fping check for multiple IPs (EFFICIENT - single fping process)

     * Uses fping's batch mode: writes all IPs to stdin and reads results from stdout.
     * This is much more efficient than running individual fping processes.

     * 2-Level Timeout Hierarchy:
     * - Level 2: Per-IP timeout (tools.fping.timeout.seconds) - fping -t parameter
     * - Level 1: Batch operation timeout (fping.batch.blocking.timeout.seconds) - process.waitFor()

     * WARNING: This method is BLOCKING and must be called from within executeBlocking() in verticles.
     *
     * @param ipAddresses List of IP addresses to check
     * @param config Configuration object
     * @return Map<String, Boolean> - Map of IP -> reachability status
     */
    public static Map<String, Boolean> batchFpingCheck(List<String> ipAddresses, JsonObject config)
    {
        try
        {
            var results = new HashMap<String, Boolean>();

            if (ipAddresses == null || ipAddresses.isEmpty())
            {
                return results;
            }

            // HOCON parses dotted keys as nested objects: tools.fping.path becomes tools -> fping -> path
            var fpingPath = config.getJsonObject("tools", new JsonObject())
                    .getJsonObject("fping", new JsonObject())
                    .getString("path", "fping");

            // Level 2: Per-IP timeout (fping -t parameter)
            var perIpTimeoutSeconds = config.getJsonObject("tools", new JsonObject())
                    .getJsonObject("fping", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 5);

            // Level 1: Batch operation timeout (process.waitFor)
            var batchTimeoutSeconds = config.getJsonObject("fping", new JsonObject())
                    .getJsonObject("batch", new JsonObject())
                    .getJsonObject("blocking", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 180);

            // fping batch mode: -a (show alive), -q (quiet), -t (timeout in ms)
            var pb = new ProcessBuilder(
                fpingPath, "-a", "-q", "-t", String.valueOf(perIpTimeoutSeconds * 1000)
            );

            var process = pb.start();

            // Write all IPs to stdin
            try (var writer = process.outputWriter())
            {
                for (var ip : ipAddresses)
                {
                    writer.write(ip + "\n");

                    results.put(ip, false); // Default to unreachable, then update to alive if reachable
                }
            } // Writer closed here, signals EOF to fping

            // Wait for process to complete using configured batch timeout
            var finished = process.waitFor(batchTimeoutSeconds, TimeUnit.SECONDS);

            if (!finished)
            {
                process.destroyForcibly();

                logger.warn("Batch fping timeout for {} IPs after {} seconds", ipAddresses.size(), batchTimeoutSeconds);

                return results;
            }

            // Read alive IPs from stdout
            var reachableCount = 0;

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
            {
                String line;

                while ((line = reader.readLine()) != null)
                {
                    var aliveIp = line.trim();

                    if (results.containsKey(aliveIp))
                    {
                        results.put(aliveIp, true);  // Update to reachable

                        reachableCount++;
                    }
                }
            }

            logger.debug("Batch fping: {}/{} IPs reachable", reachableCount, ipAddresses.size());

            return results;
        }
        catch (Exception exception)
        {
            logger.error("Error in batchFpingCheck: {}", exception.getMessage());

            return new HashMap<>();
        }
    }

    /**
     * Single port check for one IP address

     * Checks if a specific port is open on a single IP address.
     * Uses TCP socket connection with configurable timeout.

     * WARNING: This method is BLOCKING and must be called from within executeBlocking() in verticles.
     *
     * @param ipAddress IP address to check
     * @param port Port number to check
     * @param config Configuration object
     * @return Boolean - true if port is open, false otherwise
     */
    public static Boolean portCheck(String ipAddress, int port, JsonObject config)
    {
        try
        {
            // HOCON parses dotted keys as nested objects: tools.port.check.timeout.seconds
            var perSocketTimeoutSeconds = config.getJsonObject("tools", new JsonObject())
                    .getJsonObject("port", new JsonObject())
                    .getJsonObject("check", new JsonObject())
                    .getJsonObject("timeout", new JsonObject())
                    .getInteger("seconds", 5);

            try (var socket = new Socket())
            {
                socket.connect(new InetSocketAddress(ipAddress, port), perSocketTimeoutSeconds * 1000);

                logger.debug("Port {} open on {}", port, ipAddress);

                return true;
            }
        }
        catch (Exception exception)
        {
            logger.debug("Port {} not reachable on {}: {}", port, ipAddress, exception.getMessage());

            return false;
        }
    }

    /**
     * Batch port check for multiple IPs (PARALLEL - concurrent checks)

     * Uses Java parallel streams to check multiple ports concurrently.
     * Much faster than sequential port checks.
     * Internally calls portCheck() for each IP to avoid code duplication.

     * 2-Level Timeout Hierarchy:
     * - Level 2: Per-socket timeout (tools.port.check.timeout.seconds)
     * - Level 1: Batch operation timeout (port.check.batch.blocking.timeout.seconds)

     * WARNING: This method is BLOCKING and must be called from within executeBlocking() in verticles.
     *
     * @param ipAddresses List of IP addresses to check
     * @param port Port number to check
     * @param config Configuration object
     * @return Map<String, Boolean> - Map of IP -> port reachability status
     */
    public static Map<String, Boolean> batchPortCheck(List<String> ipAddresses, int port, JsonObject config)
    {
        try
        {
            if (ipAddresses == null || ipAddresses.isEmpty())
            {
                return new HashMap<>();
            }

            // Use ConcurrentHashMap for thread-safe parallel access
            var results = new ConcurrentHashMap<String, Boolean>();

            // Use parallel stream for concurrent port checks
            // Reuses portCheck() method to avoid code duplication
            ipAddresses.parallelStream().forEach(ip ->
            {
                var portOpen = portCheck(ip, port, config);

                // ConcurrentHashMap.put() is thread-safe, no synchronization needed
                results.put(ip, portOpen);
            });

            logger.debug("Batch port check (port {}): {}/{} ports reachable",
                port, results.values().stream().filter(v -> v).count(), ipAddresses.size());

            return results;
        }
        catch (Exception exception)
        {
            logger.error("Error in batchPortCheck: {}", exception.getMessage());

            return new HashMap<>();
        }
    }

}
