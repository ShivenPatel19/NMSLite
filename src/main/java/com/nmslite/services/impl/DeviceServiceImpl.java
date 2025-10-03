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
public class DeviceServiceImpl implements DeviceService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;
    private final JsonObject config;

    public DeviceServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
        this.config = vertx.getOrCreateContext().config();
    }

    // Default values from config
    private double getDefaultCpuThreshold() {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getDouble("alert.threshold.cpu", 80.0);
    }

    private double getDefaultMemoryThreshold() {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getDouble("alert.threshold.memory", 85.0);
    }

    private double getDefaultDiskThreshold() {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getDouble("alert.threshold.disk", 90.0);
    }

    private int getDatabaseBlockingTimeout() {
        return config.getJsonObject("database", new JsonObject())
                .getInteger("blocking.timeout.seconds", 60);
    }

    private int getDefaultPollingInterval() {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getInteger("device.polling.interval.seconds", 300);
    }

    private int getDefaultTimeout() {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getInteger("device.timeout.seconds", 300);
    }

    private int getDefaultRetryCount() {
        return config.getJsonObject("device", new JsonObject())
                .getJsonObject("defaults", new JsonObject())
                .getInteger("retry.count", 2);
    }

    @Override
    public void deviceListByProvisioned(boolean isProvisioned, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
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
                    .onSuccess(rows -> {
                        JsonArray devices = new JsonArray();
                        for (Row row : rows) {
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
                    .onFailure(cause -> {
                        logger.error("Failed to list devices by provision status", cause);
                        blockingPromise.fail(cause);
                    });
        }, false, resultHandler);
    }

    // REMOVED - Manual device creation not needed (devices created via discovery only)
    // @Override
    // public void deviceCreate(JsonObject deviceData, Handler<AsyncResult<JsonObject>> resultHandler) {

    //     vertx.executeBlocking(blockingPromise -> {
    //         String deviceName = deviceData.getString("device_name");
    //         String ipAddress = deviceData.getString("ip_address");
    //         String deviceType = deviceData.getString("device_type");
    //         Integer port = deviceData.getInteger("port");
    //         String protocol = deviceData.getString("protocol");
    //         String username = deviceData.getString("username");
    //         String password = deviceData.getString("password");
    //         String passwordEncrypted = deviceData.getString("password_encrypted");
    //         Boolean isMonitoringEnabled = deviceData.getBoolean("is_monitoring_enabled", true);

    //         // Use config defaults if not provided
    //         Integer pollingIntervalSeconds = deviceData.getInteger("polling_interval_seconds", getDefaultPollingInterval());
    //         Integer timeoutSeconds = deviceData.getInteger("timeout_seconds", getDefaultTimeout());
    //         Integer retryCount = deviceData.getInteger("retry_count", getDefaultRetryCount());

    //         // Use config defaults for alert thresholds if not provided
    //         Double alertThresholdCpu = deviceData.getDouble("alert_threshold_cpu", getDefaultCpuThreshold());
    //         Double alertThresholdMemory = deviceData.getDouble("alert_threshold_memory", getDefaultMemoryThreshold());
    //         Double alertThresholdDisk = deviceData.getDouble("alert_threshold_disk", getDefaultDiskThreshold());

    //         // ===== TRUST HANDLER VALIDATION =====
    //         // No validation here - handler has already validated all input
    //         // Service focuses purely on database operations

    //         // Handle password encryption (no validation - handler ensures password is provided)
    //         String finalPasswordEncrypted;
    //         if (passwordEncrypted != null) {
    //             // Password already encrypted (from discovery)
    //             finalPasswordEncrypted = passwordEncrypted;
    //         } else {
    //             // Encrypt plain text password
    //             finalPasswordEncrypted = PasswordUtil.encryptPassword(password);
    //         }

    //         String sql = """
    //                 INSERT INTO devices (device_name, ip_address, device_type, port, protocol, username, password_encrypted,
    //                                    is_monitoring_enabled, polling_interval_seconds, timeout_seconds, retry_count,
    //                                    alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk,
    //                                    host_name, is_provisioned)
    //                 VALUES ($1, '""" + ipAddress + """
    //                 '::inet, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
    //                 RETURNING device_id, device_name, ip_address::text as ip_address, device_type, port, protocol, username, is_monitoring_enabled,
    //                          polling_interval_seconds, timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk,
    //                          host_name, is_provisioned, created_at
    //                 """;

    //         // Extract hostname and provisioning status from request
    //         String hostName = deviceData.getString("host_name"); // Can be null for manual creation
    //         Boolean isProvisioned = deviceData.getBoolean("is_provisioned", false); // Default false for manual creation

    //         // Device naming logic:
    //         // 1. If device_name is provided, use it as-is
    //         // 2. If device_name is not provided but host_name is available, use host_name as device_name
    //         // 3. If neither is provided, validation should have caught this (device_name is required)
    //         if ((deviceName == null || deviceName.trim().isEmpty()) && hostName != null && !hostName.trim().isEmpty()) {
    //             deviceName = hostName;
    //             logger.info("🏷️ No device_name provided, using hostname as device_name: {}", deviceName);
    //         } else if (deviceName != null && hostName != null && !hostName.trim().isEmpty()) {
    //             logger.info("🏷️ Using provided device_name: '{}', hostname will be stored separately: '{}'", deviceName, hostName);
    //         } else if (deviceName != null && (hostName == null || hostName.trim().isEmpty())) {
    //             logger.info("🏷️ Using provided device_name: '{}', hostname not available (will be discovered later)", deviceName);
    //         }

    //         pgPool.preparedQuery(sql)
    //                 .execute(Tuple.of(deviceName, deviceType, port, protocol, username, finalPasswordEncrypted,
    //                                 isMonitoringEnabled, pollingIntervalSeconds, timeoutSeconds, retryCount,
    //                                 alertThresholdCpu, alertThresholdMemory, alertThresholdDisk,
    //                                 hostName, isProvisioned))
    //                 .onSuccess(rows -> {
    //                     Row row = rows.iterator().next();
    //                     JsonObject result = new JsonObject()
    //                             .put("success", true)
    //                             .put("device_id", row.getUUID("device_id").toString())
    //                             .put("device_name", row.getString("device_name"))
    //                             .put("ip_address", row.getString("ip_address"))
    //                             .put("device_type", row.getString("device_type"))
    //                             .put("port", row.getInteger("port"))
    //                             .put("protocol", row.getString("protocol"))
    //                             .put("username", row.getString("username"))
    //                             .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
    //                             .put("host_name", row.getString("host_name"))
    //                             .put("is_provisioned", row.getBoolean("is_provisioned"))
    //                             .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
    //                             .put("timeout_seconds", row.getInteger("timeout_seconds"))
    //                             .put("retry_count", row.getInteger("retry_count"))
    //                             .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
    //                             .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
    //                             .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
    //                             .put("created_at", row.getLocalDateTime("created_at").toString())
    //                             .put("message", "Device created successfully");
    //                     blockingPromise.complete(result);
    //                 })
    //                 .onFailure(cause -> {
    //                     logger.error("Failed to create device", cause);
    //                     if (cause.getMessage().contains("duplicate key") || cause.getMessage().contains("unique")) {
    //                         blockingPromise.fail(new IllegalArgumentException("Device with this IP address may already exist"));
    //                     } else if (cause.getMessage().contains("chk_port_range")) {
    //                         blockingPromise.fail(new IllegalArgumentException("Port must be between 1 and 65535"));
    //                     } else if (cause.getMessage().contains("chk_cpu_threshold") ||
    //                                cause.getMessage().contains("chk_memory_threshold") ||
    //                                cause.getMessage().contains("chk_disk_threshold")) {
    //                         blockingPromise.fail(new IllegalArgumentException("Threshold values must be between 0 and 100"));
    //                     } else {
    //                         blockingPromise.fail(cause);
    //                     }
    //                 });
    //     }, resultHandler);
    // }

    @Override
    public void deviceDelete(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE devices
                    SET is_deleted = true, deleted_at = CURRENT_TIMESTAMP
                    WHERE device_id = $1 AND is_deleted = false
                    RETURNING device_id, device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
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
                    .onFailure(cause -> {
                        logger.error("Failed to delete device", cause);
                        blockingPromise.fail(cause);
                    });
        }, false, resultHandler);
    }

    @Override
    public void deviceRestore(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE devices
                    SET is_deleted = false, deleted_at = NULL
                    WHERE device_id = $1 AND is_deleted = true
                    RETURNING device_id, device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
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
                    .onFailure(cause -> {
                        logger.error("Failed to restore device", cause);
                        blockingPromise.fail(cause);
                    });
        }, false, resultHandler);
    }

    @Override
    public void deviceGetById(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT d.device_id, d.device_name, d.ip_address::text as ip_address, d.device_type, d.port, d.protocol,
                           d.credential_profile_id, cp.username, cp.profile_name as credential_profile_name,
                           d.is_monitoring_enabled, d.polling_interval_seconds, d.timeout_seconds, d.retry_count,
                           d.alert_threshold_cpu, d.alert_threshold_memory, d.alert_threshold_disk, d.host_name,
                           d.is_provisioned, d.is_deleted, d.deleted_at, d.created_at, d.updated_at, d.last_polled_at, d.monitoring_enabled_at
                    FROM devices d
                    JOIN credential_profiles cp ON d.credential_profile_id = cp.credential_profile_id
                    WHERE d.device_id = $1
                    AND d.is_deleted = false""";

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
                    .onFailure(cause -> {
                        logger.error("Failed to get device by ID", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceFindByIp(String ipAddress, boolean includeDeleted, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
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
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject().put("found", false));
                            return;
                        }

                        Row row = rows.iterator().next();
                        String ipAddr = row.getString("ip_address");
                        if (ipAddr != null && ipAddr.contains("/")) {
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
                    .onFailure(cause -> {
                        logger.error("Failed to find device by IP", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceEnableMonitoring(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE devices
                    SET is_monitoring_enabled = true,
                        monitoring_enabled_at = COALESCE(monitoring_enabled_at, NOW())
                    WHERE device_id = $1 AND is_deleted = false
                    RETURNING device_id, is_monitoring_enabled, monitoring_enabled_at
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
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
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to enable monitoring for device", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }


    @Override
    public void deviceDisableMonitoring(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE devices
                    SET is_monitoring_enabled = false
                    WHERE device_id = $1 AND is_deleted = false
                    RETURNING device_id, is_monitoring_enabled, monitoring_enabled_at
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
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
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to disable monitoring for device", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }


    public void deviceListProvisionedAndMonitoringEnabled(Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_id, device_name, ip_address::text as ip_address, device_type, port, protocol, username, is_monitoring_enabled,
                           polling_interval_seconds, timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk,
                           host_name, is_provisioned, is_deleted, deleted_at, created_at, updated_at, last_polled_at, monitoring_enabled_at
                    FROM devices
                    WHERE is_provisioned = true AND is_monitoring_enabled = true AND is_deleted = false
                    ORDER BY device_name
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        JsonArray devices = new JsonArray();
                        for (Row row : rows) {
                            JsonObject device = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("device_type", row.getString("device_type"))
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("username", row.getString("username"))
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
                    .onFailure(cause -> {
                        logger.error("Failed to list provisioned and monitoring-enabled devices", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }



    @Override
    public void deviceCreateFromDiscovery(JsonObject deviceData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
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
                    .onSuccess(rows -> {
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
                    .onFailure(cause -> {
                        logger.error("Failed to create device from discovery", cause);
                        if (cause.getMessage().contains("duplicate key") || cause.getMessage().contains("unique constraint")) {
                            blockingPromise.fail(new IllegalArgumentException("Device with this IP address already exists"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    // COMMENTED OUT FOR TESTING - Device Provisioning Implementation
    // @Override
    // public void deviceSetProvisioned(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

    //     vertx.executeBlocking(blockingPromise -> {
    //         String sql = """
    //                 UPDATE devices
    //                 SET is_provisioned = true, is_monitoring_enabled = true, last_polled_at = CURRENT_TIMESTAMP
    //                 WHERE device_id = $1 AND is_deleted = false
    //                 RETURNING device_id, device_name, is_provisioned, is_monitoring_enabled, last_polled_at
    //                 """;

    //         pgPool.preparedQuery(sql)
    //                 .execute(Tuple.of(UUID.fromString(deviceId)))
    //                 .onSuccess(rows -> {
    //                     if (rows.size() == 0) {
    //                         blockingPromise.fail(new IllegalArgumentException("Device not found or already deleted"));
    //                         return;
    //                     }
    //                     Row row = rows.iterator().next();
    //                     JsonObject result = new JsonObject()
    //                             .put("success", true)
    //                             .put("device_id", row.getUUID("device_id").toString())
    //                             .put("device_name", row.getString("device_name"))
    //                             .put("is_provisioned", row.getBoolean("is_provisioned"))
    //                             .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
    //                             .put("last_polled_at", row.getLocalDateTime("last_polled_at").toString())
    //                             .put("message", "Device provisioned successfully");
    //                     blockingPromise.complete(result);
    //                 })
    //                 .onFailure(cause -> {
    //                     logger.error("Failed to set device as provisioned", cause);
    //                     blockingPromise.fail(cause);
    //                 });
    //     }, resultHandler);
    // }



    @Override
    public void deviceUpdateConfig(String deviceId, JsonObject updateFields, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            // Basic existence and deletion check
            String checkSql = """
                    SELECT device_id, is_deleted
                    FROM devices
                    WHERE device_id = $1
                    """;

            pgPool.preparedQuery(checkSql)
                    .execute(Tuple.of(UUID.fromString(deviceId)))
                    .onSuccess(checkRows -> {
                        if (checkRows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Device not found"));
                            return;
                        }
                        Row checkRow = checkRows.iterator().next();
                        if (Boolean.TRUE.equals(checkRow.getBoolean("is_deleted"))) {
                            blockingPromise.fail(new IllegalArgumentException("Device is deleted and cannot be updated"));
                            return;
                        }

                        // Build dynamic update for allowed fields only
                        StringBuilder sqlBuilder = new StringBuilder("UPDATE devices SET ");
                        JsonArray params = new JsonArray();
                        int paramIndex = 1;

                        if (updateFields.containsKey("device_name")) {
                            sqlBuilder.append("device_name = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getString("device_name"));
                        }
                        if (updateFields.containsKey("port")) {
                            sqlBuilder.append("port = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getInteger("port"));
                        }
                        if (updateFields.containsKey("polling_interval_seconds")) {
                            sqlBuilder.append("polling_interval_seconds = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getInteger("polling_interval_seconds"));
                        }
                        if (updateFields.containsKey("timeout_seconds")) {
                            sqlBuilder.append("timeout_seconds = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getInteger("timeout_seconds"));
                        }
                        if (updateFields.containsKey("retry_count")) {
                            sqlBuilder.append("retry_count = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getInteger("retry_count"));
                        }
                        if (updateFields.containsKey("alert_threshold_cpu")) {
                            sqlBuilder.append("alert_threshold_cpu = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getDouble("alert_threshold_cpu"));
                        }
                        if (updateFields.containsKey("alert_threshold_memory")) {
                            sqlBuilder.append("alert_threshold_memory = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getDouble("alert_threshold_memory"));
                        }
                        if (updateFields.containsKey("alert_threshold_disk")) {
                            sqlBuilder.append("alert_threshold_disk = $").append(paramIndex++).append(", ");
                            params.add(updateFields.getDouble("alert_threshold_disk"));
                        }

                        if (params.isEmpty()) {
                            blockingPromise.fail(new IllegalArgumentException("No updatable fields provided"));
                            return;
                        }

                        String sqlStr = sqlBuilder.toString();
                        if (sqlStr.endsWith(", ")) {
                            sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
                        }

                        String sql = sqlStr + " WHERE device_id = $" + paramIndex + " AND is_deleted = false" +
                                " RETURNING device_id, device_name, port, polling_interval_seconds, timeout_seconds, retry_count, " +
                                "alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk, is_provisioned, is_monitoring_enabled";

                        params.add(UUID.fromString(deviceId));

                        pgPool.preparedQuery(sql)
                                .execute(Tuple.from(params.getList()))
                                .onSuccess(rows -> {
                                    if (rows.size() == 0) {
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
                                })
                                .onFailure(cause -> {
                                    logger.error("Failed to update device configuration", cause);
                                    if (cause.getMessage() != null && (
                                            cause.getMessage().contains("chk_cpu_threshold") ||
                                            cause.getMessage().contains("chk_memory_threshold") ||
                                            cause.getMessage().contains("chk_disk_threshold"))) {
                                        blockingPromise.fail(new IllegalArgumentException("Threshold values must be between 0 and 100"));
                                    } else if (cause.getMessage() != null && cause.getMessage().contains("chk_port_range")) {
                                        blockingPromise.fail(new IllegalArgumentException("Port must be between 1 and 65535"));
                                    } else if (cause.getMessage() != null && (
                                            cause.getMessage().contains("chk_timeout_range") ||
                                            cause.getMessage().contains("chk_retry_count"))) {
                                        blockingPromise.fail(new IllegalArgumentException("Invalid timeout or retry count value"));
                                    } else {
                                        blockingPromise.fail(cause);
                                    }
                                });
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to check device status", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

}
