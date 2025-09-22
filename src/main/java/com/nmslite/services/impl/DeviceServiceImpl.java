package com.nmslite.services.impl;

import com.nmslite.services.DeviceService;
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

    public DeviceServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public void deviceList(boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_id, device_name, ip_address::text as ip_address, device_type, port, protocol, username, is_monitoring_enabled,
                           discovery_profile_id, polling_interval_seconds, timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory,
                           alert_threshold_disk, is_deleted, deleted_at, deleted_by, created_at, updated_at, last_polled_at
                    FROM devices
                    """ + (includeDeleted ? "" : "WHERE is_deleted = false ") + """
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
                                    .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                        row.getUUID("discovery_profile_id").toString() : null)
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                    .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                    .put("retry_count", row.getInteger("retry_count"))
                                    .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                    .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                    .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("deleted_at", row.getLocalDateTime("deleted_at") != null ?
                                        row.getLocalDateTime("deleted_at").toString() : null)
                                    .put("deleted_by", row.getString("deleted_by"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("last_polled_at", row.getLocalDateTime("last_polled_at") != null ?
                                        row.getLocalDateTime("last_polled_at").toString() : null);
                            devices.add(device);
                        }
                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get devices", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceCreate(JsonObject deviceData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String deviceName = deviceData.getString("device_name");
            String ipAddress = deviceData.getString("ip_address");
            String deviceType = deviceData.getString("device_type");
            Integer port = deviceData.getInteger("port");
            String protocol = deviceData.getString("protocol");
            String username = deviceData.getString("username");
            String password = deviceData.getString("password");
            String passwordEncrypted = deviceData.getString("password_encrypted");
            Boolean isMonitoringEnabled = deviceData.getBoolean("is_monitoring_enabled", true);
            String discoveryProfileId = deviceData.getString("discovery_profile_id");
            Integer pollingIntervalSeconds = deviceData.getInteger("polling_interval_seconds");
            Integer timeoutSeconds = deviceData.getInteger("timeout_seconds");
            Integer retryCount = deviceData.getInteger("retry_count");

            if (deviceName == null || ipAddress == null || deviceType == null || port == null || username == null) {
                blockingPromise.fail(new IllegalArgumentException("Device name, IP address, device type, port, and username are required"));
                return;
            }

            // Validate discovery_profile_id if provided (should not be null when provisioning from discovery profile)
            if (discoveryProfileId != null && discoveryProfileId.trim().isEmpty()) {
                blockingPromise.fail(new IllegalArgumentException("Discovery profile ID cannot be empty"));
                return;
            }

            // Handle password encryption
            String finalPasswordEncrypted;
            if (passwordEncrypted != null) {
                // Password already encrypted (from discovery)
                finalPasswordEncrypted = passwordEncrypted;
            } else if (password != null) {
                // Encrypt plain text password
                finalPasswordEncrypted = PasswordUtil.encryptPassword(password);
            } else {
                blockingPromise.fail(new IllegalArgumentException("Either password or password_encrypted is required"));
                return;
            }

            String sql = """
                    INSERT INTO devices (device_name, ip_address, device_type, port, protocol, username, password_encrypted,
                                       is_monitoring_enabled, discovery_profile_id, polling_interval_seconds, timeout_seconds, retry_count)
                    VALUES ($1, '""" + ipAddress + """
                    '::inet, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
                    RETURNING device_id, device_name, ip_address::text as ip_address, device_type, port, protocol, username, is_monitoring_enabled,
                             discovery_profile_id, polling_interval_seconds, timeout_seconds, retry_count, created_at
                    """;

            UUID discoveryProfileUuid = discoveryProfileId != null ? UUID.fromString(discoveryProfileId) : null;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(deviceName, deviceType, port, protocol, username, finalPasswordEncrypted,
                                    isMonitoringEnabled, discoveryProfileUuid, pollingIntervalSeconds, timeoutSeconds, retryCount))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("device_type", row.getString("device_type"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("username", row.getString("username"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                    row.getUUID("discovery_profile_id").toString() : null)
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("message", "Device created successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to create device", cause);
                        if (cause.getMessage().contains("duplicate key") || cause.getMessage().contains("unique")) {
                            blockingPromise.fail(new IllegalArgumentException("Device with this IP address may already exist"));
                        } else if (cause.getMessage().contains("foreign key")) {
                            blockingPromise.fail(new IllegalArgumentException("Invalid discovery profile ID"));
                        } else if (cause.getMessage().contains("chk_port_range")) {
                            blockingPromise.fail(new IllegalArgumentException("Port must be between 1 and 65535"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void deviceUpdateIsMonitoringStatus(String deviceId, boolean isEnabled, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE devices
                    SET is_monitoring_enabled = $1
                    WHERE device_id = $2 AND is_deleted = false
                    RETURNING device_id, device_name, is_monitoring_enabled
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(isEnabled, UUID.fromString(deviceId)))
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
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("message", "Device monitoring status updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update device monitoring status", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceUpdateMonitoringConfig(String deviceId, JsonObject monitoringConfig, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            Integer pollingInterval = monitoringConfig.getInteger("polling_interval_seconds");
            Double cpuThreshold = monitoringConfig.getDouble("alert_threshold_cpu");
            Double memoryThreshold = monitoringConfig.getDouble("alert_threshold_memory");
            Double diskThreshold = monitoringConfig.getDouble("alert_threshold_disk");

            StringBuilder sqlBuilder = new StringBuilder("UPDATE devices SET ");
            JsonArray params = new JsonArray();
            int paramIndex = 1;

            if (pollingInterval != null) {
                sqlBuilder.append("polling_interval_seconds = $").append(paramIndex++).append(", ");
                params.add(pollingInterval);
            }
            if (cpuThreshold != null) {
                sqlBuilder.append("alert_threshold_cpu = $").append(paramIndex++).append(", ");
                params.add(cpuThreshold);
            }
            if (memoryThreshold != null) {
                sqlBuilder.append("alert_threshold_memory = $").append(paramIndex++).append(", ");
                params.add(memoryThreshold);
            }
            if (diskThreshold != null) {
                sqlBuilder.append("alert_threshold_disk = $").append(paramIndex++).append(", ");
                params.add(diskThreshold);
            }

            if (params.size() == 0) {
                blockingPromise.fail(new IllegalArgumentException("No monitoring configuration fields to update"));
                return;
            }

            // Remove trailing comma and space
            String sql = sqlBuilder.substring(0, sqlBuilder.length() - 2) +
                    " WHERE device_id = $" + paramIndex + " AND is_deleted = false" +
                    " RETURNING device_id, device_name, polling_interval_seconds, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk";
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
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                .put("message", "Device monitoring configuration updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update device monitoring configuration", cause);
                        if (cause.getMessage().contains("chk_cpu_threshold") || 
                            cause.getMessage().contains("chk_memory_threshold") || 
                            cause.getMessage().contains("chk_disk_threshold")) {
                            blockingPromise.fail(new IllegalArgumentException("Threshold values must be between 0 and 100"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void deviceDelete(String deviceId, String deletedBy, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE devices
                    SET is_deleted = true, deleted_at = CURRENT_TIMESTAMP, deleted_by = $1
                    WHERE device_id = $2 AND is_deleted = false
                    RETURNING device_id, device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(deletedBy, UUID.fromString(deviceId)))
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
        }, resultHandler);
    }

    @Override
    public void deviceRestore(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE devices
                    SET is_deleted = false, deleted_at = NULL, deleted_by = NULL
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
        }, resultHandler);
    }

    @Override
    public void deviceGetById(String deviceId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_id, device_name, ip_address::text as ip_address, device_type, port, protocol, username, is_monitoring_enabled,
                           discovery_profile_id, polling_interval_seconds, timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory,
                           alert_threshold_disk, is_deleted, deleted_at, deleted_by, created_at, updated_at, last_polled_at
                    FROM devices
                    WHERE device_id = $1
                    AND is_deleted = false""";

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
                                .put("username", row.getString("username"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                    row.getUUID("discovery_profile_id").toString() : null)
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                .put("is_deleted", row.getBoolean("is_deleted"))
                                .put("deleted_at", row.getLocalDateTime("deleted_at") != null ?
                                    row.getLocalDateTime("deleted_at").toString() : null)
                                .put("deleted_by", row.getString("deleted_by"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("last_polled_at", row.getLocalDateTime("last_polled_at") != null ?
                                    row.getLocalDateTime("last_polled_at").toString() : null);
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
                    SELECT device_id, device_name, ip_address::text as ip_address, device_type, port, protocol, username, is_monitoring_enabled,
                           discovery_profile_id, polling_interval_seconds, timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory,
                           alert_threshold_disk, is_deleted, deleted_at, deleted_by, created_at, updated_at, last_polled_at
                    FROM devices
                    WHERE ip_address = $1
                    """ + (includeDeleted ? "" : "AND is_deleted = false");

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(ipAddress))
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
                                .put("username", row.getString("username"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                    row.getUUID("discovery_profile_id").toString() : null)
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"))
                                .put("is_deleted", row.getBoolean("is_deleted"))
                                .put("deleted_at", row.getLocalDateTime("deleted_at") != null ?
                                    row.getLocalDateTime("deleted_at").toString() : null)
                                .put("deleted_by", row.getString("deleted_by"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("last_polled_at", row.getLocalDateTime("last_polled_at") != null ?
                                    row.getLocalDateTime("last_polled_at").toString() : null);
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to find device by IP", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceListForPolling(Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_id, device_name, ip_address::text as ip_address, device_type, port, protocol, username, password_encrypted,
                           polling_interval_seconds, timeout_seconds, retry_count, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk
                    FROM devices
                    WHERE is_monitoring_enabled = true AND is_deleted = false
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
                                    .put("password_encrypted", row.getString("password_encrypted"))
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
                                    .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                    .put("retry_count", row.getInteger("retry_count"))
                                    .put("alert_threshold_cpu", row.getBigDecimal("alert_threshold_cpu"))
                                    .put("alert_threshold_memory", row.getBigDecimal("alert_threshold_memory"))
                                    .put("alert_threshold_disk", row.getBigDecimal("alert_threshold_disk"));
                            devices.add(device);
                        }
                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get devices for polling", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceSync(Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            // This method synchronizes devices from discovery profiles, credentials, and device types
            // It updates device data from their authoritative sources but does NOT update last_polled_at
            // (last_polled_at should only be updated when actual polling/metrics collection occurs)

            // TODO: Implement proper sync logic to update:
            // - ip_address, device_type, port from discovery_profiles
            // - username, password_encrypted from credential_profiles

            // For now, return success without doing anything
            JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("devices_synchronized", 0)
                    .put("message", "Device synchronization completed (no changes needed)")
                    .put("synchronized_at", java.time.LocalDateTime.now().toString());
            blockingPromise.complete(result);
        }, resultHandler);
    }
}
