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
                    SELECT credential_profile_id, profile_name, username, created_at, updated_at, created_by
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
                                        row.getLocalDateTime("updated_at").toString() : null)
                                    .put("created_by", row.getString("created_by"));
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
            String createdBy = credentialData.getString("created_by");

            if (profileName == null || username == null || password == null) {
                blockingPromise.fail(new IllegalArgumentException("Profile name, username, and password are required"));
                return;
            }

            // Encrypt password for secure storage
            String encryptedPassword = PasswordUtil.encryptPassword(password);

            String sql = """
                    INSERT INTO credential_profiles (profile_name, username, password_encrypted, created_by)
                    VALUES ($1, $2, $3, $4)
                    RETURNING credential_profile_id, profile_name, username, created_at, created_by
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(profileName, username, encryptedPassword, createdBy))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("created_by", row.getString("created_by"))
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

            if (params.size() == 0) {
                blockingPromise.fail(new IllegalArgumentException("No fields to update"));
                return;
            }

            // Remove trailing comma and space
            String sql = sqlBuilder.substring(0, sqlBuilder.length() - 2) +
                    " WHERE credential_profile_id = $" + paramIndex +
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
            // Hard delete the credential profile
            String sql = """
                    DELETE FROM credential_profiles
                    WHERE credential_profile_id = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(credentialId)))
                    .onSuccess(rows -> {
                        if (rows.rowCount() == 0) {
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
        }, resultHandler);
    }

    @Override
    public void credentialGetById(String credentialId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT credential_profile_id, profile_name, username, password_encrypted, created_at, updated_at, created_by
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
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("created_by", row.getString("created_by"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get credential profile by ID", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }
}
