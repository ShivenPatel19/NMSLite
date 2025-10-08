# NMSLite - Network Monitoring System

**Version:** 2.0-SNAPSHOT
**Framework:** Vert.x 5.0.4
**Java Version:** 21
**Database:** PostgreSQL 12+

A lightweight, event-driven network monitoring system built with Vert.x and PostgreSQL, featuring JWT authentication, ProxyGen service architecture, and real-time device monitoring.

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

NMSLite uses a **3-verticle event-driven architecture** with ProxyGen-based service communication:

### Core Verticles

- **ServerVerticle**: HTTP API server with JWT authentication middleware
- **DiscoveryVerticle**: Device discovery workflow (fping + GoEngine + sequential batch processing)
- **PollingMetricsVerticle**: Continuous monitoring with 4-phase polling cycle

### Database Initialization

- **DatabaseInitializer**: One-time database setup at application startup (no verticle needed)
  - Creates PostgreSQL connection pool
  - Registers all 7 ProxyGen services on event bus
  - Runs before any verticles are deployed

### Service Layer (ProxyGen)

All database operations are abstracted through Vert.x ProxyGen services for type-safe event bus communication:

- **UserService**: User management and authentication
- **DeviceTypeService**: Device type management
- **CredentialProfileService**: Credential profile management
- **DiscoveryProfileService**: Discovery profile management
- **DeviceService**: Device CRUD and monitoring configuration
- **MetricsService**: Time-series metrics data collection
- **AvailabilityService**: Device availability tracking

### Communication Pattern

```
HTTP Request → ServerVerticle → Service Proxy (Event Bus) → Service Implementation → PostgresSQL
                                      ↓
                          (Registered at startup by DatabaseInitializer)
```

All services use `createProxy(vertx)` pattern for seamless event bus communication without manual message handling.

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
UserService userService = UserService.createProxy(vertx);
userService.getUserById(userId)
    .onSuccess(user -> {
        // Handle user
    })
    .onFailure(error -> {
        // Handle error
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
1. **Vert.x Batch**: 120s - Overall batch operation timeout
2. **GoEngine Device**: 30s - Per-device discovery timeout
3. **GoEngine Credential**: 10s - Per-credential attempt timeout

**Polling Timeouts (2 Levels):**
1. **Vert.x Batch**: 330s - Overall batch operation timeout
2. **GoEngine Device**: 60s - Per-device metrics collection timeout

**Network Timeouts:**
- **fping**: 5s per-IP, 180s batch timeout
- **Port Check**: 5s per-socket, 10s batch timeout

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

PollingMetricsVerticle maintains in-memory device cache for performance:

```java
// Device cache loaded at startup and updated on changes
private final Map<String, PollingDevice> deviceCache = new ConcurrentHashMap<>();

// Fast lookup without database queries
PollingDevice device = deviceCache.get(deviceId);
```

## 🚀 Quick Start

### Prerequisites

1. **Java 21+**
2. **PostgresSQL 12+**
3. **fping** (for network connectivity checks)
4. **GoEngine** (for SSH/WinRM metrics collection)

### Database Setup

1. Create PostgresSQL database:
```sql
CREATE DATABASE nmslite;
```

2. Run the schema:
```bash
psql -d nmslite -f database/schema.sql
```

### Build & Run

1. **Build the application:**
```bash
mvn clean package
```

2. **Run the application:**
```bash
java -jar target/NMSLite-2.0-SNAPSHOT-fat.jar
```

3. **Configuration:**
All configuration is loaded from `src/main/resources/application.conf`.
To customize settings, edit the configuration file before building:

```hocon
# Database Configuration
database {
  host = "localhost"
  port = 5432
  database = "nmslite"
  user = "nmslite"
  password = "nmslite"
  maxSize = 20
}

# HTTP Server Configuration
main {
  http.port = 8080
  websocket.path = "/ws"
}

# Shared Tools Configuration
tools {
  goengine.path = "./goengine/goengine"
  fping.path = "fping"
}

# Discovery Configuration
discovery {
  batch.size = 100
}

# Polling Configuration
polling {
  system.cycle.interval.seconds = 60       # How often the system checks for devices to poll
  batch.size = 50
}
```

## 🔐 Authentication

NMSLite uses **JWT (JSON Web Token)** authentication for all API endpoints (except `/api/auth/login`).

### Authentication Flow

1. **Login**: POST `/api/auth/login` with username/password
2. **Receive JWT**: Server returns JWT token valid for 24 hours
3. **Use Token**: Include `Authorization: Bearer <token>` header in all subsequent requests
4. **Token Validation**: AuthenticationMiddleware validates JWT on every request

### JWT Configuration

- **Algorithm**: HS256 (HMAC with SHA-256)
- **Secret**: Configurable via `JWTUtil.JWT_SECRET`
- **Expiry**: 24 hours (configurable via `JWTUtil.TOKEN_EXPIRY_HOURS`)
- **Issuer**: "NMSLite"
- **Claims**: user_id, username, is_active, iat, exp

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
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "user_id": "uuid",
    "username": "admin",
    "is_active": true
  }
}
```

### Health Check
```bash
GET /api/health
Authorization: Bearer <token>
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
# Get all devices
GET /api/devices
Authorization: Bearer <token>

# Get devices available for provisioning
GET /api/devices/available-for-provision
Authorization: Bearer <token>

# Get provisioned devices
GET /api/devices/provisioned
Authorization: Bearer <token>

# Get device by ID
GET /api/devices/{device_id}
Authorization: Bearer <token>

# Update device
PUT /api/devices/{device_id}
Authorization: Bearer <token>
Content-Type: application/json

{
  "device_name": "Updated Name",
  "port": 22,
  "polling_interval_seconds": 300,
  "timeout_seconds": 60,
  "retry_count": 2,
  "alert_threshold_cpu": 80.0,
  "alert_threshold_memory": 85.0,
  "alert_threshold_disk": 90.0
}

# Enable/Disable monitoring
POST /api/devices/{device_id}/monitoring/enable
POST /api/devices/{device_id}/monitoring/disable
Authorization: Bearer <token>

# Provision device
POST /api/devices/{device_id}/provision
Authorization: Bearer <token>

# Delete device (soft delete)
DELETE /api/devices/{device_id}
Authorization: Bearer <token>
```

### Metrics
```bash
# Get device metrics
GET /api/devices/{device_id}/metrics
Authorization: Bearer <token>

# Get latest metric
GET /api/devices/{device_id}/metrics/latest
Authorization: Bearer <token>
```

### Availability
```bash
# Get device availability
GET /api/devices/{device_id}/availability
Authorization: Bearer <token>
```



## 🔧 Configuration

### Configuration File

All configuration is loaded from `src/main/resources/application.conf`. The application uses HOCON format for configuration.

| Setting | Default | Description |
|---------|---------|-------------|
| `database.host` | localhost | PostgresSQL host |
| `database.port` | 5432 | PostgresSQL port |
| `database.database` | nmslite | Database name |
| `database.user` | nmslite | Database user |
| `database.password` | nmslite | Database password |
| `main.http.port` | 8080 | HTTP server port |
| `tools.goengine.path` | ./goengine/goengine | Path to GoEngine binary |
| `tools.fping.path` | fping | Path to fping binary |
| `tools.network.connection.timeout.seconds` | 30 | Network timeout for device connections |
| `polling.system.cycle.interval.seconds` | 60 | How often system checks for devices to poll |
| `device.defaults.device.polling.interval.seconds` | 300 | Default interval for polling individual devices |

### Customizing Configuration

Edit `src/main/resources/application.conf` before building the application to customize settings.

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
3. **IP Parsing**: `IPRangeUtil` parses IP ranges (single IP, range, or CIDR)
4. **Duplicate Filtering**: Check existing devices to avoid re-discovery
5. **Batch Processing**: `DiscoveryBatchProcessor` processes IPs in configurable batches (default: 100)
6. **Connectivity Check**:
   - **fping**: Batch ICMP ping with 5s per-IP timeout, 180s batch timeout
   - **Port Check**: TCP socket connection with 5s per-socket timeout, 10s batch timeout
7. **GoEngine Discovery**:
   - Sequential credential iteration per IP (try all credentials until success)
   - 30s per-device timeout, 10s per-credential timeout
   - 120s Vert.x blocking timeout for batch
8. **Device Creation**: Successful discoveries create device entries with `is_provisioned=false`
9. **Results**: Return discovered, failed, and existing device counts

**Key Features:**
- Multiple credential support with sequential testing
- Backpressure handling via batch processing
- Comprehensive timeout hierarchy (Vert.x → Device → Credential)
- Duplicate prevention via IP address uniqueness

### 3. Provision Phase
- **Manual Provisioning**: User provisions discovered devices via API
- **Auto-Configuration**: Devices populated with default thresholds from config
- **Monitoring Ready**: Set `is_provisioned=true`, `is_monitoring_enabled=true`
- **Availability Tracking**: Initialize device_availability record

### 4. Monitoring Phase (PollingMetricsVerticle)

**4-Phase Polling Cycle Approach:**

**Phase 1: Batch Processing**
1. **Scheduler**: Wakes up every 60 seconds (configurable)
2. **Device Selection**: Filter devices due for polling based on `polling_interval_seconds`
3. **Batch Processing**: Process devices in batches (default: 50)
4. **Connectivity Check**:
   - **fping**: Batch ICMP ping for all devices
   - **Port Check**: TCP socket connection for alive devices
5. **GoEngine Metrics**: Collect CPU, memory, disk metrics for connected devices
6. **Success Handling**: Store metrics, update availability, advance schedule
7. **Failure Tracking**: Track failed devices for retry

**Phase 2: Retry Failed Devices**
1. **Retry Logic**: Retry failed devices using same batch method
2. **Success**: Reset failure counter, advance schedule
3. **Failure**: Increment consecutive failure counter

**Phase 3: Log Exhausted Failures**
1. **Failure Logging**: Log devices that failed all retries to file
2. **File Path**: `polling_failed/metrics_polling_failed.txt`

**Phase 4: Auto-Disable**
1. **Threshold Check**: Devices exceeding `max.cycles.skipped` (default: 5)
2. **Auto-Disable**: Set `is_monitoring_enabled=false`
3. **Notification**: Log auto-disabled devices

**Key Features:**
- In-memory device cache for fast lookups
- Aligned scheduling for predictable polling
- Consecutive failure tracking
- Auto-disable for persistent failures
- Comprehensive error logging
- Real-time availability updates

## 🗄️ Database Schema

The system uses **7 normalized tables** with PostgresSQL INET type for IP addresses:

### Core Tables

1. **users** - Admin users with JWT authentication
   - Fields: user_id (UUID), username, password_hash, is_active
   - Soft delete support

2. **device_types** - Device templates with default ports
   - Fields: device_type_id (UUID), device_type_name, default_port, is_active
   - Examples: Linux Server (22), Windows Server (5985)

3. **credential_profiles** - Reusable credentials (encrypted)
   - Fields: credential_profile_id (UUID), profile_name, username, password_encrypted
   - Used for both discovery and monitoring

4. **discovery_profiles** - Discovery configurations
   - Fields: profile_id (UUID), discovery_name, ip_address (TEXT), is_range (BOOLEAN)
   - **credential_profile_ids (UUID[])** - Array of credentials for sequential testing
   - Supports single IP, IP range, and CIDR notation

5. **devices** - Provisioned devices for monitoring
   - Fields: device_id (UUID), device_name, **ip_address (INET)**, device_type, port, protocol
   - **credential_profile_id** - Reference to successful credential
   - **Monitoring Config**: polling_interval_seconds, timeout_seconds, retry_count
   - **Alert Thresholds**: alert_threshold_cpu, alert_threshold_memory, alert_threshold_disk
   - **Flags**: is_provisioned, is_monitoring_enabled, is_deleted
   - **Timestamps**: created_at, updated_at, last_polled_at, monitoring_enabled_at

6. **metrics** - Time-series metrics data
   - Fields: metric_id (UUID), device_id (FK), timestamp, duration_ms
   - **CPU**: cpu_usage_percent
   - **Memory**: memory_usage_percent, memory_total_bytes, memory_used_bytes, memory_free_bytes
   - **Disk**: disk_usage_percent, disk_total_bytes, disk_used_bytes, disk_free_bytes
   - Only successful metrics stored (failures tracked in availability)

7. **device_availability** - Availability tracking
   - Fields: device_id (PK/FK), total_checks, successful_checks, failed_checks
   - **Availability**: availability_percent (calculated)
   - **Status**: current_status (up/down/unknown), status_since
   - **Timestamps**: last_check_time, last_success_time, last_failure_time

### Key Design Decisions

- **INET Type**: Native PostgresSQL IP address storage for validation and performance
- **UUID Primary Keys**: Distributed system compatibility
- **Soft Delete**: Devices marked as deleted, not physically removed
- **Credential Arrays**: Multiple credentials per discovery profile for brute-force testing
- **Normalized Design**: Credential profiles reused across discovery and devices
- **Automatic Timestamps**: Triggers for updated_at columns

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
      "credentials": [
        {"username": "admin", "password": "pass1"},
        {"username": "root", "password": "pass2"}
      ],
      "timeout": 30,
      "connection_timeout": 10
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
      "ip": "192.168.1.100",
      "port": 22,
      "username": "admin",
      "password": "password",
      "timeout": 60,
      "connection_timeout": 10
    }
  ]
}
```

### Timeout Hierarchy

**Discovery (3 Levels):**
1. **Vert.x Batch Timeout**: 120s (entire batch operation)
2. **GoEngine Per-Device**: 30s (per IP address)
3. **GoEngine Per-Credential**: 10s (per credential attempt)

**Polling (2 Levels):**
1. **Vert.x Batch Timeout**: 330s (entire batch operation)
2. **GoEngine Per-Device**: 60s (per device metrics collection)

### Platform Support

- **Linux**: SSH (port 22) - CPU, memory, disk metrics
- **Windows**: WinRM (port 5985) - CPU, memory, disk metrics
- **Protocol Detection**: Automatic based on device_type and port

### Configuration

GoEngine configuration managed via:
- **Java Backend**: Primary configuration from `application.conf`
- **GoEngine YAML**: Fallback configuration in `goengine/config/goengine.yaml`
- **Priority**: Java config overrides GoEngine YAML

Key settings:
- `goengine.path`: Path to GoEngine binary
- `goengine.max.workers`: Concurrent device processing (default: 30)
- `goengine.logging.enabled`: Enable GoEngine logging
- `goengine.timing.enabled`: Enable timing information

## 📈 Key Features & Implementation Highlights

### Event-Driven Architecture
- **Vert.x 5.0.4**: Modern reactive framework with async/await patterns
- **Event Bus**: All inter-verticle communication via event bus
- **ProxyGen**: Type-safe service proxies eliminate manual message handling
- **Non-Blocking I/O**: All database and external process operations are non-blocking

### Authentication & Security
- **JWT Authentication**: HS256 algorithm with 24-hour token expiry
- **AuthenticationMiddleware**: Automatic token validation on all protected routes
- **Password Encryption**: Secure password hashing for user credentials
- **Credential Encryption**: Encrypted storage of device credentials

### Discovery Features
- **Multiple Credentials**: Sequential credential testing per IP
- **Batch Processing**: Configurable batch sizes for network efficiency
- **Duplicate Prevention**: IP address uniqueness constraint
- **Comprehensive Timeouts**: 3-level timeout hierarchy (Vert.x → Device → Credential)
- **Backpressure Handling**: Queue-based batch processor prevents memory overflow
- **Real-time Results**: Immediate feedback on discovery success/failure

### Monitoring Features
- **4-Phase Polling Cycle**: Batch → Retry → Log → Auto-Disable
- **In-Memory Cache**: Fast device lookup without database queries
- **Aligned Scheduling**: Predictable polling based on device intervals
- **Consecutive Failure Tracking**: Auto-disable after configurable threshold
- **Availability Tracking**: Real-time availability percentages
- **Historical Metrics**: Complete time-series data for trending
- **Failure Logging**: Persistent logging of failed devices

### Database Optimization
- **PostgreSQL INET Type**: Native IP address storage with validation
- **Connection Pooling**: Vert.x PgPool with configurable pool size
- **Prepared Statements**: SQL injection prevention
- **Indexed Queries**: Optimized for common query patterns
- **Soft Delete**: Preserve historical data while hiding deleted devices

### Error Handling
- **Comprehensive Exception Handling**: Centralized error handling via `ExceptionUtil`
- **Graceful Degradation**: Failed devices don't block successful ones
- **Detailed Logging**: SLF4J with Logback for structured logging
- **Timeout Management**: Multiple timeout levels prevent hanging operations

### Configuration Management
- **HOCON Format**: Human-friendly configuration syntax
- **Hierarchical Config**: Nested configuration for different components
- **Environment Flexibility**: Easy configuration changes without code modification
- **Sensible Defaults**: Production-ready default values

## 🚀 Production Deployment

### Docker Deployment (Recommended)

```dockerfile
FROM openjdk:21-jre-slim

# Install fping
RUN apt-get update && apt-get install -y fping && rm -rf /var/lib/apt/lists/*

# Copy application
COPY target/NMSLite-2.0-SNAPSHOT-fat.jar /app/nmslite.jar
COPY goengine/goengine /app/goengine

# Set permissions
RUN chmod +x /app/goengine

# Create logs directory
RUN mkdir -p /app/logs

WORKDIR /app

EXPOSE 8080

CMD ["java", "-jar", "nmslite.jar"]
```

### Environment Variables for Production

```bash
DB_HOST=your-Postgress-host
DB_PORT=5432
DB_NAME=nmslite_prod
DB_USER=nmslite_user
DB_PASSWORD=secure_password
HTTP_PORT=8080
GOENGINE_PATH=/app/goengine
FPING_PATH=fping
POLLING_INTERVAL=60
```

## 🔧 Development

### Running in Development

```bash
# Start PostgresSQL
docker run -d --name Postgress \
  -e PostgresS_DB=nmslite \
  -e PostgresS_USER=nmslite_user \
  -e PostgresS_PASSWORD=nmslite \
  -p 5432:5432 Postgress:15

# Run application
mvn compile exec:java -Dexec.mainClass="io.vertx.core.Launcher" \
  -Dexec.args="run com.nmslite.NMSLiteApplication"
```

### Hot Reload

```bash
mvn vertx:run
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
- **Console**: Real-time logging
- **File**: `logs/nmslite.log` (rotated daily)

Log levels can be configured in `src/main/resources/logback.xml`.

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

1. **Comprehensive Documentation**: JavaDoc-style docstrings for all classes and methods
2. **Readable Formatting**: Blank lines between code statements, Allman-style braces
3. **Descriptive Naming**: Full variable names (exception instead of e, cause instead of c)
4. **Error Handling**: Centralized exception handling via ExceptionUtil
5. **Async Patterns**: Consistent use of Future/Promise for async operations
6. **Service Abstraction**: ProxyGen services for all database operations
7. **Configuration-Driven**: All timeouts, thresholds, and settings in config file

## 🎯 Summary

NMSLite provides **enterprise-grade network monitoring** in a lightweight, event-driven package:

✅ **Modern Architecture** - Vert.x 5.0.4 with ProxyGen service layer
✅ **Secure** - JWT authentication on all endpoints
✅ **Scalable** - Event-driven, non-blocking I/O
✅ **Efficient** - Smart batching and backpressure handling
✅ **Reliable** - 4-phase polling with auto-disable
✅ **Flexible** - Multiple credentials, configurable timeouts
✅ **Observable** - Comprehensive logging and metrics
✅ **Production-Ready** - Docker support, HOCON config

**Perfect for monitoring Linux and Windows servers in enterprise environments!** 🚀
