package com.nmslite.services.impl;

import com.nmslite.services.CredentialProfileService;

import com.nmslite.utils.PasswordUtil;

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
 * CredentialServiceImpl - Implementation of CredentialService

 * Provides credential profile management operations including:
 * - Credential profile CRUD operations
 * - Password encryption/decryption for secure storage
 */
public class CredentialProfileServiceImpl implements CredentialProfileService
{

    private static final Logger logger = LoggerFactory.getLogger(CredentialProfileServiceImpl.class);

    private final Pool pgPool;

    /**
     * Constructor for CredentialProfileServiceImpl
     *
     * @param pgPool PostgresSQL connection pool
     */
    public CredentialProfileServiceImpl(Pool pgPool)
    {
        this.pgPool = pgPool;
    }

    /**
     * Get list of credential profiles
     *
     * @return Future containing JsonArray of credential profiles
     */
    @Override
    public Future<JsonArray> credentialList()
    {
        var promise = Promise.<JsonArray>promise();

        var sql = """
                SELECT credential_profile_id, profile_name, username, created_at, updated_at
                FROM credential_profiles
                ORDER BY profile_name
                """;

        pgPool.query(sql)
                .execute()
                .onSuccess(rows ->
                {
                    var credentials = new JsonArray();

                    for (var row : rows)
                    {
                        var credential = new JsonObject()
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null);

                        credentials.add(credential);
                    }

                    promise.complete(credentials);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get credential profiles", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Create a new credential profile
     *
     * @param credentialData Credential profile data
     * @return Future containing JsonObject with creation result
     */
    @Override
    public Future<JsonObject> credentialCreate(JsonObject credentialData)
    {
        var promise = Promise.<JsonObject>promise();

        var profileName = credentialData.getString("profile_name");

        var username = credentialData.getString("username");

        var password = credentialData.getString("password");

        // ===== TRUST HANDLER VALIDATION =====
        // No validation here - handler has already validated all input

        // Encrypt password for secure storage
        var encryptedPassword = PasswordUtil.encryptPassword(password);

        var sql = """
                INSERT INTO credential_profiles (profile_name, username, password_encrypted)
                VALUES ($1, $2, $3)
                RETURNING credential_profile_id, profile_name, username, created_at
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(profileName, username, encryptedPassword))
                .onSuccess(rows ->
                {
                    var row = rows.iterator().next();

                    var result = new JsonObject()
                            .put("success", true)
                            .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                            .put("profile_name", row.getString("profile_name"))
                            .put("username", row.getString("username"))
                            .put("created_at", row.getLocalDateTime("created_at").toString())
                            .put("message", "Credential profile created successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to create credential profile", cause);

                    if (cause.getMessage().contains("duplicate key"))
                    {
                        promise.fail(new IllegalArgumentException("Profile name already exists"));
                    }
                    else
                    {
                        promise.fail(cause);
                    }
                });

        return promise.future();
    }

    /**
     * Update an existing credential profile
     *
     * @param credentialId Credential profile ID
     * @param credentialData Credential profile data to update
     * @return Future containing JsonObject with update result
     */
    @Override
    public Future<JsonObject> credentialUpdate(String credentialId, JsonObject credentialData)
    {
        var promise = Promise.<JsonObject>promise();

        var profileName = credentialData.getString("profile_name");

        var username = credentialData.getString("username");

        var password = credentialData.getString("password");

        var sqlBuilder = new StringBuilder("UPDATE credential_profiles SET ");

        var params = new JsonArray();

        var paramIndex = 1;

        if (profileName != null)
        {
            sqlBuilder.append("profile_name = $").append(paramIndex++).append(", ");

            params.add(profileName);
        }

        if (username != null)
        {
            sqlBuilder.append("username = $").append(paramIndex++).append(", ");

            params.add(username);
        }

        if (password != null)
        {
            var encryptedPassword = PasswordUtil.encryptPassword(password);

            sqlBuilder.append("password_encrypted = $").append(paramIndex++).append(", ");

            params.add(encryptedPassword);
        }

        // ===== TRUST HANDLER VALIDATION =====
        // No validation here - handler has already validated all input

        // Remove trailing comma and space, add WHERE clause
        var sqlStr = sqlBuilder.toString();

        if (sqlStr.endsWith(", "))
        {
            sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
        }

        var sql = sqlStr + " WHERE credential_profile_id = $" + paramIndex +
                " RETURNING credential_profile_id, profile_name, username";

        params.add(UUID.fromString(credentialId));

        pgPool.preparedQuery(sql)
                .execute(Tuple.from(params.getList()))
                .onSuccess(rows ->
                {
                    if (rows.size() == 0)
                    {
                        promise.fail(new IllegalArgumentException("Credential profile not found"));

                        return;
                    }

                    var row = rows.iterator().next();

                    var result = new JsonObject()
                            .put("success", true)
                            .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                            .put("profile_name", row.getString("profile_name"))
                            .put("username", row.getString("username"))
                            .put("message", "Credential profile updated successfully");

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to update credential profile", cause);

                    if (cause.getMessage().contains("duplicate key"))
                    {
                        promise.fail(new IllegalArgumentException("Profile name already exists"));
                    }
                    else
                    {
                        promise.fail(cause);
                    }
                });

        return promise.future();
    }

    /**
     * Delete a credential profile
     *
     * @param credentialId Credential profile ID
     * @return Future containing JsonObject with deletion result
     */
    @Override
    public Future<JsonObject> credentialDelete(String credentialId)
    {
        var promise = Promise.<JsonObject>promise();

        var credentialUuid = UUID.fromString(credentialId);

        // Step 1: Check if credential is used in devices table
        var checkDevicesSql = """
                SELECT COUNT(*) as device_count
                FROM devices
                WHERE credential_profile_id = $1 AND is_deleted = false
                """;

        pgPool.preparedQuery(checkDevicesSql)
                .execute(Tuple.of(credentialUuid))
                .onSuccess(deviceRows ->
                {
                    var deviceCount = deviceRows.iterator().next().getLong("device_count");

                    if (deviceCount > 0)
                    {
                        var errorMsg = String.format(
                            "Cannot delete credential profile - it is currently in use by %d device(s). " +
                            "Please remove or reassign these devices before deleting the credential profile.",
                            deviceCount
                        );

                        promise.fail(new IllegalStateException(errorMsg));

                        return;
                    }

                    // Step 2: Check if credential is used in discovery_profiles table
                    var checkDiscoverySql = """
                            SELECT COUNT(*) as discovery_count
                            FROM discovery_profiles
                            WHERE $1 = ANY(credential_profile_ids)
                            """;

                    pgPool.preparedQuery(checkDiscoverySql)
                            .execute(Tuple.of(credentialUuid))
                            .onSuccess(discoveryRows ->
                            {
                                var discoveryCount = discoveryRows.iterator().next().getLong("discovery_count");

                                if (discoveryCount > 0)
                                {
                                    var errorMsg = String.format(
                                        "Cannot delete credential profile - it is currently in use by %d discovery profile(s). " +
                                        "Please remove it from these discovery profiles before deleting.",
                                        discoveryCount
                                    );

                                    promise.fail(new IllegalStateException(errorMsg));

                                    return;
                                }

                                // Step 3: No usage found, proceed with deletion
                                var deleteSql = """
                                        DELETE FROM credential_profiles
                                        WHERE credential_profile_id = $1
                                        """;

                                pgPool.preparedQuery(deleteSql)
                                        .execute(Tuple.of(credentialUuid))
                                        .onSuccess(deleteRows ->
                                        {
                                            if (deleteRows.rowCount() == 0)
                                            {
                                                promise.fail(new IllegalArgumentException("Credential profile not found"));

                                                return;
                                            }

                                            var result = new JsonObject()
                                                    .put("success", true)
                                                    .put("credential_profile_id", credentialId)
                                                    .put("message", "Credential profile deleted successfully");

                                            promise.complete(result);
                                        })
                                        .onFailure(cause ->
                                        {
                                            logger.error("Failed to delete credential profile", cause);

                                            promise.fail(cause);
                                        });
                            })
                            .onFailure(cause ->
                            {
                                logger.error("Failed to check discovery profile usage", cause);

                                promise.fail(cause);
                            });
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to check device usage", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Get credential profile by ID
     *
     * @param credentialId Credential profile ID
     * @return Future containing JsonObject with credential profile data or not found
     */
    @Override
    public Future<JsonObject> credentialGetById(String credentialId)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
                SELECT credential_profile_id, profile_name, username, password_encrypted, created_at, updated_at
                FROM credential_profiles
                WHERE credential_profile_id = $1
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(credentialId)))
                .onSuccess(rows ->
                {
                    if (rows.size() == 0)
                    {
                        promise.complete(new JsonObject().put("found", false));

                        return;
                    }

                    var row = rows.iterator().next();

                    // Decrypt password for response (be careful with this in production)
                    var decryptedPassword = PasswordUtil.decryptPassword(row.getString("password_encrypted"));

                    var result = new JsonObject()
                            .put("found", true)
                            .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                            .put("profile_name", row.getString("profile_name"))
                            .put("username", row.getString("username"))
                            .put("password", decryptedPassword)  // Only for admin access
                            .put("created_at", row.getLocalDateTime("created_at").toString())
                            .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                row.getLocalDateTime("updated_at").toString() : null);

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get credential profile by ID", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

    /**
     * Get credential profiles by IDs
     *
     * @param credentialIds Array of credential profile IDs
     * @return Future containing JsonObject with credentials array
     */
    @Override
    public Future<JsonObject> credentialGetByIds(JsonArray credentialIds)
    {
        var promise = Promise.<JsonObject>promise();

        if (credentialIds.isEmpty())
        {
            promise.complete(new JsonObject()
                .put("success", true)
                .put("data", new JsonObject().put("credentials", new JsonArray())));

            return promise.future();
        }

        // Convert JsonArray to UUID array for PostgresSQL
        var uuidArray = new UUID[credentialIds.size()];

        for (var i = 0; i < credentialIds.size(); i++)
        {
            uuidArray[i] = UUID.fromString(credentialIds.getString(i));
        }

        var sql = """
                SELECT credential_profile_id, profile_name, username, password_encrypted, created_at, updated_at
                FROM credential_profiles
                WHERE credential_profile_id = ANY($1)
                """;

        pgPool.preparedQuery(sql)
                .execute(Tuple.of(uuidArray))
                .onSuccess(rows ->
                {
                    var credentials = new JsonArray();

                    for (var row : rows)
                    {
                        // Decrypt password for discovery use
                        var decryptedPassword = PasswordUtil.decryptPassword(row.getString("password_encrypted"));

                        var credential = new JsonObject()
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("password_encrypted", decryptedPassword)  // For GoEngine use
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null);

                        credentials.add(credential);
                    }

                    var result = new JsonObject()
                            .put("success", true)
                            .put("data", new JsonObject().put("credentials", credentials));

                    promise.complete(result);
                })
                .onFailure(cause ->
                {
                    logger.error("Failed to get credential profiles by IDs", cause);

                    promise.fail(cause);
                });

        return promise.future();
    }

}