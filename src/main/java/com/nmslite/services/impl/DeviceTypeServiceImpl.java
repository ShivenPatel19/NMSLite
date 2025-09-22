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
 * DeviceTypeServiceImpl - Implementation of DeviceTypeService (READ-ONLY)
 *
 * Provides device type READ-ONLY operations including:
 * - Device type listing and retrieval
 * - Device type lookup by ID/name
 * - Active device types filtering
 *
 * NOTE: Users cannot create, update, or delete device types for security reasons
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


}
