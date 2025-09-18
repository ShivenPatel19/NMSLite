package com.nmslite.services.impl;

import com.nmslite.services.MetricsService;
import io.vertx.core.Future;
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
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * MetricsServiceImpl - Implementation of MetricsService
 * 
 * Provides metrics management operations including:
 * - Metrics CRUD operations
 * - Time-series data management
 * - Device performance tracking
 * - Metrics aggregation and cleanup
 * - Dashboard data preparation
 */
public class MetricsServiceImpl implements MetricsService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public MetricsServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public Future<JsonObject> metricsCreate(JsonObject metricsData) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String deviceId = metricsData.getString("device_id");
            Boolean success = metricsData.getBoolean("success");
            Integer durationMs = metricsData.getInteger("duration_ms");
            Double cpuUsage = metricsData.getDouble("cpu_usage_percent");
            Double memoryUsage = metricsData.getDouble("memory_usage_percent");
            Long memoryTotal = metricsData.getLong("memory_total_bytes");
            Long memoryUsed = metricsData.getLong("memory_used_bytes");
            Long memoryFree = metricsData.getLong("memory_free_bytes");
            Double diskUsage = metricsData.getDouble("disk_usage_percent");
            Long diskTotal = metricsData.getLong("disk_total_bytes");
            Long diskUsed = metricsData.getLong("disk_used_bytes");
            Long diskFree = metricsData.getLong("disk_free_bytes");
            String errorMessage = metricsData.getString("error_message");

            if (deviceId == null || success == null) {
                blockingPromise.fail(new IllegalArgumentException("Device ID and success status are required"));
                return;
            }

            String sql = """
                    INSERT INTO metrics (device_id, success, duration_ms, cpu_usage_percent, memory_usage_percent, 
                                       memory_total_bytes, memory_used_bytes, memory_free_bytes, disk_usage_percent, 
                                       disk_total_bytes, disk_used_bytes, disk_free_bytes, error_message)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
                    RETURNING metric_id, device_id, success, timestamp, duration_ms, cpu_usage_percent, memory_usage_percent, 
                             disk_usage_percent, error_message
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId), success, durationMs, cpuUsage, memoryUsage, 
                                    memoryTotal, memoryUsed, memoryFree, diskUsage, diskTotal, diskUsed, diskFree, errorMessage))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("metric_id", row.getUUID("metric_id").toString())
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("success_status", row.getBoolean("success"))
                                .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                .put("duration_ms", row.getInteger("duration_ms"))
                                .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                .put("error_message", row.getString("error_message"))
                                .put("message", "Metrics data stored successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to create metrics", cause);
                        if (cause.getMessage().contains("foreign key")) {
                            blockingPromise.fail(new IllegalArgumentException("Invalid device ID"));
                        } else if (cause.getMessage().contains("chk_duration_positive")) {
                            blockingPromise.fail(new IllegalArgumentException("Duration must be positive"));
                        } else if (cause.getMessage().contains("chk_cpu_range") || 
                                 cause.getMessage().contains("chk_memory_range") || 
                                 cause.getMessage().contains("chk_disk_range")) {
                            blockingPromise.fail(new IllegalArgumentException("Usage percentages must be between 0 and 100"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> metricsList(int page, int pageSize, String deviceId) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            // Build SQL with optional device filter and ensure device is active
            StringBuilder sqlBuilder = new StringBuilder("""
                    SELECT m.metric_id, m.device_id, m.success, m.timestamp, m.duration_ms, 
                           m.cpu_usage_percent, m.memory_usage_percent, m.memory_total_bytes, m.memory_used_bytes, 
                           m.memory_free_bytes, m.disk_usage_percent, m.disk_total_bytes, m.disk_used_bytes, 
                           m.disk_free_bytes, m.error_message, d.device_name, d.ip_address
                    FROM metrics m
                    JOIN devices d ON m.device_id = d.device_id
                    WHERE d.is_deleted = false
                    """);

            JsonArray params = new JsonArray();
            int paramIndex = 1;

            if (deviceId != null) {
                sqlBuilder.append(" AND m.device_id = $").append(paramIndex++);
                params.add(UUID.fromString(deviceId));
            }

            sqlBuilder.append(" ORDER BY m.timestamp DESC");
            sqlBuilder.append(" LIMIT $").append(paramIndex++);
            sqlBuilder.append(" OFFSET $").append(paramIndex);
            params.add(pageSize);
            params.add(page * pageSize);

            // Count query for pagination
            StringBuilder countSqlBuilder = new StringBuilder("""
                    SELECT COUNT(*) as total
                    FROM metrics m
                    JOIN devices d ON m.device_id = d.device_id
                    WHERE d.is_deleted = false
                    """);

            JsonArray countParams = new JsonArray();
            if (deviceId != null) {
                countSqlBuilder.append(" AND m.device_id = $1");
                countParams.add(UUID.fromString(deviceId));
            }

            // Execute count query first
            pgPool.preparedQuery(countSqlBuilder.toString())
                    .execute(countParams.size() > 0 ? Tuple.from(countParams.getList()) : Tuple.tuple())
                    .onSuccess(countRows -> {
                        long total = countRows.iterator().next().getLong("total");
                        
                        // Execute main query
                        pgPool.preparedQuery(sqlBuilder.toString())
                                .execute(Tuple.from(params.getList()))
                                .onSuccess(rows -> {
                                    JsonArray metrics = new JsonArray();
                                    for (Row row : rows) {
                                        JsonObject metric = new JsonObject()
                                                .put("metric_id", row.getUUID("metric_id").toString())
                                                .put("device_id", row.getUUID("device_id").toString())
                                                .put("device_name", row.getString("device_name"))
                                                .put("ip_address", row.getString("ip_address"))
                                                .put("success", row.getBoolean("success"))
                                                .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                                .put("duration_ms", row.getInteger("duration_ms"))
                                                .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                                .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                                .put("memory_total_bytes", row.getLong("memory_total_bytes"))
                                                .put("memory_used_bytes", row.getLong("memory_used_bytes"))
                                                .put("memory_free_bytes", row.getLong("memory_free_bytes"))
                                                .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                                .put("disk_total_bytes", row.getLong("disk_total_bytes"))
                                                .put("disk_used_bytes", row.getLong("disk_used_bytes"))
                                                .put("disk_free_bytes", row.getLong("disk_free_bytes"))
                                                .put("error_message", row.getString("error_message"));
                                        metrics.add(metric);
                                    }

                                    JsonObject result = new JsonObject()
                                            .put("metrics", metrics)
                                            .put("pagination", new JsonObject()
                                                    .put("page", page)
                                                    .put("pageSize", pageSize)
                                                    .put("total", total)
                                                    .put("totalPages", (int) Math.ceil((double) total / pageSize)));
                                    blockingPromise.complete(result);
                                })
                                .onFailure(cause -> {
                                    logger.error("Failed to get metrics list", cause);
                                    blockingPromise.fail(cause);
                                });
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to count metrics", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> metricsGet(String metricId) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT m.metric_id, m.device_id, m.success, m.timestamp, m.duration_ms, 
                           m.cpu_usage_percent, m.memory_usage_percent, m.memory_total_bytes, m.memory_used_bytes, 
                           m.memory_free_bytes, m.disk_usage_percent, m.disk_total_bytes, m.disk_used_bytes, 
                           m.disk_free_bytes, m.error_message, d.device_name, d.ip_address
                    FROM metrics m
                    JOIN devices d ON m.device_id = d.device_id
                    WHERE m.metric_id = $1 AND d.is_deleted = false
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(metricId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject().put("found", false));
                            return;
                        }

                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("metric_id", row.getUUID("metric_id").toString())
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("success", row.getBoolean("success"))
                                .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                .put("duration_ms", row.getInteger("duration_ms"))
                                .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                .put("memory_total_bytes", row.getLong("memory_total_bytes"))
                                .put("memory_used_bytes", row.getLong("memory_used_bytes"))
                                .put("memory_free_bytes", row.getLong("memory_free_bytes"))
                                .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                .put("disk_total_bytes", row.getLong("disk_total_bytes"))
                                .put("disk_used_bytes", row.getLong("disk_used_bytes"))
                                .put("disk_free_bytes", row.getLong("disk_free_bytes"))
                                .put("error_message", row.getString("error_message"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get metric by ID", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> metricsDeleteOlderThan(int olderThanDays) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            if (olderThanDays <= 0) {
                blockingPromise.fail(new IllegalArgumentException("Days must be positive"));
                return;
            }

            String sql = """
                    DELETE FROM metrics
                    WHERE timestamp < CURRENT_TIMESTAMP - INTERVAL '%d days'
                    """.formatted(olderThanDays);

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        int deletedCount = rows.rowCount();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("deleted_count", deletedCount)
                                .put("older_than_days", olderThanDays)
                                .put("message", "Metrics cleanup completed successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete old metrics", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> metricsDeleteAllByDevice(String deviceId) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    DELETE FROM metrics
                    WHERE device_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows -> {
                        int deletedCount = rows.rowCount();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_id", deviceId)
                                .put("deleted_count", deletedCount)
                                .put("message", "All metrics for device deleted successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete metrics for device", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> metricsListByDevice(String deviceId, int page, int pageSize) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
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
                        String ipAddress = deviceRow.getString("ip_address");

                        // Count query
                        String countSql = """
                                SELECT COUNT(*) as total
                                FROM metrics
                                WHERE device_id = $1
                                """;

                        pgPool.preparedQuery(countSql)
                                .execute(Tuple.of(UUID.fromString(deviceId)))
                                .onSuccess(countRows -> {
                                    long total = countRows.iterator().next().getLong("total");

                                    // Main query
                                    String sql = """
                                            SELECT metric_id, device_id, success, timestamp, duration_ms,
                                                   cpu_usage_percent, memory_usage_percent, memory_total_bytes, memory_used_bytes,
                                                   memory_free_bytes, disk_usage_percent, disk_total_bytes, disk_used_bytes,
                                                   disk_free_bytes, error_message
                                            FROM metrics
                                            WHERE device_id = $1
                                            ORDER BY timestamp DESC
                                            LIMIT $2 OFFSET $3
                                            """;

                                    pgPool.preparedQuery(sql)
                                            .execute(Tuple.of(UUID.fromString(deviceId), pageSize, page * pageSize))
                                            .onSuccess(rows -> {
                                                JsonArray metrics = new JsonArray();
                                                for (Row row : rows) {
                                                    JsonObject metric = new JsonObject()
                                                            .put("metric_id", row.getUUID("metric_id").toString())
                                                            .put("device_id", row.getUUID("device_id").toString())
                                                            .put("device_name", deviceName)
                                                            .put("ip_address", ipAddress)
                                                            .put("success", row.getBoolean("success"))
                                                            .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                                            .put("duration_ms", row.getInteger("duration_ms"))
                                                            .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                                            .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                                            .put("memory_total_bytes", row.getLong("memory_total_bytes"))
                                                            .put("memory_used_bytes", row.getLong("memory_used_bytes"))
                                                            .put("memory_free_bytes", row.getLong("memory_free_bytes"))
                                                            .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                                            .put("disk_total_bytes", row.getLong("disk_total_bytes"))
                                                            .put("disk_used_bytes", row.getLong("disk_used_bytes"))
                                                            .put("disk_free_bytes", row.getLong("disk_free_bytes"))
                                                            .put("error_message", row.getString("error_message"));
                                                    metrics.add(metric);
                                                }

                                                JsonObject result = new JsonObject()
                                                        .put("device_id", deviceId)
                                                        .put("device_name", deviceName)
                                                        .put("ip_address", ipAddress)
                                                        .put("metrics", metrics)
                                                        .put("pagination", new JsonObject()
                                                                .put("page", page)
                                                                .put("pageSize", pageSize)
                                                                .put("total", total)
                                                                .put("totalPages", (int) Math.ceil((double) total / pageSize)));
                                                blockingPromise.complete(result);
                                            })
                                            .onFailure(cause -> {
                                                logger.error("Failed to get device metrics", cause);
                                                blockingPromise.fail(cause);
                                            });
                                })
                                .onFailure(cause -> {
                                    logger.error("Failed to count device metrics", cause);
                                    blockingPromise.fail(cause);
                                });
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to verify device", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> metricsGetLatestByDevice(String deviceId) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT m.metric_id, m.device_id, m.success, m.timestamp, m.duration_ms,
                           m.cpu_usage_percent, m.memory_usage_percent, m.memory_total_bytes, m.memory_used_bytes,
                           m.memory_free_bytes, m.disk_usage_percent, m.disk_total_bytes, m.disk_used_bytes,
                           m.disk_free_bytes, m.error_message, d.device_name, d.ip_address
                    FROM metrics m
                    JOIN devices d ON m.device_id = d.device_id
                    WHERE m.device_id = $1 AND d.is_deleted = false
                    ORDER BY m.timestamp DESC
                    LIMIT 1
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
                                .put("metric_id", row.getUUID("metric_id").toString())
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("success", row.getBoolean("success"))
                                .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                .put("duration_ms", row.getInteger("duration_ms"))
                                .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                .put("memory_total_bytes", row.getLong("memory_total_bytes"))
                                .put("memory_used_bytes", row.getLong("memory_used_bytes"))
                                .put("memory_free_bytes", row.getLong("memory_free_bytes"))
                                .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                .put("disk_total_bytes", row.getLong("disk_total_bytes"))
                                .put("disk_used_bytes", row.getLong("disk_used_bytes"))
                                .put("disk_free_bytes", row.getLong("disk_free_bytes"))
                                .put("error_message", row.getString("error_message"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get latest metric for device", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonArray> metricsGetLatestAllDevices() {
        Promise<JsonArray> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT DISTINCT ON (d.device_id)
                           m.metric_id, m.device_id, m.success, m.timestamp, m.duration_ms,
                           m.cpu_usage_percent, m.memory_usage_percent, m.memory_total_bytes, m.memory_used_bytes,
                           m.memory_free_bytes, m.disk_usage_percent, m.disk_total_bytes, m.disk_used_bytes,
                           m.disk_free_bytes, m.error_message, d.device_name, d.ip_address, d.device_type
                    FROM devices d
                    LEFT JOIN metrics m ON d.device_id = m.device_id
                    WHERE d.is_deleted = false AND d.is_monitoring_enabled = true
                    ORDER BY d.device_id, m.timestamp DESC NULLS LAST
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        JsonArray metrics = new JsonArray();
                        for (Row row : rows) {
                            JsonObject metric = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("device_type", row.getString("device_type"));

                            // Add metric data if available
                            if (row.getUUID("metric_id") != null) {
                                metric.put("metric_id", row.getUUID("metric_id").toString())
                                      .put("success", row.getBoolean("success"))
                                      .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                      .put("duration_ms", row.getInteger("duration_ms"))
                                      .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                      .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                      .put("memory_total_bytes", row.getLong("memory_total_bytes"))
                                      .put("memory_used_bytes", row.getLong("memory_used_bytes"))
                                      .put("memory_free_bytes", row.getLong("memory_free_bytes"))
                                      .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                      .put("disk_total_bytes", row.getLong("disk_total_bytes"))
                                      .put("disk_used_bytes", row.getLong("disk_used_bytes"))
                                      .put("disk_free_bytes", row.getLong("disk_free_bytes"))
                                      .put("error_message", row.getString("error_message"))
                                      .put("has_metrics", true);
                            } else {
                                metric.put("has_metrics", false)
                                      .put("message", "No metrics data available");
                            }
                            metrics.add(metric);
                        }
                        blockingPromise.complete(metrics);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get latest metrics for all devices", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonArray> metricsGetByDeviceTimeRange(String deviceId, String startTime, String endTime) {
        Promise<JsonArray> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            try {
                // Parse and validate time strings
                LocalDateTime start = LocalDateTime.parse(startTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                LocalDateTime end = LocalDateTime.parse(endTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                if (start.isAfter(end)) {
                    blockingPromise.fail(new IllegalArgumentException("Start time must be before end time"));
                    return;
                }

                String sql = """
                        SELECT m.metric_id, m.device_id, m.success, m.timestamp, m.duration_ms,
                               m.cpu_usage_percent, m.memory_usage_percent, m.memory_total_bytes, m.memory_used_bytes,
                               m.memory_free_bytes, m.disk_usage_percent, m.disk_total_bytes, m.disk_used_bytes,
                               m.disk_free_bytes, m.error_message, d.device_name, d.ip_address
                        FROM metrics m
                        JOIN devices d ON m.device_id = d.device_id
                        WHERE m.device_id = $1 AND d.is_deleted = false
                        AND m.timestamp >= $2 AND m.timestamp <= $3
                        ORDER BY m.timestamp ASC
                        """;

                pgPool.preparedQuery(sql)
                        .execute(Tuple.of(UUID.fromString(deviceId), start, end))
                        .onSuccess(rows -> {
                            JsonArray metrics = new JsonArray();
                            for (Row row : rows) {
                                JsonObject metric = new JsonObject()
                                        .put("metric_id", row.getUUID("metric_id").toString())
                                        .put("device_id", row.getUUID("device_id").toString())
                                        .put("device_name", row.getString("device_name"))
                                        .put("ip_address", row.getString("ip_address"))
                                        .put("success", row.getBoolean("success"))
                                        .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                        .put("duration_ms", row.getInteger("duration_ms"))
                                        .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                        .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                        .put("memory_total_bytes", row.getLong("memory_total_bytes"))
                                        .put("memory_used_bytes", row.getLong("memory_used_bytes"))
                                        .put("memory_free_bytes", row.getLong("memory_free_bytes"))
                                        .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                        .put("disk_total_bytes", row.getLong("disk_total_bytes"))
                                        .put("disk_used_bytes", row.getLong("disk_used_bytes"))
                                        .put("disk_free_bytes", row.getLong("disk_free_bytes"))
                                        .put("error_message", row.getString("error_message"));
                                metrics.add(metric);
                            }
                            blockingPromise.complete(metrics);
                        })
                        .onFailure(cause -> {
                            logger.error("Failed to get metrics by time range", cause);
                            blockingPromise.fail(cause);
                        });
            } catch (Exception e) {
                blockingPromise.fail(new IllegalArgumentException("Invalid time format. Use ISO 8601 format (yyyy-MM-ddTHH:mm:ss)"));
            }
        }, promise);

        return promise.future();
    }
}
