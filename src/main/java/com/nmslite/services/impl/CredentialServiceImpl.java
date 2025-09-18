package com.nmslite.services.impl;

import com.nmslite.services.CredentialService;
import com.nmslite.utils.PasswordUtil;
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
 * CredentialServiceImpl - Implementation of CredentialService
 * 
 * Provides credential profile management operations including:
 * - Credential profile CRUD operations
 * - Password encryption/decryption for secure storage
 * - Protocol management for different device types
 */
public class CredentialServiceImpl implements CredentialService {

    private static final Logger logger = LoggerFactory.getLogger(CredentialServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public CredentialServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public void credentialList(boolean includeInactive, Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT credential_profile_id, profile_name, username, protocol, is_active, created_at, updated_at, created_by
                    FROM credential_profiles
                    """ + (includeInactive ? "" : "WHERE is_active = true ") + """
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
                                    .put("protocol", row.getString("protocol"))
                                    .put("is_active", row.getBoolean("is_active"))
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
            String protocol = credentialData.getString("protocol");
            Boolean isActive = credentialData.getBoolean("is_active", true);
            String createdBy = credentialData.getString("created_by");

            if (profileName == null || username == null || password == null) {
                blockingPromise.fail(new IllegalArgumentException("Profile name, username, and password are required"));
                return;
            }

            // Encrypt password for secure storage
            String encryptedPassword = PasswordUtil.encryptPassword(password);

            String sql = """
                    INSERT INTO credential_profiles (profile_name, username, password_encrypted, protocol, is_active, created_by)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    RETURNING credential_profile_id, profile_name, username, protocol, is_active, created_at, created_by
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(profileName, username, encryptedPassword, protocol, isActive, createdBy))
                    .onSuccess(rows -> {
                        Row row = rows.iterator().next();
                        JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("credential_profile_id", row.getUUID("credential_profile_id").toString())
                                .put("profile_name", row.getString("profile_name"))
                                .put("username", row.getString("username"))
                                .put("protocol", row.getString("protocol"))
                                .put("is_active", row.getBoolean("is_active"))
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
            String protocol = credentialData.getString("protocol");
            Boolean isActive = credentialData.getBoolean("is_active");

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
            if (protocol != null) {
                sqlBuilder.append("protocol = $").append(paramIndex++).append(", ");
                params.add(protocol);
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
                    " WHERE credential_profile_id = $" + paramIndex +
                    " RETURNING credential_profile_id, profile_name, username, protocol, is_active";
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
                                .put("protocol", row.getString("protocol"))
                                .put("is_active", row.getBoolean("is_active"))
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
            // Soft delete by setting is_active to false
            String sql = """
                    UPDATE credential_profiles 
                    SET is_active = false
                    WHERE credential_profile_id = $1
                    RETURNING credential_profile_id, profile_name
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(UUID.fromString(credentialId)))
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
                                .put("message", "Credential profile deactivated successfully");
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
                    SELECT credential_profile_id, profile_name, username, password_encrypted, protocol, is_active, created_at, updated_at, created_by
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
                                .put("protocol", row.getString("protocol"))
                                .put("is_active", row.getBoolean("is_active"))
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

    @Override
    public void credentialGetByName(String profileName, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT credential_profile_id, profile_name, username, password_encrypted, protocol, is_active, created_at, updated_at, created_by
                    FROM credential_profiles
                    WHERE profile_name = $1
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(profileName))
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
                                .put("protocol", row.getString("protocol"))
                                .put("is_active", row.getBoolean("is_active"))
                                .put("created_at", row.getLocalDateTime("created_at").toString())
                                .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                    row.getLocalDateTime("updated_at").toString() : null)
                                .put("created_by", row.getString("created_by"));
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get credential profile by name", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void credentialSetActive(String credentialId, boolean isActive, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    UPDATE credential_profiles
                    SET is_active = $1
                    WHERE credential_profile_id = $2
                    RETURNING credential_profile_id, profile_name, is_active
                    """;

            pgPool.preparedQuery(sql)
                    .execute(Tuple.of(isActive, UUID.fromString(credentialId)))
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
                                .put("is_active", row.getBoolean("is_active"))
                                .put("message", "Credential profile status updated successfully");
                        blockingPromise.complete(result);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to update credential profile status", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }

    @Override
    public void credentialListActive(Handler<AsyncResult<JsonArray>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                    SELECT credential_profile_id, profile_name, username, protocol
                    FROM credential_profiles
                    WHERE is_active = true
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
                                    .put("protocol", row.getString("protocol"));
                            credentials.add(credential);
                        }
                        blockingPromise.complete(credentials);
                    })
                    .onFailure(cause -> {
                        logger.error("Failed to get active credential profiles", cause);
                        blockingPromise.fail(cause);
                    });
        }, resultHandler);
    }
}
