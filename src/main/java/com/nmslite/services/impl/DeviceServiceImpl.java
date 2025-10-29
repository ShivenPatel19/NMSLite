package com.nmslite.services.impl;

import com.nmslite.Bootstrap;

import com.nmslite.database.DatabaseHelper;

import com.nmslite.database.DatabaseInitializer;

import com.nmslite.services.DeviceService;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * DeviceServiceImpl - Implementation of DeviceService

 * Provides device management operations including:
 * - Device CRUD operations with soft delete
 * - Device provisioning (is_provisioned flag controls monitoring state)
 * - Device discovery integration
 * - Device status and availability tracking

 * NOTE: is_provisioned flag now controls monitoring - when true, monitoring is enabled; when false, monitoring is disabled
 * NOTE: port and protocol are stored in credential_profiles table, not devices table

 * Database Access:
 * - Uses DatabaseHelper for database operations
 * - No event bus publishing (handlers are responsible for that)
 * - No constructor parameters needed
 */
public class DeviceServiceImpl implements DeviceService
{

    private static final Logger logger = LoggerFactory.getLogger(DeviceServiceImpl.class);

    private final DatabaseHelper dbHelper;

    /**
     * Constructor for DeviceServiceImpl.
     * Accesses database helper via DatabaseInitializer.getDatabaseHelper().
     */
    public DeviceServiceImpl()
    {
        this.dbHelper = DatabaseInitializer.getDatabaseHelper();
    }

    /**
     * Get default polling interval from config
     *
     * @return Default polling interval in seconds
     */
    private int getDefaultPollingInterval()
    {
        // HOCON parses dotted keys as nested objects: polling.interval.seconds becomes polling -> interval -> seconds
        var pollingInterval = Bootstrap.getConfig().getJsonObject("device", new JsonObject())
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
        return Bootstrap.getConfig().getJsonObject("device", new JsonObject())
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
        try
        {
            var sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type,
                           d.credential_profile_id, cp.username, cp.password_encrypted, cp.profile_name as credential_profile_name, cp.port, cp.protocol,
                           d.polling_interval_seconds, d.timeout_seconds, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE d.is_provisioned = $1 AND d.is_deleted = false
                    ORDER BY d.device_name
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(isProvisioned))
                    .map(rows ->
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
                                    .put("password_encrypted", row.getString("password_encrypted"))
                                    .put("credential_profile_name", row.getString("credential_profile_name"))
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                    .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                    .put("host_name", row.getString("host_name"))
                                    .put("is_provisioned", row.getBoolean("is_provisioned"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("deleted_at", row.getLocalDateTime("deleted_at") != null ? row.getLocalDateTime("deleted_at").toString() : null)
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ? row.getLocalDateTime("updated_at").toString() : null);

                            devices.add(device);
                        }

                        return devices;
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceListByProvisioned service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceId)))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            throw new RuntimeException("Device not found or already deleted");
                        }

                        var row = rows.iterator().next();

                        return new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("message", "Device deleted successfully");
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceDelete service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                    UPDATE devices
                    SET is_deleted = false, deleted_at = NULL
                    WHERE device_id = $1 AND is_deleted = true
                    RETURNING device_id, device_name
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceId)))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            throw new RuntimeException("Device not found or not deleted");
                        }

                        var row = rows.iterator().next();

                        return new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("message", "Device restored successfully");
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceRestore service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name, cp.password_encrypted, cp.port, cp.protocol,
                           d.polling_interval_seconds, d.timeout_seconds, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE d.device_id = $1
                    AND d.is_deleted = false""";

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceId)))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            return new JsonObject().put("found", false);
                        }

                        var row = rows.iterator().next();

                        return new JsonObject()
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
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("host_name", row.getString("host_name"))
                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                .put("is_deleted", row.getBoolean("is_deleted"))
                                .put("deleted_at", row.getLocalDateTime("deleted_at") != null ?
                                        row.getLocalDateTime("deleted_at").toString() : null)
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceGetById service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name, cp.port, cp.protocol,
                           d.polling_interval_seconds, d.timeout_seconds, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE host(d.ip_address) = $1
                    """ + (includeDeleted ? "" : " AND d.is_deleted = false");

            return dbHelper.executePreparedQuery(sql, Tuple.of(ipAddress))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            return new JsonObject().put("found", false);
                        }

                        var row = rows.iterator().next();

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
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("username", row.getString("username"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("host_name", row.getString("host_name"))
                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                .put("is_deleted", row.getBoolean("is_deleted"))
                                .put("deleted_at", row.getLocalDateTime("deleted_at") != null ?
                                        row.getLocalDateTime("deleted_at").toString() : null)
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceFindByIp service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Enable provisioning for a device (sets is_provisioned = true)
     *
     * @param deviceId Device ID
     * @return Future containing device_id and is_provisioned status
     */
    @Override
    public Future<JsonObject> deviceEnableProvisioning(String deviceId)
    {
        try
        {
            // Check if device exists and is not deleted
            var checkSql = """
                    SELECT is_provisioned, is_deleted
                    FROM devices
                    WHERE device_id = $1
                    """;

            return dbHelper.executePreparedQuery(checkSql, Tuple.of(UUID.fromString(deviceId)))
                    .compose(checkRows ->
                    {
                        if (checkRows.size() == 0)
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device not found"));
                        }

                        var checkRow = checkRows.iterator().next();

                        var isDeleted = checkRow.getBoolean("is_deleted");

                        var isProvisioned = checkRow.getBoolean("is_provisioned");

                        if (isDeleted)
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device is deleted"));
                        }

                        if (isProvisioned)
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device is already provisioned (monitoring already enabled)"));
                        }

                        // Set is_provisioned = true to enable monitoring
                        var sql = """
                                UPDATE devices
                                SET is_provisioned = true
                                WHERE device_id = $1 AND is_deleted = false
                                RETURNING device_id, is_provisioned
                                """;

                        return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceId)))
                                .map(rows ->
                                {
                                    if (rows.size() == 0)
                                    {
                                        return new JsonObject()
                                                .put("updated", false)
                                                .put("reason", "Device not found or deleted");
                                    }

                                    var row = rows.iterator().next();

                                    var result = new JsonObject()
                                            .put("updated", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("is_provisioned", row.getBoolean("is_provisioned"))
                                            .put("message", "Device provisioning enabled successfully");

                                    logger.info("Device provisioning enabled: {}", deviceId);

                                    return result;
                                });
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceEnableProvisioning service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Disable provisioning for a device (sets is_provisioned = false)
     *
     * @param deviceId Device ID
     * @return Future containing device_id and is_provisioned status
     */
    @Override
    public Future<JsonObject> deviceDisableProvisioning(String deviceId)
    {
        try
        {
            // Check if device exists and is not deleted
            var checkSql = """
                    SELECT is_deleted, is_provisioned
                    FROM devices
                    WHERE device_id = $1
                    """;

            return dbHelper.executePreparedQuery(checkSql, Tuple.of(UUID.fromString(deviceId)))
                    .compose(checkRows ->
                    {
                        if (checkRows.size() == 0)
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device not found"));
                        }

                        var checkRow = checkRows.iterator().next();

                        var isDeleted = checkRow.getBoolean("is_deleted");

                        var isProvisioned = checkRow.getBoolean("is_provisioned");

                        if (isDeleted)
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device is deleted"));
                        }

                        if (!isProvisioned)
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("updated", false)
                                    .put("reason", "Device is already unprovisioned (monitoring already disabled)"));
                        }

                        // Set is_provisioned = false to disable monitoring
                        var sql = """
                                UPDATE devices
                                SET is_provisioned = false
                                WHERE device_id = $1 AND is_deleted = false
                                RETURNING device_id, is_provisioned
                                """;

                        return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceId)))
                                .map(rows ->
                                {
                                    if (rows.size() == 0)
                                    {
                                        return new JsonObject()
                                                .put("updated", false)
                                                .put("reason", "Device not found or deleted");
                                    }

                                    var row = rows.iterator().next();

                                    var result = new JsonObject()
                                            .put("updated", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("is_provisioned", row.getBoolean("is_provisioned"))
                                            .put("message", "Device provisioning disabled successfully");

                                    logger.info("Device provisioning disabled: {}", deviceId);

                                    return result;
                                });
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceDisableProvisioning service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var deviceName = deviceData.getString("device_name");

            var ipAddress = deviceData.getString("ip_address");

            var deviceType = deviceData.getString("device_type");

            var credentialProfileId = deviceData.getString("credential_profile_id");

            var hostName = deviceData.getString("host_name");

            // Create device with auto-provisioning: is_provisioned = true (monitoring enabled)
            // Port and protocol are now stored in credential_profiles, not devices
            // device_name = host_name initially (user can change later)
            var sql = """
                    INSERT INTO devices (device_name, ip_address, device_type, credential_profile_id,
                                       timeout_seconds, polling_interval_seconds, host_name, is_provisioned)
                    VALUES ($1, '%s'::inet, $2, $3, $4, $5, $6, $7)
                    RETURNING device_id, device_name, ip_address::text as ip_address, device_type, host_name, is_provisioned
                    """.formatted(ipAddress);

            return dbHelper.executePreparedQuery(sql, Tuple.of(deviceName, deviceType, UUID.fromString(credentialProfileId),
                            getDefaultTimeout(), getDefaultPollingInterval(), hostName, true))
                    .compose(rows ->
                    {
                        var row = rows.iterator().next();

                        var deviceId = row.getUUID("device_id").toString();

                        var result = new JsonObject()
                                .put("success", true)
                                .put("device_id", deviceId)
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("device_type", row.getString("device_type"))
                                .put("host_name", row.getString("host_name"))
                                .put("is_provisioned", row.getBoolean("is_provisioned"))
                                .put("message", "Device created from discovery and auto-provisioned successfully");

                        // Initialize device availability record with "unknown" status
                        var initAvailabilitySql = """
                                INSERT INTO device_availability (device_id, current_status)
                                VALUES ($1, 'unknown')
                                ON CONFLICT (device_id) DO NOTHING
                                """;

                        return dbHelper.executePreparedQuery(initAvailabilitySql, Tuple.of(UUID.fromString(deviceId)))
                                .map(availRows ->
                                {
                                    logger.info("Device created from discovery and auto-provisioned: {} ({})", row.getString("device_name"), deviceId);

                                    return result;
                                });
                    })
                    .recover(cause ->
                    {
                        logger.error("Failed to create device from discovery: {}", cause.getMessage());

                        if (cause.getMessage().contains("duplicate key") || cause.getMessage().contains("unique constraint"))
                        {
                            return Future.failedFuture(new Exception("Device with this IP address already exists"));
                        }
                        else
                        {
                            return Future.failedFuture(cause);
                        }
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceCreateFromDiscovery service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            // Basic existence and deletion check
            var checkSql = """
                    SELECT device_id, is_deleted
                    FROM devices
                    WHERE device_id = $1
                    """;

            return dbHelper.executePreparedQuery(checkSql, Tuple.of(UUID.fromString(deviceId)))
                    .compose(checkRows ->
                    {
                        if (checkRows.size() == 0)
                        {
                            return Future.failedFuture(new Exception("Device not found"));
                        }

                        var checkRow = checkRows.iterator().next();

                        if (Boolean.TRUE.equals(checkRow.getBoolean("is_deleted")))
                        {
                            return Future.failedFuture(new Exception("Device is deleted and cannot be updated"));
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

                        if (params.isEmpty())
                        {
                            return Future.failedFuture(new Exception("No updatable fields provided"));
                        }

                        var sqlStr = sqlBuilder.toString();

                        if (sqlStr.endsWith(", "))
                        {
                            sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
                        }

                        var sql = sqlStr + " WHERE device_id = $" + paramIndex + " AND is_deleted = false" +
                                " RETURNING device_id, device_name, polling_interval_seconds, timeout_seconds, is_provisioned";

                        params.add(UUID.fromString(deviceId));

                        return dbHelper.executePreparedQuery(sql, Tuple.from(params.getList()))
                                .map(rows ->
                                {
                                    if (rows.size() == 0)
                                    {
                                        throw new RuntimeException("Device not found or already deleted");
                                    }

                                    var row = rows.iterator().next();

                                    return new JsonObject()
                                            .put("success", true)
                                            .put("device_id", row.getUUID("device_id").toString())
                                            .put("device_name", row.getString("device_name"))
                                            .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                            .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                            .put("is_provisioned", row.getBoolean("is_provisioned"))
                                            .put("message", "Device configuration updated successfully");
                                })
                                .recover(cause ->
                                {
                                    logger.error("Failed to update device configuration: {}", cause.getMessage());

                                    if (cause.getMessage() != null && (
                                            cause.getMessage().contains("chk_cpu_threshold") ||
                                            cause.getMessage().contains("chk_memory_threshold") ||
                                            cause.getMessage().contains("chk_disk_threshold")))
                                    {
                                        return Future.failedFuture(new Exception("Threshold values must be between 0 and 100"));
                                    }
                                    else if (cause.getMessage() != null && cause.getMessage().contains("chk_port_range"))
                                    {
                                        return Future.failedFuture(new Exception("Port must be between 1 and 65535"));
                                    }
                                    else if (cause.getMessage() != null && cause.getMessage().contains("chk_timeout_range"))
                                    {
                                        return Future.failedFuture(new Exception("Invalid timeout value"));
                                    }
                                    else
                                    {
                                        return Future.failedFuture(cause);
                                    }
                                });
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceUpdateConfig service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}
