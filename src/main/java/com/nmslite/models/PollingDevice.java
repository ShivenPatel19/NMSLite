package com.nmslite.models;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

/**
 * In-memory cache model for polling scheduler.

 * This class combines:
 * 1. Persistent device data (from database)
 * 2. Runtime scheduling state (computed/tracked in-memory)

 * Data Sources:
 * - Database: devices table + credential_profiles table (via JOIN)
 * - Computed: nextScheduledAt (aligned scheduling)
 * - Tracked: consecutiveFailures (failure counter)

 * Lifecycle:
 * - Loaded on startup from database
 * - Updated reactively via event bus when device config changes
 * - Runtime state lost on restart (acceptable - recomputed on startup)
 */
public class PollingDevice
{

    // ===== PERSISTENT DATA (from database) =====

    // Identity
    public String deviceId;              // devices.device_id

    public String deviceName;            // devices.device_name

    // GoEngine required fields
    public String address;               // devices.ip_address

    public String deviceType;            // devices.device_type

    public String username;              // credential_profiles.username

    public String password;              // credential_profiles.password_encrypted (decrypted)

    public int port;                     // devices.port

    // Per-device configuration (from devices table, NOT config file)
    public int timeoutSeconds;           // devices.timeout_seconds

    public long pollingIntervalSeconds;  // devices.polling_interval_seconds

    // Global configuration (from config file, same for all devices)
    public int connectionTimeoutSeconds; // polling.connection.timeout.seconds (NOT in database)

    // Timestamps
    public Instant monitoringEnabledAt;  // devices.monitoring_enabled_at (anchor for scheduling)

    // ===== RUNTIME STATE (in-memory only, lost on restart) =====

    public Instant nextScheduledAt;      // Computed: aligned next poll time

    public int consecutiveFailures;      // Tracked: failure count for auto-disable

    public PollingResult pollingResult;  // Transient: result of current polling cycle

    /**
     * Converts the PollingDevice to GoEngine JSON format for metrics collection.

     * GoEngine  expects:
     * {
     *   "address": "10.0.0.1",
     *   "device_type": "server linux",
     *   "username": "admin",
     *   "password": "password",
     *   "port": 22,
     *   "timeout_seconds": 60,
     *   "connection_timeout": 10
     * }
     *
     * @return JsonObject formatted for GoEngine consumption
     */
    public JsonObject toGoEngineJson()
    {
        return new JsonObject()
            .put("address", address)
            .put("device_type", deviceType)
            .put("username", username)
            .put("password", password)
            .put("port", port)
            .put("timeout_seconds", timeoutSeconds)
            .put("connection_timeout", connectionTimeoutSeconds);
    }

    /**
     * Checks if the device is due for polling based on the current time.

     * A device is due when:
     * - Current time >= nextScheduledAt
     *
     * @param now Current time to compare against
     * @return true if device should be polled now, false otherwise
     */
    public boolean isDue(Instant now)
    {
        return nextScheduledAt.isBefore(now) || nextScheduledAt.equals(now);
    }

    /**
     * Advances the device to the next scheduled poll time (aligned).

     * This maintains fixed cadence:
     * - nextScheduledAt = nextScheduledAt + pollingIntervalSeconds
     */
    public void advanceSchedule()
    {
        nextScheduledAt = nextScheduledAt.plusSeconds(pollingIntervalSeconds);
    }

    /**
     * Resets the consecutive failure counter to zero.
     * Should be called after a successful poll.
     */
    public void resetFailures()
    {
        consecutiveFailures = 0;
    }

    /**
     * Increments the consecutive failure counter by one.
     * Should be called after a failed poll.
     */
    public void incrementFailures()
    {
        consecutiveFailures++;
    }

    /**
     * Checks if the device should be auto-disabled due to consecutive failures.
     *
     * @param maxCyclesSkipped Maximum allowed consecutive failures before auto-disable
     * @return true if device should be auto-disabled, false otherwise
     */
    public boolean shouldAutoDisable(int maxCyclesSkipped)
    {
        return consecutiveFailures >= maxCyclesSkipped;
    }

    /**
     * Resets the polling result to NOT_PROCESSED.
     * Should be called at the start of each polling cycle.
     */
    public void resetPollingResult()
    {
        pollingResult = PollingResult.NOT_PROCESSED;
    }

    /**
     * Checks if the device failed during the current polling cycle.
     *
     * @return true if device failed (connectivity or GoEngine), false otherwise
     */
    public boolean hasFailed()
    {
        return pollingResult == PollingResult.CONNECTIVITY_FAILED ||

               pollingResult == PollingResult.GOENGINE_FAILED;
    }
}

