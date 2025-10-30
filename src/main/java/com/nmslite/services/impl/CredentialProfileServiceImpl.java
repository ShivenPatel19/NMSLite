package com.nmslite.services.impl;

import com.nmslite.database.DatabaseHelper;

import com.nmslite.database.DatabaseInitializer;

import com.nmslite.services.CredentialProfileService;

import com.nmslite.utils.PasswordUtil;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * CredentialServiceImpl - Implementation of CredentialService

 * Provides credential profile management operations including:
 * - Credential profile CRUD operations
 * - Password encryption/decryption for secure storage

 * Database Access:
 * - Uses DatabaseInitializer.getDatabaseHelper() for database operations
 * - DatabaseHelper provides generic query execution with consistent error handling
 * - No constructor parameters needed
 */
public class CredentialProfileServiceImpl implements CredentialProfileService
{

    private static final Logger logger = LoggerFactory.getLogger(CredentialProfileServiceImpl.class);

    private final DatabaseHelper dbHelper;

    /**
     * Constructor for CredentialProfileServiceImpl.
     * Accesses database helper via DatabaseInitializer.
     */
    public CredentialProfileServiceImpl()
    {
        this.dbHelper = DatabaseInitializer.getDatabaseHelper();
    }

    /**
     * Get list of credential profiles
     *
     * @return Future containing JsonArray of credential profiles
     */
    @Override
    public Future<JsonArray> credentialList()
    {
        try
        {
            var sql = """
                    SELECT credential_profile_id, profile_name, username, port, protocol, created_at, updated_at
                    FROM credential_profiles
                    ORDER BY profile_name
                    """;

            return dbHelper.executeQuery(sql)
                    .map(rows ->
                    {
                        var credentials = new JsonArray();

                        for (var row : rows)
                        {
                            var credential = new JsonObject()
                                    .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                    .put("profile_name", row.getString("profile_name"))
                                    .put("username", row.getString("username"))
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null);

                            credentials.add(credential);
                        }

                        return credentials;
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in credentialList service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var profileName = credentialData.getString("profile_name");

            var username = credentialData.getString("username");

            var password = credentialData.getString("password");

            var port = credentialData.getInteger("port");

            var protocol = credentialData.getString("protocol");

            // Encrypt password for secure storage
            var encryptedPassword = PasswordUtil.encryptPassword(password);

            if (encryptedPassword == null)
            {
                logger.error("Failed to encrypt password for credential profile");

                return Future.failedFuture(new Exception("Failed to encrypt password"));
            }

            var sql = """
                    INSERT INTO credential_profiles (profile_name, username, password_encrypted, port, protocol)
                    VALUES ($1, $2, $3, $4, $5)
                    RETURNING credential_profile_id, profile_name, username, port, protocol, created_at
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(profileName, username, encryptedPassword, port, protocol))
                    .map(rows ->
                    {
                        var row = rows.iterator().next();

                        return new JsonObject()
                                .put("success", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("message", "Credential profile created successfully");
                    })
                    .recover(cause ->
                    {
                        logger.error("Failed to create credential profile: {}", cause.getMessage());

                        if (cause.getMessage().contains("duplicate key"))
                        {
                            return Future.failedFuture(new Exception("Profile name already exists"));
                        }
                        else
                        {
                            return Future.failedFuture(cause);
                        }
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in credentialCreate service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var profileName = credentialData.getString("profile_name");

            var username = credentialData.getString("username");

            var password = credentialData.getString("password");

            var port = credentialData.getInteger("port");

            var protocol = credentialData.getString("protocol");

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

                if (encryptedPassword == null)
                {
                    logger.error("Failed to encrypt password for credential profile update");

                    return Future.failedFuture(new Exception("Failed to encrypt password"));
                }

                sqlBuilder.append("password_encrypted = $").append(paramIndex++).append(", ");

                params.add(encryptedPassword);
            }

            if (port != null)
            {
                sqlBuilder.append("port = $").append(paramIndex++).append(", ");

                params.add(port);
            }

            if (protocol != null)
            {
                sqlBuilder.append("protocol = $").append(paramIndex++).append(", ");

                params.add(protocol);
            }

            // Remove trailing comma and space, add WHERE clause
            var sqlStr = sqlBuilder.toString();

            if (sqlStr.endsWith(", "))
            {
                sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
            }

            var sql = sqlStr + " WHERE credential_profile_id = $" + paramIndex +
                    " RETURNING credential_profile_id, profile_name, username, port, protocol";

            params.add(UUID.fromString(credentialId));

            return dbHelper.executePreparedQuery(sql, Tuple.from(params.getList()))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            return new JsonObject()
                                    .put("success", false)
                                    .put("message", "Credential profile not found");
                        }

                        var row = rows.iterator().next();

                        return new JsonObject()
                                .put("success", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("message", "Credential profile updated successfully");
                    })
                    .recover(cause ->
                    {
                        logger.error("Failed to update credential profile: {}", cause.getMessage());

                        if (cause.getMessage().contains("duplicate key"))
                        {
                            return Future.failedFuture(new Exception("Profile name already exists"));
                        }
                        else
                        {
                            return Future.failedFuture(cause);
                        }
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in credentialUpdate service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var credentialUuid = UUID.fromString(credentialId);

            // Step 1: Check if credential is used in devices table
            var checkDevicesSql = """
                    SELECT COUNT(*) as device_count
                    FROM devices
                    WHERE credential_profile_id = $1 AND is_deleted = false
                    """;

            return dbHelper.executePreparedQuery(checkDevicesSql, Tuple.of(credentialUuid))
                    .compose(deviceRows ->
                    {
                        var deviceCount = deviceRows.iterator().next().getLong("device_count");

                        if (deviceCount > 0)
                        {
                            var errorMsg = String.format(
                                "Cannot delete credential profile - it is currently in use by %d device(s). " +
                                "Please remove or reassign these devices before deleting the credential profile.",
                                deviceCount
                            );

                            return Future.failedFuture(new Exception(errorMsg));
                        }

                        // Step 2: Check if credential is used in discovery_profiles table
                        var checkDiscoverySql = """
                                SELECT COUNT(*) as discovery_count
                                FROM discovery_profiles
                                WHERE $1 = ANY(credential_profile_ids)
                                """;

                        return dbHelper.executePreparedQuery(checkDiscoverySql, Tuple.of(credentialUuid))
                                .compose(discoveryRows ->
                                {
                                    var discoveryCount = discoveryRows.iterator().next().getLong("discovery_count");

                                    if (discoveryCount > 0)
                                    {
                                        var errorMsg = String.format(
                                            "Cannot delete credential profile - it is currently in use by %d discovery profile(s). " +
                                            "Please remove it from these discovery profiles before deleting.",
                                            discoveryCount
                                        );

                                        return Future.failedFuture(new Exception(errorMsg));
                                    }

                                    // Step 3: No usage found, proceed with deletion
                                    var deleteSql = """
                                            DELETE FROM credential_profiles
                                            WHERE credential_profile_id = $1
                                            """;

                                    return dbHelper.executePreparedQuery(deleteSql, Tuple.of(credentialUuid))
                                            .map(deleteRows ->
                                            {
                                                if (deleteRows.rowCount() == 0)
                                                {
                                                    return new JsonObject()
                                                            .put("success", false)
                                                            .put("message", "Credential profile not found");
                                                }

                                                return new JsonObject()
                                                        .put("success", true)
                                                        .put("credential_profile_id", credentialId)
                                                        .put("message", "Credential profile deleted successfully");
                                            });
                                });
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in credentialDelete service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                    SELECT credential_profile_id, profile_name, username, password_encrypted, port, protocol, created_at, updated_at
                    FROM credential_profiles
                    WHERE credential_profile_id = $1
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(credentialId)))
                    .map(rows ->
                    {
                        if (rows.size() == 0)
                        {
                            return new JsonObject().put("found", false);
                        }

                        var row = rows.iterator().next();

                        // Decrypt password for response (be careful with this in production)
                        var decryptedPassword = PasswordUtil.decryptPassword(row.getString("password_encrypted"));

                        if (decryptedPassword == null)
                        {
                            logger.error("Failed to decrypt password for credential profile: {}", credentialId);

                            return new JsonObject()
                                .put("found", false)
                                .put("error", "Failed to decrypt credential password");
                        }

                        return new JsonObject()
                                .put("found", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("password", decryptedPassword)  // Only for admin access
                                .put("port", row.getInteger("port"))
                                .put("protocol", row.getString("protocol"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null);
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in credentialGetById service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            if (credentialIds.isEmpty())
            {
                return Future.succeededFuture(new JsonObject()
                    .put("success", true)
                    .put("data", new JsonObject().put("credentials", new JsonArray())));
            }

            // Convert JsonArray to UUID array for PostgresSQL
            var uuidArray = new UUID[credentialIds.size()];

            for (var i = 0; i < credentialIds.size(); i++)
            {
                uuidArray[i] = UUID.fromString(credentialIds.getString(i));
            }

            // ANY(list of ids) -> compares each row's UUID against every UUID in the array, returning row if matched.
            var sql = """
                    SELECT credential_profile_id, profile_name, username, password_encrypted, port, protocol, created_at, updated_at
                    FROM credential_profiles
                    WHERE credential_profile_id = ANY($1)
                    """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(uuidArray))
                    .map(rows ->
                    {
                        var credentials = new JsonArray();

                        for (var row : rows)
                        {
                            // Decrypt password for discovery use
                            var decryptedPassword = PasswordUtil.decryptPassword(row.getString("password_encrypted"));

                            if (decryptedPassword == null)
                            {
                                logger.error("Failed to decrypt password for credential profile: {}",
                                    row.getUUID("credential_profile_id").toString());

                                // Skip this credential and continue with others
                                continue;
                            }

                            var credential = new JsonObject()
                                    .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                    .put("profile_name", row.getString("profile_name"))
                                    .put("username", row.getString("username"))
                                    .put("password_encrypted", decryptedPassword)  // For GoEngine use
                                    .put("port", row.getInteger("port"))
                                    .put("protocol", row.getString("protocol"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null);

                            credentials.add(credential);
                        }

                        return new JsonObject()
                                .put("success", true)
                                .put("data", new JsonObject().put("credentials", credentials));
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in credentialGetByIds service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}