package com.nmslite.utils;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password Utility for NMSLite
 * Provides password hashing and encryption functionality
 */
public class PasswordUtil {
    
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String ENCRYPTION_ALGORITHM = "AES";
    private static final String ENCRYPTION_KEY = "NMSLite2024SecretKey123456789012"; // Exactly 32 bytes for AES-256
    
    /**
     * Hash a password using SHA-256 for user authentication
     * @param password Plain text password
     * @return Hashed password
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }
    
    /**
     * Verify a password against its hash
     * @param password Plain text password
     * @param hashedPassword Stored hashed password
     * @return true if password matches
     */
    public static boolean verifyPassword(String password, String hashedPassword) {
        String newHash = hashPassword(password);
        return newHash.equals(hashedPassword);
    }
    
    /**
     * Encrypt a password for storage in credential profiles
     * @param password Plain text password
     * @return Encrypted password
     */
    public static String encryptPassword(String password) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(
                ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8), 
                ENCRYPTION_ALGORITHM
            );
            
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            
            byte[] encryptedBytes = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }
    
    /**
     * Decrypt a password from credential profiles
     * @param encryptedPassword Encrypted password
     * @return Plain text password
     */
    public static String decryptPassword(String encryptedPassword) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(
                ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8), 
                ENCRYPTION_ALGORITHM
            );
            
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedPassword));
            return new String(decryptedBytes, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt password", e);
        }
    }
    
    /**
     * Generate a random salt for enhanced security
     * @return Base64 encoded salt
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
    
    /**
     * Hash password with salt for enhanced security
     * @param password Plain text password
     * @param salt Salt value
     * @return Salted hash
     */
    public static String hashPasswordWithSalt(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            String saltedPassword = password + salt;
            byte[] hash = digest.digest(saltedPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash password with salt", e);
        }
    }
    
    /**
     * Verify salted password
     * @param password Plain text password
     * @param salt Salt value
     * @param hashedPassword Stored hashed password
     * @return true if password matches
     */
    public static boolean verifySaltedPassword(String password, String salt, String hashedPassword) {
        String newHash = hashPasswordWithSalt(password, salt);
        return newHash.equals(hashedPassword);
    }
}
