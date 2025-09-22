package com.nmslite.services.impl;

import com.nmslite.services.DiscoveryProfileService;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
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
 * DiscoveryServiceImpl - Implementation of DiscoveryService
 * 
 * Provides discovery profile management operations including:
 * - Discovery profile CRUD operations
 * - IP address conflict detection
 * - Device type and credential integration
 * - Discovery execution and validation
 */
public class DiscoveryProfileServiceImpl implements DiscoveryProfileService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryProfileServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public DiscoveryProfileServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public void discoveryList(Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address::text as ip_address, dp.port, dp.protocol,
                           dp.created_at, dp.updated_at, dp.created_by,
                           dt.device_type_name, dt.default_port,
                           cp.profile_name as credential_profile_name, cp.username
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
                    ORDER BY dp.discovery_name
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        JsonArray profiles = new JsonArray();
                        for (Row row : rows) {
                            JsonObject profile = new JsonObject()
                                    .put("profile_id", row.getUUID("profile_id").toString())
                                    .put("discovery_name", row.getString("discovery_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("created_by", row.getString("created_by"))
                                    .put("device_type_name", row.getString("device_type_name"))
                                    .put("default_port", row.getInteger("default_port"))
                                    .put("credential_profile_name", row.getString("credential_profile_name"))
                                    .put("username", row.getString("username"));
                            profiles.add(profile);
                        }
                        blockingPromise.complete(profiles);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get discovery profiles", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void discoveryCreate(JsonObject profileData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String discoveryName = profileData.getString("discovery_name");
            String ipAddress = profileData.getString("ip_address");
            String deviceTypeId = profileData.getString("device_type_id");
            String credentialProfileId = profileData.getString("credential_profile_id");
            Integer port = profileData.getInteger("port");
            String protocol = profileData.getString("protocol");
            String createdBy = profileData.getString("created_by");

            if (discoveryName == null || ipAddress == null || deviceTypeId == null || credentialProfileId == null) {
                blockingPromise.fail(new IllegalArgumentException("Discovery name, IP address, device type ID, and credential profile ID are required"));
                return;
            }

            String sql = """
                    INSERT INTO discovery_profiles (discovery_name, ip_address, device_type_id, credential_profile_id,
                                                   port, protocol, created_by)
                    VALUES ($1, '""" + ipAddress + """
                    '::inet, $2, $3, $4, $5, $6)
                    RETURNING profile_id, discovery_name, ip_address::text as ip_address, port, protocol, created_at, created_by
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(discoveryName, UUID.fromString(deviceTypeId),
                                    UUID.fromString(credentialProfileId), port, protocol, createdBy))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("created_by", row.getString("created_by"))
                                .put("message", "Discovery profile created successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to create discovery profile", cause);
                        if (cause.getMessage().contains("duplicate key")) {
                            if (cause.getMessage().contains("discovery_name")) {
                                blockingPromise.fail(new IllegalArgumentException("Discovery name already exists"));
                            } else if (cause.getMessage().contains("ip_address")) {
                                blockingPromise.fail(new IllegalArgumentException("IP address already exists"));
                            } else {
                                blockingPromise.fail(new IllegalArgumentException("Duplicate key constraint violation"));
                            }
                        } else if (cause.getMessage().contains("foreign key")) {
                            blockingPromise.fail(new IllegalArgumentException("Invalid device type ID or credential profile ID"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void discoveryUpdate(String profileId, JsonObject profileData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String discoveryName = profileData.getString("discovery_name");
            String ipAddress = profileData.getString("ip_address");
            String deviceTypeId = profileData.getString("device_type_id");
            String credentialProfileId = profileData.getString("credential_profile_id");
            Integer port = profileData.getInteger("port");
            String protocol = profileData.getString("protocol");

            StringBuilder sqlBuilder = new StringBuilder("UPDATE discovery_profiles SET ");
            JsonArray params = new JsonArray();
            int paramIndex = 1;

            if (discoveryName != null) {
                sqlBuilder.append("discovery_name = $").append(paramIndex++).append(", ");
                params.add(discoveryName);
            }
            if (ipAddress != null) {
                sqlBuilder.append("ip_address = '").append(ipAddress).append("'::inet, ");
                // Note: ipAddress is not added to params since it's directly embedded in SQL
            }
            if (deviceTypeId != null) {
                sqlBuilder.append("device_type_id = $").append(paramIndex++).append(", ");
                params.add(UUID.fromString(deviceTypeId));
            }
            if (credentialProfileId != null) {
                sqlBuilder.append("credential_profile_id = $").append(paramIndex++).append(", ");
                params.add(UUID.fromString(credentialProfileId));
            }
            if (port != null) {
                sqlBuilder.append("port = $").append(paramIndex++).append(", ");
                params.add(port);
            }
            if (protocol != null) {
                sqlBuilder.append("protocol = $").append(paramIndex++).append(", ");
                params.add(protocol);
            }

            if (params.size() == 0) {
                blockingPromise.fail(new IllegalArgumentException("No fields to update"));
                return;
            }

            // Remove trailing comma and space
            String sql = sqlBuilder.substring(0, sqlBuilder.length() - 2) +
                    " WHERE profile_id = $" + paramIndex +
                    " RETURNING profile_id, discovery_name, ip_address::text as ip_address, port, protocol";
            params.add(UUID.fromString(profileId));

            pgPool.preparedQuery(sql)
                    .execute(Tuple.from(params.getList()))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Discovery profile not found"));
                            return;
                        }
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("message", "Discovery profile updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update discovery profile", cause);
                        if (cause.getMessage().contains("duplicate key")) {
                            if (cause.getMessage().contains("discovery_name")) {
                                blockingPromise.fail(new IllegalArgumentException("Discovery name already exists"));
                            } else if (cause.getMessage().contains("ip_address")) {
                                blockingPromise.fail(new IllegalArgumentException("IP address already exists"));
                            } else {
                                blockingPromise.fail(new IllegalArgumentException("Duplicate key constraint violation"));
                            }
                        } else if (cause.getMessage().contains("foreign key")) {
                            blockingPromise.fail(new IllegalArgumentException("Invalid device type ID or credential profile ID"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void discoveryDelete(String profileId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            // Hard delete the discovery profile
            String sql = """
                    DELETE FROM discovery_profiles
                    WHERE profile_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(profileId)))
                    .onSuccess(rows -> {
                        if (rows.rowCount() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Discovery profile not found"));
                            return;
                        }
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("profile_id", profileId)
                                .put("message", "Discovery profile deleted successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete discovery profile", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void discoveryGetById(String profileId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address::text as ip_address, dp.device_type_id, dp.credential_profile_id,
                           dp.port, dp.protocol, dp.created_at, dp.updated_at, dp.created_by,
                           dt.device_type_name, dt.default_port,
                           cp.profile_name as credential_profile_name, cp.username
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
                    WHERE dp.profile_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(profileId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject().put("found", false));
                            return;
                        }

                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("created_by", row.getString("created_by"))
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("username", row.getString("username"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get discovery profile by ID", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }
}
