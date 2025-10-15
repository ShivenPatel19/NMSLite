package com.nmslite.services.impl;

import com.nmslite.Bootstrap;

import com.nmslite.services.DeviceService;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.sqlclient.Pool;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;

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
     * @param pgPool PostgresSQL connection pool
     */
    public DeviceServiceImpl(Pool pgPool)
    {
        this.vertx = Bootstrap.getVertxInstance();

        this.pgPool = pgPool;

        this.config = Bootstrap.getConfig();
    }

    /**
     * Get default CPU threshold from config
     *
     * @return Default CPU threshold percentage
     */
    private double getDefaultCpuThreshold()
    {
        // HOCON parses dotted keys as nested objects: alert.threshold.cpu becomes alert -> threshold -> cpu
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getJsonObject("alert", new JsonObject())
                .getJsonObject("threshold", new JsonObject())
                .getDouble("cpu", 80.0);
    }

    /**
     * Get default memory threshold from config
     *
     * @return Default memory threshold percentage
     */
    private double getDefaultMemoryThreshold()
    {
        // HOCON parses dotted keys as nested objects: alert.threshold.memory becomes alert -> threshold -> memory
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getJsonObject("alert", new JsonObject())
                .getJsonObject("threshold", new JsonObject())
                .getDouble("memory", 85.0);
    }

    /**
     * Get default disk threshold from config
     *
     * @return Default disk threshold percentage
     */
    private double getDefaultDiskThreshold()
    {
        // HOCON parses dotted keys as nested objects: alert.threshold.disk becomes alert -> threshold -> disk
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getJsonObject("alert", new JsonObject())
                .getJsonObject("threshold", new JsonObject())
                .getDouble("disk", 90.0);
    }

    /**
     * Get default polling interval from config
     *
     * @return Default polling interval in seconds
     */
    private int getDefaultPollingInterval()
    {
        // HOCON parses dotted keys as nested objects: polling.interval.seconds becomes polling -> interval -> seconds
        var pollingInterval = config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getJsonObject("polling", new JsonObject())
                .getJsonObject("interval", new JsonObject())
                .getInteger("seconds", 300);

        logger.debug("getDefaultPollingInterval() returning: {} seconds (config value or fallback)", pollingInterval);

        return pollingInterval;
    }

    /**
     * Get default timeout from config
     *
     * @return Default timeout in seconds
     */
    private int getDefaultTimeout()
    {
        // HOCON parses dotted keys as nested objects: timeout.seconds becomes timeout -> seconds
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getJsonObject("timeout", new JsonObject())
                .getInteger("seconds", 60);
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
        var promise = Promise.<JsonArray>promise();

        try
        {
            var sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at, d.monitoring_enabled_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE d.is_provisioned = $1 AND d.is_deleted = false
                    ORDER BY d.device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(isProvisioned))
                    .onSuccess(rows ->
                    {
                        var devices = new JsonArray();

                        for (var row : rows)
                        {
                            var device = new JsonObject()
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
                                    .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                    .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                    .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                    .put("host_name", row.getString("host_name"))
                                    .put("is_provisioned", row.getBoolean("is_provisioned"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("deleted_at", row.getLocalDateTime("deleted_at") != null ? row.getLocalDateTime("deleted_at").toString() : null)
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ? row.getLocalDateTime("updated_at").toString() : null)
                                    .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ? row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                            devices.add(device);
                        }

                        promise.complete(devices);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to list devices by provision status: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceListByProvisioned service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            var sql = """
                    UPDATE devices
                    SET is_deleted = true,
                        deleted_at = CURRENT_TIMESTAMP,
                        is_monitoring_enabled = false
                    WHERE device_id = $1 AND is_deleted = false
                    RETURNING device_id, device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            promise.fail(new Exception("Device not found or already deleted"));

                            return;
                        }

                        var row = rows.iterator().next();

                        var result = new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("message", "Device deleted successfully");

                        promise.complete(result);

                        // Publish event to notify PollingMetricsVerticle to remove device from cache
                        // and clean up metrics/availability data
                        vertx.eventBus().publish("device.deleted", new JsonObject()
                                .put("device_id", deviceId));

                        logger.debug("Published device.deleted event for device: {}", deviceId);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to delete device: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceDelete service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            var sql = """
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
                            promise.fail(new Exception("Device not found or not deleted"));

                            return;
                        }

                        var row = rows.iterator().next();

                        var result = new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("message", "Device restored successfully");

                        promise.complete(result);

                        // Publish event to notify PollingMetricsVerticle to add device back to cache
                        // (only if monitoring is enabled)
                        vertx.eventBus().publish("device.restored", new JsonObject()
                                .put("device_id", deviceId));

                        logger.debug("Published device.restored event for device: {}", deviceId);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to restore device: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceRestore service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            var sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name, cp.password_encrypted,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at, d.monitoring_enabled_at
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

                        var row = rows.iterator().next();

                        var result = new JsonObject()
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
                                .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                        row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                        promise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to get device by ID: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceGetById service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            var sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at, d.monitoring_enabled_at
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
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("username", row.getString("username"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
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
                                .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                        row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                        promise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to find device by IP: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceFindByIp service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            // First check if device is provisioned and current monitoring state
            var checkSql = """
                    SELECT is_provisioned, is_deleted, is_monitoring_enabled
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

                        var checkRow = checkRows.iterator().next();

                        var isDeleted = checkRow.getBoolean("is_deleted");

                        var isProvisioned = checkRow.getBoolean("is_provisioned");

                        var isMonitoringEnabled = checkRow.getBoolean("is_monitoring_enabled");

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

                        if (isMonitoringEnabled)
                        {
                            promise.complete(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Monitoring is already enabled for this device"));

                            return;
                        }

                        // Device is provisioned, proceed with enabling monitoring
                        var sql = """
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

                                    var row = rows.iterator().next();

                                    var result = new JsonObject()
                                            .put("updated", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                            .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                                    row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                                    promise.complete(result);

                                    // Publish event to notify PollingMetricsVerticle to add device to cache
                                    vertx.eventBus().publish("device.monitoring.enabled", new JsonObject()
                                            .put("device_id", deviceId));

                                    logger.debug("Published device.monitoring.enabled event for device: {}", deviceId);
                                })
                                .onFailure(cause ->
                                {
                                    logger.error("Failed to enable monitoring for device: {}", cause.getMessage());

                                    promise.fail(cause);
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to check device provisioning status: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceEnableMonitoring service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            // First check if device exists, is not deleted, and current monitoring state
            var checkSql = """
                    SELECT is_deleted, is_monitoring_enabled
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

                        var checkRow = checkRows.iterator().next();

                        var isDeleted = checkRow.getBoolean("is_deleted");

                        var isMonitoringEnabled = checkRow.getBoolean("is_monitoring_enabled");

                        if (isDeleted)
                        {
                            promise.complete(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device is deleted"));

                            return;
                        }

                        if (!isMonitoringEnabled)
                        {
                            promise.complete(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Monitoring is already disabled for this device"));

                            return;
                        }

                        // Device exists, is not deleted, and monitoring is enabled - proceed with disabling
                        var sql = """
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

                                    var row = rows.iterator().next();

                                    var result = new JsonObject()
                                            .put("updated", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                            .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                                    row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                                    promise.complete(result);

                                    // Publish event to notify PollingMetricsVerticle to remove device from cache
                                    vertx.eventBus().publish("device.monitoring.disabled", new JsonObject()
                                            .put("device_id", deviceId));

                                    logger.debug("Published device.monitoring.disabled event for device: {}", deviceId);
                                })
                                .onFailure(cause ->
                                {
                                    logger.error("Failed to disable monitoring for device: {}", cause.getMessage());

                                    promise.fail(cause);
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to check device status: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceDisableMonitoring service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonArray>promise();

        try
        {
            var results = new JsonArray();

            var futures = new ArrayList<Future<JsonObject>>();

            for (var i = 0; i < deviceIds.size(); i++)
            {
                var deviceId = deviceIds.getString(i);

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
                        logger.error("Failed to provision devices: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceProvisionAndEnableMonitoring service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        var deviceResult = new JsonObject().put("device_id", deviceId);

        try
        {
            // Validate UUID format
            UUID.fromString(deviceId);

            // Check if device exists and is unprovisioned
            var checkSql = """
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

                        var checkRow = checkRows.iterator().next();

                        var isDeleted = checkRow.getBoolean("is_deleted");

                        var isProvisioned = checkRow.getBoolean("is_provisioned");

                        var deviceName = checkRow.getString("device_name");

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
                        var updateSql = """
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
                                        var row = updateRows.iterator().next();

                                        deviceResult.put("success", true)
                                                .put("device_name", row.getString("device_name"))
                                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                                .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ?
                                                        row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                                        // Initialize device availability record with "unknown" status (Important to NOTE)
                                        var initAvailabilitySql = """
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

                                                    logger.info("Provisioned and enabled monitoring for device: {} ({})", row.getString("device_name"), deviceId);

                                                    promise.complete(deviceResult);
                                                })
                                                .onFailure(cause ->
                                                {
                                                    logger.error("Failed to initialize availability for device: {}: {}", deviceId, cause.getMessage());

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
                                    logger.error("Failed to provision device: {}: {}", deviceId, cause.getMessage());

                                    deviceResult.put("success", false)
                                            .put("reason", "Internal error: " + cause.getMessage());

                                    promise.complete(deviceResult);
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to check device status: {}: {}", deviceId, cause.getMessage());

                        deviceResult.put("success", false)
                                .put("reason", "Internal error: " + cause.getMessage());

                        promise.complete(deviceResult);
                    });
        }
        catch (Exception exception)
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
        var promise = Promise.<JsonArray>promise();

        try
        {
            var sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk,
                           d.host_name, d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at,
                           d.monitoring_enabled_at,
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
                        var devices = new JsonArray();

                        for (var row : rows)
                        {
                            var device = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("device_type", row.getString("device_type"))
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("username", row.getString("username"))
                                    .put("password_encrypted", row.getString("password_encrypted"))  // encrypted password
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                    .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                    .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                    .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                    .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                    .put("host_name", row.getString("host_name"))
                                    .put("is_provisioned", row.getBoolean("is_provisioned"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("deleted_at", row.getLocalDateTime("deleted_at") != null ? row.getLocalDateTime("deleted_at").toString() : null)
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ? row.getLocalDateTime("updated_at").toString() : null)
                                    .put("monitoring_enabled_at", row.getLocalDateTime("monitoring_enabled_at") != null ? row.getLocalDateTime("monitoring_enabled_at").toString() : null);

                            devices.add(device);
                        }

                        promise.complete(devices);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to list provisioned and monitoring-enabled devices: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceListProvisionedAndMonitoringEnabled service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            var deviceName = deviceData.getString("device_name");

            var ipAddress = deviceData.getString("ip_address");

            var deviceType = deviceData.getString("device_type");

            var port = deviceData.getInteger("port");

            var protocol = deviceData.getString("protocol");

            var credentialProfileId = deviceData.getString("credential_profile_id");

            var hostName = deviceData.getString("host_name");

            // Create device with discovery defaults: is_provisioned = false, is_monitoring_enabled = false -> show its only being "discovered"
            // device_name = host_name initially (user can change later)
            var sql = """
                    INSERT INTO devices (device_name, ip_address, device_type, port, protocol, credential_profile_id,
                                       timeout_seconds, is_monitoring_enabled, alert_threshold_cpu,
                                       alert_threshold_memory, alert_threshold_disk, polling_interval_seconds,
                                       host_name, is_provisioned)
                    VALUES ($1, '%s'::inet, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
                    RETURNING device_id, device_name, ip_address::text as ip_address, device_type, host_name, is_provisioned, is_monitoring_enabled
                    """.formatted(ipAddress);

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(deviceName, deviceType, port, protocol, UUID.fromString(credentialProfileId),
                                    getDefaultTimeout(), false, // is_monitoring_enabled = false
                                    getDefaultCpuThreshold(), getDefaultMemoryThreshold(), getDefaultDiskThreshold(),
                                    getDefaultPollingInterval(), hostName, false)) // is_provisioned = false
                    .onSuccess(rows ->
                    {
                        var row = rows.iterator().next();

                        var result = new JsonObject()
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
                        logger.error("Failed to create device from discovery: {}", cause.getMessage());

                        if (cause.getMessage().contains("duplicate key") || cause.getMessage().contains("unique constraint"))
                        {
                            promise.fail(new Exception("Device with this IP address already exists"));
                        }
                        else
                        {
                            promise.fail(cause);
                        }
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceCreateFromDiscovery service: {}", exception.getMessage());

            promise.fail(exception);
        }

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
        var promise = Promise.<JsonObject>promise();

        try
        {
            // Basic existence and deletion check
            var checkSql = """
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
                            promise.fail(new Exception("Device not found"));

                            return;
                        }

                        var checkRow = checkRows.iterator().next();

                        if (Boolean.TRUE.equals(checkRow.getBoolean("is_deleted")))
                        {
                            promise.fail(new Exception("Device is deleted and cannot be updated"));

                            return;
                        }

                        // Build dynamic update for allowed fields only
                        var sqlBuilder = new StringBuilder("UPDATE devices SET ");

                        var params = new JsonArray();

                        var paramIndex = 1;

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
                            promise.fail(new Exception("No updatable fields provided"));

                            return;
                        }

                        var sqlStr = sqlBuilder.toString();

                        if (sqlStr.endsWith(", "))
                        {
                            sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
                        }

                        var sql = sqlStr + " WHERE device_id = $" + paramIndex + " AND is_deleted = false" +
                                " RETURNING device_id, device_name, port, polling_interval_seconds, timeout_seconds, " +
                                "alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk, is_provisioned, is_monitoring_enabled";

                        params.add(UUID.fromString(deviceId));

                        pgPool.preparedQuery(sql)
                                .execute(Tuple.from(params.getList()))
                                .onSuccess(rows ->
                                {
                                    if (rows.size() == 0)
                                    {
                                        promise.fail(new Exception("Device not found or already deleted"));

                                        return;
                                    }

                                    var row = rows.iterator().next();

                                    var result = new JsonObject()
                                            .put("success", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("device_name", row.getString("device_name"))
                                            .put("port", row.getInteger("port"))
                                            .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                            .put("timeout_seconds", row.getInteger("timeout_seconds"))
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

                                        logger.debug("Published device.config.updated event for device: {}", deviceId);
                                    }
                                })
                                .onFailure(cause ->
                                {
                                    logger.error("Failed to update device configuration: {}", cause.getMessage());

                                    if (cause.getMessage() != null && (
                                            cause.getMessage().contains("chk_cpu_threshold") ||
                                            cause.getMessage().contains("chk_memory_threshold") ||
                                            cause.getMessage().contains("chk_disk_threshold")))
                                    {
                                        promise.fail(new Exception("Threshold values must be between 0 and 100"));
                                    }
                                    else if (cause.getMessage() != null && cause.getMessage().contains("chk_port_range"))
                                    {
                                        promise.fail(new Exception("Port must be between 1 and 65535"));
                                    }
                                    else if (cause.getMessage() != null && cause.getMessage().contains("chk_timeout_range"))
                                    {
                                        promise.fail(new Exception("Invalid timeout value"));
                                    }
                                    else
                                    {
                                        promise.fail(cause);
                                    }
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to check device status: {}", cause.getMessage());

                        promise.fail(cause);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceUpdateConfig service: {}", exception.getMessage());

            promise.fail(exception);
        }

        return promise.future();
    }

}
