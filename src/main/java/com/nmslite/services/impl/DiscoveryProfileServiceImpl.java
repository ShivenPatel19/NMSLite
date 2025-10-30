package com.nmslite.services.impl;

import com.nmslite.database.DatabaseHelper;

import com.nmslite.database.DatabaseInitializer;

import com.nmslite.services.DiscoveryProfileService;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

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

 * Database Access:
 * - Uses DatabaseHelper for database operations
 * - No constructor parameters needed
 */
public class DiscoveryProfileServiceImpl implements DiscoveryProfileService
{

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryProfileServiceImpl.class);

    private final DatabaseHelper dbHelper;

    /**
     * Constructor for DiscoveryProfileServiceImpl.
     * Accesses database helper via DatabaseInitializer.getDatabaseHelper().
     */
    public DiscoveryProfileServiceImpl()
    {
        this.dbHelper = DatabaseInitializer.getDatabaseHelper();
    }

    /**
     * Get list of discovery profiles
     *
     * @return Future containing JsonArray of discovery profiles
     */
    @Override
    public Future<JsonArray> discoveryList()
    {
        try
        {
            // Get discovery profiles with device type info
            var sql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.is_range, dp.credential_profile_ids,
                           dp.created_at, dp.updated_at,
                           dt.device_type_name, dt.default_port
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    ORDER BY dp.discovery_name
                    """;

            return dbHelper.executeQuery(sql)
                    .map(rows ->
                    {
                        var profiles = new JsonArray();

                        for (var row : rows)
                        {
                            var credentialIds = (UUID[]) row.getValue("credential_profile_ids");

                            // Convert UUID array to JsonArray
                            var credentialIdsArray = new JsonArray();

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
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("device_type_name", row.getString("device_type_name"))
                                    .put("default_port", row.getInteger("default_port"));

                            profiles.add(profile);
                        }

                        return profiles;
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in discoveryList service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var discoveryName = profileData.getString("discovery_name");

            var ipAddress = profileData.getString("ip_address");

            var isRange = profileData.getBoolean("is_range");

            var deviceTypeId = profileData.getString("device_type_id");

            var credentialProfileIds = profileData.getJsonArray("credential_profile_ids");

            // Convert JsonArray to UUID array for PostgresSQL
            var credentialUUIDs = new UUID[credentialProfileIds.size()];

            for (var i = 0; i < credentialProfileIds.size(); i++)
            {
                credentialUUIDs[i] = UUID.fromString(credentialProfileIds.getString(i));
            }

            var sql = """
                    INSERT INTO discovery_profiles (discovery_name, ip_address, is_range, device_type_id, credential_profile_ids)
                    VALUES ($1, $2, $3, $4, $5)
                    RETURNING profile_id, discovery_name, ip_address, is_range, created_at
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(discoveryName, ipAddress, isRange, UUID.fromString(deviceTypeId), credentialUUIDs))
                    .map(rows ->
                    {
                        var row = rows.iterator().next();

                        return new JsonObject()
                                .put("success", true)
                                .put("profile_id", row.getUUID("profile_id").toString())
                                .put("discovery_name", row.getString("discovery_name"))
                                .put("ip_address", row.getString("ip_address"))
                                .put("is_range", row.getBoolean("is_range"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("message", "Discovery profile created successfully");
                    })
                    .recover(cause ->
                    {
                        logger.error("Failed to create discovery profile: {}", cause.getMessage());

                        if (cause.getMessage().contains("duplicate key"))
                        {
                            if (cause.getMessage().contains("discovery_name"))
                            {
                                return Future.failedFuture(new Exception("Discovery name already exists"));
                            }
                            else if (cause.getMessage().contains("ip_address"))
                            {
                                return Future.failedFuture(new Exception("IP address already exists"));
                            }
                            else
                            {
                                return Future.failedFuture(new Exception("Duplicate key constraint violation"));
                            }
                        }
                        else if (cause.getMessage().contains("foreign key"))
                        {
                            return Future.failedFuture(new Exception("Invalid device type ID or one or more credential profile IDs"));
                        }
                        else
                        {
                            return Future.failedFuture(cause);
                        }
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in discoveryCreate service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            // Hard delete the discovery profile
            var sql = """
                    DELETE FROM discovery_profiles
                    WHERE profile_id = $1
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(profileId)))
                    .map(rows ->
                    {
                        if (rows.rowCount() == 0)
                        {
                            return new JsonObject()
                                    .put("success", false)
                                    .put("message", "Discovery profile not found");
                        }

                        return new JsonObject()
                                .put("success", true)
                                .put("profile_id", profileId)
                                .put("message", "Discovery profile deleted successfully");
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in discoveryDelete service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            // First get the discovery profile basic info
            var profileSql = """
                    SELECT dp.profile_id, dp.discovery_name, dp.ip_address, dp.is_range, dp.device_type_id, dp.credential_profile_ids,
                           dp.created_at, dp.updated_at,
                           dt.device_type_name, dt.default_port
                    FROM discovery_profiles dp
                    JOIN device_types dt ON dp.device_type_id = dt.device_type_id
                    WHERE dp.profile_id = $1
                    """;

            return dbHelper.executePreparedQuery(profileSql, Tuple.of(UUID.fromString(profileId)))
                    .compose(profileRows ->
                    {
                        if (profileRows.size() == 0)
                        {
                            return Future.succeededFuture(new JsonObject().put("found", false));
                        }

                        var profileRow = profileRows.iterator().next();

                        var credentialIds = (UUID[]) profileRow.getValue("credential_profile_ids");

                        // Convert UUID array to JsonArray for response
                        var credentialIdsArray = new JsonArray();

                        for (var credId : credentialIds)
                        {
                            credentialIdsArray.add(credId.toString());
                        }

                        // Get credential profiles details (including encrypted password, port, protocol for discovery use)
                        var credentialSql = """
                                SELECT credential_profile_id, profile_name, username, password_encrypted, port, protocol
                                FROM credential_profiles
                                WHERE credential_profile_id = ANY($1)
                                ORDER BY profile_name
                                """;

                        return dbHelper.executePreparedQuery(credentialSql, Tuple.of(credentialIds))
                                .map(credentialRows ->
                                {
                                    var credentialProfiles = new JsonArray();

                                    for (var credRow : credentialRows)
                                    {
                                        var credProfile = new JsonObject()
                                                .put("credential_profile_id", credRow.getUUID("credential_profile_id").toString())
                                                .put("profile_name", credRow.getString("profile_name"))
                                                .put("username", credRow.getString("username"))
                                                .put("password_encrypted", credRow.getString("password_encrypted"))
                                                .put("port", credRow.getInteger("port"))
                                                .put("protocol", credRow.getString("protocol"));

                                        credentialProfiles.add(credProfile);
                                    }

                                    return new JsonObject()
                                            .put("found", true)
                                            .put("profile_id", profileRow.getUUID("profile_id").toString())
                                            .put("discovery_name", profileRow.getString("discovery_name"))
                                            .put("ip_address", profileRow.getString("ip_address"))
                                            .put("is_range", profileRow.getBoolean("is_range"))
                                            .put("device_type_id", profileRow.getUUID("device_type_id").toString())
                                            .put("credential_profile_ids", credentialIdsArray)
                                            .put("created_at", profileRow.getLocalDateTime("created_at").toString())
                                            .put("updated_at", profileRow.getLocalDateTime("updated_at") != null ?
                                                profileRow.getLocalDateTime("updated_at").toString() : null)
                                            .put("device_type_name", profileRow.getString("device_type_name"))
                                            .put("default_port", profileRow.getInteger("default_port"))
                                            .put("credential_profiles", credentialProfiles);
                                });
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in discoveryGetById service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}
