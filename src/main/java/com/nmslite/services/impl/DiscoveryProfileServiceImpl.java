package com.nmslite.services.impl;

import com.nmslite.services.DiscoveryProfileService;

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
 * DiscoveryServiceImpl - Implementation of DiscoveryService

 * Provides discovery profile management operations including:
 * - Discovery profile CRUD operations
 * - IP address conflict detection
 * - Device type and credential integration
 * - Discovery execution and validation
 */
public class DiscoveryProfileServiceImpl implements DiscoveryProfileService
{

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryProfileServiceImpl.class);

    private final Pool pgPool;

    /**
     * Constructor for DiscoveryProfileServiceImpl
     *
     * @param pgPool PostgresSQL connection pool
     */
    public DiscoveryProfileServiceImpl(Pool pgPool)
    {
        this.pgPool = pgPool;
    }

    /**
     * Get list of discovery profiles
     *
     * @return Future containing JsonArray of discovery profiles
     */
    @Override
    public Future<JsonArray> discoveryList()
    {
        var promise = Promise.<JsonArray>promise();

        // Get discovery profiles with device type info
        var sql = """
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
                    var profiles = new JsonArray();

                    for (var row : rows)
                    {
                        var credentialIds = (UUID[]) row.getValue("credential_profile_ids");

                        // Convert UUID array to JsonArray
                        var credentialIdsArray = new io.vertx.core.json.JsonArray();

                        for (var credId : credentialIds)
                        {
                            credentialIdsArray.add(credId.toString());
                        }

                        var profile = new JsonObject()
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

                    promise.complete(profiles);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get discovery profiles", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Create a new discovery profile
     *
     * @param profileData Discovery profile data
     * @return Future containing JsonObject with creation result
     */
    @Override
    public Future<JsonObject> discoveryCreate(JsonObject profileData)
    {
        var promise = Promise.<JsonObject>promise();

        var discoveryName = profileData.getString("discovery_name");

        var ipAddress = profileData.getString("ip_address");

        var isRange = profileData.getBoolean("is_range", false);  // Default to false for backward compatibility

        var deviceTypeId = profileData.getString("device_type_id");

        var credentialProfileIds = profileData.getJsonArray("credential_profile_ids");

        var port = profileData.getInteger("port");

        var protocol = profileData.getString("protocol");

        // Convert JsonArray to UUID array for PostgresSQL
        var credentialUUIDs = new UUID[credentialProfileIds.size()];

        for (var i = 0; i < credentialProfileIds.size(); i++)
        {
            credentialUUIDs[i] = UUID.fromString(credentialProfileIds.getString(i));
        }

        // ===== TRUST HANDLER VALIDATION =====
        // No validation here - handler has already validated all input
        // Service focuses purely on database operations

        var sql = """
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
                    var row = rows.iterator().next();

                    var result = new JsonObject()
                            .put("success", true)
                            .put("profile_id", row.getUUID("profile_id").toString())
                            .put("discovery_name", row.getString("discovery_name"))
                            .put("ip_address", row.getString("ip_address"))
                            .put("is_range", row.getBoolean("is_range"))
                            .put("port", row.getInteger("port"))
                            .put("protocol", row.getString("protocol"))
                            .put("created_at", row.getLocalDateTime("created_at").toString())
                            .put("message", "Discovery profile created successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to create discovery profile", cause);

                    if (cause.getMessage().contains("duplicate key"))
                    {
                        if (cause.getMessage().contains("discovery_name"))
                        {
                            promise.fail(new IllegalArgumentException("Discovery name already exists"));
                        }
                        else if (cause.getMessage().contains("ip_address"))
                        {
                            promise.fail(new IllegalArgumentException("IP address already exists"));
                        }
                        else
                        {
                            promise.fail(new IllegalArgumentException("Duplicate key constraint violation"));
                        }
                    }
                    else if (cause.getMessage().contains("foreign key"))
                    {
                        promise.fail(new IllegalArgumentException("Invalid device type ID or one or more credential profile IDs"));
                    }
                    else
                    {
                        promise.fail(cause);
                    }
                });

        return promise.future();
    }

    /**
     * Delete a discovery profile
     *
     * @param profileId Discovery profile ID
     * @return Future containing JsonObject with deletion result
     */
    @Override
    public Future<JsonObject> discoveryDelete(String profileId)
    {
        var promise = Promise.<JsonObject>promise();

        // Hard delete the discovery profile
        var sql = """
                DELETE FROM discovery_profiles
                WHERE profile_id = $1
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(profileId)))
                .onSuccess(rows ->
                {
                    if (rows.rowCount() == 0)
                    {
                        promise.fail(new IllegalArgumentException("Discovery profile not found"));

                        return;
                    }

                    var result = new JsonObject()
                            .put("success", true)
                            .put("profile_id", profileId)
                            .put("message", "Discovery profile deleted successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to delete discovery profile", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Get discovery profile by ID
     *
     * @param profileId Discovery profile ID
     * @return Future containing JsonObject with discovery profile data or not found
     */
    @Override
    public Future<JsonObject> discoveryGetById(String profileId)
    {
        var promise = Promise.<JsonObject>promise();

        // First get the discovery profile basic info
        var profileSql = """
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
                        promise.complete(new JsonObject().put("found", false));

                        return;
                    }

                    var profileRow = profileRows.iterator().next();

                    var credentialIds = (UUID[]) profileRow.getValue("credential_profile_ids");

                    // Convert UUID array to JsonArray for response
                    var credentialIdsArray = new io.vertx.core.json.JsonArray();

                    for (var credId : credentialIds)
                    {
                        credentialIdsArray.add(credId.toString());
                    }

                    // Get credential profiles details
                    var credentialSql = """
                            SELECT credential_profile_id, profile_name, username
                            FROM credential_profiles
                            WHERE credential_profile_id = ANY($1)
                            ORDER BY profile_name
                            """;

                    pgPool.preparedQuery(credentialSql)
                            .execute(Tuple.of(credentialIds))
                            .onSuccess(credentialRows ->
                            {
                                var credentialProfiles = new io.vertx.core.json.JsonArray();

                                for (var credRow : credentialRows)
                                {
                                    var credProfile = new JsonObject()
                                            .put("credential_profile_id", credRow.getUUID("credential_profile_id").toString())
                                            .put("profile_name", credRow.getString("profile_name"))
                                            .put("username", credRow.getString("username"));

                                    credentialProfiles.add(credProfile);
                                }

                                var result = new JsonObject()
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

                                promise.complete(result);
                            })
                            .onFailure(cause ->
                            {
                                logger.error("Failed to get credential profiles for discovery profile", cause);

                                promise.fail(cause);
                            });
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get discovery profile by ID", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

}
