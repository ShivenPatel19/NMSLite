package com.nmslite.utils;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.io.BufferedReader;

import java.io.IOException;

import java.io.InputStreamReader;

import java.net.InetSocketAddress;

import java.net.Socket;

import java.util.HashMap;

import java.util.List;

import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.TimeUnit;

/**
 * NetworkConnectivityUtil - Batch connectivity checks for Discovery and Polling

 * Provides optimized batch methods for pre-checking device reachability:
 * - Batch fping check: Single fping process for multiple IPs (efficient)
 * - Batch port check: Parallel port checks for multiple IPs (fast)

 * Used by both DiscoveryVerticle and PollingMetricsVerticle for pre-filtering
 * devices before sending to GoEngine.
 */
public class NetworkConnectivityUtil
{

    private static final Logger logger = LoggerFactory.getLogger(NetworkConnectivityUtil.class);

    /**
     * Batch fping check for multiple IPs (EFFICIENT - single fping process)

     * Uses fping's batch mode: writes all IPs to stdin and reads results from stdout.
     * This is much more efficient than running individual fping processes.

     * 2-Level Timeout Hierarchy:
     * - Level 2: Per-IP timeout (tools.fping.timeout.seconds) - fping -t parameter
     * - Level 1: Batch operation timeout (fping.batch.blocking.timeout.seconds) - process.waitFor()
     *
     * @param vertx Vert.x instance
     * @param ipAddresses List of IP addresses to check
     * @param config Configuration object
     * @return Future<Map<String, Boolean>> - Map of IP -> reachability status
     */
    public static Future<Map<String, Boolean>> batchFpingCheck(Vertx vertx, List<String> ipAddresses, JsonObject config)
    {
        var promise = Promise.<Map<String, Boolean>>promise();

        if (ipAddresses == null || ipAddresses.isEmpty())
        {
            promise.complete(new HashMap<>());

            return promise.future();
        }

        var fpingPath = config.getString("tools.fping.path", "fping");

        // Level 2: Per-IP timeout (fping -t parameter)
        var perIpTimeoutSeconds = config.getInteger("tools.fping.timeout.seconds", 5);

        // Level 1: Batch operation timeout (process.waitFor)
        var batchTimeoutSeconds = config.getInteger("fping.batch.blocking.timeout.seconds", 180);

        vertx.executeBlocking(() ->
        {
            var results = new HashMap<String, Boolean>();

            try
            {
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

                        results.put(ip, false); // Default to unreachable
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
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
                {
                    String line;

                    while ((line = reader.readLine()) != null)
                    {
                        var aliveIp = line.trim();

                        if (results.containsKey(aliveIp))
                        {
                            results.put(aliveIp, true);
                        }
                    }
                }

                logger.debug("Batch fping: {}/{} IPs reachable",
                    results.values().stream().filter(v -> v).count(), ipAddresses.size());

                return results;

            }
            catch (Exception exception)
            {
                logger.error("Batch fping failed", exception);

                // Mark all as unreachable on error
                for (var ip : ipAddresses)
                {
                    results.put(ip, false);
                }

                return results;
            }
        })
        .onSuccess(promise::complete)
        .onFailure(cause ->
        {
            logger.error("Batch fping executeBlocking failed", cause);

            var results = new HashMap<String, Boolean>();

            for (var ip : ipAddresses)
            {
                results.put(ip, false);
            }

            promise.complete(results);
        });

        return promise.future();
    }

    /**
     * Single port check for one IP address

     * Checks if a specific port is open on a single IP address.
     * Uses TCP socket connection with configurable timeout.
     *
     * @param vertx Vert.x instance
     * @param ipAddress IP address to check
     * @param port Port number to check
     * @param config Configuration object
     * @return Future<Boolean> - true if port is open, false otherwise
     */
    public static Future<Boolean> portCheck(Vertx vertx, String ipAddress, int port, JsonObject config)
    {
        var promise = Promise.<Boolean>promise();

        var perSocketTimeoutSeconds = config.getInteger("tools.port.check.timeout.seconds", 5);

        vertx.executeBlocking(() ->
        {
            try (var socket = new Socket())
            {
                socket.connect(new InetSocketAddress(ipAddress, port), perSocketTimeoutSeconds * 1000);

                logger.debug("Port {} open on {}", port, ipAddress);

                return true;
            }
            catch (IOException exception)
            {
                logger.debug("Port {} not reachable on {}: {}", port, ipAddress, exception.getMessage());

                return false;
            }
        })
        .onSuccess(promise::complete)
        .onFailure(cause ->
        {
            logger.error("Port check failed for {}:{}", ipAddress, port, cause);

            promise.complete(false);
        });

        return promise.future();
    }

    /**
     * Batch port check for multiple IPs (PARALLEL - concurrent checks)

     * Uses Java parallel streams to check multiple ports concurrently.
     * Much faster than sequential port checks.

     * 2-Level Timeout Hierarchy:
     * - Level 2: Per-socket timeout (tools.port.check.timeout.seconds)
     * - Level 1: Batch operation timeout (port.check.batch.blocking.timeout.seconds)
     *
     * @param vertx Vert.x instance
     * @param ipAddresses List of IP addresses to check
     * @param port Port number to check
     * @param config Configuration object
     * @return Future<Map<String, Boolean>> - Map of IP -> port reachability status
     */
    public static Future<Map<String, Boolean>> batchPortCheck(Vertx vertx, List<String> ipAddresses, int port, JsonObject config)
    {
        var promise = Promise.<Map<String, Boolean>>promise();

        if (ipAddresses == null || ipAddresses.isEmpty())
        {
            promise.complete(new HashMap<>());

            return promise.future();
        }

        // Level 2: Per-socket timeout (TCP connection timeout)
        var perSocketTimeoutSeconds = config.getInteger("tools.port.check.timeout.seconds", 5);

        vertx.executeBlocking(() ->
        {
            // Use ConcurrentHashMap for thread-safe parallel access
            var results = new ConcurrentHashMap<String, Boolean>();

            // Use parallel stream for concurrent port checks
            ipAddresses.parallelStream().forEach(ip ->
            {
                var portOpen = false;

                try (var socket = new Socket())
                {
                    socket.connect(new InetSocketAddress(ip, port), perSocketTimeoutSeconds * 1000);

                    portOpen = true;

                    logger.debug("Port {} open on {}", port, ip);
                }
                catch (IOException exception)
                {
                    // Port not reachable (timeout, connection refused, etc.)
                    logger.debug("Port {} not reachable on {}: {}", port, ip, exception.getMessage());
                }

                // ConcurrentHashMap.put() is thread-safe, no synchronization needed
                results.put(ip, portOpen);
            });

            logger.debug("Batch port check (port {}): {}/{} ports reachable",
                port, results.values().stream().filter(v -> v).count(), ipAddresses.size());

            return results;

        })
        .onSuccess(promise::complete)
        .onFailure(cause ->
        {
            logger.error("Batch port check executeBlocking failed", cause);

            var results = new HashMap<String, Boolean>();

            for (var ip : ipAddresses)
            {
                results.put(ip, false);
            }

            promise.complete(results);
        });

        return promise.future();
    }

}
