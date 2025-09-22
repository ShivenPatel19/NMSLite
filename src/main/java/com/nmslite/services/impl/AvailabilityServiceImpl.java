package com.nmslite.services.impl;

import com.nmslite.services.AvailabilityService;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AvailabilityServiceImpl - Implementation of AvailabilityService
 * 
 * Provides device availability status operations including:
 * - Current device availability status tracking
 * - Real-time status updates
 * - Device status monitoring
 * - Dashboard status views
 */
public class AvailabilityServiceImpl implements AvailabilityService {

    private static final Logger logger = LoggerFactory.getLogger(AvailabilityServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public AvailabilityServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public void availabilityListAll(Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT da.device_id, da.total_checks, da.successful_checks, da.failed_checks, 
                           da.availability_percent, da.last_check_time, da.last_success_time, da.last_failure_time,
                           da.current_status, da.status_since, da.updated_at,
                           d.device_name, d.ip_address, d.device_type, d.is_monitoring_enabled
                    FROM device_availability da
                    JOIN devices d ON da.device_id = d.device_id
                    WHERE d.is_deleted = false
                    ORDER BY d.device_name
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        JsonArray availabilities = new JsonArray();
                        for (Row row : rows) {
                            JsonObject availability = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getValue("ip_address").toString())
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
                            availabilities.add(availability);
                        }
                        blockingPromise.complete(availabilities);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get all device availability statuses", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void availabilityCreateOrUpdate(JsonObject availabilityData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String deviceId = availabilityData.getString("device_id");
            String status = availabilityData.getString("status");
            Long responseTime = availabilityData.getLong("response_time");
            LocalDateTime checkedAt = availabilityData.getString("checked_at") != null ? 
                LocalDateTime.parse(availabilityData.getString("checked_at")) : LocalDateTime.now();

            if (deviceId == null || status == null) {
                blockingPromise.fail(new IllegalArgumentException("Device ID and status are required"));
                return;
            }

            // Normalize status to lowercase
            String normalizedStatus = status.toLowerCase();
            if (!normalizedStatus.equals("up") && !normalizedStatus.equals("down")) {
                blockingPromise.fail(new IllegalArgumentException("Status must be 'up' or 'down'"));
                return;
            }

            // First verify device exists and is active
            String deviceCheckSql = """
                    SELECT device_name, ip_address 
                    FROM devices 
                    WHERE device_id = $1 AND is_deleted = false
                    """;

            pgPool.preparedQuery(deviceCheckSql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(deviceRows -> {
                        if (deviceRows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Device not found or deleted"));
                            return;
                        }

                        Row deviceRow = deviceRows.iterator().next();
                        String deviceName = deviceRow.getString("device_name");
                        String ipAddress = deviceRow.getValue("ip_address").toString();

                        // Upsert availability record
                        String upsertSql = """
                                INSERT INTO device_availability (device_id, total_checks, successful_checks, failed_checks, 
                                                                availability_percent, last_check_time, last_success_time, 
                                                                last_failure_time, current_status, status_since, updated_at)
                                VALUES ($1, 1, $2, $3, $4, $5, $6, $7, $8, $5, $5)
                                ON CONFLICT (device_id) DO UPDATE SET
                                    total_checks = device_availability.total_checks + 1,
                                    successful_checks = device_availability.successful_checks + $2,
                                    failed_checks = device_availability.failed_checks + $3,
                                    availability_percent = ROUND(
                                        (device_availability.successful_checks + $2) * 100.0 / 
                                        (device_availability.total_checks + 1), 2
                                    ),
                                    last_check_time = $5,
                                    last_success_time = CASE WHEN $2 = 1 THEN $5 ELSE device_availability.last_success_time END,
                                    last_failure_time = CASE WHEN $3 = 1 THEN $5 ELSE device_availability.last_failure_time END,
                                    current_status = CASE 
                                        WHEN device_availability.current_status != $8 THEN $8 
                                        ELSE device_availability.current_status 
                                    END,
                                    status_since = CASE 
                                        WHEN device_availability.current_status != $8 THEN $5 
                                        ELSE device_availability.status_since 
                                    END,
                                    updated_at = $5
                                RETURNING device_id, total_checks, successful_checks, failed_checks, availability_percent,
                                         last_check_time, current_status, status_since, updated_at
                                """;

                        int successfulIncrement = normalizedStatus.equals("up") ? 1 : 0;
                        int failedIncrement = normalizedStatus.equals("down") ? 1 : 0;
                        double initialAvailability = normalizedStatus.equals("up") ? 100.0 : 0.0;
                        LocalDateTime successTime = normalizedStatus.equals("up") ? checkedAt : null;
                        LocalDateTime failureTime = normalizedStatus.equals("down") ? checkedAt : null;

                        pgPool.preparedQuery(upsertSql)
                                .execute(Tuple.of(UUID.fromString(deviceId), successfulIncrement, failedIncrement, 
                                                initialAvailability, checkedAt, successTime, failureTime, normalizedStatus))
                                .onSuccess(rows -> {
                                    Row row = rows.iterator().next();
                                    JsonObject result = new JsonObject()
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
                                            .put("message", "Device availability status updated successfully");
                                    blockingPromise.complete(result);
                                })
                                .onFailure(cause -> {
                                    logger.error("Failed to create or update availability", cause);
                                    if (cause.getMessage().contains("chk_availability_range")) {
                                        blockingPromise.fail(new IllegalArgumentException("Availability percentage must be between 0 and 100"));
                                    } else if (cause.getMessage().contains("chk_checks_consistency")) {
                                        blockingPromise.fail(new IllegalArgumentException("Check counts consistency error"));
                                    } else {
                                        blockingPromise.fail(cause);
                                    }
                                });
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to verify device", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void availabilityGetByDevice(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT da.device_id, da.total_checks, da.successful_checks, da.failed_checks, 
                           da.availability_percent, da.last_check_time, da.last_success_time, da.last_failure_time,
                           da.current_status, da.status_since, da.updated_at,
                           d.device_name, d.ip_address, d.device_type, d.is_monitoring_enabled
                    FROM device_availability da
                    JOIN devices d ON da.device_id = d.device_id
                    WHERE da.device_id = $1 AND d.is_deleted = false
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject().put("found", false));
                            return;
                        }

                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getValue("ip_address").toString())
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
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get device availability", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void availabilityDeleteByDevice(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    DELETE FROM device_availability
                    WHERE device_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows -> {
                        int deletedCount = rows.rowCount();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_id", deviceId)
                                .put("deleted", deletedCount > 0)
                                .put("message", deletedCount > 0 ?
                                    "Device availability status deleted successfully" :
                                    "No availability status found for device");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete device availability", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }



    @Override
    public void availabilityUpdateDeviceStatus(String deviceId, String status, Long responseTime, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            if (status == null) {
                blockingPromise.fail(new IllegalArgumentException("Status is required"));
                return;
            }

            // Normalize status to lowercase
            String normalizedStatus = status.toLowerCase();
            if (!normalizedStatus.equals("up") && !normalizedStatus.equals("down")) {
                blockingPromise.fail(new IllegalArgumentException("Status must be 'up' or 'down'"));
                return;
            }

            // First verify device exists and is active
            String deviceCheckSql = """
                    SELECT device_name, ip_address
                    FROM devices
                    WHERE device_id = $1 AND is_deleted = false
                    """;

            pgPool.preparedQuery(deviceCheckSql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(deviceRows -> {
                        if (deviceRows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Device not found or deleted"));
                            return;
                        }

                        Row deviceRow = deviceRows.iterator().next();
                        String deviceName = deviceRow.getString("device_name");
                        String ipAddress = deviceRow.getValue("ip_address").toString();

                        LocalDateTime now = LocalDateTime.now();
                        int successfulIncrement = normalizedStatus.equals("up") ? 1 : 0;
                        int failedIncrement = normalizedStatus.equals("down") ? 1 : 0;

                        // Update availability record
                        String updateSql = """
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
                                    END,
                                    updated_at = $3
                                WHERE device_id = $5
                                RETURNING device_id, total_checks, successful_checks, failed_checks, availability_percent,
                                         last_check_time, current_status, status_since, updated_at
                                """;

                        pgPool.preparedQuery(updateSql)
                                .execute(Tuple.of(successfulIncrement, failedIncrement, now, normalizedStatus, UUID.fromString(deviceId)))
                                .onSuccess(rows -> {
                                    if (rows.size() == 0) {
                                        blockingPromise.fail(new IllegalArgumentException("Device availability record not found"));
                                        return;
                                    }

                                    Row row = rows.iterator().next();
                                    JsonObject result = new JsonObject()
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
                                    blockingPromise.complete(result);
                                })
                                .onFailure(cause -> {
                                    logger.error("Failed to update device status", cause);
                                    blockingPromise.fail(cause);
                                });
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to verify device", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }


}
