package com.nmslite.services.impl;

import com.nmslite.services.UserService;

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
 * UserServiceImpl - Implementation of UserService

 * Provides user management operations including:
 * - User CRUD operations
 * - Password hashing and authentication
 * - User session management
 */
public class UserServiceImpl implements UserService
{

    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    private final Pool pgPool;

    /**
     * Constructor for UserServiceImpl
     *
     * @param pgPool PostgresSQL connection pool
     */
    public UserServiceImpl(Pool pgPool)
    {
        this.pgPool = pgPool;
    }

    /**
     * Get list of users
     *
     * @param includeInactive Include inactive users
     * @return Future containing JsonArray of users
     */
    @Override
    public Future<JsonArray> userList(boolean includeInactive)
    {
        var promise = Promise.<JsonArray>promise();

        var sql = """
            SELECT user_id, username, is_active
            FROM users
            """ + (includeInactive ? "" : "WHERE is_active = true ") + """
            ORDER BY username
            """;

        pgPool.query(sql)
            .execute()
            .onSuccess(rows ->
            {
                var users = new JsonArray();

                for (var row : rows)
                {
                    var user = new JsonObject()
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"));

                    users.add(user);
                }

                promise.complete(users);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to get users", cause);

                promise.fail(cause);
            });

        return promise.future();
    }

    /**
     * Create a new user
     *
     * @param userData User data
     * @return Future containing JsonObject with creation result
     */
    @Override
    public Future<JsonObject> userCreate(JsonObject userData)
    {
        var promise = Promise.<JsonObject>promise();

        var username = userData.getString("username");

        var password = userData.getString("password");

        var isActive = userData.getBoolean("is_active", true);

        // Hash password for user authentication
        var passwordHash = PasswordUtil.hashPassword(password);

        // ===== TRUST HANDLER VALIDATION =====
        // No validation here - handler has already validated all input

        var sql = """
            INSERT INTO users (username, password_hash, is_active)
            VALUES ($1, $2, $3)
            RETURNING user_id, username, is_active
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(username, passwordHash, isActive))
            .onSuccess(rows ->
            {
                var row = rows.iterator().next();

                var result = new JsonObject()
                    .put("success", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("is_active", row.getBoolean("is_active"))
                    .put("message", "User created successfully");

                promise.complete(result);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to create user", cause);

                if (cause.getMessage().contains("duplicate key"))
                {
                    promise.fail(new IllegalArgumentException("Username already exists"));
                }
                else
                {
                    promise.fail(cause);
                }
            });

        return promise.future();
    }

    /**
     * Update an existing user
     *
     * @param userId User ID
     * @param userData User data to update
     * @return Future containing JsonObject with update result
     */
    @Override
    public Future<JsonObject> userUpdate(String userId, JsonObject userData)
    {
        var promise = Promise.<JsonObject>promise();

        var username = userData.getString("username");

        var password = userData.getString("password");

        var isActive = userData.getBoolean("is_active");

        // ===== TRUST HANDLER VALIDATION =====
        // No validation here - handler has already validated all input

        var sqlBuilder = new StringBuilder("UPDATE users SET ");

        var params = new JsonArray();

        var paramIndex = 1;

        if (username != null)
        {
            sqlBuilder.append("username = $").append(paramIndex++).append(", ");

            params.add(username);
        }

        if (password != null)
        {
            var passwordHash = PasswordUtil.hashPassword(password);

            sqlBuilder.append("password_hash = $").append(paramIndex++).append(", ");

            params.add(passwordHash);
        }

        if (isActive != null)
        {
            sqlBuilder.append("is_active = $").append(paramIndex++).append(", ");

            params.add(isActive);
        }

        // Remove trailing comma and space, add WHERE clause
        var sqlStr = sqlBuilder.toString();

        if (sqlStr.endsWith(", "))
        {
            sqlStr = sqlStr.substring(0, sqlStr.length() - 2);
        }

        var sql = sqlStr + " WHERE user_id = $" + paramIndex + " RETURNING user_id, username, is_active";

        params.add(UUID.fromString(userId));

        pgPool.preparedQuery(sql)
            .execute(Tuple.from(params.getList()))
            .onSuccess(rows ->
            {
                if (rows.size() == 0)
                {
                    promise.fail(new IllegalArgumentException("User not found"));

                    return;
                }

                var row = rows.iterator().next();

                var result = new JsonObject()
                    .put("success", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("is_active", row.getBoolean("is_active"))
                    .put("message", "User updated successfully");

                promise.complete(result);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to update user", cause);

                if (cause.getMessage().contains("duplicate key"))
                {
                    promise.fail(new IllegalArgumentException("Username already exists"));
                }
                else
                {
                    promise.fail(cause);
                }
            });

        return promise.future();
    }

    /**
     * Delete a user
     *
     * @param userId User ID
     * @return Future containing JsonObject with deletion result
     */
    @Override
    public Future<JsonObject> userDelete(String userId)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
            DELETE FROM users
            WHERE user_id = $1
            RETURNING user_id, username
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(userId)))
            .onSuccess(rows ->
            {
                if (rows.size() == 0)
                {
                    promise.fail(new IllegalArgumentException("User not found"));

                    return;
                }

                var row = rows.iterator().next();

                var result = new JsonObject()
                    .put("success", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("message", "User deleted successfully");

                promise.complete(result);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to delete user", cause);

                promise.fail(cause);
            });

        return promise.future();
    }

    /**
     * Authenticate a user
     *
     * @param username Username
     * @param password Password
     * @return Future containing JsonObject with authentication result
     */
    @Override
    public Future<JsonObject> userAuthenticate(String username, String password)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
            SELECT user_id, username, password_hash, is_active
            FROM users
            WHERE username = $1 AND is_active = true
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(username))
            .onSuccess(rows ->
            {
                if (rows.size() == 0)
                {
                    promise.complete(new JsonObject()
                        .put("authenticated", false)
                        .put("message", "Invalid username or password"));

                    return;
                }

                var row = rows.iterator().next();

                var storedPasswordHash = row.getString("password_hash");

                if (PasswordUtil.verifyPassword(password, storedPasswordHash))
                {
                    var result = new JsonObject()
                        .put("authenticated", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("message", "Authentication successful");

                    promise.complete(result);
                }
                else
                {
                    promise.complete(new JsonObject()
                        .put("authenticated", false)
                        .put("message", "Invalid username or password"));
                }
            })
            .onFailure(cause ->
            {
                logger.error("Failed to authenticate user", cause);

                promise.fail(cause);
            });

        return promise.future();
    }

    /**
     * Get user by ID
     *
     * @param userId User ID
     * @return Future containing JsonObject with user data or not found
     */
    @Override
    public Future<JsonObject> userGetById(String userId)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
            SELECT user_id, username, is_active
            FROM users
            WHERE user_id = $1
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(UUID.fromString(userId)))
            .onSuccess(rows ->
            {
                if (rows.size() == 0)
                {
                    promise.complete(new JsonObject().put("found", false));

                    return;
                }

                var row = rows.iterator().next();

                var result = new JsonObject()
                    .put("found", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("is_active", row.getBoolean("is_active"));

                promise.complete(result);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to get user by ID", cause);

                promise.fail(cause);
            });

        return promise.future();
    }

    /**
     * Set user active status
     *
     * @param userId User ID
     * @param isActive Active status
     * @return Future containing JsonObject with update result
     */
    @Override
    public Future<JsonObject> userSetActive(String userId, boolean isActive)
    {
        var promise = Promise.<JsonObject>promise();

        var sql = """
            UPDATE users
            SET is_active = $1
            WHERE user_id = $2
            RETURNING user_id, username, is_active
            """;

        pgPool.preparedQuery(sql)
            .execute(Tuple.of(isActive, UUID.fromString(userId)))
            .onSuccess(rows ->
            {
                if (rows.size() == 0)
                {
                    promise.fail(new IllegalArgumentException("User not found"));

                    return;
                }

                var row = rows.iterator().next();

                var result = new JsonObject()
                    .put("success", true)
                    .put("user_id", row.getUUID("user_id").toString())
                    .put("username", row.getString("username"))
                    .put("is_active", row.getBoolean("is_active"))
                    .put("message", "User status updated successfully");

                promise.complete(result);
            })
            .onFailure(cause ->
            {
                logger.error("Failed to update user status", cause);

                promise.fail(cause);
            });

        return promise.future();
    }

}
