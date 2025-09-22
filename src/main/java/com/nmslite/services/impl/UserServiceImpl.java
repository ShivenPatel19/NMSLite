package com.nmslite.services.impl;

import com.nmslite.services.UserService;
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
 * UserServiceImpl - Implementation of UserService
 * 
 * Provides user management operations including:
 * - User CRUD operations
 * - Password hashing and authentication
 * - User session management
 */
public class UserServiceImpl implements UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);
    private final Vertx vertx;
    private final PgPool pgPool;

    public UserServiceImpl(Vertx vertx, PgPool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    @Override
    public void userList(boolean includeInactive, Handler<AsyncResult<JsonArray>> resultHandler) {
        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                SELECT user_id, username, is_active, created_at, updated_at, last_login_at
                FROM users
                """ + (includeInactive ? "" : "WHERE is_active = true ") + """
                ORDER BY created_at DESC
                """;

            pgPool.query(sql)
                .execute()
                .onSuccess(rows -> {
                    JsonArray users = new JsonArray();
                    for (Row row : rows) {
                        JsonObject user = new JsonObject()
                            .put("user_id", row.getUUID("user_id").toString())
                            .put("username", row.getString("username"))
                            .put("is_active", row.getBoolean("is_active"))
                            .put("created_at", row.getLocalDateTime("created_at").toString())
                            .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                                row.getLocalDateTime("updated_at").toString() : null)
                            .put("last_login_at", row.getLocalDateTime("last_login_at") != null ?
                                row.getLocalDateTime("last_login_at").toString() : null);
                        users.add(user);
                    }
                    blockingPromise.complete(users);
                })
                .onFailure(cause -> {
                    logger.error("Failed to get users", cause);
                    blockingPromise.fail(cause);
                });
        }, resultHandler);
    }

    /**
     * Get all users (default: active users only)
     */
    public void userList(Handler<AsyncResult<JsonArray>> resultHandler) {
        userList(false, resultHandler);
    }

    @Override
    public void userCreate(JsonObject userData, Handler<AsyncResult<JsonObject>> resultHandler) {
        vertx.executeBlocking(blockingPromise -> {
            String username = userData.getString("username");
            String password = userData.getString("password");
            Boolean isActive = userData.getBoolean("is_active", true);

            if (username == null || password == null) {
                blockingPromise.fail(new IllegalArgumentException("Username and password are required"));
                return;
            }

            // Hash password for user authentication
            String passwordHash = PasswordUtil.hashPassword(password);

            String sql = """
                INSERT INTO users (username, password_hash, is_active)
                VALUES ($1, $2, $3)
                RETURNING user_id, username, is_active, created_at
                """;

            pgPool.preparedQuery(sql)
                .execute(Tuple.of(username, passwordHash, isActive))
                .onSuccess(rows -> {
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("created_at", row.getLocalDateTime("created_at").toString())
                        .put("message", "User created successfully");
                    blockingPromise.complete(result);
                })
                .onFailure(cause -> {
                    logger.error("Failed to create user", cause);
                    if (cause.getMessage().contains("duplicate key")) {
                        blockingPromise.fail(new IllegalArgumentException("Username already exists"));
                    } else {
                        blockingPromise.fail(cause);
                    }
                });
        }, resultHandler);
    }

    @Override
    public void userUpdate(String userId, JsonObject userData, Handler<AsyncResult<JsonObject>> resultHandler) {
        vertx.executeBlocking(blockingPromise -> {
            String username = userData.getString("username");
            String password = userData.getString("password");
            Boolean isActive = userData.getBoolean("is_active");

            StringBuilder sqlBuilder = new StringBuilder("UPDATE users SET ");
            JsonArray params = new JsonArray();
            int paramIndex = 1;

            if (username != null) {
                sqlBuilder.append("username = $").append(paramIndex++).append(", ");
                params.add(username);
            }
            if (password != null) {
                String passwordHash = PasswordUtil.hashPassword(password);
                sqlBuilder.append("password_hash = $").append(paramIndex++).append(", ");
                params.add(passwordHash);
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
                " WHERE user_id = $" + paramIndex + " RETURNING user_id, username, is_active, updated_at";
            params.add(UUID.fromString(userId));

            pgPool.preparedQuery(sql)
                .execute(Tuple.from(params.getList()))
                .onSuccess(rows -> {
                    if (rows.size() == 0) {
                        blockingPromise.fail(new IllegalArgumentException("User not found"));
                        return;
                    }
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("updated_at", row.getLocalDateTime("updated_at").toString())
                        .put("message", "User updated successfully");
                    blockingPromise.complete(result);
                })
                .onFailure(cause -> {
                    logger.error("Failed to update user", cause);
                    if (cause.getMessage().contains("duplicate key")) {
                        blockingPromise.fail(new IllegalArgumentException("Username already exists"));
                    } else {
                        blockingPromise.fail(cause);
                    }
                });
        }, resultHandler);
    }

    @Override
    public void userDelete(String userId, Handler<AsyncResult<JsonObject>> resultHandler) {
        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                DELETE FROM users
                WHERE user_id = $1
                RETURNING user_id, username
                """;

            pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(userId)))
                .onSuccess(rows -> {
                    if (rows.size() == 0) {
                        blockingPromise.fail(new IllegalArgumentException("User not found"));
                        return;
                    }
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("message", "User deleted successfully");
                    blockingPromise.complete(result);
                })
                .onFailure(cause -> {
                    logger.error("Failed to delete user", cause);
                    blockingPromise.fail(cause);
                });
        }, resultHandler);
    }

    @Override
    public void userAuthenticate(String username, String password, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                SELECT user_id, username, password_hash, is_active
                FROM users
                WHERE username = $1 AND is_active = true
                """;

            pgPool.preparedQuery(sql)
                .execute(Tuple.of(username))
                .onSuccess(rows -> {
                    if (rows.size() == 0) {
                        blockingPromise.complete(new JsonObject()
                            .put("authenticated", false)
                            .put("message", "Invalid username or password"));
                        return;
                    }

                    Row row = rows.iterator().next();
                    String storedPasswordHash = row.getString("password_hash");
                    
                    if (PasswordUtil.verifyPassword(password, storedPasswordHash)) {
                        JsonObject result = new JsonObject()
                            .put("authenticated", true)
                            .put("user_id", row.getUUID("user_id").toString())
                            .put("username", row.getString("username"))
                            .put("is_active", row.getBoolean("is_active"))
                            .put("message", "Authentication successful");
                        blockingPromise.complete(result);
                    } else {
                        blockingPromise.complete(new JsonObject()
                            .put("authenticated", false)
                            .put("message", "Invalid username or password"));
                    }
                })
                .onFailure(cause -> {
                    logger.error("Failed to authenticate user", cause);
                    blockingPromise.fail(cause);
                });
        }, resultHandler);
    }

    @Override
    public void userUpdateLastLogin(String userId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                UPDATE users
                SET last_login_at = CURRENT_TIMESTAMP
                WHERE user_id = $1 AND is_active = true
                RETURNING user_id, username, last_login_at
                """;

            pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(userId)))
                .onSuccess(rows -> {
                    if (rows.size() == 0) {
                        blockingPromise.fail(new IllegalArgumentException("User not found or inactive"));
                        return;
                    }
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("last_login_at", row.getLocalDateTime("last_login_at").toString())
                        .put("message", "Last login updated successfully");
                    blockingPromise.complete(result);
                })
                .onFailure(cause -> {
                    logger.error("Failed to update last login", cause);
                    blockingPromise.fail(cause);
                });
        }, resultHandler);
    }

    @Override
    public void userChangePassword(String userId, String oldPassword, String newPassword, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            // First verify the old password
            String selectSql = """
                SELECT password_hash
                FROM users
                WHERE user_id = $1 AND is_active = true
                """;

            pgPool.preparedQuery(selectSql)
                .execute(Tuple.of(UUID.fromString(userId)))
                .onSuccess(rows -> {
                    if (rows.size() == 0) {
                        blockingPromise.fail(new IllegalArgumentException("User not found or inactive"));
                        return;
                    }

                    Row row = rows.iterator().next();
                    String storedPasswordHash = row.getString("password_hash");

                    if (!PasswordUtil.verifyPassword(oldPassword, storedPasswordHash)) {
                        blockingPromise.complete(new JsonObject()
                            .put("success", false)
                            .put("message", "Current password is incorrect"));
                        return;
                    }

                    // Update with new password
                    String newPasswordHash = PasswordUtil.hashPassword(newPassword);
                    String updateSql = """
                        UPDATE users
                        SET password_hash = $1
                        WHERE user_id = $2
                        RETURNING user_id, username
                        """;

                    pgPool.preparedQuery(updateSql)
                        .execute(Tuple.of(newPasswordHash, UUID.fromString(userId)))
                        .onSuccess(updateRows -> {
                            Row updateRow = updateRows.iterator().next();
                            JsonObject result = new JsonObject()
                                .put("success", true)
                                .put("user_id", updateRow.getUUID("user_id").toString())
                                .put("username", updateRow.getString("username"))
                                .put("message", "Password changed successfully");
                            blockingPromise.complete(result);
                        })
                        .onFailure(cause -> {
                            logger.error("Failed to update password", cause);
                            blockingPromise.fail(cause);
                        });
                })
                .onFailure(cause -> {
                    logger.error("Failed to verify old password", cause);
                    blockingPromise.fail(cause);
                });
        }, resultHandler);
    }

    @Override
    public void userGetById(String userId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                SELECT user_id, username, is_active, created_at, updated_at, last_login_at
                FROM users
                WHERE user_id = $1
                """;

            pgPool.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(userId)))
                .onSuccess(rows -> {
                    if (rows.size() == 0) {
                        blockingPromise.complete(new JsonObject().put("found", false));
                        return;
                    }

                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("found", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("created_at", row.getLocalDateTime("created_at").toString())
                        .put("updated_at", row.getLocalDateTime("updated_at") != null ?
                            row.getLocalDateTime("updated_at").toString() : null)
                        .put("last_login_at", row.getLocalDateTime("last_login_at") != null ?
                            row.getLocalDateTime("last_login_at").toString() : null);
                    blockingPromise.complete(result);
                })
                .onFailure(cause -> {
                    logger.error("Failed to get user by ID", cause);
                    blockingPromise.fail(cause);
                });
        }, resultHandler);
    }

    @Override
    public void userSetActive(String userId, boolean isActive, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                UPDATE users
                SET is_active = $1
                WHERE user_id = $2
                RETURNING user_id, username, is_active
                """;

            pgPool.preparedQuery(sql)
                .execute(Tuple.of(isActive, UUID.fromString(userId)))
                .onSuccess(rows -> {
                    if (rows.size() == 0) {
                        blockingPromise.fail(new IllegalArgumentException("User not found"));
                        return;
                    }
                    Row row = rows.iterator().next();
                    JsonObject result = new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("message", "User status updated successfully");
                    blockingPromise.complete(result);
                })
                .onFailure(cause -> {
                    logger.error("Failed to update user status", cause);
                    blockingPromise.fail(cause);
                });
        }, resultHandler);
    }
}
