package com.nmslite.services.impl;

import com.nmslite.services.DeviceTypeService;

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
 * DeviceTypeServiceImpl - Implementation of DeviceTypeService (READ-ONLY)

 * Provides device type READ-ONLY operations including:
 * - Device type listing and retrieval
 * - Device type lookup by ID/name
 * - Active device types filtering

 * NOTE: Users cannot create, update, or delete device types for security reasons
 */
public class DeviceTypeServiceImpl implements DeviceTypeService
{

    private static final Logger logger = LoggerFactory.getLogger(DeviceTypeServiceImpl.class);

    private final Pool pgPool;

    /**
     * Constructor for DeviceTypeServiceImpl
     *
     * @param pgPool PostgresSQL connection pool
     */
    public DeviceTypeServiceImpl(Pool pgPool)
    {
        this.pgPool = pgPool;
    }

    /**
     * Get list of device types
     *
     * @param includeInactive Include inactive device types
     * @return Future containing JsonArray of device types
     */
    @Override
    public Future<JsonArray> deviceTypeList(boolean includeInactive)
    {
        var promise = Promise.<JsonArray>promise();

        var sql = """
                SELECT device_type_id, device_type_name, default_port, is_active, created_at
                FROM device_types
                """ + (includeInactive ? "" : "WHERE is_active = true ") + """
                ORDER BY device_type_name
                """;

        pgPool.query(sql)
                .execute()
                .onSuccess(rows ->
                {
                    var deviceTypes = new JsonArray();

                    for (var row : rows)
                    {
                        var deviceType = new JsonObject()
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString());

                        deviceTypes.add(deviceType);
                    }

                    promise.complete(deviceTypes);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get device types", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Get device type by ID
     *
     * @param deviceTypeId Device type ID
     * @return Future containing JsonObject with device type data or not found
     */
    @Override
    public Future<JsonObject> deviceTypeGetById(String deviceTypeId)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
                SELECT device_type_id, device_type_name, default_port, is_active, created_at
                FROM device_types
                WHERE device_type_id = $1
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(deviceTypeId)))
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
                            .put("device_type_id", row.getUUID("device_type_id").toString())
                            .put("device_type_name", row.getString("device_type_name"))
                            .put("default_port", row.getInteger("default_port"))
                            .put("is_active", row.getBoolean("is_active"))
                            .put("created_at", row.getLocalDateTime("created_at").toString());

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get device type by ID", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

}
