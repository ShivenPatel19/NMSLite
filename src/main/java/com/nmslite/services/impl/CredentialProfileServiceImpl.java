package com.nmslite.services.impl;

import com.nmslite.services.CredentialProfileService;
import com.nmslite.utils.PasswordUtil;
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
 * CredentialServiceImpl - Implementation of CredentialService
 *
 * Provides credential profile management operations including:
 * - Credential profile CRUD operations
 * - Password encryption/decryption for secure storage
 */
public class CredentialProfileServiceImpl implements CredentialProfileService {

    private static final Logger logger = LoggerFactory.getLogger(CredentialProfileServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public CredentialProfileServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public void credentialList(Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT credential_profile_id, profile_name, username, created_at, updated_at
                    FROM credential_profiles
                    ORDER BY profile_name
                    """;

            pgPool.query(sql)
                    .execute()
                    .onSuccess(rows -> {
                        JsonArray credentials = new JsonArray();
                        for (Row row : rows) {
                            JsonObject credential = new JsonObject()
                                    .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                    .put("profile_name", row.getString("profile_name"))
                                    .put("username", row.getString("username"))
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null);
                            credentials.add(credential);
                        }
                        blockingPromise.complete(credentials);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get credential profiles", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void credentialCreate(JsonObject credentialData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String profileName = credentialData.getString("profile_name");
            String username = credentialData.getString("username");
            String password = credentialData.getString("password");

            // ===== TRUST HANDLER VALIDATION =====
            // No validation here - handler has already validated all input

            // Encrypt password for secure storage
            String encryptedPassword = PasswordUtil.encryptPassword(password);

            String sql = """
                    INSERT INTO credential_profiles (profile_name, username, password_encrypted)
                    VALUES ($1, $2, $3)
                    RETURNING credential_profile_id, profile_name, username, created_at
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(profileName, username, encryptedPassword))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("message", "Credential profile created successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to create credential profile", cause);
                        if (cause.getMessage().contains("duplicate key")) {
                            blockingPromise.fail(new IllegalArgumentException("Profile name already exists"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void credentialUpdate(String credentialId, JsonObject credentialData, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String profileName = credentialData.getString("profile_name");
            String username = credentialData.getString("username");
            String password = credentialData.getString("password");

            StringBuilder sqlBuilder = new StringBuilder("UPDATE credential_profiles SET ");
            JsonArray params = new JsonArray();
            int paramIndex = 1;

            if (profileName != null) {
                sqlBuilder.append("profile_name = $").append(paramIndex++).append(", ");
                params.add(profileName);
            }
            if (username != null) {
                sqlBuilder.append("username = $").append(paramIndex++).append(", ");
                params.add(username);
            }
            if (password != null) {
                String encryptedPassword = PasswordUtil.encryptPassword(password);
                sqlBuilder.append("password_encrypted = $").append(paramIndex++).append(", ");
                params.add(encryptedPassword);
            }

            // ===== TRUST HANDLER VALIDATION =====
            // No validation here - handler has already validated all input

            // Remove trailing comma and space, add WHERE clause
            String sqlStr = sqlBuilder.toString();
            if (sqlStr.endsWith(", ")) {
                sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
            }
            String sql = sqlStr + " WHERE credential_profile_id = $" + paramIndex +
                    " RETURNING credential_profile_id, profile_name, username";
            params.add(UUID.fromString(credentialId));

            pgPool.preparedQuery(sql)
                    .execute(Tuple.from(params.getList()))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.fail(new IllegalArgumentException("Credential profile not found"));
                            return;
                        }
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("message", "Credential profile updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update credential profile", cause);
                        if (cause.getMessage().contains("duplicate key")) {
                            blockingPromise.fail(new IllegalArgumentException("Profile name already exists"));
                        } else {
                            blockingPromise.fail(cause);
                        }
                    });
        }, resultHandler);
    }

    @Override
    public void credentialDelete(String credentialId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            UUID credentialUuid = UUID.fromString(credentialId);

            // Step 1: Check if credential is used in devices table
            String checkDevicesSql = """
                    SELECT COUNT(*) as device_count
                    FROM devices
                    WHERE credential_profile_id = $1 AND is_deleted = false
                    """;

            pgPool.preparedQuery(checkDevicesSql)
                    .execute(Tuple.of(credentialUuid))
                    .onSuccess(deviceRows -> {
                        long deviceCount = deviceRows.iterator().next().getLong("device_count");

                        if (deviceCount > 0) {
                            String errorMsg = String.format(
                                "Cannot delete credential profile - it is currently in use by %d device(s). " +
                                "Please remove or reassign these devices before deleting the credential profile.",
                                deviceCount
                            );
                            blockingPromise.fail(new IllegalStateException(errorMsg));
                            return;
                        }

                        // Step 2: Check if credential is used in discovery_profiles table
                        String checkDiscoverySql = """
                                SELECT COUNT(*) as discovery_count
                                FROM discovery_profiles
                                WHERE $1 = ANY(credential_profile_ids)
                                """;

                        pgPool.preparedQuery(checkDiscoverySql)
                                .execute(Tuple.of(credentialUuid))
                                .onSuccess(discoveryRows -> {
                                    long discoveryCount = discoveryRows.iterator().next().getLong("discovery_count");

                                    if (discoveryCount > 0) {
                                        String errorMsg = String.format(
                                            "Cannot delete credential profile - it is currently in use by %d discovery profile(s). " +
                                            "Please remove it from these discovery profiles before deleting.",
                                            discoveryCount
                                        );
                                        blockingPromise.fail(new IllegalStateException(errorMsg));
                                        return;
                                    }

                                    // Step 3: No usage found, proceed with deletion
                                    String deleteSql = """
                                            DELETE FROM credential_profiles
                                            WHERE credential_profile_id = $1
                                            """;

                                    pgPool.preparedQuery(deleteSql)
                                            .execute(Tuple.of(credentialUuid))
                                            .onSuccess(deleteRows -> {
                                                if (deleteRows.rowCount() == 0) {
                                                    blockingPromise.fail(new IllegalArgumentException("Credential profile not found"));
                                                    return;
                                                }
                                                JsonObject result = new JsonObject()
                                                        .put("success", true)
                                                        .put("credential_profile_id", credentialId)
                                                        .put("message", "Credential profile deleted successfully");
                                                blockingPromise.complete(result);
                                            })
                                            .onFailure(cause -> {
                                                logger.error("Failed to delete credential profile", cause);
                                                blockingPromise.fail(cause);
                                            });
                                })
                                .onFailure(cause -> {
                                    logger.error("Failed to check discovery profile usage", cause);
                                    blockingPromise.fail(cause);
                                });
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to check device usage", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void credentialGetById(String credentialId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT credential_profile_id, profile_name, username, password_encrypted, created_at, updated_at
                    FROM credential_profiles
                    WHERE credential_profile_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(credentialId)))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            blockingPromise.complete(new JsonObject().put("found", false));
                            return;
                        }

                        Row row = rows.iterator().next();
                        // Decrypt password for response (be careful with this in production)
                        String decryptedPassword = PasswordUtil.decryptPassword(row.getString("password_encrypted"));

                        JsonObject result = new JsonObject()
                                .put("found", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("password", decryptedPassword)  // Only for admin access
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null);
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get credential profile by ID", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void credentialGetByIds(JsonArray credentialIds, Handler<AsyncResult<JsonObject>> resultHandler) {
        vertx.executeBlocking(blockingPromise -> {
            if (credentialIds.isEmpty()) {
                blockingPromise.complete(new JsonObject()
                    .put("success", true)
                    .put("data", new JsonObject().put("credentials", new JsonArray())));
                return;
            }

            // Convert JsonArray to UUID array for PostgreSQL
            UUID[] uuidArray = new UUID[credentialIds.size()];
            for (int i = 0; i < credentialIds.size(); i++) {
                uuidArray[i] = UUID.fromString(credentialIds.getString(i));
            }

            String sql = """
                    SELECT credential_profile_id, profile_name, username, password_encrypted, created_at, updated_at
                    FROM credential_profiles
                    WHERE credential_profile_id = ANY($1)
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(uuidArray))
                    .onSuccess(rows -> {
                        JsonArray credentials = new JsonArray();

                        for (Row row : rows) {
                            // Decrypt password for discovery use
                            String decryptedPassword = PasswordUtil.decryptPassword(row.getString("password_encrypted"));

                            JsonObject credential = new JsonObject()
                                    .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                    .put("profile_name", row.getString("profile_name"))
                                    .put("username", row.getString("username"))
                                    .put("password_encrypted", decryptedPassword)  // For GoEngine use
                                    .put("created_at", row.getLocalDateTime("created_at").toString())
                                    .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                        row.getLocalDateTime("updated_at").toString() : null);
                            credentials.add(credential);
                        }

                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("data", new JsonObject().put("credentials", credentials));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get credential profiles by IDs", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }
}