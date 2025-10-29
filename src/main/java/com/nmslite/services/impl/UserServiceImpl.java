package com.nmslite.services.impl;

import com.nmslite.database.DatabaseHelper;

import com.nmslite.database.DatabaseInitializer;

import com.nmslite.services.UserService;

import com.nmslite.utils.PasswordUtil;

import io.vertx.core.Future;

import io.vertx.core.json.JsonArray;

import io.vertx.core.json.JsonObject;

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

 * Database Access:
 * - Uses DatabaseInitializer.getDatabaseHelper() for database operations
 * - DatabaseHelper provides generic query execution with consistent error handling
 * - No constructor parameters needed
 */
public class UserServiceImpl implements UserService
{

    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    private final DatabaseHelper dbHelper;

    /**
     * Constructor for UserServiceImpl.
     * Accesses database helper via DatabaseInitializer.
     */
    public UserServiceImpl()
    {
        this.dbHelper = DatabaseInitializer.getDatabaseHelper();
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
        try
        {
            var sql = """
                SELECT user_id, username, is_active
                FROM users
                """ + (includeInactive ? "" : "WHERE is_active = true ") + """
                ORDER BY username
                """;

            return dbHelper.executeQuery(sql)
                .map(rows ->
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

                    return users;
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in userList service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var username = userData.getString("username");

            var password = userData.getString("password");

            var isActive = userData.getBoolean("is_active", true);

            // Hash password for user authentication
            var passwordHash = PasswordUtil.hashPassword(password);

            if (passwordHash == null)
            {
                logger.error("Failed to hash password");

                return Future.failedFuture(new Exception("Failed to hash password"));
            }

            var sql = """
                INSERT INTO users (username, password_hash, is_active)
                VALUES ($1, $2, $3)
                RETURNING user_id, username, is_active
                """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(username, passwordHash, isActive))
                .map(rows ->
                {
                    var row = rows.iterator().next();

                    return new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("message", "User created successfully");
                })
                .recover(cause ->
                {
                    logger.error("Failed to create user: {}", cause.getMessage());

                    if (cause.getMessage().contains("duplicate key"))
                    {
                        return Future.failedFuture(new Exception("Username already exists"));
                    }
                    else
                    {
                        return Future.failedFuture(cause);
                    }
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in userCreate service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var username = userData.getString("username");

            var password = userData.getString("password");

            var isActive = userData.getBoolean("is_active");

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

                if (passwordHash == null)
                {
                    logger.error("Failed to hash password");

                    return Future.failedFuture(new Exception("Failed to hash password"));
                }

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

            return dbHelper.executePreparedQuery(sql, Tuple.from(params.getList()))
                .map(rows ->
                {
                    if (rows.size() == 0)
                    {
                        throw new RuntimeException("User not found");
                    }

                    var row = rows.iterator().next();

                    return new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"))
                        .put("message", "User updated successfully");
                })
                .recover(cause ->
                {
                    logger.error("Failed to update user: {}", cause.getMessage());

                    if (cause.getMessage().contains("duplicate key"))
                    {
                        return Future.failedFuture(new Exception("Username already exists"));
                    }
                    else
                    {
                        return Future.failedFuture(cause);
                    }
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in userUpdate service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                DELETE FROM users
                WHERE user_id = $1
                RETURNING user_id, username
                """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(userId)))
                .map(rows ->
                {
                    if (rows.size() == 0)
                    {
                        throw new RuntimeException("User not found");
                    }

                    var row = rows.iterator().next();

                    return new JsonObject()
                        .put("success", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("message", "User deleted successfully");
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in userDelete service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                SELECT user_id, username, password_hash, is_active
                FROM users
                WHERE username = $1 AND is_active = true
                """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(username))
                .map(rows ->
                {
                    if (rows.size() == 0)
                    {
                        return new JsonObject()
                            .put("authenticated", false)
                            .put("message", "Invalid username or password");
                    }

                    var row = rows.iterator().next();

                    var storedPasswordHash = row.getString("password_hash");

                    if (PasswordUtil.verifyPassword(password, storedPasswordHash))
                    {
                        return new JsonObject()
                            .put("authenticated", true)
                            .put("user_id", row.getUUID("user_id").toString())
                            .put("username", row.getString("username"))
                            .put("is_active", row.getBoolean("is_active"))
                            .put("message", "Authentication successful");
                    }
                    else
                    {
                        return new JsonObject()
                            .put("authenticated", false)
                            .put("message", "Invalid username or password");
                    }
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in userAuthenticate service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
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
        try
        {
            var sql = """
                SELECT user_id, username, is_active
                FROM users
                WHERE user_id = $1
                """;

            return dbHelper.executePreparedQuery(sql, Tuple.of(UUID.fromString(userId)))
                .map(rows ->
                {
                    if (rows.size() == 0)
                    {
                        return new JsonObject().put("found", false);
                    }

                    var row = rows.iterator().next();

                    return new JsonObject()
                        .put("found", true)
                        .put("user_id", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("is_active", row.getBoolean("is_active"));
                });
        }
        catch (Exception exception)
        {
            logger.error("Error in userGetById service: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

}
