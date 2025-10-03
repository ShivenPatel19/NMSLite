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
                SELECT user_id, username, is_active
                FROM users
                """ + (includeInactive ? "" : "WHERE is_active = true ") + """
                ORDER BY username
                """;

            pgPool.query(sql)
                .execute()
                .onSuccess(rows -> {
                    JsonArray users = new JsonArray();
                    for (Row row : rows) {
                        JsonObject user = new JsonObject()
                            .put("user_id", row.getUUID("user_id").toString())
                            .put("username", row.getString("username"))
                            .put("is_active", row.getBoolean("is_active"));
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

    @Override
    public void userCreate(JsonObject userData, Handler<AsyncResult<JsonObject>> resultHandler) {
        vertx.executeBlocking(blockingPromise -> {
            String username = userData.getString("username");
            String password = userData.getString("password");
            Boolean isActive = userData.getBoolean("is_active", true);

            // Hash password for user authentication
            String passwordHash = PasswordUtil.hashPassword(password);

            // ===== TRUST HANDLER VALIDATION =====
            // No validation here - handler has already validated all input

            String sql = """
                INSERT INTO users (username, password_hash, is_active)
                VALUES ($1, $2, $3)
                RETURNING user_id, username, is_active
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

            // ===== TRUST HANDLER VALIDATION =====
            // No validation here - handler has already validated all input

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

            // Remove trailing comma and space, add WHERE clause
            String sqlStr = sqlBuilder.toString();
            if (sqlStr.endsWith(", ")) {
                sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
            }
            String sql = sqlStr + " WHERE user_id = $" + paramIndex + " RETURNING user_id, username, is_active";
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
    public void userGetById(String userId, Handler<AsyncResult<JsonObject>> resultHandler) {

        vertx.executeBlocking(blockingPromise -> {
            String sql = """
                SELECT user_id, username, is_active
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
                        .put("is_active", row.getBoolean("is_active"));
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
