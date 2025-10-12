package com.nmslite.services.impl;

import com.nmslite.services.MetricsService;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.sqlclient.Pool;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * MetricsServiceImpl - Implementation of MetricsService

 * Provides metrics management operations including:
 * - Metrics creation for polling
 * - Metrics retrieval by device
 * - Metrics cleanup operations
 */
public class MetricsServiceImpl implements MetricsService
{

    private static final Logger logger = LoggerFactory.getLogger(MetricsServiceImpl.class);

    private final Pool pgPool;

    /**
     * Constructor for MetricsServiceImpl
     *
     * @param pgPool PostgresSQL connection pool
     */
    public MetricsServiceImpl(Pool pgPool)
    {
        this.pgPool = pgPool;
    }

    /**
     * Create new metrics record (used by PollingMetricsVerticle)
     *
     * @param metricsData Metrics data
     * @return Future containing JsonObject with creation result
     */
    @Override
    public Future<JsonObject> metricsCreate(JsonObject metricsData)
    {
        var promise = Promise.<JsonObject>promise();

        var deviceId = metricsData.getString("device_id");

        var cpuUsage = metricsData.getDouble("cpu_usage_percent");

        var memoryUsage = metricsData.getDouble("memory_usage_percent");

        var memoryTotal = metricsData.getLong("memory_total_bytes");

        var memoryUsed = metricsData.getLong("memory_used_bytes");

        var memoryFree = metricsData.getLong("memory_free_bytes");

        var diskUsage = metricsData.getDouble("disk_usage_percent");

        var diskTotal = metricsData.getLong("disk_total_bytes");

        var diskUsed = metricsData.getLong("disk_used_bytes");

        var diskFree = metricsData.getLong("disk_free_bytes");

        var sql = """
                INSERT INTO metrics (device_id, cpu_usage_percent, memory_usage_percent,
                                   memory_total_bytes, memory_used_bytes, memory_free_bytes, disk_usage_percent,
                                   disk_total_bytes, disk_used_bytes, disk_free_bytes)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                RETURNING metric_id, device_id, timestamp, cpu_usage_percent, memory_usage_percent,
                         disk_usage_percent
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(deviceId), cpuUsage, memoryUsage,
                                memoryTotal, memoryUsed, memoryFree, diskUsage, diskTotal, diskUsed, diskFree))
                .onSuccess(rows ->
                {
                    var row = rows.iterator().next();

                    var result = new JsonObject()
                            .put("success", true)
                            .put("metric_id", row.getUUID("metric_id").toString())
                            .put("device_id", row.getUUID("device_id").toString())
                            .put("timestamp", row.getLocalDateTime("timestamp").toString())
                            .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                            .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                            .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                            .put("message", "Metrics data stored successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to create metrics", cause);

                    if (cause.getMessage().contains("foreign key"))
                    {
                        promise.fail(new IllegalArgumentException("Invalid device ID"));
                    }
                    else if (cause.getMessage().contains("chk_cpu_range") ||
                             cause.getMessage().contains("chk_memory_range") ||
                             cause.getMessage().contains("chk_disk_range"))
                    {
                        promise.fail(new IllegalArgumentException("Usage percentages must be between 0 and 100"));
                    }
                    else
                    {
                        promise.fail(cause);
                    }
                });

        return promise.future();
    }

    /**
     * Get all metrics for a specific device ordered by timestamp DESC (latest to old)
     *
     * @param deviceId Device ID
     * @return Future containing JsonArray of all metrics for the device
     */
    @Override
    public Future<JsonArray> metricsGetAllByDevice(String deviceId)
    {
        var promise = Promise.<JsonArray>promise();

        var sql = """
                SELECT m.metric_id, m.device_id, m.timestamp,
                       m.cpu_usage_percent, m.memory_usage_percent, m.memory_total_bytes, m.memory_used_bytes,
                       m.memory_free_bytes, m.disk_usage_percent, m.disk_total_bytes, m.disk_used_bytes,
                       m.disk_free_bytes,
                       d.device_name, d.ip_address::text as ip_address, d.device_type
                FROM metrics m
                JOIN devices d ON m.device_id = d.device_id
                WHERE m.device_id = $1 AND d.is_deleted = false
                ORDER BY m.timestamp DESC
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(deviceId)))
                .onSuccess(rows ->
                {
                    var metrics = new JsonArray();

                    for (var row : rows)
                    {
                        var ipAddr = row.getString("ip_address");

                        // Removing CIDR notation
                        if (ipAddr != null && ipAddr.contains("/"))
                        {
                            ipAddr = ipAddr.split("/")[0]; // Remove CIDR notation
                        }

                        var metric = new JsonObject()
                                .put("metric_id", row.getUUID("metric_id").toString())
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", ipAddr)
                                .put("device_type", row.getString("device_type"))
                                .put("timestamp", row.getLocalDateTime("timestamp").toString())
                                .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                                .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                                .put("memory_total_bytes", row.getLong("memory_total_bytes"))
                                .put("memory_used_bytes", row.getLong("memory_used_bytes"))
                                .put("memory_free_bytes", row.getLong("memory_free_bytes"))
                                .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                                .put("disk_total_bytes", row.getLong("disk_total_bytes"))
                                .put("disk_used_bytes", row.getLong("disk_used_bytes"))
                                .put("disk_free_bytes", row.getLong("disk_free_bytes"));

                        metrics.add(metric);
                    }

                    promise.complete(metrics);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get metrics for device: {}", deviceId, cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Delete all metrics for a device (when device is deleted)
     *
     * @param deviceId Device ID
     * @return Future containing JsonObject with deletion result
     */
    @Override
    public Future<JsonObject> metricsDeleteAllByDevice(String deviceId)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
                DELETE FROM metrics
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
                            .put("deleted_count", deletedCount)
                            .put("message", "All metrics for device deleted successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to delete metrics for device", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

}

