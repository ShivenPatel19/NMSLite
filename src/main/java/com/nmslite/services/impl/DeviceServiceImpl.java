package com.nmslite.services.impl;

import com.nmslite.services.DeviceService;

import com.nmslite.services.DiscoveryProfileService;

import com.nmslite.utils.IPRangeUtil;

import com.nmslite.utils.PasswordUtil;

import io.vertx.core.AsyncResult;

import io.vertx.core.Future;

import io.vertx.core.Handler;

import io.vertx.core.Promise;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.pgclient.PgPool;

import io.vertx.sqlclient.Row;

import io.vertx.sqlclient.RowSet;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import java.util.List;

import java.util.UUID;

/**
 * DeviceServiceImpl - Implementation of DeviceService
 *
 * Provides device management operations including:
 * - Device CRUD operations with soft delete
 * - Device monitoring management
 * - Device provisioning and discovery integration
 * - Device status and availability tracking
 */
public class DeviceServiceImpl implements DeviceService
{

    private static final Logger logger = LoggerFactory.getLogger(DeviceServiceImpl.class);

    private final Vertx vertx;

    private final PgPool pgPool;

    private final JsonObject config;

    /**
     * Constructor for DeviceServiceImpl
     *
     * @param vertx Vert.x instance
     * @param pgPool PostgreSQL connection pool
     */
    public DeviceServiceImpl(Vertx vertx, PgPool pgPool)
    {
        this.vertx = vertx;

        this.pgPool = pgPool;

        this.config = vertx.getOrCreateContext().config();
    }

    /**
     * Get default CPU threshold from config
     *
     * @return Default CPU threshold percentage
     */
    private double getDefaultCpuThreshold()
    {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getDouble("alert.threshold.cpu", 80.0);
    }

    /**
     * Get default memory threshold from config
     *
     * @return Default memory threshold percentage
     */
    private double getDefaultMemoryThreshold()
    {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getDouble("alert.threshold.memory", 85.0);
    }

    /**
     * Get default disk threshold from config
     *
     * @return Default disk threshold percentage
     */
    private double getDefaultDiskThreshold()
    {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getDouble("alert.threshold.disk", 90.0);
    }

    /**
     * Get database blocking timeout from config
     *
     * @return Database blocking timeout in seconds
     */
    private int getDatabaseBlockingTimeout()
    {
        return config.getJsonObject("database", new JsonObject())
                .getInteger("blocking.timeout.seconds", 60);
    }

    /**
     * Get default polling interval from config
     *
     * @return Default polling interval in seconds
     */
    private int getDefaultPollingInterval()
    {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getInteger("device.polling.interval.seconds", 300);
    }

    /**
     * Get default timeout from config
     *
     * @return Default timeout in seconds
     */
    private int getDefaultTimeout()
    {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getInteger("device.timeout.seconds", 300);
    }

    /**
     * Get default retry count from config
     *
     * @return Default retry count
     */
    private int getDefaultRetryCount()
    {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getInteger("retry.count", 2);
    }

    /**
     * List devices by provision status
     *
     * @param isProvisioned Provision status filter
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceListByProvisioned(boolean isProvisioned, Handler<AsyncResult<JsonArray>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds, d.retry_count,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at, d.last_polled_at, d.monitoring_enabled_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE d.is_provisioned = $1 AND d.is_deleted = false
                    ORDER BY d.device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(isProvisioned))
                    .onSuccess(rows ->
                    {
                        JsonArray devices = new JsonArray();

                        for (Row row : rows)
                        {
                            JsonObject device = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("device_type", row.getString("device_type"))
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                    .put("username", row.getString("username"))
                                    .put("credential_profile_name", row.getString("credential_profile_name"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                    .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                    .put("retry_count", row.getInteger("retry_count"))
                                    .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                    .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                    .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                    .put("host_name", row.getString("host_name"))
                                    .put("is_provisioned", row.getBoolean("is_provisioned"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("deleted_at", row.getLocalDateTime("deleted_at") != null ? row.getLocalDateTime("deleted_at").toString() : null)
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ? row.getLocalDateTime("updated_at").toString() : null)
                                    .put("last_polled_at", row.getLocalDateTime("last_polled_at") != null ? row.getLocalDateTime("last_polled_at").toString() : null)
                                    .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ? row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                            devices.add(device);
                        }

                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to list devices by provision status", cause);

                        blockingPromise.fail(cause);
                    });
        }, false, resultHandler);
    }

    /**
     * Delete device (soft delete)
     *
     * @param deviceId Device ID
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceDelete(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String sql = """
                    UPDATE devices
                    SET is_deleted = true, deleted_at = CURRENT_TIMESTAMP
                    WHERE device_id = $1 AND is_deleted = false
                    RETURNING device_id, device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            blockingPromise.fail(new IllegalArgumentException("Device not found or already deleted"));

                            return;
                        }

                        Row row = rows.iterator().next();

                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("message", "Device deleted successfully");

                        blockingPromise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to delete device", cause);

                        blockingPromise.fail(cause);
                    });
        }, false, resultHandler);
    }

    /**
     * Restore deleted device
     *
     * @param deviceId Device ID
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceRestore(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String sql = """
                    UPDATE devices
                    SET is_deleted = false, deleted_at = NULL
                    WHERE device_id = $1 AND is_deleted = true
                    RETURNING device_id, device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            blockingPromise.fail(new IllegalArgumentException("Device not found or not deleted"));

                            return;
                        }

                        Row row = rows.iterator().next();

                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("message", "Device restored successfully");

                        blockingPromise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to restore device", cause);

                        blockingPromise.fail(cause);
                    });
        }, false, resultHandler);
    }

    /**
     * Get device by ID
     *
     * @param deviceId Device ID
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceGetById(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name, cp.password_encrypted,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds, d.retry_count,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at, d.last_polled_at, d.monitoring_enabled_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE d.device_id = $1
                    AND d.is_deleted = false""";

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            blockingPromise.complete(new JsonObject().put("found", false));

                            return;
                        }

                        Row row = rows.iterator().next();

                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("device_type", row.getString("device_type"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("username", row.getString("username"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("password_encrypted", row.getString("password_encrypted"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                .put("host_name", row.getString("host_name"))
                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                .put("is_deleted", row.getBoolean("is_deleted"))
                                .put("deleted_at", row.getLocalDateTime("deleted_at") != null ?
                                        row.getLocalDateTime("deleted_at").toString() : null)
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                .put("last_polled_at", row.getLocalDateTime("last_polled_at") != null ?
                                        row.getLocalDateTime("last_polled_at").toString() : null)
                                .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                        row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                        blockingPromise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to get device by ID", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    /**
     * Find device by IP address
     *
     * @param ipAddress IP address to search for
     * @param includeDeleted Whether to include deleted devices
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceFindByIp(String ipAddress, boolean includeDeleted, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds, d.retry_count,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at, d.last_polled_at, d.monitoring_enabled_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE host(d.ip_address) = $1
                    """ + (includeDeleted ? "" : " AND d.is_deleted = false");

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(ipAddress))
                    .onSuccess(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            blockingPromise.complete(new JsonObject().put("found", false));

                            return;
                        }

                        Row row = rows.iterator().next();

                        String ipAddr = row.getString("ip_address");

                        if (ipAddr != null && ipAddr.contains("/"))
                        {
                            ipAddr = ipAddr.split("/")[0]; // Remove CIDR notation
                        }

                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", ipAddr)
                                .put("device_type", row.getString("device_type"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("username", row.getString("username"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                .put("host_name", row.getString("host_name"))
                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                .put("is_deleted", row.getBoolean("is_deleted"))
                                .put("deleted_at", row.getLocalDateTime("deleted_at") != null ?
                                        row.getLocalDateTime("deleted_at").toString() : null)
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                .put("last_polled_at", row.getLocalDateTime("last_polled_at") != null ?
                                        row.getLocalDateTime("last_polled_at").toString() : null)
                                .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                        row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                        blockingPromise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to find device by IP", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    /**
     * Enable monitoring for a device
     *
     * @param deviceId Device ID
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceEnableMonitoring(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            // First check if device is provisioned
            String checkSql = """
                    SELECT is_provisioned, is_deleted
                    FROM devices
                    WHERE device_id = $1
                    """;

            pgPool.preparedQuery(checkSql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(checkRows ->
                    {
                        if (checkRows.size() == 0)
                        {
                            blockingPromise.complete(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device not found"));

                            return;
                        }

                        Row checkRow = checkRows.iterator().next();

                        boolean isDeleted = checkRow.getBoolean("is_deleted");

                        boolean isProvisioned = checkRow.getBoolean("is_provisioned");

                        if (isDeleted)
                        {
                            blockingPromise.complete(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device is deleted"));

                            return;
                        }

                        if (!isProvisioned)
                        {
                            blockingPromise.complete(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Cannot enable monitoring on unprovisioned device. Please provision the device first."));

                            return;
                        }

                        // Device is provisioned, proceed with enabling monitoring
                        String sql = """
                                UPDATE devices
                                SET is_monitoring_enabled = true,
                                    monitoring_enabled_at = COALESCE(monitoring_enabled_at, NOW())
                                WHERE device_id = $1 AND is_deleted = false
                                RETURNING device_id, is_monitoring_enabled, monitoring_enabled_at
                                """;

                        pgPool.preparedQuery(sql)
                                .execute(Tuple.of(UUID.fromString(deviceId)))
                                .onSuccess(rows ->
                                {
                                    if (rows.size() == 0)
                                    {
                                        blockingPromise.complete(new JsonObject()
                                                .put("updated", false)
                                                .put("reason", "Device not found or deleted"));

                                        return;
                                    }

                                    Row row = rows.iterator().next();

                                    JsonObject result = new JsonObject()
                                            .put("updated", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                            .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                                    row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                                    blockingPromise.complete(result);

                                    // Publish event to notify PollingMetricsVerticle to add device to cache
                                    vertx.eventBus().publish("device.monitoring.enabled", new JsonObject()
                                            .put("device_id", deviceId));

                                    logger.debug("📡 Published device.monitoring.enabled event for device: {}", deviceId);
                                })
                                .onFailure(cause ->
                                {
                                    logger.error("Failed to enable monitoring for device", cause);

                                    blockingPromise.fail(cause);
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to check device provisioning status", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    /**
     * Disable monitoring for a device
     *
     * @param deviceId Device ID
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceDisableMonitoring(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String sql = """
                    UPDATE devices
                    SET is_monitoring_enabled = false
                    WHERE device_id = $1 AND is_deleted = false
                    RETURNING device_id, is_monitoring_enabled, monitoring_enabled_at
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            blockingPromise.complete(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device not found or deleted"));

                            return;
                        }

                        Row row = rows.iterator().next();

                        JsonObject result = new JsonObject()
                                .put("updated", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                        row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                        blockingPromise.complete(result);

                        // Publish event to notify PollingMetricsVerticle to remove device from cache
                        vertx.eventBus().publish("device.monitoring.disabled", new JsonObject()
                                .put("device_id", deviceId));

                        logger.debug("📡 Published device.monitoring.disabled event for device: {}", deviceId);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to disable monitoring for device", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    /**
     * Provision devices and enable monitoring
     *
     * @param deviceIds Array of device IDs to provision
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceProvisionAndEnableMonitoring(JsonArray deviceIds, Handler<AsyncResult<JsonArray>> resultHandler)
    {
        vertx.executeBlocking(blockingPromise ->
        {
            JsonArray results = new JsonArray();

            try
            {
                for (int i = 0; i < deviceIds.size(); i++)
                {
                    String deviceId = deviceIds.getString(i);

                    JsonObject deviceResult = new JsonObject().put("device_id", deviceId);

                    try
                    {
                        // Validate UUID format
                        UUID.fromString(deviceId);

                        // Check if device exists and is unprovisioned
                        String checkSql = """
                                SELECT device_id, is_provisioned, is_deleted, device_name
                                FROM devices
                                WHERE device_id = $1
                                """;

                        RowSet<Row> checkRows = pgPool.preparedQuery(checkSql)
                                .execute(Tuple.of(UUID.fromString(deviceId)))
                                .toCompletionStage()
                                .toCompletableFuture()
                                .get();

                        if (checkRows.size() == 0)
                        {
                            deviceResult.put("success", false)
                                    .put("reason", "Device not found");

                            results.add(deviceResult);

                            continue;
                        }

                        Row checkRow = checkRows.iterator().next();

                        boolean isDeleted = checkRow.getBoolean("is_deleted");

                        boolean isProvisioned = checkRow.getBoolean("is_provisioned");

                        String deviceName = checkRow.getString("device_name");

                        if (isDeleted)
                        {
                            deviceResult.put("success", false)
                                    .put("reason", "Device is deleted")
                                    .put("device_name", deviceName);

                            results.add(deviceResult);

                            continue;
                        }

                        if (isProvisioned)
                        {
                            deviceResult.put("success", false)
                                    .put("reason", "Device is already provisioned")
                                    .put("device_name", deviceName);

                            results.add(deviceResult);

                            continue;
                        }

                        // Provision and enable monitoring
                        String updateSql = """
                                UPDATE devices
                                SET is_provisioned = true,
                                    is_monitoring_enabled = true,
                                    monitoring_enabled_at = NOW()
                                WHERE device_id = $1
                                RETURNING device_id, device_name, is_provisioned, is_monitoring_enabled, monitoring_enabled_at
                                """;

                        RowSet<Row> updateRows = pgPool.preparedQuery(updateSql)
                                .execute(Tuple.of(UUID.fromString(deviceId)))
                                .toCompletionStage()
                                .toCompletableFuture()
                                .get();

                        if (updateRows.size() > 0)
                        {
                            Row row = updateRows.iterator().next();

                            deviceResult.put("success", true)
                                    .put("device_name", row.getString("device_name"))
                                    .put("is_provisioned", row.getBoolean("is_provisioned"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                            row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                            // Initialize device availability record with "unknown" status
                            String initAvailabilitySql = """
                                    INSERT INTO device_availability (device_id, current_status)
                                    VALUES ($1, 'unknown')
                                    ON CONFLICT (device_id) DO NOTHING
                                    """;

                            pgPool.preparedQuery(initAvailabilitySql)
                                    .execute(Tuple.of(UUID.fromString(deviceId)))
                                    .toCompletionStage()
                                    .toCompletableFuture()
                                    .get();

                            // Publish event to notify PollingMetricsVerticle to add device to cache
                            vertx.eventBus().publish("device.monitoring.enabled", new JsonObject()
                                    .put("device_id", deviceId));

                            logger.info("✅ Provisioned and enabled monitoring for device: {} ({})", deviceName, deviceId);
                        }
                        else
                        {
                            deviceResult.put("success", false)
                                    .put("reason", "Update failed");
                        }

                    }
                    catch (IllegalArgumentException exception)
                    {
                        deviceResult.put("success", false)
                                .put("reason", "Invalid device ID format");
                    }
                    catch (Exception exception)
                    {
                        logger.error("Failed to provision device: {}", deviceId, exception);

                        deviceResult.put("success", false)
                                .put("reason", "Internal error: " + exception.getMessage());
                    }

                    results.add(deviceResult);
                }

                blockingPromise.complete(results);

            }
            catch (Exception exception)
            {
                logger.error("Failed to provision devices", exception);

                blockingPromise.fail(exception);
            }
        }, resultHandler);
    }

    /**
     * List all provisioned devices with monitoring enabled
     *
     * @param resultHandler Handler for the async result
     */
    public void deviceListProvisionedAndMonitoringEnabled(Handler<AsyncResult<JsonArray>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds, d.retry_count,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk,
                           d.host_name, d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at,
                           d.last_polled_at, d.monitoring_enabled_at,
                           cp.username, cp.password_encrypted
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE d.is_provisioned = true AND d.is_monitoring_enabled = true AND d.is_deleted = false
                    ORDER BY d.device_name
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows ->
                    {
                        JsonArray devices = new JsonArray();

                        for (Row row : rows)
                        {
                            JsonObject device = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("device_type", row.getString("device_type"))
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("username", row.getString("username"))
                                    .put("password_encrypted", row.getString("password_encrypted"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                    .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                    .put("retry_count", row.getInteger("retry_count"))
                                    .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                    .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                    .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                    .put("host_name", row.getString("host_name"))
                                    .put("is_provisioned", row.getBoolean("is_provisioned"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("deleted_at", row.getLocalDateTime("deleted_at") != null ? row.getLocalDateTime("deleted_at").toString() : null)
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ? row.getLocalDateTime("updated_at").toString() : null)
                                    .put("last_polled_at", row.getLocalDateTime("last_polled_at") != null ? row.getLocalDateTime("last_polled_at").toString() : null)
                                    .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ? row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                            devices.add(device);
                        }

                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to list provisioned and monitoring-enabled devices", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    /**
     * Create device from discovery
     *
     * @param deviceData Device data from discovery
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceCreateFromDiscovery(JsonObject deviceData, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            // ===== TRUST HANDLER VALIDATION =====
            // No validation here - handler has already validated all input

            String deviceName = deviceData.getString("device_name");

            String ipAddress = deviceData.getString("ip_address");

            String deviceType = deviceData.getString("device_type");

            Integer port = deviceData.getInteger("port");

            String protocol = deviceData.getString("protocol");

            String credentialProfileId = deviceData.getString("credential_profile_id");

            String hostName = deviceData.getString("host_name");

            // Create device with discovery defaults: is_provisioned = false, is_monitoring_enabled = false
            // device_name = host_name initially (user can change later)
            String sql = """
                    INSERT INTO devices (device_name, ip_address, device_type, port, protocol, credential_profile_id,
                                       timeout_seconds, retry_count, is_monitoring_enabled, alert_threshold_cpu,
                                       alert_threshold_memory, alert_threshold_disk, polling_interval_seconds,
                                       host_name, is_provisioned)
                    VALUES ($1, '%s'::inet, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
                    RETURNING device_id, device_name, ip_address::text as ip_address, device_type, host_name, is_provisioned, is_monitoring_enabled
                    """.formatted(ipAddress);

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(deviceName, deviceType, port, protocol, UUID.fromString(credentialProfileId),
                                    getDefaultTimeout(), getDefaultRetryCount(), false, // is_monitoring_enabled = false
                                    getDefaultCpuThreshold(), getDefaultMemoryThreshold(), getDefaultDiskThreshold(),
                                    getDefaultPollingInterval(), hostName, false)) // is_provisioned = false
                    .onSuccess(rows ->
                    {
                        Row row = rows.iterator().next();

                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("device_type", row.getString("device_type"))
                                .put("host_name", row.getString("host_name"))
                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("message", "Device created from discovery successfully");

                        blockingPromise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to create device from discovery", cause);

                        if (cause.getMessage().contains("duplicate key") || cause.getMessage().contains("unique constraint"))
                        {
                            blockingPromise.fail(new IllegalArgumentException("Device with this IP address already exists"));
                        }
                        else
                        {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    /**
     * Update device configuration
     *
     * @param deviceId Device ID
     * @param updateFields Fields to update
     * @param resultHandler Handler for the async result
     */
    @Override
    public void deviceUpdateConfig(String deviceId, JsonObject updateFields, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            // Basic existence and deletion check
            String checkSql = """
                    SELECT device_id, is_deleted
                    FROM devices
                    WHERE device_id = $1
                    """;

            pgPool.preparedQuery(checkSql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(checkRows ->
                    {
                        if (checkRows.size() == 0)
                        {
                            blockingPromise.fail(new IllegalArgumentException("Device not found"));

                            return;
                        }

                        Row checkRow = checkRows.iterator().next();

                        if (Boolean.TRUE.equals(checkRow.getBoolean("is_deleted")))
                        {
                            blockingPromise.fail(new IllegalArgumentException("Device is deleted and cannot be updated"));

                            return;
                        }

                        // Build dynamic update for allowed fields only
                        StringBuilder sqlBuilder = new StringBuilder("UPDATE devices SET ");

                        JsonArray params = new JsonArray();

                        int paramIndex = 1;

                        if (updateFields.containsKey("device_name"))
                        {
                            sqlBuilder.append("device_name = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getString("device_name"));
                        }

                        if (updateFields.containsKey("port"))
                        {
                            sqlBuilder.append("port = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getInteger("port"));
                        }

                        if (updateFields.containsKey("polling_interval_seconds"))
                        {
                            sqlBuilder.append("polling_interval_seconds = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getInteger("polling_interval_seconds"));
                        }

                        if (updateFields.containsKey("timeout_seconds"))
                        {
                            sqlBuilder.append("timeout_seconds = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getInteger("timeout_seconds"));
                        }

                        if (updateFields.containsKey("retry_count"))
                        {
                            sqlBuilder.append("retry_count = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getInteger("retry_count"));
                        }

                        if (updateFields.containsKey("alert_threshold_cpu"))
                        {
                            sqlBuilder.append("alert_threshold_cpu = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getDouble("alert_threshold_cpu"));
                        }

                        if (updateFields.containsKey("alert_threshold_memory"))
                        {
                            sqlBuilder.append("alert_threshold_memory = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getDouble("alert_threshold_memory"));
                        }

                        if (updateFields.containsKey("alert_threshold_disk"))
                        {
                            sqlBuilder.append("alert_threshold_disk = $").append(paramIndex++).append(", ");

                            params.add(updateFields.getDouble("alert_threshold_disk"));
                        }

                        if (params.isEmpty())
                        {
                            blockingPromise.fail(new IllegalArgumentException("No updatable fields provided"));

                            return;
                        }

                        String sqlStr = sqlBuilder.toString();

                        if (sqlStr.endsWith(", "))
                        {
                            sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
                        }

                        String sql = sqlStr + " WHERE device_id = $" + paramIndex + " AND is_deleted = false" +
                                " RETURNING device_id, device_name, port, polling_interval_seconds, timeout_seconds, retry_count, " +
                                "alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk, is_provisioned, is_monitoring_enabled";

                        params.add(UUID.fromString(deviceId));

                        pgPool.preparedQuery(sql)
                                .execute(Tuple.from(params.getList()))
                                .onSuccess(rows ->
                                {
                                    if (rows.size() == 0)
                                    {
                                        blockingPromise.fail(new IllegalArgumentException("Device not found or already deleted"));

                                        return;
                                    }

                                    Row row = rows.iterator().next();

                                    JsonObject result = new JsonObject()
                                            .put("success", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("device_name", row.getString("device_name"))
                                            .put("port", row.getInteger("port"))
                                            .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                            .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                            .put("retry_count", row.getInteger("retry_count"))
                                            .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                            .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                            .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                            .put("is_provisioned", row.getBoolean("is_provisioned"))
                                            .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                            .put("message", "Device configuration updated successfully");

                                    blockingPromise.complete(result);

                                    // Publish event to notify PollingMetricsVerticle to update device in cache
                                    // Only publish if device is monitoring enabled (otherwise not in cache)
                                    if (row.getBoolean("is_monitoring_enabled"))
                                    {
                                        vertx.eventBus().publish("device.config.updated", new JsonObject()
                                                .put("device_id", deviceId));

                                        logger.debug("📡 Published device.config.updated event for device: {}", deviceId);
                                    }
                                })
                                .onFailure(cause ->
                                {
                                    logger.error("Failed to update device configuration", cause);

                                    if (cause.getMessage() != null && (
                                            cause.getMessage().contains("chk_cpu_threshold") ||
                                            cause.getMessage().contains("chk_memory_threshold") ||
                                            cause.getMessage().contains("chk_disk_threshold")))
                                    {
                                        blockingPromise.fail(new IllegalArgumentException("Threshold values must be between 0 and 100"));
                                    }
                                    else if (cause.getMessage() != null && cause.getMessage().contains("chk_port_range"))
                                    {
                                        blockingPromise.fail(new IllegalArgumentException("Port must be between 1 and 65535"));
                                    }
                                    else if (cause.getMessage() != null && (
                                            cause.getMessage().contains("chk_timeout_range") ||
                                            cause.getMessage().contains("chk_retry_count")))
                                    {
                                        blockingPromise.fail(new IllegalArgumentException("Invalid timeout or retry count value"));
                                    }
                                    else
                                    {
                                        blockingPromise.fail(cause);
                                    }
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to check device status", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

}
