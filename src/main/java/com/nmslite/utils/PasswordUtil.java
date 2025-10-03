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
    private static final String ENCRYPTION_KEY = "NMSLite2025SecretKey123456789012"; // Exactly 32 bytes for AES-256
    
    /**
     * Hash a password using SHA-256 for user authentication (ONE-WAY hashing)
     * @param password Plain text password
     * @return Hashed password (irreversible)
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
     * Verify a password against its hash (ONE-WAY verification)
     * @param password Plain text password
     * @param hashedPassword Stored hashed password
     * @return true if password matches
     */
    public static boolean verifyPassword(String password, String hashedPassword) {
        String newHash = hashPassword(password);
        return newHash.equals(hashedPassword);
    }
    
    /**
     * Encrypt a password for storage in credential profiles (TWO-WAY encryption)
     * @param password Plain text password
     * @return Encrypted password (reversible)
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
     * Decrypt a password from credential profiles (TWO-WAY decryption)
     * @param encryptedPassword Encrypted password
     * @return Plain text password (original password)
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
}
