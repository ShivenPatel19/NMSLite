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
                    SELECT device_id, device_name, ip_address, device_type, port, username, is_monitoring_enabled,
                           discovery_profile_id, polling_interval_seconds, alert_threshold_cpu, alert_threshold_memory, 
                           alert_threshold_disk, is_deleted, deleted_at, deleted_by, created_at, updated_at, last_discovered_at
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
                                    .put("username", row.getString("username"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ? 
                                        row.getUUID("discovery_profile_id").toString() : null)
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
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
                                    .put("last_discovered_at", row.getLocalDateTime("last_discovered_at") != null ? 
                                        row.getLocalDateTime("last_discovered_at").toString() : null);
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
            String username = deviceData.getString("username");
            String password = deviceData.getString("password");
            String passwordEncrypted = deviceData.getString("password_encrypted");
            Boolean isMonitoringEnabled = deviceData.getBoolean("is_monitoring_enabled", true);
            String discoveryProfileId = deviceData.getString("discovery_profile_id");

            if (deviceName == null || ipAddress == null || deviceType == null || port == null || username == null) {
                blockingPromise.fail(new IllegalArgumentException("Device name, IP address, device type, port, and username are required"));
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
                    INSERT INTO devices (device_name, ip_address, device_type, port, username, password_encrypted, 
                                       is_monitoring_enabled, discovery_profile_id)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                    RETURNING device_id, device_name, ip_address, device_type, port, username, is_monitoring_enabled, 
                             discovery_profile_id, created_at, last_discovered_at
                    """;

            UUID discoveryProfileUuid = discoveryProfileId != null ? UUID.fromString(discoveryProfileId) : null;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(deviceName, ipAddress, deviceType, port, username, finalPasswordEncrypted, 
                                    isMonitoringEnabled, discoveryProfileUuid))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_id", row.getUUID("device_id").toString())
                                .put("device_name", row.getString("device_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("device_type", row.getString("device_type"))
                                .put("port", row.getInteger("port"))
                                .put("username", row.getString("username"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ? 
                                    row.getUUID("discovery_profile_id").toString() : null)
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("last_discovered_at", row.getLocalDateTime("last_discovered_at") != null ? 
                                    row.getLocalDateTime("last_discovered_at").toString() : null)
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
    public void deviceUpdateMonitoring(String deviceId, boolean isEnabled, Handler<AsyncResult<JsonObject>> resultHandler) {

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
    public void deviceGetById(String deviceId, boolean includeDeleted, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_id, device_name, ip_address, device_type, port, username, is_monitoring_enabled,
                           discovery_profile_id, polling_interval_seconds, alert_threshold_cpu, alert_threshold_memory,
                           alert_threshold_disk, is_deleted, deleted_at, deleted_by, created_at, updated_at, last_discovered_at
                    FROM devices
                    WHERE device_id = $1
                    """ + (includeDeleted ? "" : "AND is_deleted = false");

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
                                .put("username", row.getString("username"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                    row.getUUID("discovery_profile_id").toString() : null)
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
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
                                .put("last_discovered_at", row.getLocalDateTime("last_discovered_at") != null ?
                                    row.getLocalDateTime("last_discovered_at").toString() : null);
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
                    SELECT device_id, device_name, ip_address, device_type, port, username, is_monitoring_enabled,
                           discovery_profile_id, polling_interval_seconds, alert_threshold_cpu, alert_threshold_memory,
                           alert_threshold_disk, is_deleted, deleted_at, deleted_by, created_at, updated_at, last_discovered_at
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
                                .put("username", row.getString("username"))
                                .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                    row.getUUID("discovery_profile_id").toString() : null)
                                .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
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
                                .put("last_discovered_at", row.getLocalDateTime("last_discovered_at") != null ?
                                    row.getLocalDateTime("last_discovered_at").toString() : null);
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
                    SELECT device_id, device_name, ip_address, device_type, port, username, password_encrypted,
                           polling_interval_seconds, alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk
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
                                    .put("username", row.getString("username"))
                                    .put("password_encrypted", row.getString("password_encrypted"))
                                    .put("polling_interval_seconds", row.getInteger("polling_interval_seconds"))
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
    public void deviceListWithAvailability(boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT d.device_id, d.device_name, d.ip_address, d.device_type, d.port, d.username,
                           d.is_monitoring_enabled, d.is_deleted, d.created_at,
                           da.availability_percentage, da.uptime_hours, da.downtime_hours, da.last_check_time, da.status
                    FROM devices d
                    LEFT JOIN device_availability da ON d.device_id = da.device_id
                    """ + (includeDeleted ? "" : "WHERE d.is_deleted = false ") + """
                    ORDER BY d.device_name
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
                                    .put("username", row.getString("username"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("availability_percentage", row.getBigDecimal("availability_percentage"))
                                    .put("uptime_hours", row.getBigDecimal("uptime_hours"))
                                    .put("downtime_hours", row.getBigDecimal("downtime_hours"))
                                    .put("last_check_time", row.getLocalDateTime("last_check_time") != null ?
                                        row.getLocalDateTime("last_check_time").toString() : null)
                                    .put("status", row.getString("status"));
                            devices.add(device);
                        }
                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get devices with availability", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceListWithStatus(boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT d.device_id, d.device_name, d.ip_address, d.device_type, d.port, d.username,
                           d.is_monitoring_enabled, d.is_deleted, d.created_at, d.last_discovered_at,
                           da.status, da.last_check_time, da.availability_percentage
                    FROM devices d
                    LEFT JOIN device_availability da ON d.device_id = da.device_id
                    """ + (includeDeleted ? "" : "WHERE d.is_deleted = false ") + """
                    ORDER BY d.device_name
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
                                    .put("username", row.getString("username"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("last_discovered_at", row.getLocalDateTime("last_discovered_at") != null ?
                                        row.getLocalDateTime("last_discovered_at").toString() : null)
                                    .put("status", row.getString("status"))
                                    .put("last_check_time", row.getLocalDateTime("last_check_time") != null ?
                                        row.getLocalDateTime("last_check_time").toString() : null)
                                    .put("availability_percentage", row.getBigDecimal("availability_percentage"));
                            devices.add(device);
                        }
                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get devices with status", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceSearchByName(String namePattern, boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_id, device_name, ip_address, device_type, port, username, is_monitoring_enabled,
                           discovery_profile_id, is_deleted, created_at, updated_at, last_discovered_at
                    FROM devices
                    WHERE device_name ILIKE $1
                    """ + (includeDeleted ? "" : "AND is_deleted = false ") + """
                    ORDER BY device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of("%" + namePattern + "%"))
                    .onSuccess(rows -> {
                        JsonArray devices = new JsonArray();
                        for (Row row : rows) {
                            JsonObject device = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("device_type", row.getString("device_type"))
                                    .put("port", row.getInteger("port"))
                                    .put("username", row.getString("username"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                        row.getUUID("discovery_profile_id").toString() : null)
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("last_discovered_at", row.getLocalDateTime("last_discovered_at") != null ?
                                        row.getLocalDateTime("last_discovered_at").toString() : null);
                            devices.add(device);
                        }
                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to search devices by name", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceListByMonitoringStatus(boolean isMonitoringEnabled, boolean includeDeleted, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_id, device_name, ip_address, device_type, port, username, is_monitoring_enabled,
                           discovery_profile_id, is_deleted, created_at, updated_at, last_discovered_at
                    FROM devices
                    WHERE is_monitoring_enabled = $1
                    """ + (includeDeleted ? "" : "AND is_deleted = false ") + """
                    ORDER BY device_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(isMonitoringEnabled))
                    .onSuccess(rows -> {
                        JsonArray devices = new JsonArray();
                        for (Row row : rows) {
                            JsonObject device = new JsonObject()
                                    .put("device_id", row.getUUID("device_id").toString())
                                    .put("device_name", row.getString("device_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("device_type", row.getString("device_type"))
                                    .put("port", row.getInteger("port"))
                                    .put("username", row.getString("username"))
                                    .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                                    .put("discovery_profile_id", row.getUUID("discovery_profile_id") != null ?
                                        row.getUUID("discovery_profile_id").toString() : null)
                                    .put("is_deleted", row.getBoolean("is_deleted"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("last_discovered_at", row.getLocalDateTime("last_discovered_at") != null ?
                                        row.getLocalDateTime("last_discovered_at").toString() : null);
                            devices.add(device);
                        }
                        blockingPromise.complete(devices);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get devices by monitoring status", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceGetCounts(Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT
                        COUNT(*) as total,
                        COUNT(CASE WHEN is_deleted = false THEN 1 END) as active,
                        COUNT(CASE WHEN is_deleted = true THEN 1 END) as deleted,
                        COUNT(CASE WHEN is_monitoring_enabled = true AND is_deleted = false THEN 1 END) as monitoring_enabled,
                        COUNT(CASE WHEN is_monitoring_enabled = false AND is_deleted = false THEN 1 END) as monitoring_disabled
                    FROM devices
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("total", row.getLong("total"))
                                .put("active", row.getLong("active"))
                                .put("deleted", row.getLong("deleted"))
                                .put("monitoring_enabled", row.getLong("monitoring_enabled"))
                                .put("monitoring_disabled", row.getLong("monitoring_disabled"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get device counts", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceSync(Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            // This method would synchronize devices from discovery profiles, credentials, and device types
            // For now, we'll implement a basic sync that updates last_discovered_at for all active devices
            String sql = """
                    UPDATE devices
                    SET last_discovered_at = CURRENT_TIMESTAMP
                    WHERE is_deleted = false
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        int updatedCount = rows.rowCount();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("devices_synchronized", updatedCount)
                                .put("message", "Device synchronization completed successfully")
                                .put("synchronized_at", java.time.LocalDateTime.now().toString());
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to synchronize devices", cause);
                        JsonObject result = new JsonObject()
                                .put("success", false)
                                .put("message", "Device synchronization failed: " + cause.getMessage());
                        blockingPromise.complete(result);
                    });
        }, resultHandler);
    }
}
