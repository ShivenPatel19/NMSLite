package com.nmslite.services.impl;

import com.nmslite.services.DeviceService;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.sqlclient.Pool;

import io.vertx.sqlclient.Row;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import java.util.List;

import java.util.UUID;

/**
 * DeviceServiceImpl - Implementation of DeviceService

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

    private final Pool pgPool;

    private final JsonObject config;

    /**
     * Constructor for DeviceServiceImpl
     *
     * @param vertx Vert.x instance
     * @param pgPool PostgresSQL connection pool
     */
    public DeviceServiceImpl(Vertx vertx, Pool pgPool)
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

                .getInteger("polling.interval.seconds", 300);
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

                .getInteger("timeout.seconds", 60);
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
     * @return Future containing JsonArray of devices
     */
    @Override
    public Future<JsonArray> deviceListByProvisioned(boolean isProvisioned)
    {
        Promise<JsonArray> promise = Promise.promise();

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

                    promise.complete(devices);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to list devices by provision status", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Delete device (soft delete)
     *
     * @param deviceId Device ID
     * @return Future containing JsonObject with deletion result
     */
    @Override
    public Future<JsonObject> deviceDelete(String deviceId)
    {
        Promise<JsonObject> promise = Promise.promise();

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
                        promise.fail(new IllegalArgumentException("Device not found or already deleted"));

                        return;
                    }

                    Row row = rows.iterator().next();

                    JsonObject result = new JsonObject()
                            .put("success", true)
                            .put("device_id", row.getUUID("device_id").toString())
                            .put("device_name", row.getString("device_name"))
                            .put("message", "Device deleted successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to delete device", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Restore deleted device
     *
     * @param deviceId Device ID
     * @return Future containing JsonObject with restoration result
     */
    @Override
    public Future<JsonObject> deviceRestore(String deviceId)
    {
        Promise<JsonObject> promise = Promise.promise();

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
                        promise.fail(new IllegalArgumentException("Device not found or not deleted"));

                        return;
                    }

                    Row row = rows.iterator().next();

                    JsonObject result = new JsonObject()
                            .put("success", true)
                            .put("device_id", row.getUUID("device_id").toString())
                            .put("device_name", row.getString("device_name"))
                            .put("message", "Device restored successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to restore device", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Get device by ID
     *
     * @param deviceId Device ID
     * @return Future containing JsonObject with device data or not found
     */
    @Override
    public Future<JsonObject> deviceGetById(String deviceId)
    {
        Promise<JsonObject> promise = Promise.promise();

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
                        promise.complete(new JsonObject().put("found", false));

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

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get device by ID", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Find device by IP address
     *
     * @param ipAddress IP address to search for
     * @param includeDeleted Whether to include deleted devices
     * @return Future containing JsonObject with device data or not found
     */
    @Override
    public Future<JsonObject> deviceFindByIp(String ipAddress, boolean includeDeleted)
    {
        Promise<JsonObject> promise = Promise.promise();

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
                        promise.complete(new JsonObject().put("found", false));

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

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to find device by IP", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Enable monitoring for a device
     *
     * @param deviceId Device ID
     * @return Future containing device_id, is_monitoring_enabled, monitoring_enabled_at
     */
    @Override
    public Future<JsonObject> deviceEnableMonitoring(String deviceId)
    {
        Promise<JsonObject> promise = Promise.promise();

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
                        promise.complete(new JsonObject()
                                .put("updated", false)
                                .put("reason", "Device not found"));

                        return;
                    }

                    Row checkRow = checkRows.iterator().next();

                    boolean isDeleted = checkRow.getBoolean("is_deleted");

                    boolean isProvisioned = checkRow.getBoolean("is_provisioned");

                    if (isDeleted)
                    {
                        promise.complete(new JsonObject()
                                .put("updated", false)
                                .put("reason", "Device is deleted"));

                        return;
                    }

                    if (!isProvisioned)
                    {
                        promise.complete(new JsonObject()
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
                                    promise.complete(new JsonObject()
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

                                promise.complete(result);

                                // Publish event to notify PollingMetricsVerticle to add device to cache
                                vertx.eventBus().publish("device.monitoring.enabled", new JsonObject()
                                        .put("device_id", deviceId));

                                logger.debug("📡 Published device.monitoring.enabled event for device: {}", deviceId);
                            })
                            .onFailure(cause ->
                            {
                                logger.error("Failed to enable monitoring for device", cause);

                                promise.fail(cause);
                            });
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to check device provisioning status", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Disable monitoring for a device
     *
     * @param deviceId Device ID
     * @return Future containing device_id, is_monitoring_enabled, monitoring_enabled_at
     */
    @Override
    public Future<JsonObject> deviceDisableMonitoring(String deviceId)
    {
        Promise<JsonObject> promise = Promise.promise();

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
                        promise.complete(new JsonObject()
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

                    promise.complete(result);

                    // Publish event to notify PollingMetricsVerticle to remove device from cache
                    vertx.eventBus().publish("device.monitoring.disabled", new JsonObject()
                            .put("device_id", deviceId));

                    logger.debug("📡 Published device.monitoring.disabled event for device: {}", deviceId);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to disable monitoring for device", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Provision devices and enable monitoring
     *
     * @param deviceIds Array of device IDs to provision
     * @return Future containing JsonArray of provision results
     */
    @Override
    public Future<JsonArray> deviceProvisionAndEnableMonitoring(JsonArray deviceIds)
    {
        Promise<JsonArray> promise = Promise.promise();

        JsonArray results = new JsonArray();

        List<Future<JsonObject>> futures = new ArrayList<>();

        for (int i = 0; i < deviceIds.size(); i++)
        {
            String deviceId = deviceIds.getString(i);

            futures.add(provisionSingleDevice(deviceId));
        }

        Future.all(futures)
                .onSuccess(compositeFuture ->
                {
                    for (Future<JsonObject> future : futures) {
                        results.add(future.result());
                    }

                    promise.complete(results);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to provision devices", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Provision a single device and enable monitoring
     *
     * @param deviceId Device ID to provision
     * @return Future containing JsonObject with provision result
     */
    private Future<JsonObject> provisionSingleDevice(String deviceId)
    {
        Promise<JsonObject> promise = Promise.promise();

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

            pgPool.preparedQuery(checkSql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(checkRows ->
                    {
                        if (checkRows.size() == 0)
                        {
                            deviceResult.put("success", false)
                                    .put("reason", "Device not found");

                            promise.complete(deviceResult);

                            return;
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

                            promise.complete(deviceResult);

                            return;
                        }

                        if (isProvisioned)
                        {
                            deviceResult.put("success", false)
                                    .put("reason", "Device is already provisioned")
                                    .put("device_name", deviceName);

                            promise.complete(deviceResult);

                            return;
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

                        pgPool.preparedQuery(updateSql)
                                .execute(Tuple.of(UUID.fromString(deviceId)))
                                .onSuccess(updateRows ->
                                {
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
                                                .onSuccess(availRows ->
                                                {
                                                    // Publish event to notify PollingMetricsVerticle to add device to cache
                                                    vertx.eventBus().publish("device.monitoring.enabled", new JsonObject()
                                                            .put("device_id", deviceId));

                                                    logger.info("✅ Provisioned and enabled monitoring for device: {} ({})", row.getString("device_name"), deviceId);

                                                    promise.complete(deviceResult);
                                                })
                                                .onFailure(cause ->
                                                {
                                                    logger.error("Failed to initialize availability for device: {}", deviceId, cause);

                                                    deviceResult.put("success", false)
                                                            .put("reason", "Failed to initialize availability: " + cause.getMessage());

                                                    promise.complete(deviceResult);
                                                });
                                    }
                                    else
                                    {
                                        deviceResult.put("success", false)
                                                .put("reason", "Update failed");

                                        promise.complete(deviceResult);
                                    }
                                })
                                .onFailure(cause ->
                                {
                                    logger.error("Failed to provision device: {}", deviceId, cause);

                                    deviceResult.put("success", false)
                                            .put("reason", "Internal error: " + cause.getMessage());

                                    promise.complete(deviceResult);
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to check device status: {}", deviceId, cause);

                        deviceResult.put("success", false)
                                .put("reason", "Internal error: " + cause.getMessage());

                        promise.complete(deviceResult);
                    });
        }
        catch (IllegalArgumentException exception)
        {
            deviceResult.put("success", false)
                    .put("reason", "Invalid device ID format");

            promise.complete(deviceResult);
        }

        return promise.future();
    }

    /**
     * List all provisioned devices with monitoring enabled
     *
     * @return Future containing JsonArray of devices
     */
    public Future<JsonArray> deviceListProvisionedAndMonitoringEnabled()
    {
        Promise<JsonArray> promise = Promise.promise();

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

                    promise.complete(devices);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to list provisioned and monitoring-enabled devices", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Create device from discovery
     *
     * @param deviceData Device data from discovery
     * @return Future containing JsonObject with created device data
     */
    @Override
    public Future<JsonObject> deviceCreateFromDiscovery(JsonObject deviceData)
    {
        Promise<JsonObject> promise = Promise.promise();

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

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to create device from discovery", cause);

                    if (cause.getMessage().contains("duplicate key") || cause.getMessage().contains("unique constraint"))
                    {
                        promise.fail(new IllegalArgumentException("Device with this IP address already exists"));
                    }
                    else
                    {
                        promise.fail(cause);
                    }
                });

        return promise.future();
    }

    /**
     * Update device configuration
     *
     * @param deviceId Device ID
     * @param updateFields Fields to update
     * @return Future containing JsonObject with updated device configuration
     */
    @Override
    public Future<JsonObject> deviceUpdateConfig(String deviceId, JsonObject updateFields)
    {
        Promise<JsonObject> promise = Promise.promise();

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
                        promise.fail(new IllegalArgumentException("Device not found"));

                        return;
                    }

                    Row checkRow = checkRows.iterator().next();

                    if (Boolean.TRUE.equals(checkRow.getBoolean("is_deleted")))
                    {
                        promise.fail(new IllegalArgumentException("Device is deleted and cannot be updated"));

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
                        promise.fail(new IllegalArgumentException("No updatable fields provided"));

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
                                    promise.fail(new IllegalArgumentException("Device not found or already deleted"));

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

                                promise.complete(result);

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
                                    promise.fail(new IllegalArgumentException("Threshold values must be between 0 and 100"));
                                }
                                else if (cause.getMessage() != null && cause.getMessage().contains("chk_port_range"))
                                {
                                    promise.fail(new IllegalArgumentException("Port must be between 1 and 65535"));
                                }
                                else if (cause.getMessage() != null && (
                                        cause.getMessage().contains("chk_timeout_range") ||
                                        cause.getMessage().contains("chk_retry_count")))
                                {
                                    promise.fail(new IllegalArgumentException("Invalid timeout or retry count value"));
                                }
                                else
                                {
                                    promise.fail(cause);
                                }
                            });
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to check device status", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

}
