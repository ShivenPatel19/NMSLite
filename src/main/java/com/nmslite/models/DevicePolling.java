package com.nmslite.models;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

/**
 * In-memory cache model for polling scheduler.
 *
 * <p>Combines persistent device data from database with runtime scheduling state
 * (nextScheduledAt, consecutiveFailures). Runtime state is recomputed on restart.</p>
 */
public class DevicePolling
{

    // Identity
    public String deviceId;              // devices.device_id

    public String deviceName;            // devices.device_name

    // GoEngine required fields
    public String address;               // devices.ip_address

    public String deviceType;            // devices.device_type

    public String username;              // credential_profiles.username

    public String password;              // credential_profiles.password_encrypted (decrypted)

    public int port;                     // credential_profiles.port

    // Per-device configuration (from devices table, NOT config file)
    public int timeoutSeconds;           // devices.timeout_seconds

    public long pollingIntervalSeconds;  // devices.polling_interval_seconds

    // Global configuration (from config file, same for all devices)
    public int connectionTimeoutSeconds; // polling.connection.timeout.seconds (NOT in database)

    // ===== RUNTIME STATE (in-memory only, lost on restart) =====

    public Instant nextScheduledAt;      // Computed: aligned next poll time

    public int consecutiveFailures;      // Tracked: failure count for auto-disable

    /**
     * Converts the DevicePolling to GoEngine JSON format for metrics collection.
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

}

