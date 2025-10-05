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
public class DiscoveryProfileServiceImpl implements DiscoveryProfileService
{

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryProfileServiceImpl.class);

    private final Vertx vertx;

    private final PgPool pgPool;

    /**
     * Constructor for DiscoveryProfileServiceImpl
     *
     * @param vertx Vert.x instance
     * @param pgPool PostgreSQL connection pool
     */
    public DiscoveryProfileServiceImpl(Vertx vertx, PgPool pgPool)
    {
        this.vertx = vertx;

        this.pgPool = pgPool;
    }

    /**
     * Get list of discovery profiles
     *
     * @param resultHandler Handler for the async result
     */
    @Override
    public void discoveryList(Handler<AsyncResult<JsonArray>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            // Get discovery profiles with device type info
            String sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.is_range, dp.credential_profile_ids,
                           dp.port, dp.protocol, dp.created_at, dp.updated_at,
                           dt.device_type_name, dt.default_port
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    ORDER BY dp.discovery_name
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows ->
                    {
                        JsonArray profiles = new JsonArray();

                        for (Row row : rows)
                        {
                            UUID[] credentialIds = (UUID[]) row.getValue("credential_profile_ids");

                            // Convert UUID array to JsonArray
                            io.vertx.core.json.JsonArray credentialIdsArray = new io.vertx.core.json.JsonArray();

                            for (UUID credId : credentialIds)
                            {
                                credentialIdsArray.add(credId.toString());
                            }

                            JsonObject profile = new JsonObject()
                                    .put("profile_id", row.getUUID("profile_id").toString())
                                    .put("discovery_name", row.getString("discovery_name"))
                                    .put("ip_address", row.getString("ip_address"))
                                    .put("is_range", row.getBoolean("is_range"))
                                    .put("credential_profile_ids", credentialIdsArray)
                                    .put("credential_count", credentialIds.length)
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("device_type_name", row.getString("device_type_name"))
                                    .put("default_port", row.getInteger("default_port"));

                            profiles.add(profile);
                        }

                        blockingPromise.complete(profiles);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to get discovery profiles", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    /**
     * Create a new discovery profile
     *
     * @param profileData Discovery profile data
     * @param resultHandler Handler for the async result
     */
    @Override
    public void discoveryCreate(JsonObject profileData, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            String discoveryName = profileData.getString("discovery_name");

            String ipAddress = profileData.getString("ip_address");

            Boolean isRange = profileData.getBoolean("is_range", false);  // Default to false for backward compatibility

            String deviceTypeId = profileData.getString("device_type_id");

            io.vertx.core.json.JsonArray credentialProfileIds = profileData.getJsonArray("credential_profile_ids");

            Integer port = profileData.getInteger("port");

            String protocol = profileData.getString("protocol");

            // Convert JsonArray to UUID array for PostgreSQL
            UUID[] credentialUUIDs = new UUID[credentialProfileIds.size()];

            for (int i = 0; i < credentialProfileIds.size(); i++)
            {
                credentialUUIDs[i] = UUID.fromString(credentialProfileIds.getString(i));
            }

            // ===== TRUST HANDLER VALIDATION =====
            // No validation here - handler has already validated all input
            // Service focuses purely on database operations

            String sql = """
                    INSERT INTO discovery_profiles (discovery_name, ip_address, is_range, device_type_id, credential_profile_ids,
                                                   port, protocol)
                    VALUES ($1, $2, $3, $4, $5, $6, $7)
                    RETURNING profile_id, discovery_name, ip_address, is_range, port, protocol, created_at
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(discoveryName, ipAddress, isRange, UUID.fromString(deviceTypeId),
                                    credentialUUIDs, port, protocol))
                    .onSuccess(rows ->
                    {
                        Row row = rows.iterator().next();

                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("is_range", row.getBoolean("is_range"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("message", "Discovery profile created successfully");

                        blockingPromise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to create discovery profile", cause);

                        if (cause.getMessage().contains("duplicate key"))
                        {
                            if (cause.getMessage().contains("discovery_name"))
                            {
                                blockingPromise.fail(new IllegalArgumentException("Discovery name already exists"));
                            }
                            else if (cause.getMessage().contains("ip_address"))
                            {
                                blockingPromise.fail(new IllegalArgumentException("IP address already exists"));
                            }
                            else
                            {
                                blockingPromise.fail(new IllegalArgumentException("Duplicate key constraint violation"));
                            }
                        }
                        else if (cause.getMessage().contains("foreign key"))
                        {
                            blockingPromise.fail(new IllegalArgumentException("Invalid device type ID or one or more credential profile IDs"));
                        }
                        else
                        {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    /**
     * Delete a discovery profile
     *
     * @param profileId Discovery profile ID
     * @param resultHandler Handler for the async result
     */
    @Override
    public void discoveryDelete(String profileId, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            // Hard delete the discovery profile
            String sql = """
                    DELETE FROM discovery_profiles
                    WHERE profile_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(profileId)))
                    .onSuccess(rows ->
                    {
                        if (rows.rowCount() == 0)
                        {
                            blockingPromise.fail(new IllegalArgumentException("Discovery profile not found"));

                            return;
                        }

                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("profile_id", profileId)
                                .put("message", "Discovery profile deleted successfully");

                        blockingPromise.complete(result);
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to delete discovery profile", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    /**
     * Get discovery profile by ID
     *
     * @param profileId Discovery profile ID
     * @param resultHandler Handler for the async result
     */
    @Override
    public void discoveryGetById(String profileId, Handler<AsyncResult<JsonObject>> resultHandler)
    {

        vertx.executeBlocking(blockingPromise ->
        {
            // First get the discovery profile basic info
            String profileSql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.is_range, dp.device_type_id, dp.credential_profile_ids,
                           dp.port, dp.protocol, dp.created_at, dp.updated_at,
                           dt.device_type_name, dt.default_port
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    WHERE dp.profile_id = $1
                    """;

            pgPool.preparedQuery(profileSql)
                    .execute(Tuple.of(UUID.fromString(profileId)))
                    .onSuccess(profileRows ->
                    {
                        if (profileRows.size() == 0)
                        {
                            blockingPromise.complete(new JsonObject().put("found", false));

                            return;
                        }

                        Row profileRow = profileRows.iterator().next();

                        UUID[] credentialIds = (UUID[]) profileRow.getValue("credential_profile_ids");

                        // Convert UUID array to JsonArray for response
                        io.vertx.core.json.JsonArray credentialIdsArray = new io.vertx.core.json.JsonArray();

                        for (UUID credId : credentialIds)
                        {
                            credentialIdsArray.add(credId.toString());
                        }

                        // Get credential profiles details
                        String credentialSql = """
                                SELECT credential_profile_id, profile_name, username
                                FROM credential_profiles
                                WHERE credential_profile_id = ANY($1)
                                ORDER BY profile_name
                                """;

                        pgPool.preparedQuery(credentialSql)
                                .execute(Tuple.of(credentialIds))
                                .onSuccess(credentialRows ->
                                {
                                    io.vertx.core.json.JsonArray credentialProfiles = new io.vertx.core.json.JsonArray();

                                    for (Row credRow : credentialRows)
                                    {
                                        JsonObject credProfile = new JsonObject()
                                                .put("credential_profile_id", credRow.getUUID("credential_profile_id").toString())
                                                .put("profile_name", credRow.getString("profile_name"))
                                                .put("username", credRow.getString("username"));

                                        credentialProfiles.add(credProfile);
                                    }

                                    JsonObject result = new JsonObject()
                                            .put("found", true)
                                            .put("profile_id", profileRow.getUUID("profile_id").toString())
                                            .put("discovery_name", profileRow.getString("discovery_name"))
                                            .put("ip_address", profileRow.getString("ip_address"))
                                            .put("is_range", profileRow.getBoolean("is_range"))
                                            .put("device_type_id", profileRow.getUUID("device_type_id").toString())
                                            .put("credential_profile_ids", credentialIdsArray)
                                            .put("port", profileRow.getInteger("port"))
                                            .put("protocol", profileRow.getString("protocol"))
                                            .put("created_at", profileRow.getLocalDateTime("created_at").toString())
                                            .put("updated_at", profileRow.getLocalDateTime("updated_at") != null ?
                                                profileRow.getLocalDateTime("updated_at").toString() : null)
                                            .put("device_type_name", profileRow.getString("device_type_name"))
                                            .put("default_port", profileRow.getInteger("default_port"))
                                            .put("credential_profiles", credentialProfiles);

                                    blockingPromise.complete(result);
                                })
                                .onFailure(cause ->
                                {
                                    logger.error("Failed to get credential profiles for discovery profile", cause);

                                    blockingPromise.fail(cause);
                                });
                    })
                    .onFailure(cause ->
                    {
                        logger.error("Failed to get discovery profile by ID", cause);

                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

}
