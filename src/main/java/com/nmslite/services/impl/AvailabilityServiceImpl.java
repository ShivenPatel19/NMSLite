package com.nmslite.services.impl;

import com.nmslite.database.DatabaseHelper;

import com.nmslite.database.DatabaseInitializer;

import com.nmslite.services.AvailabilityService;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import java.util.UUID;

/**
 * AvailabilityServiceImpl - Implementation of AvailabilityService

 * Provides device availability status operations including:
 * - Current device availability status retrieval
 * - Real-time status updates for polling
 * - Availability cleanup operations

 * Database Access:
 * - Uses DatabaseInitializer.getDatabaseHelper() for database operations
 * - DatabaseHelper provides generic query execution with consistent error handling
 * - No constructor parameters needed
 */
public class AvailabilityServiceImpl implements AvailabilityService
{

    private static final Logger logger = LoggerFactory.getLogger(AvailabilityServiceImpl.class);

    private final DatabaseHelper dbHelper;

    /**
     * Constructor for AvailabilityServiceImpl.
     * Accesses database helper via DatabaseInitializer.
     */
    public AvailabilityServiceImpl()
    {
        this.dbHelper = DatabaseInitializer.getDatabaseHelper();
    }

    /**
     * Get all availability status records
     *
     * @return Future containing JsonArray of all availability records
     */
    @Override
    public Future<JsonArray> availabilityGetAll()
    {
        try
        {
            var sql = """
                    SELECT device_id, current_status
                    FROM device_availability
                    ORDER BY device_id
                    """;

            return dbHelper.executeQuery(sql)
                    .map(rows ->
                    {
                        var results = new JsonArray();

                        for (var row : rows)
                        {
                            results.add(new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("status", row.getString("current_status")));
                        }

                        return results;
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in availabilityGetAll service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Get device availability by device ID
     *
     * @param deviceId Device ID
     * @return Future containing JsonObject with availability data or not found
     */
    @Override
    public Future<JsonObject> availabilityGetByDevice(String deviceId)
    {
        try
        {
            var sql = """
                    SELECT da.device_id, da.total_checks, da.successful_checks, da.failed_checks,
                           da.availability_percent, da.last_check_time, da.last_success_time, da.last_failure_time,
                           da.current_status, da.updated_at,
                           d.device_name, d.ip_address::text as ip_address, d.device_type, d.is_provisioned
                    FROM device_availability da
                    JOIN devices d ON da.device_id = d.device_id
                    WHERE da.device_id = $1 AND d.is_deleted = false
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceId)))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            return new JsonObject().put("found", false);
                        }

                        var row = rows.iterator().next();

                        // Removing CIDR notation
                        var ipAddr = row.getString("ip_address");

                        if (ipAddr != null && ipAddr.contains("/"))
                        {
                            ipAddr = ipAddr.split("/")[0]; // Remove CIDR notation
                        }

                        return new JsonObject()
                                .put("found", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", ipAddr)
                                .put("device_type", row.getString("device_type"))
                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                .put("total_checks", row.getInteger("total_checks"))
                                .put("successful_checks", row.getInteger("successful_checks"))
                                .put("failed_checks", row.getInteger("failed_checks"))
                                .put("availability_percent", row.getBigDecimal("availability_percent"))
                                .put("last_check_time", row.getLocalDateTime("last_check_time") != null ?
                                    row.getLocalDateTime("last_check_time").toString() : null)
                                .put("last_success_time", row.getLocalDateTime("last_success_time") != null ?
                                    row.getLocalDateTime("last_success_time").toString() : null)
                                .put("last_failure_time", row.getLocalDateTime("last_failure_time") != null ?
                                    row.getLocalDateTime("last_failure_time").toString() : null)
                                .put("current_status", row.getString("current_status"))
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in availabilityGetByDevice service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Update device availability status (used by AvailabilityVerticle)
     * Updates current_status and last check timestamps
     *
     * @param deviceId Device ID
     * @param status Device status (up/down)
     * @return Future containing JsonObject with update result
     */
    @Override
    public Future<JsonObject> availabilityUpdate(String deviceId, String status)
    {
        try
        {
            // Normalize status to lowercase
            var normalizedStatus = status.toLowerCase();

            if (!normalizedStatus.equals("up") && !normalizedStatus.equals("down"))
            {
                return Future.failedFuture(new Exception("Status must be 'up' or 'down'"));
            }

            // First verify device exists and is active
            var deviceCheckSql = """
                    SELECT device_name, ip_address::text as ip_address
                    FROM devices
                    WHERE device_id = $1 AND is_deleted = false
                    """;

            return dbHelper.executePreparedQuery(deviceCheckSql, Tuple.of(UUID.fromString(deviceId)))
                    .compose(deviceRows ->
                    {
                        if (deviceRows.size() == 0)
                        {
                            return Future.failedFuture(new Exception("Device not found or deleted"));
                        }

                        var deviceRow = deviceRows.iterator().next();

                        var deviceName = deviceRow.getString("device_name");

                        var ipAddressRaw = deviceRow.getString("ip_address");

                        // Removing CIDR notation
                        final var ipAddress = (ipAddressRaw != null && ipAddressRaw.contains("/"))
                                ? ipAddressRaw.split("/")[0]  // Remove CIDR notation
                                : ipAddressRaw;

                        var now = LocalDateTime.now();

                        var successfulIncrement = normalizedStatus.equals("up") ? 1 : 0;

                        var failedIncrement = normalizedStatus.equals("down") ? 1 : 0;

                        // Update availability record
                        var updateSql = """
                                UPDATE device_availability SET
                                    total_checks = total_checks + 1,
                                    successful_checks = successful_checks + $1,
                                    failed_checks = failed_checks + $2,
                                    availability_percent = ROUND(
                                        (successful_checks + $1) * 100.0 / (total_checks + 1), 2
                                    ),
                                    last_check_time = $3,
                                    last_success_time = CASE WHEN $1 = 1 THEN $3 ELSE last_success_time END,
                                    last_failure_time = CASE WHEN $2 = 1 THEN $3 ELSE last_failure_time END,
                                    current_status = CASE
                                        WHEN current_status != $4 THEN $4
                                        ELSE current_status
                                    END
                                WHERE device_id = $5
                                RETURNING device_id, total_checks, successful_checks, failed_checks, availability_percent,
                                         last_check_time, current_status, updated_at
                                """;

                        return dbHelper.executePreparedQuery(updateSql, Tuple.of(successfulIncrement, failedIncrement, now, normalizedStatus, UUID.fromString(deviceId)))
                                .map(rows ->
                                {
                                    if (rows.size() == 0)
                                    {
                                        return new JsonObject()
                                            .put("success", false)
                                            .put("message", "Device availability record not found");
                                    }

                                    var row = rows.iterator().next();

                                    return new JsonObject()
                                            .put("success", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("device_name", deviceName)
                                            .put("ip_address", ipAddress)
                                            .put("total_checks", row.getInteger("total_checks"))
                                            .put("successful_checks", row.getInteger("successful_checks"))
                                            .put("failed_checks", row.getInteger("failed_checks"))
                                            .put("availability_percent", row.getBigDecimal("availability_percent"))
                                            .put("last_check_time", row.getLocalDateTime("last_check_time").toString())
                                            .put("current_status", row.getString("current_status"))
                                            .put("updated_at", row.getLocalDateTime("updated_at").toString())
                                            .put("message", "Device status updated successfully");
                                });
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in availabilityUpdateDeviceStatus service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Delete device availability by device ID (when device is permanently deleted)
     *
     * @param deviceId Device ID
     * @return Future containing JsonObject with deletion result
     */
    @Override
    public Future<JsonObject> availabilityDeleteByDevice(String deviceId)
    {
        try
        {
            var sql = """
                    DELETE FROM device_availability
                    WHERE device_id = $1
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceId)))
                    .map(rows ->
                    {
                        var deletedCount = rows.rowCount();

                        return new JsonObject()
                                .put("success", true)
                                .put("device_id", deviceId)
                                .put("deleted", deletedCount > 0)
                                .put("message", deletedCount > 0 ?
                                    "Device availability status deleted successfully" :
                                    "No availability status found for device");
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in availabilityDeleteByDevice service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}

