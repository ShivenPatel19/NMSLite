package com.nmslite.services.impl;

import com.nmslite.services.AvailabilityService;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.json.JsonObject;

import io.vertx.sqlclient.Pool;

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
 */
public class AvailabilityServiceImpl implements AvailabilityService
{

    private static final Logger logger = LoggerFactory.getLogger(AvailabilityServiceImpl.class);

    private final Pool pgPool;

    /**
     * Constructor for AvailabilityServiceImpl
     *
     * @param pgPool PostgresSQL connection pool
     */
    public AvailabilityServiceImpl(Pool pgPool)
    {
        this.pgPool = pgPool;
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
        var promise = Promise.<JsonObject>promise();

        var sql = """
                SELECT da.device_id, da.total_checks, da.successful_checks, da.failed_checks,
                       da.availability_percent, da.last_check_time, da.last_success_time, da.last_failure_time,
                       da.current_status, da.status_since, da.updated_at,
                       d.device_name, d.ip_address::text as ip_address, d.device_type, d.is_monitoring_enabled
                FROM device_availability da
                JOIN devices d ON da.device_id = d.device_id
                WHERE da.device_id = $1 AND d.is_deleted = false
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(deviceId)))
                .onSuccess(rows ->
                {
                    if (rows.size() == 0)
                    {
                        promise.complete(new JsonObject().put("found", false));

                        return;
                    }

                    var row = rows.iterator().next();

                    var ipAddr = row.getString("ip_address");

                    if (ipAddr != null && ipAddr.contains("/"))
                    {
                        ipAddr = ipAddr.split("/")[0]; // Remove CIDR notation
                    }

                    var result = new JsonObject()
                            .put("found", true)
                            .put("device_id", row.getUUID("device_id").toString())
                            .put("device_name", row.getString("device_name"))
                            .put("ip_address", ipAddr)
                            .put("device_type", row.getString("device_type"))
                            .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
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
                            .put("status_since", row.getLocalDateTime("status_since") != null ?
                                row.getLocalDateTime("status_since").toString() : null)
                            .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                row.getLocalDateTime("updated_at").toString() : null);

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get device availability", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Update device availability status (used by PollingMetricsVerticle)
     *
     * @param deviceId Device ID
     * @param status Device status (up/down)
     * @param responseTime Response time in milliseconds
     * @return Future containing JsonObject with status update result
     */
    @Override
    public Future<JsonObject> availabilityUpdateDeviceStatus(String deviceId, String status, Long responseTime)
    {
        var promise = Promise.<JsonObject>promise();

        // Normalize status to lowercase
        var normalizedStatus = status.toLowerCase();

        if (!normalizedStatus.equals("up") && !normalizedStatus.equals("down"))
        {
            promise.fail(new IllegalArgumentException("Status must be 'up' or 'down'"));

            return promise.future();
        }

        // First verify device exists and is active
        var deviceCheckSql = """
                SELECT device_name, ip_address::text as ip_address
                FROM devices
                WHERE device_id = $1 AND is_deleted = false
                """;

        pgPool.preparedQuery(deviceCheckSql)
                .execute(Tuple.of(UUID.fromString(deviceId)))
                .onSuccess(deviceRows ->
                {
                    if (deviceRows.size() == 0)
                    {
                        promise.fail(new IllegalArgumentException("Device not found or deleted"));

                        return;
                    }

                    var deviceRow = deviceRows.iterator().next();

                    var deviceName = deviceRow.getString("device_name");

                    var ipAddressRaw = deviceRow.getString("ip_address");

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
                                END,
                                status_since = CASE
                                    WHEN current_status != $4 THEN $3
                                    ELSE status_since
                                END
                            WHERE device_id = $5
                            RETURNING device_id, total_checks, successful_checks, failed_checks, availability_percent,
                                     last_check_time, current_status, status_since, updated_at
                            """;

                    pgPool.preparedQuery(updateSql)
                            .execute(Tuple.of(successfulIncrement, failedIncrement, now, normalizedStatus, UUID.fromString(deviceId)))
                            .onSuccess(rows ->
                            {
                                if (rows.size() == 0)
                                {
                                    promise.fail(new IllegalArgumentException("Device availability record not found"));

                                    return;
                                }

                                var row = rows.iterator().next();

                                var result = new JsonObject()
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
                                        .put("status_since", row.getLocalDateTime("status_since").toString())
                                        .put("updated_at", row.getLocalDateTime("updated_at").toString())
                                        .put("response_time", responseTime)
                                        .put("message", "Device status updated successfully");

                                promise.complete(result);
                            })
                            .onFailure(cause ->
                            {
                                logger.error("Failed to update device status", cause);

                                promise.fail(cause);
                            });
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to verify device", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Delete device availability by device ID (when device is deleted)
     *
     * @param deviceId Device ID
     * @return Future containing JsonObject with deletion result
     */
    @Override
    public Future<JsonObject> availabilityDeleteByDevice(String deviceId)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
                DELETE FROM device_availability
                WHERE device_id = $1
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(deviceId)))
                .onSuccess(rows ->
                {
                    var deletedCount = rows.rowCount();

                    var result = new JsonObject()
                            .put("success", true)
                            .put("device_id", deviceId)
                            .put("deleted", deletedCount > 0)
                            .put("message", deletedCount > 0 ?
                                "Device availability status deleted successfully" :
                                "No availability status found for device");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to delete device availability", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

}

