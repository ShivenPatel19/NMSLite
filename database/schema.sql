-- =====================================================
-- 1. USERS TABLE - Admin users only, with soft delete
-- =====================================================
CREATE TABLE users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    is_active BOOLEAN DEFAULT true,
);

-- =================================================================
-- 2. DEVICE TYPES TABLE - supported device types, with soft delete
-- =================================================================
CREATE TABLE device_types (
    device_type_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_type_name VARCHAR(100) UNIQUE NOT NULL,
    default_port INTEGER,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_default_port_range CHECK (default_port BETWEEN 1 AND 65535)
);

-- =====================================================
-- 3. CREDENTIAL PROFILES TABLE
-- =====================================================
CREATE TABLE credential_profiles (
    credential_profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_name VARCHAR(100) UNIQUE NOT NULL,
    username VARCHAR(100) NOT NULL,
    password_encrypted VARCHAR(500) NOT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
);

-- =====================================================
-- 4. DISCOVERY PROFILES TABLE
-- =====================================================
-- Stores discovery configurations for network device discovery
-- Supports both single IP addresses and IP ranges for bulk device provisioning
-- Examples:
--   Single IP: ip_address='192.168.1.100', is_range=false
--   IP Range:   ip_address='192.168.1.1-50', is_range=true
--
-- MULTIPLE CREDENTIALS SUPPORT:
-- credential_profile_ids stores array of UUIDs for brute-force credential testing
-- During discovery, each credential is tried until one succeeds for each IP
CREATE TABLE discovery_profiles (
    profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    discovery_name VARCHAR(100) UNIQUE NOT NULL,
    ip_address TEXT NOT NULL,
    is_range BOOLEAN NOT NULL DEFAULT false,                                                      -- Flag: false=single IP, true=IP range
    device_type_id UUID NOT NULL REFERENCES device_types(device_type_id) ON DELETE RESTRICT,
    credential_profile_ids UUID[] NOT NULL,                                                       -- Array of credential profile UUIDs for brute-force testing
    port INTEGER,
    protocol VARCHAR(50),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
);

-- ========================================================
-- 5. DEVICES TABLE - Provisioned devices with soft delete
-- ========================================================
-- Stores individual network devices (always single IP addresses)
-- Devices can be provisioned from discovery profiles (including IP ranges)
-- Each device in a range gets its own row with individual IP address
CREATE TABLE devices (
    device_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_name VARCHAR(100) NOT NULL,
    ip_address INET NOT NULL UNIQUE,                                                                               -- Always single IP address (e.g., '192.168.1.100')
    device_type VARCHAR(100) NOT NULL,
    port INTEGER NOT NULL,
    protocol VARCHAR(50),
    credential_profile_id UUID NOT NULL REFERENCES credential_profiles(credential_profile_id) ON DELETE RESTRICT,  -- Reference to the successful credential
    is_monitoring_enabled BOOLEAN DEFAULT true,

    -- Monitoring configuration
    polling_interval_seconds INTEGER,
    timeout_seconds INTEGER,
    alert_threshold_cpu DECIMAL(5,2),
    alert_threshold_memory DECIMAL(5,2),
    alert_threshold_disk DECIMAL(5,2),

    host_name VARCHAR(100),
    is_provisioned BOOLEAN DEFAULT false,

    -- Soft delete fields
    is_deleted BOOLEAN DEFAULT false,
    deleted_at TIMESTAMP,

    -- Timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    monitoring_enabled_at TIMESTAMP,

    -- Constraints
    CONSTRAINT chk_port_range CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT chk_timeout_range CHECK (timeout_seconds BETWEEN 0 AND 600),              -- 0 seconds to 10 minutes
    CONSTRAINT chk_cpu_threshold CHECK (alert_threshold_cpu BETWEEN 0 AND 100),
    CONSTRAINT chk_memory_threshold CHECK (alert_threshold_memory BETWEEN 0 AND 100),
    CONSTRAINT chk_disk_threshold CHECK (alert_threshold_disk BETWEEN 0 AND 100)
);

-- =====================================================
-- 6. METRICS TABLE - Enhanced performance metrics
-- =====================================================
CREATE TABLE metrics (
    metric_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- System metrics (only successful metrics stored)
    cpu_usage_percent DECIMAL(5,2) NOT NULL,
    memory_usage_percent DECIMAL(5,2) NOT NULL,
    memory_total_bytes BIGINT NOT NULL,
    memory_used_bytes BIGINT NOT NULL,
    memory_free_bytes BIGINT NOT NULL,
    disk_usage_percent DECIMAL(5,2) NOT NULL,
    disk_total_bytes BIGINT NOT NULL,
    disk_used_bytes BIGINT NOT NULL,
    disk_free_bytes BIGINT NOT NULL,

    -- Constraints
    CONSTRAINT chk_cpu_range CHECK (cpu_usage_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_memory_range CHECK (memory_usage_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_disk_range CHECK (disk_usage_percent BETWEEN 0 AND 100)
);

-- ========================================================
-- 7. DEVICE AVAILABILITY TABLE - Enhanced uptime tracking
-- ========================================================
CREATE TABLE device_availability (
    device_id UUID PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    total_checks INTEGER DEFAULT 0,
    successful_checks INTEGER DEFAULT 0,
    failed_checks INTEGER DEFAULT 0,
    availability_percent DECIMAL(5,2) DEFAULT 0.00,
    last_check_time TIMESTAMP,
    last_success_time TIMESTAMP,
    last_failure_time TIMESTAMP,
    current_status VARCHAR(10) DEFAULT 'unknown' CHECK (current_status IN ('up', 'down', 'unknown')),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT chk_availability_range CHECK (availability_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_checks_consistency CHECK (total_checks = successful_checks + failed_checks)
);

-- =====================================================
-- TRIGGERS FOR AUTOMATIC UPDATES
-- =====================================================

-- Update timestamp trigger function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply triggers to tables with updated_at columns
CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_credential_profiles_updated_at
    BEFORE UPDATE ON credential_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_discovery_profiles_updated_at
    BEFORE UPDATE ON discovery_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_device_availability_updated_at
    BEFORE UPDATE ON device_availability
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();


-- ==========
-- DEFAULTS
-- ==========

INSERT INTO device_types (device_type_name, default_port, is_active) VALUES ('server linux', 22, true)
INSERT INTO device_types (device_type_name, default_port, is_active) VALUES ('server windows', 5985, true)
INSERT INTO device_types (device_type_name, default_port, is_active) VALUES ('router', 161, false)

-- Insert motadataadmin user (password: motadataadmin)
INSERT INTO users (username, password_hash, is_active) VALUES ('motadataadmin', '4/9zHIYgUFS9f7gpQewwsTGu9oiHxHMYdLvmyYahtPk=', true)

-- Insert nmsliteadmin user  (password: nsmliteadmin)
INSERT INTO users (username, password_hash, is_active) VALUES ('nmsliteadmin', 'rmRi57AHCCA6q/cIb5uYQfBq9RvRx3Jz7QlhSYhco/8=', false)