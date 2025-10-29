# NMSLite - Network Monitoring System

**Version:** 3.0-SNAPSHOT
**Framework:** Vert.x 5.0.4
**Java Version:** 21
**Database:** PostgreSQL 12+
**Security:** HTTPS-only with JWT Authentication

A lightweight, event-driven network monitoring system built with Vert.x and PostgreSQL, featuring HTTPS-only communication, JWT authentication, ProxyGen service architecture, GoEngine integration for SSH/WinRM metrics collection, automated device discovery, and dual-cycle monitoring (availability + metrics).

---

## 📋 Table of Contents

- [Architecture](#️-architecture)
- [Code Approach & Design Patterns](#-code-approach--design-patterns)
- [Quick Start](#-quick-start)
- [Authentication](#-authentication)
- [API Endpoints](#-api-endpoints)
- [Configuration](#-configuration)
- [Workflow & Code Approach](#-workflow--code-approach)
- [Database Schema](#️-database-schema)
- [PostgreSQL INET Type Handling](#-postgresql-inet-type-handling)
- [GoEngine Integration](#-goengine-integration)
- [Key Features & Implementation](#-key-features--implementation-highlights)
- [Production Deployment](#-production-deployment)
- [Development](#-development)
- [Technology Stack](#-technology-stack)
- [Code Quality Standards](#-code-quality-standards)

---

## 🏗️ Architecture

NMSLite uses a **4-verticle event-driven architecture** with ProxyGen-based service communication:

### Core Verticles

- **ServerVerticle**: HTTPS API server with JWT authentication middleware
- **AvailabilityVerticle**: Device availability monitoring (10-second cycle with fping + port checks)
- **PollingMetricsVerticle**: Device metrics collection (60-second cycle with GoEngine SSH/WinRM)
- **DiscoveryVerticle**: Device discovery workflow (fping + port check + GoEngine + sequential batch processing)

### Database Initialization

- **DatabaseInitializer**: One-time database setup at application startup (no verticle needed)
  - Creates PostgreSQL connection pool
  - Registers all 7 ProxyGen services on event bus
  - Runs before any verticles are deployed

### Service Layer (ProxyGen)

All database operations are abstracted through Vert.x ProxyGen services for type-safe event bus communication:

- **UserService**: User management and authentication
- **DeviceTypeService**: Device type management (read-only for security)
- **CredentialProfileService**: Credential profile management with encryption
- **DiscoveryProfileService**: Discovery profile management with IP range support
- **DeviceService**: Device CRUD and monitoring configuration
- **MetricsService**: Time-series metrics data collection
- **AvailabilityService**: Device availability tracking and statistics

### Communication Pattern

```
HTTPS Request → ServerVerticle → Service Proxy (Event Bus) → Service Implementation → PostgreSQL
                                       ↓
                           (Registered at startup by DatabaseInitializer)
```

All services use `createProxy()` pattern for seamless event bus communication without manual message handling.

### Application Startup Flow

```
1. Bootstrap.main()
   ↓
2. Load application.conf (HOCON format)
   ↓
3. Configure logging (Logback)
   ↓
4. Configure Vert.x options (blocked thread checker, worker pool)
   ↓
5. DatabaseInitializer.initialize()
   - Create PostgreSQL connection pool
   - Instantiate all 7 service implementations
   - Register ProxyGen services on event bus
   ↓
6. Deploy verticles sequentially via VerticleDeployer:
   - ServerVerticle (HTTPS API on port 8443)
   - AvailabilityVerticle (10s cycle, creates shared availability cache)
   - PollingMetricsVerticle (60s cycle, reads from availability cache)
   - DiscoveryVerticle (on-demand device discovery)
   ↓
7. Application ready (HTTPS server on port 8443)
```

### Verticle Dependencies

**Critical Deployment Order:**
- **AvailabilityVerticle** MUST deploy BEFORE **PollingMetricsVerticle**
- AvailabilityVerticle creates and populates the shared `availability-cache` LocalMap
- PollingMetricsVerticle reads from this cache to check device status before metrics collection
- If order is reversed, PollingMetricsVerticle will create an empty cache

## 💡 Code Approach & Design Patterns

### ProxyGen Service Architecture

NMSLite uses **Vert.x ProxyGen** for all database operations, eliminating manual event bus message handling:

**Traditional Event Bus Approach (NOT used):**
```java
// ❌ Manual message handling - verbose and error-prone
vertx.eventBus().request("db.users.get", userId, reply -> {
    if (reply.succeeded()) {
        JsonObject result = (JsonObject) reply.result().body();
        // Handle result
    } else {
        // Handle error
    }
});
```

**ProxyGen Approach (Used in NMSLite):**
```java
// ✅ Type-safe service proxy - clean and maintainable
UserService userService = UserService.createProxy();
userService.userGetById(userId)
    .onSuccess(user -> {
        ResponseUtil.handleSuccess(ctx, user);
    })
    .onFailure(error -> {
        ExceptionUtil.handleHttp(ctx, error, "Failed to get user");
    });
```

**Benefits:**
- **Type Safety**: Compile-time checking of method signatures
- **Clean Code**: No manual JSON serialization/deserialization
- **Automatic Routing**: Service addresses managed by ProxyGen
- **Future-Based**: Native Vert.x Future/Promise patterns
- **Error Handling**: Automatic exception propagation

### Async/Non-Blocking Patterns

All operations use Vert.x Future composition for non-blocking execution:

```java
// Example: Discovery workflow with Future composition
getDiscoveryProfile(profileId)
    .compose(this::parseIPTargets)
    .compose(this::filterExistingDevices)
    .compose(this::executeBatchDiscovery)
    .compose(this::processResults)
    .onSuccess(result -> {
        // Success handling
    })
    .onFailure(error -> {
        // Error handling
    });
```

### Timeout Management

NMSLite implements **hierarchical timeout strategy** for reliability:

**Discovery Timeouts (3 Levels):**
1. **Event Bus**: 300s (5 min) - HTTP request to event bus communication timeout (`discovery.eventbus.timeout.seconds`)
2. **Vert.x Batch**: 300s (5 min) - Overall batch operation timeout (`discovery.blocking.timeout.goengine`)
3. **GoEngine Device**: 60s - Per-device discovery timeout (`discovery.goengine.timeout.seconds`)
4. **GoEngine Credential**: 20s - Per-credential attempt timeout (`discovery.goengine.connection.timeout.seconds`)

**Availability Timeouts (2 Levels):**
1. **Vert.x Worker Pool**: 600s (10 min) - Worker pool timeout (`availability.worker.pool.timeout.seconds`)
2. **fping**: 5s per-IP (`tools.fping.timeout.seconds`), 180s batch timeout (`tools.fping.batch.blocking.timeout.seconds`)
3. **Port Check**: 5s per-socket (`tools.port.check.timeout.seconds`), 10s batch timeout (`tools.port.check.batch.blocking.timeout.seconds`)

**Polling/Metrics Timeouts (3 Levels):**
1. **Vert.x Worker Pool**: 600s (10 min) - Worker pool timeout (`polling.worker.pool.timeout.seconds`)
2. **Vert.x Batch**: 300s (5 min) - Overall batch operation timeout (`polling.blocking.timeout.goengine`)
3. **GoEngine Device**: 60s - Per-device metrics collection timeout (`device.defaults.timeout.seconds`)
4. **GoEngine Connection**: 20s - SSH/WinRM connection timeout (`polling.connection.timeout.seconds`)

### Batch Processing

Sequential batch processing prevents memory overflow and network congestion:

```java
// DiscoveryBatchProcessor - processes IPs in configurable batches
class DiscoveryBatchProcessor {
    private final int batchSize = 100; // Configurable

    void processNext(Promise<JsonArray> promise) {
        if (hasMoreBatches()) {
            processBatch(currentBatch)
                .onSuccess(result -> {
                    results.addAll(result);
                    processNext(promise); // Recursive sequential processing
                })
                .onFailure(promise::fail);
        } else {
            promise.complete(results);
        }
    }
}
```

### In-Memory Caching

NMSLite uses **two separate in-memory caches** for optimal performance:

#### 1. Availability Cache (SharedData LocalMap)
**Owner:** AvailabilityVerticle
**Shared With:** PollingMetricsVerticle (read-only)
**Storage:** `vertx.sharedData().getLocalMap("availability-cache")`

```java
// AvailabilityVerticle creates and updates the cache
LocalMap<String, JsonObject> availabilityCache = vertx.sharedData().getLocalMap("availability-cache");

// Stores device availability status (up/down)
availabilityCache.put(deviceId, new JsonObject()
    .put("device_id", deviceId)
    .put("address", ipAddress)
    .put("port", port)
    .put("status", "up"));  // or "down"

// PollingMetricsVerticle reads from cache before metrics collection
JsonObject deviceStatus = availabilityCache.get(deviceId);
if ("up".equals(deviceStatus.getString("status"))) {
    // Collect metrics
}
```

**Cache Lifecycle:**
- Created and populated by AvailabilityVerticle on startup
- Updated every 10 seconds by availability cycle
- Shared across verticles via Vert.x SharedData
- Thread-safe with immutable JsonObject pattern

#### 2. Polling Device Cache (ConcurrentHashMap)
**Owner:** PollingMetricsVerticle
**Shared With:** None (private to PollingMetricsVerticle)
**Storage:** `ConcurrentHashMap<String, PollingDevice>`

```java
// Device cache loaded at startup and updated on changes via event bus
private final Map<String, PollingDevice> deviceCache = new ConcurrentHashMap<>();

// Fast lookup without database queries during polling cycles
PollingDevice device = deviceCache.get(deviceId);

// Cache updated reactively when devices are provisioned/updated/disabled
vertx.eventBus().consumer("device.cache.update", message -> {
    // Reload device into cache
});
```

**Cache Lifecycle:**
- Loaded on startup from database (provisioned devices only)
- Updated via event bus when device configuration changes
- Cleared on verticle shutdown
- Runtime state (nextScheduledAt, consecutiveFailures) lost on restart (recomputed on startup)

## 🚀 Quick Start

### Prerequisites

1. **Java 21+** - Required for running the application
2. **PostgreSQL 12+** - Database for storing devices, metrics, and configuration
3. **fping** - Command-line tool for ICMP ping checks (install via package manager)
4. **GoEngine** - External Go binary for SSH/WinRM communication (included in `goengine/` directory)
5. **Maven 3.x** - Build tool (for building from source)
6. **SSL Certificate** - HTTPS keystore file (keystore.jks) for secure communication

### Database Setup

1. **Install PostgreSQL 12+** (if not already installed)

2. **Create database and user:**
```sql
CREATE DATABASE nmslite;
CREATE USER nmslite WITH PASSWORD 'nmslite';
GRANT ALL PRIVILEGES ON DATABASE nmslite TO nmslite;
```

3. **Run the schema:**
```bash
psql -h localhost -U nmslite -d nmslite -f database/schema.sql
```

The schema creates 8 tables:
- `users` - Admin users with JWT authentication
- `device_types` - Device templates (Linux Server, Windows Server, etc.)
- `credential_profiles` - Reusable credentials (encrypted) with port and protocol
- `discovery_profiles` - Discovery configurations with IP ranges and credential arrays
- `devices` - Discovered and provisioned devices
- `metrics` - Time-series metrics data (CPU, memory, disk)
- `device_availability` - Availability tracking and statistics
- `metrics_backup` - Automatic backup of deleted metrics (via trigger)

### Build & Run

1. **Build the application:**
```bash
mvn clean package
```

2. **Run the application:**
```bash
java -jar target/NMSLite-3.0-SNAPSHOT-fat.jar
```

3. **Configuration:**
All configuration is loaded from `src/main/resources/application.conf`.
To customize settings, edit the configuration file before building:

```hocon
# Logging Configuration
logging {
  enabled = true
  level = "INFO"                           # TRACE, DEBUG, INFO, WARN, ERROR
  file.path = "logs/nmslite.log"
  file.enabled = true
  console.enabled = true
}

# Database Configuration
database {
  host = "localhost"
  port = 5432
  database = "nmslite"
  user = "nmslite"
  password = "nmslite"
  maxSize = 5
}

# HTTPS Server Configuration (Production - HTTPS Only)
server {
  https {
    port = 8443
    keystore {
      path = "keystore.jks"                  # Path to keystore file
      password = "motadataadmin"             # Keystore password
    }
  }
}

# Vert.x Configuration
vertx {
  blocked.thread.check {
    interval = 5                           # Check interval (5 seconds)
    max.eventloop.execute.time = 2         # Event loop warning threshold (2 seconds)
    max.worker.execute.time = 600          # Worker thread warning threshold (10 minutes)
    warning.exception.time = 30            # Full stack trace threshold (30 seconds)
  }
  worker.pool.size = 10                    # Worker pool size
}

# Shared Tools Configuration
tools {
  goengine.path = "./goengine/goengine"

  fping {
    path = "fping"
    timeout.seconds = 5                    # Per-IP timeout
    batch.blocking.timeout.seconds = 180   # Batch operation timeout
  }

  port.check {
    timeout.seconds = 5                    # Per-socket timeout
    batch.blocking.timeout.seconds = 10    # Batch operation timeout
  }
}

# GoEngine Global Configuration
goengine {
  working.directory = "./goengine"
  config.file = "config/goengine.yaml"
}

# Discovery Configuration
discovery {
  batch.size = 100                         # Max IPs per GoEngine discovery request
  blocking.timeout.goengine = 300          # Vert.x blocking timeout (5 minutes)
  eventbus.timeout.seconds = 300           # Event bus timeout for test discovery (5 minutes)

  goengine {
    timeout.seconds = 60                   # Per-device timeout
    connection.timeout.seconds = 20        # Per-credential timeout
  }
}

# Availability Configuration
availability {
  cycle.interval.seconds = 10              # How often to check device availability (10 seconds)
  batch.size = 50                          # Max devices per batch
  worker.pool.size = 10                    # Worker pool size
  worker.pool.timeout.seconds = 600        # Worker pool timeout (10 minutes)
}

# Polling Configuration
polling {
  cycle.interval.seconds = 60              # How often scheduler checks for due devices
  batch.size = 50                          # Max devices per batch
  max.cycles.skipped = 5                   # Auto-disable after N failures
  worker.pool.size = 10                    # Worker pool size
  worker.pool.timeout.seconds = 600        # Worker pool timeout (10 minutes)

  blocking.timeout.goengine = 300          # Vert.x blocking timeout (5 minutes)
  connection.timeout.seconds = 20          # SSH/WinRM connection timeout
}

# Device Default Configuration
device {
  defaults {
    polling.interval.seconds = 65          # Default polling interval (65 seconds for testing)
    timeout.seconds = 60                   # Default per-device timeout
  }
}
```

## 🔐 Authentication & Security

NMSLite uses **HTTPS-only communication** with **JWT (JSON Web Token)** authentication for all API endpoints (except `/api/auth/login`).

### Security Features

1. **HTTPS Only**: All communication encrypted via TLS/SSL (port 8443)
2. **JWT Authentication**: Token-based authentication for all protected endpoints
3. **Password Encryption**: Secure password hashing for user credentials
4. **Credential Encryption**: Encrypted storage of device credentials in database
5. **Keystore Security**: SSL certificates managed via Java KeyStore (keystore.jks)

### Authentication Flow

1. **Login**: POST `https://localhost:8443/api/auth/login` with username/password
2. **Receive JWT**: Server returns JWT token valid for 24 hours
3. **Use Token**: Include `Authorization: Bearer <token>` header in all subsequent requests
4. **Token Validation**: AuthenticationMiddleware validates JWT on every request

### JWT Configuration

- **Algorithm**: HS256 (HMAC with SHA-256)
- **Secret**: Configurable via `JWTUtil.JWT_SECRET`
- **Expiry**: 24 hours (configurable via `JWTUtil.TOKEN_EXPIRY_HOURS`)
- **Issuer**: "NMSLite"
- **Claims**: user_id, username, is_active, iat, exp

### Default Users

The schema includes two default users:
- **motadataadmin** / motadataadmin (active)
- **nmsliteadmin** / nmsliteadmin (inactive)

## 📡 API Endpoints

### Authentication
```bash
# Login (No authentication required)
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "password"
}

# Response
{
  "success": true,
  "authenticated": true,
  "jwt_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in_hours": 24,
  "user_id": "uuid",
  "username": "admin",
  "is_active": true,
  "message": "Authentication successful - JWT token generated"
}
```

### Health Check & API Documentation
```bash
# Health check
GET https://localhost:8443/api/health
Authorization: Bearer <token>

# Swagger UI (API Documentation)
GET https://localhost:8443/swagger-ui
# Interactive API documentation with request/response examples
```

### User Management
```bash
# Get all users
GET /api/users
Authorization: Bearer <token>

# Create user
POST /api/users
Authorization: Bearer <token>
Content-Type: application/json

{
  "username": "newuser",
  "password": "password123"
}
```

### Device Types
```bash
# Get all device types
GET /api/device-types
Authorization: Bearer <token>

# Create device type
POST /api/device-types
Authorization: Bearer <token>
Content-Type: application/json

{
  "device_type_name": "Linux Server",
  "default_port": 22
}
```

### Credential Profiles
```bash
# Get all credential profiles
GET /api/credentials
Authorization: Bearer <token>

# Create credential profile
POST /api/credentials
Authorization: Bearer <token>
Content-Type: application/json

{
  "profile_name": "SSH Admin",
  "username": "admin",
  "password": "password123"
}
```

### Discovery Profiles
```bash
# Get all discovery profiles
GET /api/discovery-profiles
Authorization: Bearer <token>

# Create discovery profile
POST /api/discovery-profiles
Authorization: Bearer <token>
Content-Type: application/json

{
  "discovery_name": "Office Network",
  "ip_address": "192.168.1.1-50",
  "is_range": true,
  "device_type_id": "uuid",
  "credential_profile_ids": ["uuid1", "uuid2"],
  "port": 22,
  "protocol": "ssh"
}

# Test discovery from profile
POST /api/discovery-profiles/{profile_id}/test
Authorization: Bearer <token>
```

### Devices
```bash
# Get discovered devices (not yet provisioned)
GET /api/devices/discovered
Authorization: Bearer <token>

# Get provisioned devices
GET /api/devices/provisioned
Authorization: Bearer <token>

# Get device by ID
GET /api/devices/{device_id}
Authorization: Bearer <token>

# Update device configuration
PUT /api/devices/{device_id}
Authorization: Bearer <token>
Content-Type: application/json

{
  "device_name": "Updated Name",
  "polling_interval_seconds": 300,
  "timeout_seconds": 60
}

# Enable monitoring (sets is_provisioned=true)
POST /api/devices/{device_id}/enable
Authorization: Bearer <token>

# Disable monitoring (sets is_provisioned=false)
POST /api/devices/{device_id}/disable
Authorization: Bearer <token>

# Delete device (soft delete - sets is_deleted=true)
DELETE /api/devices/{device_id}
Authorization: Bearer <token>
```

### Metrics
```bash
# Get all metrics for a device
GET /api/metrics/{device_id}
Authorization: Bearer <token>
```

### Availability
```bash
# Get device availability statistics
GET /api/availability/{device_id}
Authorization: Bearer <token>
```



## 🔧 Configuration

### Configuration File

All configuration is loaded from `src/main/resources/application.conf`. The application uses HOCON format for configuration.

| Setting | Default | Description |
|---------|---------|-------------|
| `logging.enabled` | true | Enable/disable application logging |
| `logging.level` | INFO | Log level (TRACE, DEBUG, INFO, WARN, ERROR) |
| `database.host` | localhost | PostgreSQL host |
| `database.port` | 5432 | PostgreSQL port |
| `database.database` | nmslite | Database name |
| `database.user` | nmslite | Database user |
| `database.password` | nmslite | Database password |
| `database.maxSize` | 5 | Connection pool size |
| `server.https.port` | 8443 | HTTPS server port |
| `server.https.keystore.path` | keystore.jks | Path to SSL keystore |
| `server.https.keystore.password` | motadataadmin | Keystore password |
| `vertx.worker.pool.size` | 10 | Worker thread pool size |
| `vertx.blocked.thread.check.interval` | 5 | Blocked thread check interval (seconds) |
| `vertx.blocked.thread.check.max.eventloop.execute.time` | 2 | Event loop warning threshold (seconds) |
| `vertx.blocked.thread.check.max.worker.execute.time` | 600 | Worker thread warning threshold (seconds) |
| `tools.goengine.path` | ./goengine/goengine | Path to GoEngine binary |
| `tools.fping.path` | fping | Path to fping binary |
| `tools.fping.timeout.seconds` | 5 | Per-IP fping timeout |
| `tools.port.check.timeout.seconds` | 5 | Per-socket port check timeout |
| `discovery.batch.size` | 100 | Max IPs per discovery batch |
| `discovery.goengine.timeout.seconds` | 60 | Per-device discovery timeout |
| `discovery.goengine.connection.timeout.seconds` | 20 | Per-credential timeout |
| `availability.cycle.interval.seconds` | 10 | Availability check interval |
| `availability.batch.size` | 50 | Max devices per availability batch |
| `availability.worker.pool.size` | 10 | Availability worker pool size |
| `polling.cycle.interval.seconds` | 60 | Polling scheduler interval |
| `polling.batch.size` | 50 | Max devices per polling batch |
| `polling.max.cycles.skipped` | 5 | Auto-disable threshold |
| `polling.worker.pool.size` | 10 | Polling worker pool size |
| `polling.connection.timeout.seconds` | 20 | SSH/WinRM connection timeout |
| `device.defaults.polling.interval.seconds` | 65 | Default device polling interval (testing value) |
| `device.defaults.timeout.seconds` | 60 | Default per-device timeout |

### Customizing Configuration

Edit `src/main/resources/application.conf` before building to customize settings.

## 📊 Workflow & Code Approach

### 1. Setup Phase
- **User Authentication**: Admin users login via JWT authentication
- **Device Types**: Create device types (Linux Server, Windows Server, etc.) with default ports
- **Credential Profiles**: Create reusable credential profiles (username/password encrypted)
- **Discovery Profiles**: Configure discovery profiles with IP ranges and multiple credentials

### 2. Discovery Phase (DiscoveryVerticle)

**Sequential Batch Processing Approach:**

1. **Profile Creation**: Frontend creates discovery profile with IP range and credential array
2. **Test Discovery**: User triggers test discovery from profile
3. **IP Parsing**: `DiscoveryVerticle` parses IP ranges (single IP, range, or CIDR)
4. **Duplicate Filtering**: Check existing devices to avoid re-discovery
5. **Batch Processing**: `DiscoveryBatchProcessor` processes IPs in configurable batches (default: 100)
6. **Connectivity Check**:
   - **fping**: Batch ICMP ping with 5s per-IP timeout, 180s batch timeout
   - **Port Check**: TCP socket connection with 5s per-socket timeout, 10s batch timeout
7. **GoEngine Discovery**:
   - Sequential credential iteration per IP (try all credentials until success)
   - 60s per-device timeout, 20s per-credential timeout
   - 300s Vert.x blocking timeout for batch (5 minutes)
8. **Device Creation**: Successful discoveries create device entries with `is_provisioned=false`
9. **Results**: Return discovered, failed, and existing device counts

**Key Features:**
- Multiple credential support with sequential testing
- Backpressure handling via batch processing
- Comprehensive timeout hierarchy (Vert.x → Device → Credential)
- Duplicate prevention via IP address uniqueness

### 3. Provision Phase
- **Automatic Provisioning**: Devices are automatically provisioned upon successful discovery
- **Auto-Configuration**: Devices populated with default configuration from config
- **Monitoring Ready**: Set `is_provisioned=true` (monitoring enabled)
- **Availability Tracking**: Initialize device_availability record

### 4. Monitoring Phase (Dual-Cycle Architecture)

NMSLite uses **two separate monitoring cycles** running independently:

#### Cycle 1: Availability Monitoring (AvailabilityVerticle)
**Interval:** 10 seconds
**Purpose:** Fast device availability detection

1. **Scheduler**: Wakes up every 10 seconds
2. **Device Selection**: All provisioned devices (is_provisioned=true, is_deleted=false)
3. **Batch Processing**: Process devices in batches (default: 50)
4. **Connectivity Check**:
   - **fping**: Batch ICMP ping for all devices
   - **Port Check**: TCP socket connection for alive devices
5. **Cache Update**: Update shared availability cache (up/down status)
6. **Database Update**: Update device_availability table
7. **Statistics**: Track total_checks, successful_checks, failed_checks, availability_percent

**Key Features:**
- Fast 10-second cycle for quick availability detection
- Shared cache accessible by PollingMetricsVerticle
- No GoEngine calls (lightweight checks only)
- Real-time availability statistics

#### Cycle 2: Metrics Collection (PollingMetricsVerticle)
**Interval:** 60 seconds
**Purpose:** Detailed metrics collection via SSH/WinRM

**Phase 1: Batch Processing**
1. **Scheduler**: Wakes up every 60 seconds
2. **Device Selection**: Filter devices due for polling based on `polling_interval_seconds`
3. **Availability Check**: Read from shared availability cache (no fping/port check)
4. **Batch Processing**: Process "up" devices in batches (default: 50)
5. **GoEngine Metrics**: Collect CPU, memory, disk metrics via SSH/WinRM
6. **Success Handling**: Store metrics, advance schedule
7. **Failure Tracking**: Track failed devices for auto-disable

**Phase 2: Auto-Disable**
1. **Threshold Check**: Devices exceeding `max.cycles.skipped` (default: 5) consecutive failures
2. **Auto-Disable**: Set `is_provisioned=false` to disable monitoring
3. **Notification**: Log auto-disabled devices

**Key Features:**
- Reads availability from cache (no redundant fping/port checks)
- Only polls devices marked as "up" in availability cache
- In-memory device cache for fast lookups
- Aligned scheduling for predictable polling
- Consecutive failure tracking
- Auto-disable for persistent failures

## 🗄️ Database Schema

The system uses **8 normalized tables** with PostgreSQL INET type for IP addresses:

### Core Tables

1. **users** - Admin users with JWT authentication
   - Fields: user_id (UUID), username, password_hash, is_active
   - No soft delete (users are deactivated via is_active flag)
   - Default users: motadataadmin (active), nmsliteadmin (inactive)

2. **device_types** - Device templates with default ports
   - Fields: device_type_id (UUID), device_type_name, default_port, is_active, created_at
   - Examples: server linux (22), server windows (5985), router (161, inactive)
   - Read-only for security (users cannot create/update/delete device types)

3. **credential_profiles** - Reusable credentials (encrypted)
   - Fields: credential_profile_id (UUID), profile_name, username, password_encrypted
   - **port** (INTEGER) - Port for this credential
   - **protocol** (VARCHAR) - Protocol (ssh/winrm)
   - Used for both discovery and monitoring
   - Timestamps: created_at, updated_at

4. **discovery_profiles** - Discovery configurations
   - Fields: profile_id (UUID), discovery_name, ip_address (TEXT), is_range (BOOLEAN)
   - **device_type_id** (UUID FK) - Reference to device_types
   - **credential_profile_ids (UUID[])** - Array of credentials for sequential testing
   - Supports single IP, IP range, and CIDR notation
   - Timestamps: created_at, updated_at

5. **devices** - Provisioned devices for monitoring
   - Fields: device_id (UUID), device_name, **ip_address (INET)**, device_type, host_name
   - **credential_profile_id** (UUID FK) - Reference to successful credential
   - **Monitoring Config**: polling_interval_seconds, timeout_seconds
   - **Flags**: is_provisioned (true = monitoring enabled), is_deleted (soft delete)
   - **Timestamps**: created_at, updated_at, deleted_at
   - **Note**: port and protocol stored in credential_profiles, not devices

6. **metrics** - Time-series metrics data
   - Fields: metric_id (UUID), device_id (FK), timestamp
   - **CPU**: cpu_usage_percent (DECIMAL 5,2)
   - **Memory**: memory_usage_percent, memory_total_bytes, memory_used_bytes, memory_free_bytes
   - **Disk**: disk_usage_percent, disk_total_bytes, disk_used_bytes, disk_free_bytes
   - Only successful metrics stored (failures tracked in availability)
   - Constraints: cpu/memory/disk percentages between 0-100

7. **device_availability** - Availability tracking
   - Fields: device_id (PK/FK), total_checks, successful_checks, failed_checks
   - **Availability**: availability_percent (DECIMAL 5,2, calculated)
   - **Status**: current_status (up/down/unknown)
   - **Timestamps**: last_check_time, last_success_time, last_failure_time, updated_at
   - Constraint: total_checks = successful_checks + failed_checks

8. **metrics_backup** - Automatic backup of deleted metrics
   - Fields: backup_id (UUID), metric_id, device_id, timestamp, all metric fields
   - **backed_up_at** (TIMESTAMP) - When backup was created
   - Automatically populated via trigger (transparent to application)
   - Preserves historical data when metrics are deleted

### Database Triggers

1. **update_updated_at_column()** - Automatically updates updated_at timestamp
   - Applied to: credential_profiles, discovery_profiles, devices, device_availability

2. **backup_metric_before_delete()** - Backs up metrics before deletion
   - Applied to: metrics table
   - Copies entire metric record to metrics_backup table

### Key Design Decisions

- **INET Type**: Native PostgreSQL IP address storage for validation and performance
- **UUID Primary Keys**: Distributed system compatibility
- **Soft Delete**: Devices marked as deleted (is_deleted=true), not physically removed
- **Credential Arrays**: Multiple credentials per discovery profile for brute-force testing
- **Normalized Design**: Credential profiles reused across discovery and devices
- **Automatic Timestamps**: Triggers for updated_at columns
- **Metrics Backup**: Automatic backup via trigger before deletion
- **Port/Protocol Storage**: Stored in credential_profiles, not devices (normalization)

## 🔧 PostgresSQL INET Type Handling

### ⚠️ Critical Implementation Detail

NMSLite stores IP addresses using PostgresSQL's native `INET` data type for optimal storage and querying. However, the Vert.x PostgresSQL client has compatibility issues with INET type handling that require special workarounds.

### 🚨 The Problem

PostgresSQL's `INET` data type is designed for storing IP addresses efficiently, but Vert.x PostgresSQL client doesn't handle it properly:

1. **Reading INET columns**: Using `row.getString("ip_address")` throws `ClassCastException`
2. **Writing INET columns**: Using parameter casting `$1::inet` doesn't work correctly

### ✅ The Solution

We implement different approaches for reading and writing INET values:

#### **Reading INET Values**
```java
// ❌ WRONG - Causes ClassCastException:
.put("ip_address", row.getString("ip_address"))

// ✅ CORRECT - Use getValue().toString():
.put("ip_address", row.getValue("ip_address").toString())
```

#### **Writing INET Values**
```java
// ❌ WRONG - Parameter casting doesn't work:
String sql = "INSERT INTO devices (ip_address) VALUES ($1::inet)";
pgPool.preparedQuery(sql).execute(Tuple.of(ipAddress));

// ✅ CORRECT - Use string concatenation:
String sql = "INSERT INTO devices (ip_address) VALUES ('" + ipAddress + "'::inet)";
pgPool.preparedQuery(sql).execute(Tuple.of(/* other params without IP */));
```

### 🎯 Implementation Locations

This pattern is implemented across **4 services** that handle IP addresses:

1. **DiscoveryServiceImpl** - 7 reading fixes + 2 writing fixes
2. **DeviceServiceImpl** - 9 reading fixes + 1 writing fix
3. **AvailabilityServiceImpl** - 5 reading fixes
4. **MetricsServiceImpl** - 6 reading fixes

### 🔍 Why This Approach?

1. **Database Integrity**: INET type provides proper IP address validation and indexing
2. **Storage Efficiency**: INET uses 7-19 bytes vs 15+ bytes for VARCHAR
3. **Query Performance**: Native IP operations (subnet matching, sorting)
4. **Type Safety**: PostgresSQL validates IP format at database level

### 💡 Alternative Approaches Considered

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **VARCHAR storage** | Simple Vert.x compatibility | No validation, larger storage, poor performance | ❌ Rejected |
| **Custom type mapping** | Clean code | Complex setup, maintenance overhead | ❌ Rejected |
| **String concatenation** | Works reliably, maintains INET benefits | Slightly verbose | ✅ **Chosen** |

### 🛡️ Security Considerations

The string concatenation approach is safe because:
- IP addresses are validated before database operations
- Input sanitization prevents SQL injection
- PostgresSQL INET casting provides additional validation

### 🧪 Testing Validation

All INET operations are thoroughly tested:
- **Round-trip testing**: Write → Read → Verify
- **Edge cases**: IPv4, IPv6, CIDR notation
- **Error handling**: Invalid IP formats
- **Performance**: Bulk operations

This implementation ensures **100% compatibility** between Vert.x and PostgresSQL INET types while maintaining all the benefits of native IP address storage! 🎯

## 🔍 GoEngine Integration

NMSLite integrates with **GoEngine** (external Go binary) for SSH/WinRM device communication:

### Integration Approach

**Process Execution:**
- Vert.x `executeBlocking()` for non-blocking process execution
- JSON input via stdin, JSON output via stdout
- Real-time streaming with line-by-line parsing
- Comprehensive error handling and timeout management

**Discovery Mode:**
```json
{
  "mode": "discovery",
  "targets": [
    {
      "ip": "192.168.1.100",
      "port": 22,
      "device_type": "linux",
      "credentials": [
        {"username": "admin", "password": "pass1"},
        {"username": "root", "password": "pass2"}
      ],
      "timeout": 60,
      "connection_timeout": 20
    }
  ]
}
```

**Metrics Mode:**
```json
{
  "mode": "metrics",
  "targets": [
    {
      "device_address": "192.168.1.100",
      "port": 22,
      "device_type": "linux",
      "username": "admin",
      "password": "password",
      "timeout": 60,
      "connection_timeout": 20
    }
  ]
}
```

### Timeout Hierarchy

**Discovery (4 Levels):**
1. **Event Bus**: 300s (5 min) - HTTP request to event bus communication - `discovery.eventbus.timeout.seconds`
2. **Vert.x Batch**: 300s (5 min) - Overall batch operation - `discovery.blocking.timeout.goengine`
3. **GoEngine Per-Device**: 60s - Per IP address - `discovery.goengine.timeout.seconds`
4. **GoEngine Per-Credential**: 20s - Per credential attempt - `discovery.goengine.connection.timeout.seconds`

**Availability (3 Levels):**
1. **Vert.x Worker Pool**: 600s (10 min) - Worker pool timeout - `availability.worker.pool.timeout.seconds`
2. **fping Batch**: 180s (3 min) - Batch operation - `tools.fping.batch.blocking.timeout.seconds`
3. **fping Per-IP**: 5s - Per IP address - `tools.fping.timeout.seconds`
4. **Port Check Batch**: 10s - Batch operation - `tools.port.check.batch.blocking.timeout.seconds`
5. **Port Check Per-Socket**: 5s - Per socket - `tools.port.check.timeout.seconds`

**Polling/Metrics (4 Levels):**
1. **Vert.x Worker Pool**: 600s (10 min) - Worker pool timeout - `polling.worker.pool.timeout.seconds`
2. **Vert.x Batch**: 300s (5 min) - Overall batch operation - `polling.blocking.timeout.goengine`
3. **GoEngine Per-Device**: 60s - Per device metrics collection - `device.defaults.timeout.seconds` (from database)
4. **GoEngine Connection**: 20s - SSH/WinRM connection - `polling.connection.timeout.seconds`

### Platform Support

- **Linux**: SSH (port 22) - CPU, memory, disk metrics
- **Windows**: WinRM (port 5985) - CPU, memory, disk metrics
- **Protocol Detection**: Automatic based on device_type and port

### Configuration

GoEngine configuration managed via:
- **Java Backend**: Primary configuration from `application.conf` (all operational parameters)
- **GoEngine YAML**: Feature flags and resource limits in `goengine/config/goengine.yaml`
- **Priority**: Java backend provides all timeouts, credentials, and connection settings

**Java Backend Configuration (`application.conf`):**
- `tools.goengine.path`: Path to GoEngine binary
- `goengine.working.directory`: GoEngine working directory
- `goengine.config.file`: Path to YAML config file
- All timeout values (discovery, polling, connection)
- All credential information
- All device connection parameters

**GoEngine YAML Configuration (`goengine/config/goengine.yaml`):**
- `logging.enabled`: Enable GoEngine logging to file
- `max_workers`: Maximum concurrent device processing (default: 30)
- `discovery.enabled`: Enable discovery mode
- `metrics.enabled`: Enable metrics mode

## 📈 Key Features & Implementation Highlights

### Event-Driven Architecture
- **Vert.x 5.0.4**: Modern reactive framework with async/await patterns
- **4-Verticle Architecture**: ServerVerticle, AvailabilityVerticle, PollingMetricsVerticle, DiscoveryVerticle
- **Event Bus**: All inter-verticle communication via event bus
- **ProxyGen**: Type-safe service proxies eliminate manual message handling
- **Non-Blocking I/O**: All database and external process operations are non-blocking
- **Blocked Thread Checker**: Configurable monitoring of event loop and worker threads

### Authentication & Security
- **HTTPS Only**: All communication encrypted via TLS/SSL (port 8443)
- **JWT Authentication**: HS256 algorithm with 24-hour token expiry
- **AuthenticationMiddleware**: Automatic token validation on all protected routes
- **Password Encryption**: Secure password hashing for user credentials
- **Credential Encryption**: Encrypted storage of device credentials
- **Keystore Security**: SSL certificates managed via Java KeyStore

### Discovery Features
- **Multiple Credentials**: Sequential credential testing per IP (credential array support)
- **Batch Processing**: Configurable batch sizes for network efficiency (default: 100)
- **Duplicate Prevention**: IP address uniqueness constraint
- **Comprehensive Timeouts**: 4-level timeout hierarchy (Event Bus → Vert.x → Device → Credential)
- **Backpressure Handling**: Queue-based batch processor prevents memory overflow
- **Real-time Results**: Immediate feedback on discovery success/failure
- **Automatic Provisioning**: Devices automatically provisioned upon successful discovery

### Monitoring Features (Dual-Cycle Architecture)
- **Availability Cycle**: 10-second cycle with fping + port checks (AvailabilityVerticle)
- **Metrics Cycle**: 60-second cycle with GoEngine SSH/WinRM (PollingMetricsVerticle)
- **Shared Availability Cache**: AvailabilityVerticle populates, PollingMetricsVerticle reads
- **In-Memory Device Cache**: Fast device lookup without database queries
- **Aligned Scheduling**: Predictable polling based on device intervals
- **Consecutive Failure Tracking**: Auto-disable after configurable threshold (default: 5)
- **Availability Statistics**: Real-time availability percentages and status tracking
- **Historical Metrics**: Complete time-series data for trending
- **Metrics Backup**: Automatic backup via trigger before deletion

### Database Optimization
- **PostgreSQL INET Type**: Native IP address storage with validation
- **Connection Pooling**: Vert.x PgPool with configurable pool size
- **Prepared Statements**: SQL injection prevention
- **Indexed Queries**: Optimized for common query patterns
- **Soft Delete**: Preserve historical data while hiding deleted devices
- **Automatic Triggers**: updated_at timestamps and metrics backup
- **Normalized Design**: Credential profiles reused across discovery and devices

### Error Handling
- **Comprehensive Exception Handling**: Centralized error handling via `ExceptionUtil`
- **Graceful Degradation**: Failed devices don't block successful ones
- **Detailed Logging**: SLF4J with Logback for structured logging
- **Timeout Management**: Multiple timeout levels prevent hanging operations
- **Worker Pool Isolation**: Separate worker pools for availability and polling

### Configuration Management
- **HOCON Format**: Human-friendly configuration syntax
- **Hierarchical Config**: Nested configuration for different components
- **Environment Flexibility**: Easy configuration changes without code modification
- **Sensible Defaults**: Production-ready default values
- **Centralized Access**: Bootstrap.getConfig() for global configuration access

### API Documentation
- **Swagger UI**: Interactive API documentation at `/swagger-ui`
- **OpenAPI Specification**: Complete API specification in `openapi.yaml`
- **Request/Response Examples**: Comprehensive examples for all endpoints

## 🚀 Production Deployment

### Docker Deployment (Recommended)

```dockerfile
FROM openjdk:21-jre-slim

# Install fping
RUN apt-get update && apt-get install -y fping && rm -rf /var/lib/apt/lists/*

# Copy application
COPY target/NMSLite-3.0-SNAPSHOT-fat.jar /app/nmslite.jar
COPY goengine /app/goengine
COPY keystore.jks /app/keystore.jks

# Set permissions
RUN chmod +x /app/goengine/goengine

# Create directories
RUN mkdir -p /app/logs

WORKDIR /app

EXPOSE 8443

CMD ["java", "-jar", "nmslite.jar"]
```

### Production Configuration

For production deployment, edit `src/main/resources/application.conf` with production values:

```hocon
database {
  host = "production-db-host"
  port = 5432
  database = "nmslite_prod"
  user = "nmslite_user"
  password = "secure_password"
  maxSize = 10
}

server {
  https {
    port = 8443
    keystore {
      path = "keystore.jks"
      password = "your_secure_keystore_password"
    }
  }
}

logging {
  enabled = true
  level = "INFO"
  file.path = "/var/log/nmslite/nmslite.log"
}

# Production polling intervals
device {
  defaults {
    polling.interval.seconds = 300  # 5 minutes for production
    timeout.seconds = 60
  }
}
```

### SSL Certificate Generation

Generate a self-signed certificate for development/testing:

```bash
keytool -genkeypair -alias nmslite -keyalg RSA -keysize 2048 \
  -storetype JKS -keystore keystore.jks -validity 3650 \
  -dname "CN=localhost, OU=NMSLite, O=Motadata, L=City, ST=State, C=US" \
  -storepass motadataadmin -keypass motadataadmin
```

For production, use a certificate from a trusted Certificate Authority (CA).

## 🔧 Development

### Running in Development

```bash
# Start PostgreSQL
docker run -d --name postgres \
  -e POSTGRES_DB=nmslite \
  -e POSTGRES_USER=nmslite \
  -e POSTGRES_PASSWORD=nmslite \
  -p 5432:5432 postgres:15

# Initialize database schema
psql -h localhost -U nmslite -d nmslite -f database/schema.sql

# Run application
mvn clean compile exec:java -Dexec.mainClass="com.nmslite.Bootstrap"
```

### Running with Maven

```bash
# Build and run
mvn clean package
java -jar target/NMSLite-3.0-SNAPSHOT-fat.jar
```

### 🔧 Developer Notes - INET Type Handling

When working with IP addresses in this codebase, always use the established patterns:

**Reading IP addresses from database:**
```java
// Always use getValue().toString() for INET columns
String ipAddress = row.getValue("ip_address").toString();
```

**Writing IP addresses to database:**
```java
// Use string concatenation for INET values
String sql = "INSERT INTO table (ip_address) VALUES ('" + ipAddress + "'::inet)";
// Remove IP from parameter tuple - use only for other fields
pgPool.preparedQuery(sql).execute(Tuple.of(otherParam1, otherParam2));
```

**Services with INET handling:**
- `DiscoveryServiceImpl` - Discovery operations
- `DeviceServiceImpl` - Device management
- `AvailabilityServiceImpl` - Availability tracking
- `MetricsServiceImpl` - Metrics collection

⚠️ **Important**: Never use `row.getString()` for INET columns or `$X::inet` parameter casting!

## 📝 Logs

Application logs are written to:
- **Console**: Real-time logging (configurable via `logging.console.enabled`)
- **File**: `logs/nmslite.log` (configurable via `logging.file.path`)
- **GoEngine**: `logs/goengine.log` (if GoEngine logging is enabled)

Log levels can be configured in `application.conf`:
```hocon
logging {
  enabled = true
  level = "INFO"  # TRACE, DEBUG, INFO, WARN, ERROR
  file.enabled = true
  console.enabled = true
}
```

### Log Files
- `logs/nmslite.log` - Main application log
- `logs/goengine.log` - GoEngine process log

## 🎯 Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| **Framework** | Vert.x | 5.0.4 |
| **Language** | Java | 21 |
| **Database** | PostgreSQL | 12+ |
| **Authentication** | JWT (HS256) | Auth0 Java JWT 4.4.0 |
| **Configuration** | HOCON | Vert.x Config |
| **Logging** | SLF4J + Logback | 1.4.14 |
| **Build Tool** | Maven | 3.x |
| **External Tools** | fping, GoEngine | - |

## 🎯 Code Quality Standards

NMSLite follows strict coding standards for maintainability:

1. **Comprehensive Documentation**: JavaDoc-style docstrings for all classes and methods with @param and @return tags
2. **Readable Formatting**: Blank lines between every line of code, Allman-style braces (opening braces on new lines)
3. **Descriptive Naming**: Full variable names (exception instead of e, cause instead of c)
4. **Error Handling**: Every method has try-catch with Exception, centralized handling via ExceptionUtil
5. **Async Patterns**: Consistent use of Future/Promise for async operations, no blocking on event loop
6. **Service Abstraction**: ProxyGen services for all database operations, no manual event bus messaging
7. **Configuration-Driven**: All timeouts, thresholds, and settings in application.conf
8. **Response Handling**: Use ResponseUtil for success responses, ExceptionUtil.handleHttp() for HTTP exceptions

## 🎯 Summary

NMSLite provides **enterprise-grade network monitoring** in a lightweight, event-driven package:

✅ **Modern Architecture** - Vert.x 5.0.4 with 4-verticle architecture and ProxyGen service layer
✅ **Secure** - HTTPS-only with JWT authentication and encrypted credentials
✅ **Dual-Cycle Monitoring** - Separate availability (10s) and metrics (60s) cycles
✅ **Scalable** - Event-driven, non-blocking I/O with worker pool isolation
✅ **Efficient** - Smart batching, shared caching, and backpressure handling
✅ **Reliable** - Auto-disable for failed devices, metrics backup via triggers
✅ **Flexible** - Multiple credentials, configurable timeouts, automatic provisioning
✅ **Observable** - Comprehensive logging, Swagger UI, and real-time statistics
✅ **Production-Ready** - Docker support, HOCON config, blocked thread monitoring

**Perfect for monitoring Linux and Windows servers in enterprise environments!** 🚀

---

## 📞 Support & Documentation

- **Swagger UI**: `https://localhost:8443/swagger-ui` - Interactive API documentation
- **Logs**: `logs/nmslite.log` - Application logs
- **GoEngine Logs**: `logs/goengine.log` - GoEngine process logs
- **Configuration**: `src/main/resources/application.conf` - All settings
- **Database Schema**: `database/schema.sql` - Complete database structure

---

**Built with ❤️ using Vert.x 5.0.4 and PostgreSQL**
