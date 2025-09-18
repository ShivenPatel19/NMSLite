package com.nmslite.services.impl;

import com.nmslite.services.DeviceTypeService;
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
 * DeviceTypeServiceImpl - Implementation of DeviceTypeService
 * 
 * Provides device type management operations including:
 * - Device type CRUD operations
 * - Device type validation and usage tracking
 * - Default port management
 */
public class DeviceTypeServiceImpl implements DeviceTypeService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceTypeServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public DeviceTypeServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public void deviceTypeList(boolean includeInactive, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_type_id, device_type_name, default_port, is_active, created_at
                    FROM device_types
                    """ + (includeInactive ? "" : "WHERE is_active = true ") + """
                    ORDER BY device_type_name
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        JsonArray deviceTypes = new JsonArray();
                        for (Row row : rows) {
                            JsonObject deviceType = new JsonObject()
                                    .put("device_type_id", row.getUUID("device_type_id").toString())
                                    .put("device_type_name", row.getString("device_type_name"))
                                    .put("default_port", row.getInteger("default_port"))
                                    .put("is_active", row.getBoolean("is_active"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString());
                            deviceTypes.add(deviceType);
                        }
                        blockingPromise.complete(deviceTypes);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get device types", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceTypeCreate(JsonObject deviceTypeData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String deviceTypeName = deviceTypeData.getString("device_type_name");
            Integer defaultPort = deviceTypeData.getInteger("default_port");
            Boolean isActive = deviceTypeData.getBoolean("is_active", true);

            if (deviceTypeName == null) {
                blockingPromise.fail(new IllegalArgumentException("Device type name is required"));
                return;
            }

            // Validate port range (1-65535)
            if (defaultPort != null && (defaultPort < 1 || defaultPort > 65535)) {
                blockingPromise.fail(new IllegalArgumentException("Port must be between 1 and 65535"));
                return;
            }

            String sql = """
                    INSERT INTO device_types (device_type_name, default_port, is_active)
                    VALUES ($1, $2, $3)
                    RETURNING device_type_id, device_type_name, default_port, is_active, created_at
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(deviceTypeName, defaultPort, isActive))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("message", "Device type created successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to create device type", cause);
                        if (cause.getMessage().contains("duplicate key")) {
                            blockingPromise.fail(new IllegalArgumentException("Device type name already exists"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void deviceTypeUpdate(String deviceTypeId, JsonObject deviceTypeData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String deviceTypeName = deviceTypeData.getString("device_type_name");
            Integer defaultPort = deviceTypeData.getInteger("default_port");
            Boolean isActive = deviceTypeData.getBoolean("is_active");

            // Validate port range (1-65535)
            if (defaultPort != null && (defaultPort < 1 || defaultPort > 65535)) {
                blockingPromise.fail(new IllegalArgumentException("Port must be between 1 and 65535"));
                return;
            }

            StringBuilder sqlBuilder = new StringBuilder("UPDATE device_types SET ");
            JsonArray params = new JsonArray();
            int paramIndex = 1;

            if (deviceTypeName != null) {
                sqlBuilder.append("device_type_name = $").append(paramIndex++).append(", ");
                params.add(deviceTypeName);
            }
            if (defaultPort != null) {
                sqlBuilder.append("default_port = $").append(paramIndex++).append(", ");
                params.add(defaultPort);
            }
            if (isActive != null) {
                sqlBuilder.append("is_active = $").append(paramIndex++).append(", ");
                params.add(isActive);
            }

            if (params.size() == 0) {
                blockingPromise.fail(new IllegalArgumentException("No fields to update"));
                return;
            }

            // Remove trailing comma and space
            String sql = sqlBuilder.substring(0, sqlBuilder.length() - 2) +
                    " WHERE device_type_id = $" + paramIndex +
                    " RETURNING device_type_id, device_type_name, default_port, is_active";
            params.add(UUID.fromString(deviceTypeId));

            pgPool.preparedQuery(sql)
                    .execute(Tuple.from(params.getList()))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Device type not found"));
                            return;
                        }
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("message", "Device type updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update device type", cause);
                        if (cause.getMessage().contains("duplicate key")) {
                            blockingPromise.fail(new IllegalArgumentException("Device type name already exists"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void deviceTypeDelete(String deviceTypeId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            // Soft delete by setting is_active to false
            String sql = """
                    UPDATE device_types 
                    SET is_active = false
                    WHERE device_type_id = $1
                    RETURNING device_type_id, device_type_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceTypeId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Device type not found"));
                            return;
                        }
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("message", "Device type deactivated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete device type", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceTypeGetById(String deviceTypeId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_type_id, device_type_name, default_port, is_active, created_at
                    FROM device_types
                    WHERE device_type_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(deviceTypeId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject().put("found", false));
                            return;
                        }

                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString());
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get device type by ID", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceTypeGetByName(String deviceTypeName, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_type_id, device_type_name, default_port, is_active, created_at
                    FROM device_types
                    WHERE device_type_name = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(deviceTypeName))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject().put("found", false));
                            return;
                        }

                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString());
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get device type by name", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceTypeSetActive(String deviceTypeId, boolean isActive, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE device_types
                    SET is_active = $1
                    WHERE device_type_id = $2
                    RETURNING device_type_id, device_type_name, is_active
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(isActive, UUID.fromString(deviceTypeId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Device type not found"));
                            return;
                        }
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("message", "Device type status updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update device type status", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void deviceTypeListActive(Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT device_type_id, device_type_name, default_port
                    FROM device_types
                    WHERE is_active = true
                    ORDER BY device_type_name
                    """;

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
                        blockingPromise.complete(deviceTypes);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get active device types", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }
}
