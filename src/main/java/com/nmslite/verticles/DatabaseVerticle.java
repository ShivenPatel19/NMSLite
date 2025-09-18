package com.nmslite.verticles;

import com.nmslite.utils.PasswordUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DatabaseVerticle - All Database Operations
 * 
 * Responsibilities:
 * - PostgresSQL connection management
 * - CRUD operations for all tables
 * - Event bus message handling for database operations
 * - Connection pooling and transaction management
 */
public class DatabaseVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseVerticle.class);

    private PgPool pgPool;
    private int blockingTimeoutSeconds;

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("📊 Starting DatabaseVerticle - PostgresSQL Operations");

        // Get blocking timeout from config
        blockingTimeoutSeconds = config().getInteger("blocking.timeout.seconds", 60);
        logger.info("🕐 Database blocking timeout: {} seconds", blockingTimeoutSeconds);

        // Setup PostgresSQL connection
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setPort(config().getInteger("port", 5432))
            .setHost(config().getString("host", "localhost"))
            .setDatabase(config().getString("database", "nmslite"))
            .setUser(config().getString("user", "nmslite"))
            .setPassword(config().getString("password", "nmslite"));

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(config().getInteger("maxSize", 20));

        pgPool = PgPool.pool(vertx, connectOptions, poolOptions);

        // Test database connection
        pgPool.getConnection()
            .onSuccess(connection -> {
                logger.info("✅ Database connection established");
                connection.close();
                setupEventBusConsumers();
                startPromise.complete();
            })
            .onFailure(cause -> {
                logger.error("❌ Failed to connect to database", cause);
                startPromise.fail(cause);
            });
    }

    private void setupEventBusConsumers() {
        // Database query operations
        vertx.eventBus().consumer("db.query", message -> {
            JsonObject request = (JsonObject) message.body();
            String operation = request.getString("operation");
            JsonObject params = request.getJsonObject("params", new JsonObject());

            switch (operation) {
                case "get_devices" -> handleGetDevices(message, params);
                case "get_device_metrics" -> handleGetDeviceMetrics(message, params);
                case "get_device_types" -> handleGetDeviceTypes(message, params);
                case "get_credentials" -> handleGetCredentials(message, params);
                case "devices_ready_for_polling" -> handleGetDevicesForPolling(message, params);
                case "get_discovery_and_credentials" -> handleGetDiscoveryAndCredentials(message, params);
                case "discovery_profile_by_ip" -> handleGetDiscoveryProfileByIp(message, params);
                case "device_by_ip" -> handleGetDeviceByIp(message, params);

                // Enhanced Metrics APIs
                case "get_device_latest_metrics" -> handleGetDeviceLatestMetrics(message, params);
                case "get_device_availability" -> handleGetDeviceAvailability(message, params);

                // Discovery Profile Management APIs
                case "get_discovery_profiles" -> handleGetDiscoveryProfiles(message, params);

                // Discovery Validation APIs
                case "check_discovery_conflicts" -> handleCheckDiscoveryConflicts(message, params);

                // User Management APIs
                case "get_users" -> handleGetUsers(message, params);
                case "create_user" -> handleCreateUser(message, params);
                case "update_user" -> handleUpdateUser(message, params);
                case "delete_user" -> handleDeleteUser(message, params);

                default -> message.fail(400, "Unknown query operation: " + operation);
            }
        });

        // Database insert operations
        vertx.eventBus().consumer("db.insert", message -> {
            JsonObject request = (JsonObject) message.body();
            String operation = request.getString("operation");
            JsonObject params = request.getJsonObject("params", new JsonObject());

            switch (operation) {
                case "device" -> handleInsertDevice(message, params);
                case "metrics" -> handleInsertMetrics(message, params);
                case "create_credentials" -> handleCreateCredentials(message, params);

                // New insert operations
                case "discovery_profile" -> handleInsertDiscoveryProfile(message, params);

                default -> message.fail(400, "Unknown insert operation: " + operation);
            }
        });

        // Database update operations
        vertx.eventBus().consumer("db.update", message -> {
            JsonObject request = (JsonObject) message.body();
            String operation = request.getString("operation");
            JsonObject params = request.getJsonObject("params", new JsonObject());

            switch (operation) {
                case "device_monitoring_status" -> handleUpdateDeviceMonitoringStatus(message, params);
                case "device_availability" -> handleUpdateDeviceAvailability(message, params);
                case "soft_delete_device" -> handleSoftDeleteDevice(message, params);
                case "restore_device" -> handleRestoreDevice(message, params);
                case "delete_discovery_profile" -> handleDeleteDiscoveryProfile(message, params);

                // New update operations
                case "discovery_profile" -> handleUpdateDiscoveryProfile(message, params);
                case "credential_profile" -> handleUpdateCredentialProfile(message, params);
                case "update_user" -> handleUpdateUser(message, params);

                default -> message.fail(400, "Unknown update operation: " + operation);
            }
        });

        // Database delete operations
        vertx.eventBus().consumer("db.delete", message -> {
            JsonObject request = (JsonObject) message.body();
            String operation = request.getString("operation");
            JsonObject params = request.getJsonObject("params", new JsonObject());

            switch (operation) {
                case "credential_profile" -> handleDeleteCredentialProfile(message, params);
                case "discovery_profile" -> handleDeleteDiscoveryProfilePermanent(message, params);
                case "delete_user" -> handleDeleteUser(message, params);
                default -> message.fail(400, "Unknown delete operation: " + operation);
            }
        });

        logger.info("📡 Database event bus consumers setup complete");
    }

    // Query Operations
    private void handleGetDevices(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        vertx.executeBlocking(promise -> {
            boolean includeDeleted = params.getBoolean("include_deleted", false);

            String sql = """
                SELECT d.device_id, d.device_name, d.ip_address, d.device_type, d.port,
                       d.is_monitoring_enabled, d.is_deleted, d.deleted_at, d.deleted_by, d.created_at,
                       da.availability_percent, da.last_check_time
                FROM devices d
                LEFT JOIN device_availability da ON d.device_id = da.device_id
                """ + (includeDeleted ? "" : "WHERE d.is_deleted = false ") + """
                ORDER BY d.created_at DESC
                """;

            pgPool.query(sql)
                .execute()
                .onSuccess(rows -> {
                    JsonArray devices = new JsonArray();
                    for (Row row : rows) {
                        // Convert IP address to string to avoid JSON serialization issues
                        String ipAddress = null;
                        if (row.getValue("ip_address") != null) {
                            ipAddress = ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress();
                        }

                        JsonObject device = new JsonObject()
                            .put("device_id", row.getUUID("device_id").toString())
                            .put("device_name", row.getString("device_name"))
                            .put("ip_address", ipAddress)
                            .put("device_type", row.getString("device_type"))
                            .put("port", row.getInteger("port"))
                            .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                            .put("is_deleted", row.getBoolean("is_deleted"))
                            .put("deleted_at", row.getLocalDateTime("deleted_at") != null ? row.getLocalDateTime("deleted_at").toString() : null)
                            .put("deleted_by", row.getString("deleted_by"))
                            .put("availability_percent", row.getBigDecimal("availability_percent"))
                            .put("last_check_time", row.getLocalDateTime("last_check_time") != null ? row.getLocalDateTime("last_check_time").toString() : null)
                            .put("created_at", row.getLocalDateTime("created_at") != null ? row.getLocalDateTime("created_at").toString() : null);
                        devices.add(device);
                    }
                    promise.complete(new JsonObject().put("devices", devices));
                })
                .onFailure(cause -> {
                    logger.error("Failed to get devices", cause);
                    promise.fail(cause);
                });
        })
        .onSuccess(result -> message.reply(result))
        .onFailure(cause -> {
            logger.error("Database operation failed", cause);
            message.fail(500, "Database error: " + cause.getMessage());
        });
    }

    private void handleGetDeviceMetrics(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String deviceId = params.getString("device_id");
        String sql = """
            SELECT metric_id, success, timestamp, duration_ms,
                   cpu_usage_percent, memory_usage_percent, disk_usage_percent,
                   error_message
            FROM metrics
            WHERE device_id = $1
            ORDER BY timestamp DESC
            LIMIT 100
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(deviceId)))
            .onSuccess(rows -> {
                JsonArray metrics = new JsonArray();
                for (Row row : rows) {
                    JsonObject metric = new JsonObject()
                        .put("metric_id", row.getUUID("metric_id").toString())
                        .put("success", row.getBoolean("success"))
                        .put("timestamp", row.getLocalDateTime("timestamp").toString())
                        .put("duration_ms", row.getInteger("duration_ms"))
                        .put("cpu_usage_percent", row.getBigDecimal("cpu_usage_percent"))
                        .put("memory_usage_percent", row.getBigDecimal("memory_usage_percent"))
                        .put("disk_usage_percent", row.getBigDecimal("disk_usage_percent"))
                        .put("error_message", row.getString("error_message"));
                    metrics.add(metric);
                }
                message.reply(new JsonObject().put("metrics", metrics));
            })
            .onFailure(cause -> {
                logger.error("Failed to get device metrics", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleGetDeviceTypes(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        vertx.executeBlocking(promise -> {
            String sql = "SELECT device_type_id, device_type_name, default_port FROM device_types ORDER BY device_type_name";

            pgPool.query(sql)
                .execute()
                .onSuccess(rows -> {
                    JsonArray deviceTypes = new JsonArray();
                    for (Row row : rows) {
                        JsonObject deviceType = new JsonObject()
                            .put("device_type_id", row.getUUID("device_type_id").toString())
                            .put("device_type_name", row.getString("device_type_name"))
                            .put("default_port", row.getInteger("default_port"));
                        deviceTypes.add(deviceType);
                    }
                    promise.complete(new JsonObject().put("device_types", deviceTypes));
                })
                .onFailure(cause -> {
                    logger.error("Failed to get device types", cause);
                    promise.fail(cause);
                });
        })
        .onSuccess(result -> message.reply(result))
        .onFailure(cause -> {
            logger.error("Database operation failed", cause);
            message.fail(500, "Database error: " + cause.getMessage());
        });
    }

    private void handleGetCredentials(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            SELECT credential_profile_id, profile_name, username, protocol, is_active, created_at
            FROM credential_profiles
            ORDER BY profile_name
            """;

        pgPool.query(sql)
            .execute()
            .onSuccess(rows -> {
                JsonArray credentials = new JsonArray();
                for (Row row : rows) {
                    JsonObject credential = new JsonObject()
                        .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                        .put("profile_name", row.getString("profile_name"))
                        .put("username", row.getString("username"))
                        .put("protocol", row.getString("protocol"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("created_at", row.getLocalDateTime("created_at").toString());
                    credentials.add(credential);
                }
                message.reply(new JsonObject().put("credentials", credentials));
            })
            .onFailure(cause -> {
                logger.error("Failed to get credentials", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleGetDevicesForPolling(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            SELECT d.device_id, d.device_name, d.ip_address, d.device_type, d.port,
                   d.username, d.password_encrypted
            FROM devices d
            WHERE d.is_monitoring_enabled = true AND d.is_deleted = false
            ORDER BY d.device_name
            """;

        pgPool.query(sql)
            .execute()
            .onSuccess(rows -> {
                JsonArray devices = new JsonArray();
                for (Row row : rows) {
                    // Convert IP address to string to avoid JSON serialization issues
                    String ipAddress = null;
                    if (row.getValue("ip_address") != null) {
                        ipAddress = ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress();
                    }

                    JsonObject device = new JsonObject()
                        .put("device_id", row.getUUID("device_id").toString())
                        .put("device_name", row.getString("device_name"))
                        .put("ip_address", ipAddress)
                        .put("device_type", row.getString("device_type"))
                        .put("port", row.getInteger("port"))
                        .put("username", row.getString("username"))
                        .put("password", PasswordUtil.decryptPassword(row.getString("password_encrypted")));
                    devices.add(device);
                }
                message.reply(new JsonObject().put("devices", devices));
            })
            .onFailure(cause -> {
                logger.error("Failed to get devices for polling", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleGetDiscoveryAndCredentials(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String profileId = params.getString("profile_id");
        String sql = """
            SELECT dp.discovery_name, dp.ip_address, dp.port, dt.device_type_name, dt.default_port,
                   cp.username, cp.password_encrypted
            FROM discovery_profiles dp
            JOIN device_types dt ON dp.device_type_id = dt.device_type_id
            JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
            WHERE dp.profile_id = $1
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(profileId)))
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("discovery_name", row.getString("discovery_name"))
                        .put("ip_address", ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress())
                        .put("port", row.getInteger("port") != null ? row.getInteger("port") : row.getInteger("default_port"))
                        .put("device_type_name", row.getString("device_type_name"))
                        .put("username", row.getString("username"))
                        .put("password", PasswordUtil.decryptPassword(row.getString("password_encrypted")));
                    message.reply(result);
                } else {
                    message.fail(404, "Discovery profile not found");
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to get discovery and credentials", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }



    // Insert Operations

    private void handleInsertDevice(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        // Debug logging
        logger.info("🔍 DEBUG: Received device insertion params: {}", params.encode());
        logger.info("🔍 DEBUG: device_name = {}", params.getString("device_name"));
        logger.info("🔍 DEBUG: ip_address = {}", params.getString("ip_address"));
        logger.info("🔍 DEBUG: device_type = {}", params.getString("device_type"));

        // Try using string concatenation to avoid parameter binding issues
        String deviceName = params.getString("device_name");
        String ipAddress = params.getString("ip_address");
        String deviceType = params.getString("device_type");
        Integer port = params.getInteger("port");
        String username = params.getString("username");
        String passwordEncrypted = params.getString("password_encrypted");
        Boolean isMonitoringEnabled = params.getBoolean("is_monitoring_enabled", true);
        String discoveryProfileId = params.getString("discovery_profile_id");

        String sql;
        if (discoveryProfileId != null) {
            // Include discovery_profile_id when provided
            sql = String.format("""
                INSERT INTO devices (device_name, ip_address, device_type, port, username, password_encrypted, discovery_profile_id, is_monitoring_enabled)
                VALUES ('%s', '%s'::inet, '%s', %d, '%s', '%s', '%s'::uuid, %s)
                RETURNING device_id
                """,
                deviceName.replace("'", "''"), // Escape single quotes
                ipAddress,
                deviceType.replace("'", "''"),
                port,
                username.replace("'", "''"),
                passwordEncrypted.replace("'", "''"),
                discoveryProfileId,
                isMonitoringEnabled
            );
        } else {
            // Legacy insertion without discovery_profile_id
            sql = String.format("""
                INSERT INTO devices (device_name, ip_address, device_type, port, username, password_encrypted, is_monitoring_enabled)
                VALUES ('%s', '%s'::inet, '%s', %d, '%s', '%s', %s)
                RETURNING device_id
                """,
                deviceName.replace("'", "''"), // Escape single quotes
                ipAddress,
                deviceType.replace("'", "''"),
                port,
                username.replace("'", "''"),
                passwordEncrypted.replace("'", "''"),
                isMonitoringEnabled
            );
        }

        // Debug the SQL query
        logger.info("🔍 DEBUG: Executing SQL: {}", sql);

        pgPool.query(sql)
            .execute()
            .onSuccess(rows -> {
                Row row = rows.iterator().next();
                String deviceId = row.getUUID("device_id").toString();
                
                // Also initialize device availability
                initializeDeviceAvailability(deviceId);
                
                message.reply(new JsonObject().put("device_id", deviceId));
            })
            .onFailure(cause -> {
                logger.error("Failed to insert device", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void initializeDeviceAvailability(String deviceId) {
        String sql = """
            INSERT INTO device_availability (device_id, total_checks, successful_checks, availability_percent)
            VALUES ($1, 0, 0, 0.00)
            ON CONFLICT (device_id) DO NOTHING
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(deviceId)))
            .onSuccess(rows -> logger.debug("Device availability initialized for {}", deviceId))
            .onFailure(cause -> logger.error("Failed to initialize device availability", cause));
    }

    // Discovery attempts removed - handled in real-time via WebSocket

    private void handleInsertMetrics(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            INSERT INTO metrics (device_id, success, timestamp, duration_ms, cpu_usage_percent, 
                               memory_usage_percent, memory_total_bytes, memory_used_bytes, memory_free_bytes,
                               disk_usage_percent, disk_total_bytes, disk_used_bytes, disk_free_bytes, error_message)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
            RETURNING metric_id
            """;

        Tuple tuple = Tuple.of(
            UUID.fromString(params.getString("device_id")),
            params.getBoolean("success"),
            LocalDateTime.now(),
            params.getInteger("duration_ms"),
            params.getDouble("cpu_usage_percent"),
            params.getDouble("memory_usage_percent"),
            params.getLong("memory_total_bytes"),
            params.getLong("memory_used_bytes"),
            params.getLong("memory_free_bytes"),
            params.getDouble("disk_usage_percent"),
            params.getLong("disk_total_bytes"),
            params.getLong("disk_used_bytes"),
            params.getLong("disk_free_bytes"),
            params.getString("error_message")
        );

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                Row row = rows.iterator().next();
                String metricId = row.getUUID("metric_id").toString();
                message.reply(new JsonObject().put("metric_id", metricId));
            })
            .onFailure(cause -> {
                logger.error("Failed to insert metrics", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleCreateCredentials(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            INSERT INTO credential_profiles (profile_name, username, password_encrypted, protocol, created_by)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING credential_profile_id, created_at
            """;

        // Encrypt the password before storing
        String plainPassword = params.getString("password");
        String encryptedPassword = PasswordUtil.encryptPassword(plainPassword);

        Tuple tuple = Tuple.of(
            params.getString("profile_name"),
            params.getString("username"),
            encryptedPassword,
            params.getString("protocol", "ssh"),
            params.getString("created_by", "system")
        );

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                Row row = rows.iterator().next();
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                    .put("created_at", row.getLocalDateTime("created_at").toString())
                    .put("message", "Credential profile created successfully");
                message.reply(result);
            })
            .onFailure(cause -> {
                logger.error("Failed to create credentials", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    // Update Operations
    private void handleUpdateDeviceMonitoringStatus(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = "UPDATE devices SET is_monitoring_enabled = $1, updated_at = CURRENT_TIMESTAMP WHERE device_id = $2 AND is_deleted = false";

        Tuple tuple = Tuple.of(
            params.getBoolean("is_monitoring_enabled"),
            UUID.fromString(params.getString("device_id"))
        );

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                message.reply(new JsonObject().put("updated", rows.rowCount()));
            })
            .onFailure(cause -> {
                logger.error("Failed to update device monitoring status", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleSoftDeleteDevice(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            UPDATE devices
            SET is_deleted = true,
                deleted_at = CURRENT_TIMESTAMP,
                deleted_by = $2,
                deletion_reason = $3,
                is_monitoring_enabled = false,
                updated_at = CURRENT_TIMESTAMP
            WHERE device_id = $1 AND is_deleted = false
            """;

        Tuple tuple = Tuple.of(
            UUID.fromString(params.getString("device_id")),
            params.getString("deleted_by"),
            params.getString("deletion_reason", "Manual deletion")
        );

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                if (rows.rowCount() > 0) {
                    logger.info("Device soft deleted: {}", params.getString("device_id"));
                    message.reply(new JsonObject().put("deleted", true).put("device_id", params.getString("device_id")));
                } else {
                    message.fail(404, "Device not found or already deleted");
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to soft delete device", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleRestoreDevice(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            UPDATE devices
            SET is_deleted = false,
                deleted_at = NULL,
                deleted_by = NULL,
                deletion_reason = NULL,
                is_monitoring_enabled = true,
                updated_at = CURRENT_TIMESTAMP
            WHERE device_id = $1 AND is_deleted = true
            """;

        Tuple tuple = Tuple.of(UUID.fromString(params.getString("device_id")));

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                if (rows.rowCount() > 0) {
                    logger.info("Device restored: {}", params.getString("device_id"));
                    message.reply(new JsonObject().put("restored", true).put("device_id", params.getString("device_id")));
                } else {
                    message.fail(404, "Device not found or not deleted");
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to restore device", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleDeleteDiscoveryProfile(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String profileId = params.getString("profile_id");

        if (profileId == null) {
            message.fail(400, "profile_id parameter is required");
            return;
        }

        // First check if any active devices are using this discovery profile
        String checkSql = """
            SELECT d.device_id, d.device_name, d.ip_address, dp.discovery_name
            FROM devices d
            JOIN discovery_profiles dp ON d.ip_address = dp.ip_address
            WHERE dp.profile_id = $1 AND d.is_deleted = false
            """;

        pgPool.preparedQuery(checkSql)
            .execute(Tuple.of(UUID.fromString(profileId)))
            .onSuccess(checkRows -> {
                if (checkRows.rowCount() > 0) {
                    // Active devices exist - block deletion
                    JsonArray activeDevices = new JsonArray();
                    String discoveryName = null;

                    for (Row row : checkRows) {
                        if (discoveryName == null) {
                            discoveryName = row.getString("discovery_name");
                        }
                        activeDevices.add(new JsonObject()
                            .put("device_id", row.getUUID("device_id").toString())
                            .put("device_name", row.getString("device_name"))
                            .put("ip_address", ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress()));
                    }

                    logger.warn("❌ Cannot delete discovery profile '{}' - {} active devices depend on it",
                        discoveryName, activeDevices.size());

                    JsonObject errorResponse = new JsonObject()
                        .put("success", false)
                        .put("error", "Cannot delete discovery profile - active devices depend on it")
                        .put("profile_id", profileId)
                        .put("discovery_name", discoveryName)
                        .put("active_devices", activeDevices)
                        .put("active_device_count", activeDevices.size())
                        .put("suggestion", "Soft delete or deactivate devices first, then delete discovery profile");

                    message.reply(errorResponse);
                } else {
                    // No active devices - safe to delete
                    String deleteSql = "DELETE FROM discovery_profiles WHERE profile_id = $1";

                    pgPool.preparedQuery(deleteSql)
                        .execute(Tuple.of(UUID.fromString(profileId)))
                        .onSuccess(deleteRows -> {
                            if (deleteRows.rowCount() > 0) {
                                logger.info("✅ Discovery profile deleted: {}", profileId);
                                message.reply(new JsonObject()
                                    .put("success", true)
                                    .put("deleted", true)
                                    .put("profile_id", profileId));
                            } else {
                                message.fail(404, "Discovery profile not found");
                            }
                        })
                        .onFailure(cause -> {
                            logger.error("Failed to delete discovery profile", cause);
                            message.fail(500, "Database error: " + cause.getMessage());
                        });
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to check active devices for discovery profile", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleUpdateDeviceAvailability(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            UPDATE device_availability 
            SET total_checks = total_checks + 1,
                successful_checks = successful_checks + CASE WHEN $2 THEN 1 ELSE 0 END,
                availability_percent = ((successful_checks + CASE WHEN $2 THEN 1 ELSE 0 END) * 100.0 / (total_checks + 1)),
                last_check_time = $3,
                last_success_time = CASE WHEN $2 THEN $3 ELSE last_success_time END,
                updated_at = $3
            WHERE device_id = $1
            """;

        Tuple tuple = Tuple.of(
            UUID.fromString(params.getString("device_id")),
            params.getBoolean("success"),
            LocalDateTime.now()
        );

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                message.reply(new JsonObject().put("updated", rows.rowCount()));
            })
            .onFailure(cause -> {
                logger.error("Failed to update device availability", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }





    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("🛑 Stopping DatabaseVerticle");
        
        if (pgPool != null) {
            pgPool.close()
                .onComplete(result -> {
                    logger.info("✅ Database connection pool closed");
                    stopPromise.complete();
                });
        } else {
            stopPromise.complete();
        }
    }

    private void handleGetDiscoveryProfileByIp(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String ipAddress = params.getString("ip_address");

        if (ipAddress == null) {
            message.fail(400, "ip_address parameter is required");
            return;
        }

        String sql = """
            SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.port, dp.created_at,
                   dt.device_type_name, dt.default_port,
                   cp.profile_name as credential_profile_name, cp.username,
                   dp.created_by as created_by_username
            FROM discovery_profiles dp
            JOIN device_types dt ON dp.device_type_id = dt.device_type_id
            JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
            WHERE dp.ip_address = $1
            ORDER BY dp.created_at DESC
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(ipAddress))
            .onSuccess(rows -> {
                JsonArray profiles = new JsonArray();

                for (Row row : rows) {
                    JsonObject profile = new JsonObject()
                        .put("profile_id", row.getUUID("profile_id").toString())
                        .put("discovery_name", row.getString("discovery_name"))
                        .put("ip_address", ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress())
                        .put("port", row.getInteger("port") != null ? row.getInteger("port") : row.getInteger("default_port"))
                        .put("type_name", row.getString("device_type_name"))
                        .put("credential_profile_name", row.getString("credential_profile_name"))
                        .put("username", row.getString("username"))
                        .put("created_by_username", row.getString("created_by_username"))
                        .put("created_at", row.getLocalDateTime("created_at").toString());

                    profiles.add(profile);
                }

                JsonObject response = new JsonObject()
                    .put("profiles", profiles)
                    .put("count", profiles.size());

                logger.info("📊 Found {} discovery profiles with IP: {}", profiles.size(), ipAddress);
                message.reply(response);
            })
            .onFailure(err -> {
                logger.error("Failed to query discovery profile by IP: " + ipAddress, err);
                message.fail(500, "Database query failed: " + err.getMessage());
            });
    }

    private void handleGetDeviceByIp(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String ipAddress = params.getString("ip_address");
        boolean includeDeleted = params.getBoolean("include_deleted", true); // Default true for discovery checks

        if (ipAddress == null) {
            message.fail(400, "ip_address parameter is required");
            return;
        }

        String sql = """
            SELECT device_id, device_name, ip_address, device_type, port, username,
                   is_monitoring_enabled, is_deleted, deleted_at, deleted_by, created_at
            FROM devices
            WHERE ip_address = $1
            """ + (includeDeleted ? "" : "AND is_deleted = false") + """
            ORDER BY created_at DESC
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(ipAddress))
            .onSuccess(rows -> {
                JsonArray devices = new JsonArray();

                for (Row row : rows) {
                    JsonObject device = new JsonObject()
                        .put("device_id", row.getUUID("device_id").toString())
                        .put("device_name", row.getString("device_name"))
                        .put("ip_address", ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress())
                        .put("device_type", row.getString("device_type"))
                        .put("port", row.getInteger("port"))
                        .put("username", row.getString("username"))
                        .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                        .put("is_deleted", row.getBoolean("is_deleted"))
                        .put("deleted_at", row.getLocalDateTime("deleted_at") != null ? row.getLocalDateTime("deleted_at").toString() : null)
                        .put("deleted_by", row.getString("deleted_by"))
                        .put("created_at", row.getLocalDateTime("created_at").toString());

                    devices.add(device);
                }

                JsonObject response = new JsonObject()
                    .put("devices", devices)
                    .put("count", devices.size())
                    .put("has_active", devices.stream().anyMatch(d -> !((JsonObject)d).getBoolean("is_deleted")))
                    .put("has_deleted", devices.stream().anyMatch(d -> ((JsonObject)d).getBoolean("is_deleted")));

                logger.info("📊 Found {} devices with IP: {} (active: {}, deleted: {})",
                    devices.size(), ipAddress,
                    response.getBoolean("has_active"),
                    response.getBoolean("has_deleted"));
                message.reply(response);
            })
            .onFailure(err -> {
                logger.error("Failed to query device by IP: " + ipAddress, err);
                message.fail(500, "Database query failed: " + err.getMessage());
            });
    }

    // ========================================
    // ENHANCED METRICS OPERATIONS
    // ========================================

    private void handleGetDeviceLatestMetrics(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String deviceId = params.getString("device_id");

        if (deviceId == null) {
            message.fail(400, "device_id parameter is required");
            return;
        }

        String sql = """
            SELECT metric_id, success, timestamp, duration_ms,
                   cpu_usage_percent, memory_usage_percent, memory_total_bytes,
                   memory_used_bytes, memory_free_bytes, disk_usage_percent,
                   disk_total_bytes, disk_used_bytes, disk_free_bytes, error_message
            FROM metrics
            WHERE device_id = $1
            ORDER BY timestamp DESC
            LIMIT 1
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(deviceId)))
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    JsonObject metric = new JsonObject()
                        .put("metric_id", row.getUUID("metric_id").toString())
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

                    message.reply(new JsonObject().put("latest_metric", metric));
                } else {
                    message.reply(new JsonObject().put("latest_metric", null));
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to get latest metrics", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleGetDeviceAvailability(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String deviceId = params.getString("device_id");

        if (deviceId == null) {
            message.fail(400, "device_id parameter is required");
            return;
        }

        String sql = """
            SELECT da.total_checks, da.successful_checks, da.availability_percent,
                   da.last_check_time, da.last_success_time, da.updated_at,
                   d.device_name, d.ip_address, d.is_monitoring_enabled
            FROM device_availability da
            JOIN devices d ON da.device_id = d.device_id
            WHERE da.device_id = $1
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(deviceId)))
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    JsonObject availability = new JsonObject()
                        .put("device_id", deviceId)
                        .put("device_name", row.getString("device_name"))
                        .put("ip_address", ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress())
                        .put("is_monitoring_enabled", row.getBoolean("is_monitoring_enabled"))
                        .put("total_checks", row.getInteger("total_checks"))
                        .put("successful_checks", row.getInteger("successful_checks"))
                        .put("availability_percent", row.getBigDecimal("availability_percent"))
                        .put("last_check_time", row.getLocalDateTime("last_check_time") != null ? row.getLocalDateTime("last_check_time").toString() : null)
                        .put("last_success_time", row.getLocalDateTime("last_success_time") != null ? row.getLocalDateTime("last_success_time").toString() : null)
                        .put("updated_at", row.getLocalDateTime("updated_at").toString());

                    message.reply(new JsonObject().put("availability", availability));
                } else {
                    message.reply(new JsonObject().put("availability", null));
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to get device availability", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    // ========================================
    // DISCOVERY PROFILE MANAGEMENT OPERATIONS
    // ========================================

    private void handleGetDiscoveryProfiles(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = """
            SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.port,
                   dp.created_at, dp.updated_at, dp.created_by,
                   dt.device_type_id, dt.device_type_name,
                   cp.credential_profile_id, cp.profile_name, cp.username,
                   COUNT(d.device_id) as active_devices_count
            FROM discovery_profiles dp
            JOIN device_types dt ON dp.device_type_id = dt.device_type_id
            JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
            LEFT JOIN devices d ON dp.profile_id = d.discovery_profile_id AND d.is_deleted = false
            GROUP BY dp.profile_id, dt.device_type_id, cp.credential_profile_id
            ORDER BY dp.created_at DESC
            """;

        pgPool.query(sql)
            .execute()
            .onSuccess(rows -> {
                JsonArray profiles = new JsonArray();
                for (Row row : rows) {
                    JsonObject profile = new JsonObject()
                        .put("profile_id", row.getUUID("profile_id").toString())
                        .put("discovery_name", row.getString("discovery_name"))
                        .put("ip_address", ((io.vertx.pgclient.data.Inet) row.getValue("ip_address")).getAddress().getHostAddress())
                        .put("port", row.getInteger("port"))
                        .put("created_at", row.getLocalDateTime("created_at").toString())
                        .put("updated_at", row.getLocalDateTime("updated_at") != null ? row.getLocalDateTime("updated_at").toString() : null)
                        .put("created_by", row.getString("created_by"))
                        .put("device_type", new JsonObject()
                            .put("device_type_id", row.getUUID("device_type_id").toString())
                            .put("device_type_name", row.getString("device_type_name")))
                        .put("credential_profile", new JsonObject()
                            .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                            .put("profile_name", row.getString("profile_name"))
                            .put("username", row.getString("username")))
                        .put("active_devices_count", row.getInteger("active_devices_count"));
                    profiles.add(profile);
                }
                message.reply(new JsonObject().put("discovery_profiles", profiles));
            })
            .onFailure(cause -> {
                logger.error("Failed to get discovery profiles", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleInsertDiscoveryProfile(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        // Debug logging
        logger.info("🔍 DEBUG: Received discovery profile insertion params: {}", params.encode());

        String discoveryName = params.getString("discovery_name");
        String ipAddress = params.getString("ip_address");
        String deviceTypeId = params.getString("device_type_id");
        String credentialProfileId = params.getString("credential_profile_id");
        Integer port = params.getInteger("port"); // No default - must be provided
        Integer timeoutSeconds = params.getInteger("timeout_seconds"); // No default
        Integer retryCount = params.getInteger("retry_count"); // No default
        String createdBy = params.getString("created_by", "system");

        logger.info("🔍 DEBUG: discoveryName={}, ipAddress={}, deviceTypeId={}, credentialProfileId={}",
            discoveryName, ipAddress, deviceTypeId, credentialProfileId);

        // Use string concatenation to avoid parameter binding issues with INET type
        String sql = String.format("""
            INSERT INTO discovery_profiles (discovery_name, ip_address, device_type_id, credential_profile_id, port, timeout_seconds, retry_count, created_by)
            VALUES ('%s', '%s'::inet, '%s'::uuid, '%s'::uuid, %d, %d, %d, '%s')
            RETURNING profile_id, created_at
            """,
            discoveryName.replace("'", "''"), // Escape single quotes
            ipAddress,
            deviceTypeId,
            credentialProfileId,
            port != null ? port : 22, // Default port
            timeoutSeconds != null ? timeoutSeconds : 30, // Default timeout
            retryCount != null ? retryCount : 3, // Default retry count
            createdBy.replace("'", "''")
        );

        logger.info("🔍 DEBUG: Executing SQL: {}", sql);

        pgPool.query(sql)
            .execute()
            .onSuccess(rows -> {
                Row row = rows.iterator().next();
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("profile_id", row.getUUID("profile_id").toString())
                    .put("created_at", row.getLocalDateTime("created_at").toString())
                    .put("message", "Discovery profile created successfully");
                message.reply(result);
            })
            .onFailure(cause -> {
                logger.error("Failed to create discovery profile", cause);
                if (cause.getMessage().contains("duplicate key")) {
                    message.reply(new JsonObject()
                        .put("success", false)
                        .put("error", "Discovery profile with this IP address already exists"));
                } else {
                    message.fail(500, "Database error: " + cause.getMessage());
                }
            });
    }

    private void handleUpdateDiscoveryProfile(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String profileId = params.getString("profile_id");
        String discoveryName = params.getString("discovery_name");
        String ipAddress = params.getString("ip_address");
        String deviceTypeId = params.getString("device_type_id");
        String credentialProfileId = params.getString("credential_profile_id");
        Integer port = params.getInteger("port");

        String sql = """
            UPDATE discovery_profiles
            SET discovery_name = COALESCE($2, discovery_name),
                ip_address = COALESCE($3::inet, ip_address),
                device_type_id = COALESCE($4, device_type_id),
                credential_profile_id = COALESCE($5, credential_profile_id),
                port = COALESCE($6, port),
                updated_at = CURRENT_TIMESTAMP
            WHERE profile_id = $1
            RETURNING updated_at
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(profileId), discoveryName, ipAddress,
                            deviceTypeId != null ? UUID.fromString(deviceTypeId) : null,
                            credentialProfileId != null ? UUID.fromString(credentialProfileId) : null,
                            port))
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("success", true)
                        .put("updated_at", row.getLocalDateTime("updated_at").toString())
                        .put("message", "Discovery profile updated successfully");
                    message.reply(result);
                } else {
                    message.reply(new JsonObject()
                        .put("success", false)
                        .put("error", "Discovery profile not found"));
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to update discovery profile", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    // ========================================
    // DISCOVERY VALIDATION OPERATIONS
    // ========================================

    private void handleCheckDiscoveryConflicts(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String ipAddress = params.getString("ip_address");

        if (ipAddress == null) {
            message.fail(400, "ip_address parameter is required");
            return;
        }

        String sql = """
            SELECT
                COUNT(CASE WHEN d.is_deleted = false THEN 1 END) as active_devices,
                COUNT(CASE WHEN d.is_deleted = true THEN 1 END) as deleted_devices,
                COUNT(dp.*) as discovery_profiles
            FROM devices d
            FULL OUTER JOIN discovery_profiles dp ON d.ip_address = dp.ip_address
            WHERE d.ip_address = $1::inet OR dp.ip_address = $1::inet
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(ipAddress))
            .onSuccess(rows -> {
                Row row = rows.iterator().next();
                JsonObject conflicts = new JsonObject()
                    .put("ip_address", ipAddress)
                    .put("has_active_device", row.getInteger("active_devices") > 0)
                    .put("has_deleted_device", row.getInteger("deleted_devices") > 0)
                    .put("has_discovery_profile", row.getInteger("discovery_profiles") > 0)
                    .put("can_discover", row.getInteger("active_devices") == 0);

                message.reply(new JsonObject().put("conflicts", conflicts));
            })
            .onFailure(cause -> {
                logger.error("Failed to check discovery conflicts", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    // ========================================
    // ENHANCED CREDENTIAL MANAGEMENT OPERATIONS
    // ========================================

    private void handleUpdateCredentialProfile(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String credentialProfileId = params.getString("credential_profile_id");
        String profileName = params.getString("profile_name");
        String username = params.getString("username");
        String password = params.getString("password");
        String protocol = params.getString("protocol");
        Boolean isActive = params.getBoolean("is_active");

        // Encrypt password if provided
        String encryptedPassword = null;
        if (password != null) {
            encryptedPassword = PasswordUtil.encryptPassword(password);
        }

        String sql = """
            UPDATE credential_profiles
            SET profile_name = COALESCE($2, profile_name),
                username = COALESCE($3, username),
                password_encrypted = COALESCE($4, password_encrypted),
                protocol = COALESCE($5, protocol),
                is_active = COALESCE($6, is_active),
                updated_at = CURRENT_TIMESTAMP
            WHERE credential_profile_id = $1
            RETURNING updated_at
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(credentialProfileId), profileName, username, encryptedPassword, protocol, isActive))
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("success", true)
                        .put("updated_at", row.getLocalDateTime("updated_at").toString())
                        .put("message", "Credential profile updated successfully");
                    message.reply(result);
                } else {
                    message.reply(new JsonObject()
                        .put("success", false)
                        .put("error", "Credential profile not found"));
                }
            })
            .onFailure(cause -> {
                logger.error("Failed to update credential profile", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleDeleteCredentialProfile(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String credentialProfileId = params.getString("credential_profile_id");

        // Check if credential profile has dependencies (discovery profiles)
        String checkSql = """
            SELECT COUNT(*) as dependency_count
            FROM discovery_profiles
            WHERE credential_profile_id = $1
            """;

        pgPool.preparedQuery(checkSql)
            .execute(Tuple.of(UUID.fromString(credentialProfileId)))
            .onSuccess(checkRows -> {
                Row checkRow = checkRows.iterator().next();
                int dependencyCount = checkRow.getInteger("dependency_count");

                if (dependencyCount > 0) {
                    message.reply(new JsonObject()
                        .put("success", false)
                        .put("error", "Cannot delete credential profile with active discovery profiles")
                        .put("dependency_count", dependencyCount));
                    return;
                }

                // Safe to delete
                String deleteSql = "DELETE FROM credential_profiles WHERE credential_profile_id = $1";
                pgPool.preparedQuery(deleteSql)
                    .execute(Tuple.of(UUID.fromString(credentialProfileId)))
                    .onSuccess(deleteRows -> {
                        if (deleteRows.rowCount() > 0) {
                            message.reply(new JsonObject()
                                .put("success", true)
                                .put("message", "Credential profile deleted successfully"));
                        } else {
                            message.reply(new JsonObject()
                                .put("success", false)
                                .put("error", "Credential profile not found"));
                        }
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete credential profile", cause);
                        message.fail(500, "Database error: " + cause.getMessage());
                    });
            })
            .onFailure(cause -> {
                logger.error("Failed to check credential profile dependencies", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleDeleteDiscoveryProfilePermanent(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String profileId = params.getString("profile_id");

        // Check if discovery profile has dependencies (active devices)
        String checkSql = """
            SELECT COUNT(*) as dependency_count
            FROM devices
            WHERE discovery_profile_id = $1 AND is_deleted = false
            """;

        pgPool.preparedQuery(checkSql)
            .execute(Tuple.of(UUID.fromString(profileId)))
            .onSuccess(checkRows -> {
                Row checkRow = checkRows.iterator().next();
                int dependencyCount = checkRow.getInteger("dependency_count");

                if (dependencyCount > 0) {
                    message.reply(new JsonObject()
                        .put("success", false)
                        .put("error", "Cannot delete discovery profile with active devices")
                        .put("dependency_count", dependencyCount));
                    return;
                }

                // Safe to delete
                String deleteSql = "DELETE FROM discovery_profiles WHERE profile_id = $1";
                pgPool.preparedQuery(deleteSql)
                    .execute(Tuple.of(UUID.fromString(profileId)))
                    .onSuccess(deleteRows -> {
                        if (deleteRows.rowCount() > 0) {
                            message.reply(new JsonObject()
                                .put("success", true)
                                .put("message", "Discovery profile deleted successfully"));
                        } else {
                            message.reply(new JsonObject()
                                .put("success", false)
                                .put("error", "Discovery profile not found"));
                        }
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete discovery profile", cause);
                        message.fail(500, "Database error: " + cause.getMessage());
                    });
            })
            .onFailure(cause -> {
                logger.error("Failed to check discovery profile dependencies", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    // User Management Handlers
    private void handleGetUsers(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String sql = "SELECT user_id, username, is_active, created_at, updated_at FROM users WHERE is_active = true ORDER BY created_at DESC";

        pgPool.query(sql)
            .execute()
            .onSuccess(rows -> {
                JsonArray users = new JsonArray();
                for (Row row : rows) {
                    JsonObject user = new JsonObject()
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("created_at", row.getLocalDateTime("created_at").toString())
                        .put("updated_at", row.getLocalDateTime("updated_at").toString());
                    users.add(user);
                }
                message.reply(new JsonObject().put("users", users));
            })
            .onFailure(cause -> {
                logger.error("Failed to get users", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleCreateUser(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String username = params.getString("username");
        String password = params.getString("password");

        if (username == null || password == null) {
            message.fail(400, "Missing required fields: username, password");
            return;
        }

        // Hash the password
        String hashedPassword = PasswordUtil.hashPassword(password);

        String sql = "INSERT INTO users (username, password_hash) VALUES ($1, $2) RETURNING user_id, username, is_active, created_at";
        Tuple tuple = Tuple.of(username, hashedPassword);

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                Row row = rows.iterator().next();
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("is_active", row.getBoolean("is_active"))
                    .put("created_at", row.getLocalDateTime("created_at").toString())
                    .put("message", "User created successfully");
                message.reply(result);
            })
            .onFailure(cause -> {
                logger.error("Failed to create user", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleUpdateUser(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String userId = params.getString("user_id");
        JsonObject data = params.getJsonObject("data");

        if (userId == null || data == null) {
            message.fail(400, "Missing required fields: user_id, data");
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE users SET updated_at = CURRENT_TIMESTAMP");
        Tuple tuple = Tuple.tuple();
        int paramIndex = 1;

        if (data.containsKey("username")) {
            sql.append(", username = $").append(paramIndex++);
            tuple.addString(data.getString("username"));
        }

        if (data.containsKey("password")) {
            String hashedPassword = PasswordUtil.hashPassword(data.getString("password"));
            sql.append(", password_hash = $").append(paramIndex++);
            tuple.addString(hashedPassword);
        }

        if (data.containsKey("is_active")) {
            sql.append(", is_active = $").append(paramIndex++);
            tuple.addBoolean(data.getBoolean("is_active"));
        }

        sql.append(" WHERE user_id = $").append(paramIndex);
        tuple.addUUID(UUID.fromString(userId));
        sql.append(" RETURNING user_id, username, is_active, updated_at");

        pgPool.preparedQuery(sql.toString())
            .execute(tuple)
            .onSuccess(rows -> {
                if (rows.size() == 0) {
                    message.fail(404, "User not found");
                    return;
                }

                Row row = rows.iterator().next();
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("is_active", row.getBoolean("is_active"))
                    .put("updated_at", row.getLocalDateTime("updated_at").toString())
                    .put("message", "User updated successfully");
                message.reply(result);
            })
            .onFailure(cause -> {
                logger.error("Failed to update user", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }

    private void handleDeleteUser(io.vertx.core.eventbus.Message<Object> message, JsonObject params) {
        String userId = params.getString("user_id");

        if (userId == null) {
            message.fail(400, "Missing required field: user_id");
            return;
        }

        // Soft delete - set is_active to false
        String sql = "UPDATE users SET is_active = false, updated_at = CURRENT_TIMESTAMP WHERE user_id = $1 AND is_active = true RETURNING user_id, username";
        Tuple tuple = Tuple.of(UUID.fromString(userId));

        pgPool.preparedQuery(sql)
            .execute(tuple)
            .onSuccess(rows -> {
                if (rows.size() == 0) {
                    message.fail(404, "User not found or already deleted");
                    return;
                }

                Row row = rows.iterator().next();
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("message", "User deleted successfully");
                message.reply(result);
            })
            .onFailure(cause -> {
                logger.error("Failed to delete user", cause);
                message.fail(500, "Database error: " + cause.getMessage());
            });
    }
}
