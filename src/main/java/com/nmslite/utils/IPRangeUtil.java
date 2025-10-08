package com.nmslite.utils;

import java.util.ArrayList;

import java.util.List;

import java.util.regex.Pattern;

/**
 * IPRangeUtil - Utility class for parsing and validating IP addresses and ranges

 * This utility supports:
 * - Single IP addresses: "192.168.1.100"
 * - IP ranges in same subnet: "192.168.1.1-50" (expands to 192.168.1.1 through 192.168.1.50)

 * Features:
 * - IP format validation
 * - Range parsing and expansion

 * - IPv4 support (IPv6 can be added later)
 */
public class IPRangeUtil
{

    // Regex patterns for validation
    private static final Pattern SINGLE_IP_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    private static final Pattern IP_RANGE_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)-(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    /**
     * Parse IP address or range into a list of individual IP addresses
     *
     * @param ipAddress The IP address or range string
     * @param isRange Flag indicating if this should be treated as a range
     * @return List of individual IP addresses
     * @throws IllegalArgumentException if the format is invalid
     */
    public static List<String> parseIPRange(String ipAddress, boolean isRange)
    {
        if (ipAddress == null || ipAddress.trim().isEmpty())
        {
            throw new IllegalArgumentException("IP address cannot be null or empty");
        }

        ipAddress = ipAddress.trim();

        List<String> ipList;

        if (!isRange)
        {
            // Single IP address
            if (!isValidSingleIP(ipAddress))
            {
                throw new IllegalArgumentException("Invalid IP address format: " + ipAddress);
            }

            ipList = new ArrayList<>();

            ipList.add(ipAddress);
        }
        else
        {
            // IP range
            if (!isValidIPRange(ipAddress))
            {
                throw new IllegalArgumentException("Invalid IP range format: " + ipAddress +
                    ". Expected format: '192.168.1.1-50'");
            }

            ipList = expandIPRange(ipAddress);
        }

        return ipList;
    }

    /**
     * Validate if a string is a valid single IP address
     *
     * @param ip The IP address string to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidSingleIP(String ip)
    {
        if (ip == null || ip.trim().isEmpty())
        {
            return false;
        }

        return SINGLE_IP_PATTERN.matcher(ip.trim()).matches();
    }

    /**
     * Validate if a string is a valid IP range format
     *
     * @param ipRange The IP range string to validate (e.g., "192.168.1.1-50")
     * @return true if valid, false otherwise
     */
    public static boolean isValidIPRange(String ipRange)
    {
        if (ipRange == null || ipRange.trim().isEmpty())
        {
            return false;
        }

        ipRange = ipRange.trim();

        // Check basic format
        if (!IP_RANGE_PATTERN.matcher(ipRange).matches())
        {
            return false;
        }

        try
        {
            // Parse and validate range
            var parts = ipRange.split("-");

            if (parts.length != 2)
            {
                return false;
            }

            var baseIP = parts[0];

            var endOctet = Integer.parseInt(parts[1]);

            // Validate base IP
            if (!isValidSingleIP(baseIP))
            {
                return false;
            }

            // Extract last octet from base IP
            var ipParts = baseIP.split("\\.");

            var startOctet = Integer.parseInt(ipParts[3]);

            // Validate range
            if (startOctet > endOctet)
            {
                return false; // Start must be <= end
            }

            if (endOctet > 255)
            {
                return false; // Invalid octet value
            }

            return true;
        }
        catch (NumberFormatException exception)
        {
            return false;
        }
    }

    /**
     * Expand an IP range into individual IP addresses
     *
     * @param ipRange The IP range string (e.g., "192.168.1.1-50")
     * @return List of individual IP addresses
     * @throws IllegalArgumentException if the range is invalid
     */
    private static List<String> expandIPRange(String ipRange)
    {
        var ipList = new ArrayList<String>();

        try
        {
            var parts = ipRange.split("-");

            var baseIP = parts[0];

            var endOctet = Integer.parseInt(parts[1]);

            // Extract IP base and start octet
            var ipParts = baseIP.split("\\.");

            var ipBase = ipParts[0] + "." + ipParts[1] + "." + ipParts[2] + ".";

            var startOctet = Integer.parseInt(ipParts[3]);

            // Generate IP addresses in range
            for (var i = startOctet; i <= endOctet; i++)
            {
                ipList.add(ipBase + i);
            }

        }
        catch (Exception exception)
        {
            throw new IllegalArgumentException("Failed to expand IP range: " + ipRange, exception);
        }

        return ipList;
    }

}
