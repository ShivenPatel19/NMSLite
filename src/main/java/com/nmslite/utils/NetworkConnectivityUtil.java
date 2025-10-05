package com.nmslite.utils;

import io.vertx.config.ConfigRetriever;

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

import java.util.concurrent.TimeUnit;

/**
 * NetworkConnectivityUtil - Batch connectivity checks for Discovery and Polling
 *
 * Provides optimized batch methods for pre-checking device reachability:
 * - Batch fping check: Single fping process for multiple IPs (efficient)
 * - Batch port check: Parallel port checks for multiple IPs (fast)
 *
 * Used by both DiscoveryVerticle and PollingMetricsVerticle for pre-filtering
 * devices before sending to GoEngine.
 */
public class NetworkConnectivityUtil
{

    private static final Logger logger = LoggerFactory.getLogger(NetworkConnectivityUtil.class);

    /**
     * Batch fping check for multiple IPs (EFFICIENT - single fping process)
     *
     * Uses fping's batch mode: writes all IPs to stdin and reads results from stdout.
     * This is much more efficient than running individual fping processes.
     *
     * @param vertx Vert.x instance
     * @param ipAddresses List of IP addresses to check
     * @param config Configuration object
     * @return Future<Map<String, Boolean>> - Map of IP -> reachability status
     */
    public static Future<Map<String, Boolean>> batchFpingCheck(Vertx vertx, List<String> ipAddresses, JsonObject config)
    {
        Promise<Map<String, Boolean>> promise = Promise.promise();

        if (ipAddresses == null || ipAddresses.isEmpty())
        {
            promise.complete(new HashMap<>());

            return promise.future();
        }

        String fpingPath = config.getString("tools.fping.path", "fping");

        int timeoutSeconds = config.getInteger("tools.fping.timeout.seconds", 5);

        vertx.executeBlocking(blockingPromise ->
        {
            Map<String, Boolean> results = new HashMap<>();

            try
            {
                // fping batch mode: -a (show alive), -q (quiet), -t (timeout in ms)
                ProcessBuilder pb = new ProcessBuilder(
                    fpingPath, "-a", "-q", "-t", String.valueOf(timeoutSeconds * 1000)
                );

                Process process = pb.start();

                // Write all IPs to stdin
                try (var writer = process.outputWriter())
                {
                    for (String ip : ipAddresses)
                    {
                        writer.write(ip + "\n");

                        results.put(ip, false); // Default to unreachable
                    }
                } // Writer closed here, signals EOF to fping

                // Wait for process to complete
                boolean finished = process.waitFor(timeoutSeconds + 10, TimeUnit.SECONDS);

                if (!finished)
                {
                    process.destroyForcibly();

                    logger.warn("Batch fping timeout for {} IPs", ipAddresses.size());

                    blockingPromise.complete(results);

                    return;
                }

                // Read alive IPs from stdout
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

                logger.debug("Batch fping: {}/{} IPs reachable",
                    results.values().stream().filter(v -> v).count(), ipAddresses.size());

                blockingPromise.complete(results);

            }
            catch (Exception exception)
            {
                logger.error("Batch fping failed", exception);

                // Mark all as unreachable on error
                for (String ip : ipAddresses)
                {
                    results.put(ip, false);
                }

                blockingPromise.complete(results);
            }
        }, promise);

        return promise.future();
    }

    /**
     * Batch port check for multiple IPs (PARALLEL - concurrent checks)
     *
     * Uses Java parallel streams to check multiple ports concurrently.
     * Much faster than sequential port checks.
     *
     * @param vertx Vert.x instance
     * @param ipAddresses List of IP addresses to check
     * @param port Port number to check
     * @param config Configuration object
     * @return Future<Map<String, Boolean>> - Map of IP -> port reachability status
     */
    public static Future<Map<String, Boolean>> batchPortCheck(Vertx vertx, List<String> ipAddresses, int port, JsonObject config)
    {
        Promise<Map<String, Boolean>> promise = Promise.promise();

        if (ipAddresses == null || ipAddresses.isEmpty())
        {
            promise.complete(new HashMap<>());

            return promise.future();
        }

        int timeoutSeconds = config.getInteger("tools.port.check.timeout.seconds", 5);

        vertx.executeBlocking(blockingPromise ->
        {
            Map<String, Boolean> results = new HashMap<>();

            // Use parallel stream for concurrent port checks
            ipAddresses.parallelStream().forEach(ip ->
            {
                boolean portOpen = false;

                try (Socket socket = new Socket())
                {
                    socket.connect(new InetSocketAddress(ip, port), timeoutSeconds * 1000);

                    portOpen = true;
                }
                catch (IOException exception)
                {
                    // Port not reachable
                }

                synchronized (results)
                {
                    results.put(ip, portOpen);
                }
            });

            logger.debug("Batch port check (port {}): {}/{} ports reachable",
                port, results.values().stream().filter(v -> v).count(), ipAddresses.size());

            blockingPromise.complete(results);

        }, promise);

        return promise.future();
    }

}
