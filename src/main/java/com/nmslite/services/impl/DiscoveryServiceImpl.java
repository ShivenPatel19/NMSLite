package com.nmslite.services.impl;

import com.nmslite.services.DiscoveryService;
import io.vertx.core.Future;
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
 * DiscoveryServiceImpl - Implementation of DiscoveryService
 * 
 * Provides discovery profile management operations including:
 * - Discovery profile CRUD operations
 * - IP address conflict detection
 * - Device type and credential integration
 * - Discovery execution and validation
 */
public class DiscoveryServiceImpl implements DiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public DiscoveryServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public Future<JsonArray> discoveryList(boolean includeInactive) {
        Promise<JsonArray> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.port, dp.timeout_seconds, dp.retry_count, 
                           dp.is_active, dp.created_at, dp.updated_at, dp.created_by,
                           dt.device_type_name, dt.default_port,
                           cp.profile_name as credential_profile_name, cp.username, cp.protocol
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
                    """ + (includeInactive ? "" : "WHERE dp.is_active = true ") + """
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
                                    .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                    .put("retry_count", row.getInteger("retry_count"))
                                    .put("is_active", row.getBoolean("is_active"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ? 
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("created_by", row.getString("created_by"))
                                    .put("device_type_name", row.getString("device_type_name"))
                                    .put("default_port", row.getInteger("default_port"))
                                    .put("credential_profile_name", row.getString("credential_profile_name"))
                                    .put("username", row.getString("username"))
                                    .put("protocol", row.getString("protocol"));
                            profiles.add(profile);
                        }
                        blockingPromise.complete(profiles);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get discovery profiles", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoveryCreate(JsonObject profileData) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String discoveryName = profileData.getString("discovery_name");
            String ipAddress = profileData.getString("ip_address");
            String deviceTypeId = profileData.getString("device_type_id");
            String credentialProfileId = profileData.getString("credential_profile_id");
            Integer port = profileData.getInteger("port");
            Integer timeoutSeconds = profileData.getInteger("timeout_seconds");
            Integer retryCount = profileData.getInteger("retry_count");
            Boolean isActive = profileData.getBoolean("is_active", true);
            String createdBy = profileData.getString("created_by");

            if (discoveryName == null || ipAddress == null || deviceTypeId == null || credentialProfileId == null) {
                blockingPromise.fail(new IllegalArgumentException("Discovery name, IP address, device type ID, and credential profile ID are required"));
                return;
            }

            String sql = """
                    INSERT INTO discovery_profiles (discovery_name, ip_address, device_type_id, credential_profile_id, 
                                                   port, timeout_seconds, retry_count, is_active, created_by)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                    RETURNING profile_id, discovery_name, ip_address, port, timeout_seconds, retry_count, is_active, created_at, created_by
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(discoveryName, ipAddress, UUID.fromString(deviceTypeId), 
                                    UUID.fromString(credentialProfileId), port, timeoutSeconds, retryCount, isActive, createdBy))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("port", row.getInteger("port"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("is_active", row.getBoolean("is_active"))
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
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoveryUpdate(String profileId, JsonObject profileData) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String discoveryName = profileData.getString("discovery_name");
            String ipAddress = profileData.getString("ip_address");
            String deviceTypeId = profileData.getString("device_type_id");
            String credentialProfileId = profileData.getString("credential_profile_id");
            Integer port = profileData.getInteger("port");
            Integer timeoutSeconds = profileData.getInteger("timeout_seconds");
            Integer retryCount = profileData.getInteger("retry_count");
            Boolean isActive = profileData.getBoolean("is_active");

            StringBuilder sqlBuilder = new StringBuilder("UPDATE discovery_profiles SET ");
            JsonArray params = new JsonArray();
            int paramIndex = 1;

            if (discoveryName != null) {
                sqlBuilder.append("discovery_name = $").append(paramIndex++).append(", ");
                params.add(discoveryName);
            }
            if (ipAddress != null) {
                sqlBuilder.append("ip_address = $").append(paramIndex++).append(", ");
                params.add(ipAddress);
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
            if (timeoutSeconds != null) {
                sqlBuilder.append("timeout_seconds = $").append(paramIndex++).append(", ");
                params.add(timeoutSeconds);
            }
            if (retryCount != null) {
                sqlBuilder.append("retry_count = $").append(paramIndex++).append(", ");
                params.add(retryCount);
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
                    " WHERE profile_id = $" + paramIndex +
                    " RETURNING profile_id, discovery_name, ip_address, port, timeout_seconds, retry_count, is_active";
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
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("is_active", row.getBoolean("is_active"))
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
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoveryDelete(String profileId) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            // Soft delete by setting is_active to false
            String sql = """
                    UPDATE discovery_profiles
                    SET is_active = false
                    WHERE profile_id = $1
                    RETURNING profile_id, discovery_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(profileId)))
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
                                .put("message", "Discovery profile deactivated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to delete discovery profile", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoveryGetById(String profileId) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.device_type_id, dp.credential_profile_id,
                           dp.port, dp.timeout_seconds, dp.retry_count, dp.is_active, dp.created_at, dp.updated_at, dp.created_by,
                           dt.device_type_name, dt.default_port,
                           cp.profile_name as credential_profile_name, cp.username, cp.protocol
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
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("created_by", row.getString("created_by"))
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("username", row.getString("username"))
                                .put("protocol", row.getString("protocol"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get discovery profile by ID", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoveryFindByIp(String ipAddress) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.device_type_id, dp.credential_profile_id,
                           dp.port, dp.timeout_seconds, dp.retry_count, dp.is_active, dp.created_at, dp.updated_at, dp.created_by,
                           dt.device_type_name, dt.default_port,
                           cp.profile_name as credential_profile_name, cp.username, cp.protocol
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
                    WHERE dp.ip_address = $1
                    """;

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
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("device_type_id", row.getUUID("device_type_id").toString())
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("port", row.getInteger("port"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("created_by", row.getString("created_by"))
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("username", row.getString("username"))
                                .put("protocol", row.getString("protocol"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to find discovery profile by IP", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoveryGetByName(String discoveryName) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.device_type_id, dp.credential_profile_id,
                           dp.port, dp.timeout_seconds, dp.retry_count, dp.is_active, dp.created_at, dp.updated_at, dp.created_by,
                           dt.device_type_name, dt.default_port,
                           cp.profile_name as credential_profile_name, cp.username, cp.protocol
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
                    WHERE dp.discovery_name = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(discoveryName))
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
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("created_by", row.getString("created_by"))
                                .put("device_type_name", row.getString("device_type_name"))
                                .put("default_port", row.getInteger("default_port"))
                                .put("credential_profile_name", row.getString("credential_profile_name"))
                                .put("username", row.getString("username"))
                                .put("protocol", row.getString("protocol"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get discovery profile by name", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoverySetActive(String profileId, boolean isActive) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE discovery_profiles
                    SET is_active = $1
                    WHERE profile_id = $2
                    RETURNING profile_id, discovery_name, is_active
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(isActive, UUID.fromString(profileId)))
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
                                .put("is_active", row.getBoolean("is_active"))
                                .put("message", "Discovery profile status updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update discovery profile status", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonArray> discoveryListActive() {
        Promise<JsonArray> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.port,
                           dt.device_type_name, cp.profile_name as credential_profile_name
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
                    WHERE dp.is_active = true
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
                                    .put("device_type_name", row.getString("device_type_name"))
                                    .put("credential_profile_name", row.getString("credential_profile_name"));
                            profiles.add(profile);
                        }
                        blockingPromise.complete(profiles);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get active discovery profiles", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }

    @Override
    public Future<JsonObject> discoveryExecute(String profileId) {
        Promise<JsonObject> promise = Promise.promise();

        vertx.executeBlocking(blockingPromise -> {
            // First get the discovery profile details
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.port, dp.timeout_seconds, dp.retry_count,
                           dt.device_type_name, cp.username, cp.password_encrypted, cp.protocol
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    JOIN credential_profiles cp ON dp.credential_profile_id = cp.credential_profile_id
                    WHERE dp.profile_id = $1 AND dp.is_active = true
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(profileId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject()
                                    .put("success", false)
                                    .put("message", "Discovery profile not found or inactive"));
                            return;
                        }

                        Row row = rows.iterator().next();
                        JsonObject discoveryData = new JsonObject()
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("port", row.getInteger("port"))
                                .put("timeout_seconds", row.getInteger("timeout_seconds"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("device_type", row.getString("device_type_name"))
                                .put("username", row.getString("username"))
                                .put("password_encrypted", row.getString("password_encrypted"))
                                .put("protocol", row.getString("protocol"));

                        // Send discovery request to DiscoveryVerticle via event bus
                        vertx.eventBus().request("discovery.execute.profile", discoveryData, reply -> {
                            if (reply.succeeded()) {
                                JsonObject result = (JsonObject) reply.result().body();
                                blockingPromise.complete(result);
                            } else {
                                logger.error("Discovery execution failed", reply.cause());
                                blockingPromise.complete(new JsonObject()
                                        .put("success", false)
                                        .put("message", "Discovery execution failed: " + reply.cause().getMessage()));
                            }
                        });
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get discovery profile for execution", cause);
                        blockingPromise.fail(cause);
                    });
        }, promise);

        return promise.future();
    }
}
