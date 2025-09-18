-- =====================================================
-- NMSLite Database Schema v2.0 - API Compatible
-- Enterprise Network Monitoring System
-- =====================================================

-- =====================================================
-- 1. USERS TABLE - Admin users only
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
-- 2. DEVICE TYPES TABLE - supported device types
-- =====================================================
CREATE TABLE device_types (
    device_type_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_type_name VARCHAR(50) UNIQUE NOT NULL,
    default_port INTEGER,
    is_active BOOLEAN DEFAULT true,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP

    CONSTRAINT chk_port_range CHECK (default_port BETWEEN 1 AND 65535),
);

-- =====================================================
-- 3. CREDENTIAL PROFILES TABLE - Simple credentials
-- =====================================================
CREATE TABLE credential_profiles (
    credential_profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_name VARCHAR(100) UNIQUE NOT NULL,
    username VARCHAR(100) NOT NULL,
    password_encrypted VARCHAR(500) NOT NULL,
    protocol VARCHAR(10),
    is_active BOOLEAN DEFAULT true,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50)
);

-- =====================================================
-- 4. DISCOVERY PROFILES TABLE - Enhanced configuration
-- =====================================================
CREATE TABLE discovery_profiles (
    profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    discovery_name VARCHAR(100) UNIQUE NOT NULL,
    ip_address INET UNIQUE NOT NULL,
    device_type_id UUID NOT NULL REFERENCES device_types(device_type_id) ON DELETE RESTRICT,
    credential_profile_id UUID NOT NULL REFERENCES credential_profiles(credential_profile_id) ON DELETE RESTRICT,
    port INTEGER,
    timeout_seconds INTEGER,
    retry_count INTEGER,
    is_active BOOLEAN DEFAULT true,

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
    ip_address INET NOT NULL,
    device_type VARCHAR(50) NOT NULL,
    port INTEGER NOT NULL,
    username VARCHAR(100) NOT NULL,
    password_encrypted VARCHAR(500) NOT NULL,
    is_monitoring_enabled BOOLEAN DEFAULT true,
    discovery_profile_id UUID REFERENCES discovery_profiles(profile_id) ON DELETE SET NULL,

    -- Monitoring configuration
    polling_interval_seconds INTEGER
    alert_threshold_cpu DECIMAL(5,2),
    alert_threshold_memory DECIMAL(5,2),
    alert_threshold_disk DECIMAL(5,2),

    -- Soft delete fields
    is_deleted BOOLEAN DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(50),
    
    -- Timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_discovered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT chk_port_range CHECK (port BETWEEN 1 AND 65535),
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
-- 8. ALERTS TABLE - System alerts and notifications
-- =====================================================
CREATE TABLE alerts (
    alert_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    alert_type VARCHAR(20) NOT NULL CHECK (alert_type IN ('cpu', 'memory', 'disk', 'connectivity')),
    severity VARCHAR(10) NOT NULL CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    message TEXT NOT NULL,
    threshold_value DECIMAL(5,2),
    current_value DECIMAL(5,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- PERFORMANCE INDEXES
-- =====================================================

-- Devices table
CREATE INDEX idx_devices_ip_address ON devices(ip_address);
CREATE INDEX idx_devices_monitoring ON devices(is_monitoring_enabled) WHERE is_deleted = false;
CREATE INDEX idx_devices_deleted ON devices(is_deleted, deleted_at);
CREATE INDEX idx_devices_discovery_profile ON devices(discovery_profile_id);
CREATE INDEX idx_devices_device_type ON devices(device_type);
CREATE UNIQUE INDEX idx_devices_ip_active ON devices(ip_address) WHERE is_deleted = false;

-- Metrics table (time series heavy)
CREATE INDEX idx_metrics_device_timestamp ON metrics(device_id, timestamp DESC);
CREATE INDEX idx_metrics_device_success ON metrics(device_id, success, timestamp DESC);

-- Alerts table
CREATE INDEX idx_alerts_device ON alerts(device_id, created_at DESC);
CREATE INDEX idx_alerts_severity ON alerts(severity, created_at DESC);


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