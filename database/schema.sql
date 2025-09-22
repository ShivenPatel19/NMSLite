-- =====================================================
-- 1. USERS TABLE - Admin users only, with soft delete
-- =====================================================
CREATE TABLE users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT true,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);

-- =====================================================
-- 2. DEVICE TYPES TABLE - supported device types, with soft delete
-- =====================================================
CREATE TABLE device_types (
    device_type_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_type_name VARCHAR(50) UNIQUE NOT NULL,
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
    created_by VARCHAR(50)
);

-- =====================================================
-- 4. DISCOVERY PROFILES TABLE
-- =====================================================
CREATE TABLE discovery_profiles (
    profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    discovery_name VARCHAR(100) UNIQUE NOT NULL,
    ip_address INET UNIQUE NOT NULL,
    device_type_id UUID NOT NULL REFERENCES device_types(device_type_id) ON DELETE RESTRICT,
    credential_profile_id UUID NOT NULL REFERENCES credential_profiles(credential_profile_id) ON DELETE RESTRICT,
    port INTEGER,
    protocol VARCHAR(10),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50)
);

-- =====================================================
-- 5. DEVICES TABLE - Provisioned devices with soft delete
-- =====================================================
CREATE TABLE devices (
    device_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_name VARCHAR(100) NOT NULL,
    ip_address INET NOT NULL,                                           -- for following deviceSync() is required.
    device_type VARCHAR(50) NOT NULL,                                   -- need to sync if device type changes
    port INTEGER NOT NULL,                                              -- need to sync if device type/discovery profile changes
    protocol VARCHAR(10),                                               -- need to sync if discovery profile changes
    username VARCHAR(100) NOT NULL,                                     -- need to sync if credentials profile changes
    password_encrypted VARCHAR(500) NOT NULL,                           -- need to sync if credentials profile changes
    is_monitoring_enabled BOOLEAN DEFAULT true,
    discovery_profile_id UUID REFERENCES discovery_profiles(profile_id) ON DELETE SET NULL,

    -- Monitoring configuration
    polling_interval_seconds INTEGER,
    timeout_seconds INTEGER DEFAULT 300,   -- 5 minutes default timeout
    retry_count INTEGER DEFAULT 2,         -- Default retry count of 2
    alert_threshold_cpu DECIMAL(5,2) DEFAULT 80.0,
    alert_threshold_memory DECIMAL(5,2) DEFAULT 80.0,
    alert_threshold_disk DECIMAL(5,2) DEFAULT 80.0,

    -- Soft delete fields
    is_deleted BOOLEAN DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(50),
    
    -- Timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_polled_at TIMESTAMP,

    -- Constraints
    CONSTRAINT chk_port_range CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT chk_timeout_range CHECK (timeout_seconds BETWEEN 0 AND 600),   -- 0 seconds to 10 minutes
    CONSTRAINT chk_retry_range CHECK (retry_count BETWEEN 0 AND 5),           -- 0 to 5 retries
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
    success BOOLEAN NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    duration_ms INTEGER,
    
    -- System metrics
    cpu_usage_percent DECIMAL(5,2),
    memory_usage_percent DECIMAL(5,2),
    memory_total_bytes BIGINT,
    memory_used_bytes BIGINT,
    memory_free_bytes BIGINT,
    disk_usage_percent DECIMAL(5,2),
    disk_total_bytes BIGINT,
    disk_used_bytes BIGINT,
    disk_free_bytes BIGINT,

    error_message TEXT,
    
    -- Constraints
    CONSTRAINT chk_duration_positive CHECK (duration_ms >= 0),
    CONSTRAINT chk_cpu_range CHECK (cpu_usage_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_memory_range CHECK (memory_usage_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_disk_range CHECK (disk_usage_percent BETWEEN 0 AND 100)
);

-- =====================================================
-- 7. DEVICE AVAILABILITY TABLE - Enhanced uptime tracking
-- =====================================================
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
    status_since TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
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