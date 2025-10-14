package com.nmslite.utils;

import javax.crypto.Cipher;

import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;

import java.security.MessageDigest;

import java.util.Base64;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * Password Utility for NMSLite
 * Provides password hashing and encryption functionality

 * Uses:
 * - SHA-256 for one-way password hashing (user authentication)
 * - AES symmetric encryption for two-way password encryption (credential storage)
 */
public class PasswordUtil
{

    private static final Logger logger = LoggerFactory.getLogger(PasswordUtil.class);

    private static final String HASH_ALGORITHM = "SHA-256";

    private static final String ENCRYPTION_ALGORITHM = "AES";

    // Shared secret key for AES symmetric encryption (must be kept confidential)
    private static final String SHARED_SECRET_KEY = "NMSLite2025SecretKey123456789012"; // Exactly 32 bytes for AES-256

    /**
     * Hash a password using SHA-256 for user authentication (ONE-WAY hashing)
     *
     * @param password Plain text password
     * @return Hashed password (irreversible)
     */
    public static String hashPassword(String password)
    {
        try
        {
            var digest = MessageDigest.getInstance(HASH_ALGORITHM);

            var hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(hash);
        }
        catch (Exception exception)
        {
            logger.error("Failed to hash password: {}", exception.getMessage());

            return null;
        }
    }

    /**
     * Verify a password against its hash (ONE-WAY verification)
     *
     * @param password Plain text password
     * @param hashedPassword Stored hashed password
     * @return true if password matches
     */
    public static boolean verifyPassword(String password, String hashedPassword)
    {
        var newHash = hashPassword(password);

        return newHash.equals(hashedPassword);
    }

    /**
     * Encrypt a password for storage in credential profiles (TWO-WAY symmetric encryption)
     * Uses AES with shared secret key - same key is used for encryption and decryption
     *
     * @param password Plain text password
     * @return Encrypted password (reversible)
     */
    public static String encryptPassword(String password)
    {
        try
        {
            var secretKey = new SecretKeySpec(
                SHARED_SECRET_KEY.getBytes(StandardCharsets.UTF_8),
                ENCRYPTION_ALGORITHM
            );

            var cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);

            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            var encryptedBytes = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(encryptedBytes);

        }
        catch (Exception exception)
        {
            logger.error("Failed to encrypt password: {}", exception.getMessage());

            return null;
        }
    }

    /**
     * Decrypt a password from credential profiles (TWO-WAY symmetric decryption)
     * Uses AES with shared secret key - same key is used for encryption and decryption
     *
     * @param encryptedPassword Encrypted password
     * @return Plain text password (original password)
     */
    public static String decryptPassword(String encryptedPassword)
    {
        try
        {
            var secretKey = new SecretKeySpec(
                SHARED_SECRET_KEY.getBytes(StandardCharsets.UTF_8),
                ENCRYPTION_ALGORITHM
            );

            var cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);

            cipher.init(Cipher.DECRYPT_MODE, secretKey);

            var decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedPassword));

            return new String(decryptedBytes, StandardCharsets.UTF_8);

        }
        catch (Exception exception)
        {
            logger.error("Failed to decrypt password: {}", exception.getMessage());

            return null;
        }
    }

}
