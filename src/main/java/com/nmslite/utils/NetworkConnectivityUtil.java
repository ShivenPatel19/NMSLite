package com.nmslite.utils;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * NetworkConnectivityUtil - Simple utility for fping and port connectivity checks
 *
 * Provides only:
 * - fping check
 * - port check
 */
public class NetworkConnectivityUtil {

    private static final Logger logger = LoggerFactory.getLogger(NetworkConnectivityUtil.class);

    /**
     * Check ping connectivity using fping (reads timeout from config)
     */
    public static Future<Boolean> fpingCheck(Vertx vertx, String ipAddress, JsonObject config) {
        Promise<Boolean> promise = Promise.promise();

        String fpingPath = config.getString("tools.fping.path", "fping");
        int timeoutSeconds = config.getInteger("tools.fping.timeout.seconds", 5);
        int blockingTimeoutSeconds = config.getInteger("tools.fping.blocking.timeout.seconds", 15);

        vertx.executeBlocking(blockingPromise -> {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(
                    fpingPath, "-c", "1", "-t", String.valueOf(timeoutSeconds * 1000), ipAddress
                );
                Process process = processBuilder.start();

                boolean finished = process.waitFor(timeoutSeconds + 5, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    blockingPromise.complete(false);
                    return;
                }

                blockingPromise.complete(process.exitValue() == 0);

            } catch (Exception e) {
                blockingPromise.complete(false);
            }
        }, promise);

        return promise.future();
    }

    /**
     * Check port connectivity (reads timeout from config)
     */
    public static Future<Boolean> portCheck(Vertx vertx, String ipAddress, int port, JsonObject config) {
        Promise<Boolean> promise = Promise.promise();

        int timeoutSeconds = config.getInteger("tools.port.check.timeout.seconds", 5);
        int blockingTimeoutSeconds = config.getInteger("tools.port.check.blocking.timeout.seconds", 10);

        vertx.executeBlocking(blockingPromise -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ipAddress, port), timeoutSeconds * 1000);
                blockingPromise.complete(true);
            } catch (IOException e) {
                blockingPromise.complete(false);
            }
        }, false, promise);

        return promise.future();
    }
}
