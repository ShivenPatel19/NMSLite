-- =====================================================
-- INSERT DEFAULT USERS FOR NMSLITE
-- =====================================================

-- Insert motadataadmin user (password: motadataadmin)
INSERT INTO users (username, password_hash, is_active) 
VALUES ('motadataadmin', '4/9zHIYgUFS9f7gpQewwsTGu9oiHxHMYdLvmyYahtPk=', true)
ON CONFLICT (username) DO UPDATE SET 
    password_hash = EXCLUDED.password_hash,
    is_active = EXCLUDED.is_active;

-- Insert nmsliteadmin user  (password: nsmliteadmin)
INSERT INTO users (username, password_hash, is_active)
VALUES ('nmsliteadmin', 'rmRi57AHCCA6q/cIb5uYQfBq9RvRx3Jz7QlhSYhco/8=', false)
ON CONFLICT (username) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    is_active = EXCLUDED.is_active;

-- =====================================================
-- INSERT DEFAULT DEVICE TYPES
-- =====================================================

-- Insert "server linux" device type (SSH port 22)
INSERT INTO device_types (device_type_name, default_port, is_active)
VALUES ('server linux', 22, true)
ON CONFLICT (device_type_name) DO UPDATE SET
    default_port = EXCLUDED.default_port,
    is_active = EXCLUDED.is_active;

-- Insert "server windows" device type (WinRM port 5985)
INSERT INTO device_types (device_type_name, default_port, is_active)
VALUES ('server windows', 5985, true)
ON CONFLICT (device_type_name) DO UPDATE SET
    default_port = EXCLUDED.default_port,
    is_active = EXCLUDED.is_active;

-- Insert "router" device type (SNMP port 161) - INACTIVE by default
INSERT INTO device_types (device_type_name, default_port, is_active)
VALUES ('router', 161, false)
ON CONFLICT (device_type_name) DO UPDATE SET
    default_port = EXCLUDED.default_port,
    is_active = EXCLUDED.is_active;