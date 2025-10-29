package com.nmslite.services.impl;

import com.nmslite.database.DatabaseHelper;

import com.nmslite.database.DatabaseInitializer;

import com.nmslite.services.DeviceTypeService;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

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

 * Database Access:
 * - Uses DatabaseInitializer.getDatabaseHelper() for database operations
 * - DatabaseHelper provides generic query execution with consistent error handling
 * - No constructor parameters needed
 */
public class DeviceTypeServiceImpl implements DeviceTypeService
{

    private static final Logger logger = LoggerFactory.getLogger(DeviceTypeServiceImpl.class);

    private final DatabaseHelper dbHelper;

    /**
     * Constructor for DeviceTypeServiceImpl.
     * Accesses database helper via DatabaseInitializer.
     */
    public DeviceTypeServiceImpl()
    {
        this.dbHelper = DatabaseInitializer.getDatabaseHelper();
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
        try
        {
            var sql = """
                    SELECT device_type_id, device_type_name, default_port, is_active, created_at
                    FROM device_types
                    """ + (includeInactive ? "" : "WHERE is_active = true ") + """
                    ORDER BY device_type_name
                    """;

            return dbHelper.executeQuery(sql)
                    .map(rows ->
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

                        return deviceTypes;
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceTypeList service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                    SELECT device_type_id, device_type_name, default_port, is_active, created_at
                    FROM device_types
                    WHERE device_type_id = $1
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(deviceTypeId)))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            return new JsonObject().put("found", false);
                        }

                        var row = rows.iterator().next();

                        return new JsonObject()
                                .put("found", true)
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString());
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in deviceTypeGetById service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}
