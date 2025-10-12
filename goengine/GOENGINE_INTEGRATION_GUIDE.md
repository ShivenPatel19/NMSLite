# GoEngine v8.0.0 Integration Guide

## Overview
GoEngine v8.0.0 is a backend-driven server discovery and metrics collection engine designed for Java integration. It features intelligent credential iteration discovery that automatically tests multiple credential profiles per IP until one succeeds, providing real-time stdout streaming of JSON results with detailed credential tracking.

## Key Features
- **Credential Iteration Discovery**: Automatically tests multiple credential profiles per IP until one succeeds
- **Intelligent Credential Management**: Stops testing credentials once one succeeds per IP
- **Java-Driven Configuration**: All operational parameters (timeouts, credentials) provided by Java backend
- **Overall Timeout Protection**: Prevents runaway operations with configurable per-IP/per-device timeouts from Java
- **Batch Processing**: Concurrent IP processing with configurable concurrency limits
- **Real-time Streaming**: JSON results streamed to stdout line-by-line with credential details
- **Enhanced Validation**: Comprehensive input validation and error messages
- **Hostname Retrieval**: Automatic hostname discovery with successful credential tracking
- **Cross-Platform Support**: Linux (SSH) and Windows (WinRM) server discovery
- **Simplified YAML Configuration**: Only feature flags and resource limits in YAML

**Note**: Retry logic is handled by the Java backend. GoEngine performs single attempts per credential/device.

## Table of Contents
1. [Installation Guide](#installation-guide)
2. [Device Type System](#device-type-system)
3. [CLI Interface](#cli-interface)
4. [Discovery Mode](#discovery-mode)
5. [Metrics Mode](#metrics-mode)
6. [JSON Input/Output Formats](#json-inputoutput-formats)
7. [Error Handling](#error-handling)
8. [Java Vert.x Integration Examples](#java-vertx-integration-examples)
9. [Performance Considerations](#performance-considerations)
10. [Configuration File](#configuration-file)
11. [Best Practices](#best-practices)
12. [Java Backend Required Parameters](#java-backend-required-parameters)
13. [Quick Reference](#quick-reference)

## Installation Guide

### **System Requirements**
- **OS**: Linux (Ubuntu 18.04+, CentOS 7+), Windows Server 2016+
- **Hardware**: 2+ CPU cores, 4GB+ RAM, 100MB disk space
- **Network**: SSH (port 22) and WinRM (port 5985) access to target devices
- **Java**: 8+ for backend integration

### **Installation Steps**

#### **1. Download and Install Binary**
```bash
# Download GoEngine v8.0.0
wget https://releases.example.com/goengine/v8.0.0/goengine-linux-amd64
chmod +x goengine-linux-amd64
sudo mv goengine-linux-amd64 /usr/local/bin/goengine

# Verify installation
goengine --version
```

#### **2. Create Directory Structure**
```bash
# Create GoEngine directory
sudo mkdir -p /opt/goengine/{config,logs}
sudo chown -R $USER:$USER /opt/goengine
```

#### **3. Create Configuration**
```bash
cat > /opt/goengine/config/goengine.yaml << 'EOF'
logging:
  enabled: true              # Device results still stream to stdout, even if disabled


max_workers: 30              # Maximum concurrent IP/device processing

discovery:
  enabled: true              # Enable discovery mode

metrics:
  enabled: true              # Enable metrics mode
EOF
```

#### **4. Test Installation**
```bash
cd /opt/goengine
goengine --help
```

## Device Type System

GoEngine v7.0.0 uses a database-aligned device type system for flexible device categorization.

### Supported Device Types
- **server linux**: Linux servers accessed via SSH (default port 22)
- **server windows**: Windows servers accessed via WinRM (default port 5985)

### Device Type Format
```json
{
  "device_type": "server linux"    // Required: Explicit device type
}
```

### Java Backend Integration
```java
// Database device type mapping
String dbDeviceType = "server_linux";           // From database
String goEngineType = dbDeviceType.replace("_", " ");  // "server linux"

// Device object creation
JsonObject device = new JsonObject()
    .put("address", "10.0.0.1")
    .put("device_type", goEngineType)           // Use device_type
    .put("username", "admin")
    .put("password", "password")
    .put("port", 22);
```

### Future Extensibility
The device type system supports future expansion:
- `"router cisco"` - Cisco routers
- `"switch juniper"` - Juniper switches
- `"firewall palo"` - Palo Alto firewalls
- `"server aix"` - AIX servers

## CLI Interface

### Basic Command Structure
```bash
./goengine --mode <discovery|metrics> --<targets|devices> '<JSON>' [--timeout <duration>]
```

### Command Line Arguments
- `--mode`: Execution mode (`discovery` or `metrics`)
- `--targets`: JSON string of discovery request with credential iteration (discovery mode only)
- `--devices`: JSON string of devices for metrics collection (metrics mode only)
- `--timeout`: Execution timeout duration (default: 5m, format: "30s", "5m", "1h")
- `--help`: Show help information
- `--version`: Show version information

### Output Behavior
- **stdout**: JSON results (one line per IP for discovery, one line per device for metrics)
- **stderr**: Empty (no error output)
- **logs**: Application logs written to `logs/goengine.log`
- **Exit Code**: 0 for success, non-zero for critical failures

## Discovery Mode

### Purpose
Intelligent credential-based discovery that automatically tests multiple credential profiles per IP until one succeeds, retrieving hostnames for both Linux and Windows servers.

### Command Format
```bash
./goengine --mode discovery \
  --targets '{
    "discovery_request": {
      "target_ips": ["192.168.1.10", "192.168.1.11"],
      "credentials": [
        {"credential_id": "cred-1", "username": "admin", "password": "admin123"},
        {"credential_id": "cred-2", "username": "user", "password": "user123"}
      ],
      "discovery_config": {
        "device_type": "server linux",
        "port": 22,
        "timeout_seconds": 30,
        "connection_timeout": 10
      }
    }
  }'
```

### Input JSON Schema (discovery_request)
```json
{
  "discovery_request": {
    "target_ips": ["192.168.1.10", "192.168.1.11"],  // Required: Array of IP addresses
    "credentials": [                                  // Required: Array of credential profiles
      {
        "credential_id": "cred-1",                    // Required: Unique credential identifier
        "username": "admin",                          // Required: SSH/WinRM username
        "password": "admin123"                        // Required: SSH/WinRM password
      }
    ],
    "discovery_config": {                             // Required: Discovery configuration
      "device_type": "server linux",                 // Required: "server linux" or "server windows"
      "port": 22,                                     // Required: SSH(22) or WinRM(5985)
      "timeout_seconds": 30,                          // Required: Overall timeout per IP (seconds)
      "connection_timeout": 10                        // Required: Connection timeout per credential (seconds)
    }
  }
}
```

### Device Type Configuration
- **server linux**: SSH port 22, password authentication
- **server windows**: WinRM port 5985, NTLM authentication

### Credential Iteration Process
1. **For Each IP**: Process IPs concurrently (up to `max_workers` from YAML config)
2. **For Each Credential**: Test credentials sequentially until one succeeds
3. **SSH (server linux)**: `ssh.Dial()` → `session.Output("hostname")` → Retrieve hostname
4. **WinRM (server windows)**: `winrm.NewClient()` → `client.RunWithString("hostname", "")` → Retrieve hostname
5. **Success**: Stop testing remaining credentials for that IP
6. **Failure**: Continue to next credential or report failure if all fail

### Timeout Behavior
- **Per-IP Timeout**: `timeout_seconds` applies to entire discovery process per IP
- **Per-Credential Timeout**: `connection_timeout` applies to each credential test
- **No Retry**: Each credential gets a single attempt (retry handled by Java backend)

### Output JSON Schema (per IP)
```json
{
  "ip_address": "192.168.1.10",         // IP address that was tested
  "success": true,                       // Whether discovery succeeded
  "hostname": "server-01",               // Retrieved hostname (success only)
  "device_type": "server linux",        // Device type from discovery_config
  "credential_id": "cred-2",             // ID of successful credential
  "port": 22,                           // Port used for connection
  "error": ""                           // Error message (failure only)
}
```

### Discovery Success Example
```json
{
  "ip_address": "10.20.40.253",
  "success": true,
  "hostname": "shaunak-ThinkPad-T14s-Gen-1",
  "device_type": "server linux",
  "credential_id": "cred-shaunak",
  "port": 22
}
```

### Discovery Failure Example
```json
{
  "ip_address": "192.168.1.999",
  "success": false,
  "error": "All 3 credentials failed for IP 192.168.1.999"
}
```

### Credential Iteration Behavior
- **Sequential Testing**: For each IP, credentials are tested one by one in order
- **Early Success**: Once a credential succeeds, remaining credentials are skipped for that IP
- **Concurrent IPs**: Multiple IPs are processed simultaneously (up to `max_workers` from YAML config)
- **Detailed Results**: Each result shows which credential succeeded and connection details

## Metrics Mode

### Purpose
Collects real-time system performance metrics (CPU, Memory, Disk).

### Command Format
```bash
./goengine --mode metrics \
  --devices '[{"address":"IP","device_type":"server linux|server windows","username":"user","password":"pass","port":22|5985}]'
```

### Input JSON Schema (devices)
```json
[
  {
    "address": "10.0.0.1",           // Required: IP address
    "device_type": "server linux",   // Required: "server linux" or "server windows"
    "username": "admin",             // Required: SSH/WinRM username
    "password": "password",          // Required: SSH/WinRM password
    "port": 22,                      // Required: SSH(22) or WinRM(5985)
    "timeout_seconds": 45            // Required: Overall timeout per device (seconds)
  }
]
```

### Per-Device Configuration Support
GoEngine supports individual timeout and retry configuration per device for metrics:
```json
{
  "address": "10.20.40.253",
  "device_type": "server linux",
  "username": "shaunak",
  "password": "Mind@123",
  "port": 22,
  "timeout_seconds": 45              // Overall timeout for this device
}
```

### Configuration Philosophy
**All operational parameters (timeouts, connection settings, credentials, etc.) MUST be provided by the Java backend.**

The YAML configuration only controls:
- Feature enablement flags (`discovery.enabled`, `metrics.enabled`)
- Application behavior (`logging.enabled`)
- Resource limits (`max_workers`)

**Discovery Required Parameters (from Java):**
- `timeout_seconds`: Overall timeout per IP - MUST be provided in `discovery_config.timeout_seconds`
- `connection_timeout`: Timeout per credential attempt - MUST be provided in `discovery_config.connection_timeout`

**Metrics Required Parameters (from Java):**
- `timeout_seconds`: Overall timeout per device - MUST be provided in `ServerInfo.timeout_seconds`

**Note**: Retry logic is handled by the Java backend. GoEngine performs single attempts per credential/device.

### Metrics Collection Process

#### Linux (SSH Commands)
```bash
# CPU Usage
top -bn1 | grep '%Cpu'

# Memory Usage
free -b

# Disk Usage
df -B1 /
```

#### Windows (WMI Commands)
```bash
# CPU Usage
wmic cpu get loadpercentage /value

# Memory Usage
wmic OS get TotalVisibleMemorySize,FreePhysicalMemory /value

# Disk Usage
wmic logicaldisk get size,freespace,caption /value
```

### Output JSON Schema (per device)
```json
{
  "device_address": "10.0.0.1",
  "success": true,
  "cpu": {
    "usage_percent": 15.2
  },
  "memory": {
    "usage_percent": 48.37,
    "total_bytes": 16375091200,      // ~15.2 GB
    "used_bytes": 7920664576,        // ~7.4 GB
    "free_bytes": 802430976          // ~765 MB
  },
  "disk": {
    "usage_percent": 68.0,
    "total_bytes": 250375106560,     // ~233 GB
    "used_bytes": 161380077568,      // ~150 GB
    "free_bytes": 76202139648        // ~71 GB
  },
  "error": ""                        // Empty on success
}
```

## JSON Input/Output Formats

### Multiple Device Input
```json
[
  {
    "address": "10.0.0.1",
    "device_type": "server linux",
    "username": "admin",
    "password": "password"
  },
  {
    "address": "192.168.1.100",
    "device_type": "server windows",
    "username": "administrator",
    "password": "Mind@123"
  }
]
```

### Real-time Streaming Output

#### Discovery Streaming Output
Each discovery result is output as a separate JSON line with hostname and device type:
```
{"ip_address":"10.0.0.1","hostname":"server-01","device_type":"server linux","credential_id":"cred-1","port":22,"success":true}
{"ip_address":"10.0.0.2","hostname":"","device_type":"server linux","success":false,"error":"SSH connection failed..."}
{"ip_address":"192.168.1.100","hostname":"win-server-01","device_type":"server windows","credential_id":"cred-2","port":5985,"success":true}
```

#### Metrics Streaming Output
Each metrics result is output as a separate JSON line:
```
{"device_address":"10.0.0.1","success":true,"cpu":{"usage_percent":15.2},"memory":{...},"disk":{...}}
{"device_address":"10.0.0.2","success":false,"error":"SSH connection failed...","cpu":{"usage_percent":0},"memory":{...},"disk":{...}}
```

## Error Handling

### Error Types and Messages

#### Network Connectivity Errors
```json
{
  "success": false,
  "error": "SSH connection failed to 10.0.0.1:22: dial tcp: lookup 10.0.0.1: no such host"
}
```

#### Authentication Errors
```json
{
  "success": false,
  "error": "SSH connection failed to 10.0.0.1:22: ssh: handshake failed: ssh: unable to authenticate, attempted methods [none password], no supported methods remain"
}
```

#### Port Connection Errors
```json
{
  "success": false,
  "error": "SSH connection failed to 127.0.0.1:9999: dial tcp 127.0.0.1:9999: connect: connection refused"
}
```

#### Missing Credentials
```json
{
  "success": false,
  "error": "username and password are required for server 127.0.0.1"
}
```

#### Windows WinRM Errors
```json
{
  "success": false,
  "error": "Windows metrics parsing failed on 192.168.1.999:5985: WinRM connectivity test failed: unknown error Post \"http://192.168.1.999:5985/wsman\": dial tcp: lookup 192.168.1.999: no such host"
}
```

### Error Response Structure

#### Discovery Error Response
```json
{
  "ip_address": "10.0.0.1",
  "hostname": "",
  "device_type": "server linux",
  "success": false,
  "error": "Specific error message with actual system error"
}
```

#### Metrics Error Response
```json
{
  "device_address": "10.0.0.1",
  "success": false,
  "cpu": {"usage_percent": 0},
  "memory": {"usage_percent": 0, "total_bytes": 0, "used_bytes": 0, "free_bytes": 0},
  "disk": {"usage_percent": 0, "total_bytes": 0, "used_bytes": 0, "free_bytes": 0},
  "error": "Specific error message with actual system error"
}
```

## Java Vert.x Integration Examples

### 1. Discovery Verticle Implementation

```java
public class DiscoveryVerticle extends AbstractVerticle {

    @Override
    public void start() {
        vertx.eventBus().consumer("discovery.request", this::handleDiscoveryRequest);
    }

    private void handleDiscoveryRequest(Message<JsonObject> message) {
        JsonObject request = message.body();
        JsonArray targets = request.getJsonArray("targets");
        String requestId = request.getString("requestId");

        // Execute GoEngine discovery
        executeGoEngineDiscovery(targets, requestId)
            .onSuccess(result -> message.reply(new JsonObject().put("status", "completed")))
            .onFailure(error -> message.fail(500, error.getMessage()));
    }

    private Future<Void> executeGoEngineDiscovery(JsonArray targets, String requestId) {
        Promise<Void> promise = Promise.promise();

        // Prepare GoEngine command
        List<String> command = Arrays.asList(
            "./goengine",
            "--mode", "discovery",
            "--targets", targets.encode()
        );

        // Execute process with real-time stdout processing
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File("/path/to/goengine"));

        try {
            Process process = pb.start();

            // Read stdout line by line for real-time processing
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            vertx.executeBlocking(blockingPromise -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Parse JSON result
                        JsonObject deviceResult = new JsonObject(line);

                        // Publish to event bus for real-time processing
                        vertx.eventBus().publish("discovery.result", deviceResult);
                    }

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        blockingPromise.complete();
                    } else {
                        blockingPromise.fail("GoEngine process failed with exit code: " + exitCode);
                    }
                } catch (Exception e) {
                    blockingPromise.fail(e);
                }
            }, result -> {
                if (result.succeeded()) {
                    promise.complete();
                } else {
                    promise.fail(result.cause());
                }
            });

        } catch (IOException e) {
            promise.fail(e);
        }

        return promise.future();
    }
}
```

### 2. Metrics Polling Verticle Implementation

```java
public class PollingVerticle extends AbstractVerticle {
    private long timerId;

    @Override
    public void start() {
        // Set up periodic polling every 5 minutes (300000 ms)
        timerId = vertx.setPeriodic(300000, this::performMetricsCollection);

        // Handle manual metrics requests
        vertx.eventBus().consumer("metrics.request", this::handleMetricsRequest);
    }

    private void performMetricsCollection(Long timerId) {
        // Get active devices from database
        vertx.eventBus().request("db.query", "SELECT active_devices")
            .onSuccess(reply -> {
                JsonArray devices = (JsonArray) reply.body();

                // Perform batch fping first
                performBatchFping(devices)
                    .compose(reachableDevices -> executeMetricsCollection(reachableDevices))
                    .onFailure(error -> logger.error("Metrics collection failed", error));
            });
    }

    private Future<JsonArray> performBatchFping(JsonArray devices) {
        Promise<JsonArray> promise = Promise.promise();

        // Build fping command for all devices
        List<String> ips = devices.stream()
            .map(device -> ((JsonObject) device).getString("address"))
            .collect(Collectors.toList());

        List<String> fpingCommand = new ArrayList<>();
        fpingCommand.add("fping");
        fpingCommand.add("-c");
        fpingCommand.add("1");
        fpingCommand.add("-t");
        fpingCommand.add("1000");
        fpingCommand.add("-q");
        fpingCommand.addAll(ips);

        ProcessBuilder pb = new ProcessBuilder(fpingCommand);

        vertx.executeBlocking(blockingPromise -> {
            try {
                Process process = pb.start();

                // Parse fping results
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                );

                Set<String> aliveIps = new HashSet<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    // Parse fping output to get alive IPs
                    if (line.contains("alive")) {
                        String ip = line.split(":")[0].trim();
                        aliveIps.add(ip);
                    }
                }

                // Filter devices to only include reachable ones
                JsonArray reachableDevices = new JsonArray();
                devices.forEach(device -> {
                    JsonObject deviceObj = (JsonObject) device;
                    if (aliveIps.contains(deviceObj.getString("address"))) {
                        reachableDevices.add(deviceObj);
                    }
                });

                blockingPromise.complete(reachableDevices);

            } catch (Exception e) {
                blockingPromise.fail(e);
            }
        }, result -> {
            if (result.succeeded()) {
                promise.complete((JsonArray) result.result());
            } else {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    private Future<Void> executeMetricsCollection(JsonArray devices) {
        if (devices.isEmpty()) {
            return Future.succeededFuture();
        }

        Promise<Void> promise = Promise.promise();

        // Prepare GoEngine command
        List<String> command = Arrays.asList(
            "./goengine",
            "--mode", "metrics",
            "--devices", devices.encode()
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File("/path/to/goengine"));

        try {
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            vertx.executeBlocking(blockingPromise -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        JsonObject metricsResult = new JsonObject(line);

                        // Update availability tracking
                        updateAvailabilityTracking(metricsResult);

                        // Publish metrics for storage and alerting
                        vertx.eventBus().publish("metrics.collected", metricsResult);

                        // Check for threshold breaches
                        checkThresholds(metricsResult);
                    }

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        blockingPromise.complete();
                    } else {
                        blockingPromise.fail("GoEngine metrics collection failed");
                    }
                } catch (Exception e) {
                    blockingPromise.fail(e);
                }
            }, result -> {
                if (result.succeeded()) {
                    promise.complete();
                } else {
                    promise.fail(result.cause());
                }
            });

        } catch (IOException e) {
            promise.fail(e);
        }

        return promise.future();
    }

    private void updateAvailabilityTracking(JsonObject metricsResult) {
        String deviceAddress = metricsResult.getString("device_address");
        boolean success = metricsResult.getBoolean("success");

        // Update availability counters in database
        JsonObject availabilityUpdate = new JsonObject()
            .put("device_address", deviceAddress)
            .put("success", success)
            .put("timestamp", System.currentTimeMillis());

        vertx.eventBus().send("db.update.availability", availabilityUpdate);
    }

    private void checkThresholds(JsonObject metricsResult) {
        if (!metricsResult.getBoolean("success")) {
            return;
        }

        JsonObject cpu = metricsResult.getJsonObject("cpu");
        JsonObject memory = metricsResult.getJsonObject("memory");
        JsonObject disk = metricsResult.getJsonObject("disk");

        // Check CPU threshold (example: 90%)
        if (cpu.getDouble("usage_percent") > 90.0) {
            triggerAlert("CPU_HIGH", metricsResult);
        }

        // Check Memory threshold (example: 85%)
        if (memory.getDouble("usage_percent") > 85.0) {
            triggerAlert("MEMORY_HIGH", metricsResult);
        }

        // Check Disk threshold (example: 95%)
        if (disk.getDouble("usage_percent") > 95.0) {
            triggerAlert("DISK_HIGH", metricsResult);
        }
    }

    private void triggerAlert(String alertType, JsonObject metricsResult) {
        JsonObject alert = new JsonObject()
            .put("type", alertType)
            .put("device_address", metricsResult.getString("device_address"))
            .put("metrics", metricsResult)
            .put("timestamp", System.currentTimeMillis());

        vertx.eventBus().publish("alert.triggered", alert);
    }
}
```

### 3. Server Verticle with WebSocket Support

```java
public class ServerVerticle extends AbstractVerticle {

    @Override
    public void start() {
        Router router = Router.router(vertx);

        // REST API endpoints
        router.post("/api/devices/discover").handler(this::handleDiscoveryRequest);
        router.post("/api/devices/metrics").handler(this::handleMetricsRequest);
        router.get("/api/devices/availability").handler(this::handleAvailabilityRequest);

        // WebSocket for real-time updates
        router.route("/ws/*").handler(this::setupWebSocket);

        // Event bus consumers for real-time updates
        vertx.eventBus().consumer("discovery.result", this::forwardToWebSocket);
        vertx.eventBus().consumer("metrics.collected", this::forwardToWebSocket);
        vertx.eventBus().consumer("alert.triggered", this::forwardToWebSocket);

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8080);
    }

    private void handleDiscoveryRequest(RoutingContext context) {
        JsonObject requestBody = context.getBodyAsJson();
        String requestId = "DISC_" + System.currentTimeMillis();

        JsonObject discoveryRequest = new JsonObject()
            .put("targets", requestBody.getJsonArray("targets"))
            .put("requestId", requestId);

        vertx.eventBus().request("discovery.request", discoveryRequest)
            .onSuccess(reply -> {
                context.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("status", "completed")
                        .put("requestId", requestId)
                        .encode());
            })
            .onFailure(error -> {
                context.response()
                    .setStatusCode(500)
                    .end(new JsonObject()
                        .put("error", error.getMessage())
                        .encode());
            });
    }

    private void setupWebSocket(RoutingContext context) {
        // WebSocket setup for real-time updates
        context.request().toWebSocket()
            .onSuccess(webSocket -> {
                // Store WebSocket connection for broadcasting
                storeWebSocketConnection(webSocket);
            });
    }

    private void forwardToWebSocket(Message<JsonObject> message) {
        // Broadcast to all connected WebSocket clients
        broadcastToWebSockets(message.body());
    }
}
```

## Performance Considerations

### 1. Concurrent Processing
- GoEngine processes devices concurrently using goroutines
- Java should handle multiple GoEngine processes simultaneously
- Use Vert.x `executeBlocking()` for process execution

### 2. Memory Management
- Stream JSON results line-by-line, don't buffer entire output
- Process results immediately upon receipt
- Use bounded queues for device processing

### 3. Timeout Handling
```java
// Set appropriate timeouts for GoEngine processes
ProcessBuilder pb = new ProcessBuilder(command);
Process process = pb.start();

// Set timeout for process completion
boolean finished = process.waitFor(5, TimeUnit.MINUTES);
if (!finished) {
    process.destroyForcibly();
    throw new RuntimeException("GoEngine process timed out");
}
```

### 4. Batch Optimization
- Use batch fping for connectivity pre-checks
- Group devices by device type for efficient processing
- Implement intelligent retry mechanisms

## Configuration File

GoEngine reads configuration from `config/goengine.yaml`:

```yaml
logging:
  enabled: true              # Device results still stream to stdout, even if disabled


max_workers: 30              # Maximum concurrent IP/device processing

discovery:
  enabled: true              # Enable discovery mode

metrics:
  enabled: true              # Enable metrics mode
```

### Configuration Philosophy
**All operational parameters (timeouts, connection settings, credentials, etc.) MUST be provided by the Java backend.**

The YAML configuration only controls:
- Feature enablement flags (`discovery.enabled`, `metrics.enabled`)
- Application behavior (`logging.enabled`)
- Resource limits (`max_workers`)

### Configuration Details
- **logging.enabled**: Controls application logging to `logs/goengine.log` (stdout streaming always works)
- **max_workers**: Maximum concurrent device processing (channel-based concurrency)
- **discovery.enabled**: Enable/disable discovery mode
- **metrics.enabled**: Enable/disable metrics mode

**Important**: All operational parameters (timeout_seconds, connection_timeout, credentials, etc.) MUST be provided by the Java backend in each request. There are no YAML fallbacks for these values.

**Note**: Retry logic is handled by the Java backend. GoEngine performs single attempts per credential/device.

## Best Practices

### 1. Error Handling
```java
// Always check GoEngine exit codes
int exitCode = process.waitFor();
if (exitCode != 0) {
    // Handle GoEngine failure
    logger.error("GoEngine failed with exit code: " + exitCode);
}

// Parse and handle individual device errors
JsonObject result = new JsonObject(line);
if (!result.getBoolean("success")) {
    String error = result.getString("error");
    // Handle specific error types
}
```

### 2. Logging and Monitoring
```java
// Log GoEngine executions
logger.info("Starting GoEngine discovery for {} devices", devices.size());

// Monitor performance metrics
long startTime = System.currentTimeMillis();
// ... execute GoEngine ...
long duration = System.currentTimeMillis() - startTime;
logger.info("GoEngine completed in {}ms", duration);
```

### 3. Resource Management
```java
// Always close streams and processes
try (BufferedReader reader = new BufferedReader(
    new InputStreamReader(process.getInputStream()))) {
    // Process output
} finally {
    process.destroyForcibly(); // Ensure cleanup
}
```

### 4. Configuration Management
```java
// Use configuration for GoEngine paths and timeouts
JsonObject config = config();
String goEnginePath = config.getString("goengine.path", "./goengine");
int timeout = config.getInteger("goengine.timeout", 300); // 5 minutes
```

### 5. Availability Tracking
```sql
-- Database schema for availability tracking
CREATE TABLE device_availability (
    device_id VARCHAR(50) PRIMARY KEY,
    success_count INT DEFAULT 0,
    total_count INT DEFAULT 0,
    availability_percent DECIMAL(5,2) DEFAULT 0.00,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Update availability on each check
UPDATE device_availability
SET success_count = success_count + ?,
    total_count = total_count + 1,
    availability_percent = (success_count / total_count) * 100,
    last_updated = CURRENT_TIMESTAMP
WHERE device_id = ?;
```

## Summary

GoEngine provides a robust, real-time streaming interface for server discovery and metrics collection. Key integration points:

1. **Real-time Processing**: Stream JSON results line-by-line
2. **Error Handling**: Parse specific error messages for proper handling
3. **Batch Operations**: Use fping for efficient connectivity pre-checks
4. **Concurrent Execution**: Handle multiple GoEngine processes simultaneously
5. **Availability Tracking**: Implement comprehensive availability monitoring

This guide provides the foundation for building a scalable, enterprise-ready monitoring platform using GoEngine with Java Vert.x.

## Java Backend Required Parameters

### Discovery Mode Parameters

| Parameter | Java Backend Field | Required | Description |
|-----------|-------------------|----------|-------------|
| `timeout_seconds` | `discovery_config.timeout_seconds` | ✅ | Overall timeout per IP (seconds) |
| `connection_timeout` | `discovery_config.connection_timeout` | ✅ | Timeout per credential attempt (seconds) |
| `device_type` | `discovery_config.device_type` | ✅ | "server linux" or "server windows" |
| `port` | `discovery_config.port` | ✅ | SSH (22) or WinRM (5985) |
| `target_ips` | `discovery_request.target_ips` | ✅ | Array of IP addresses to discover |
| `credentials` | `discovery_request.credentials` | ✅ | Array of credential profiles to test |

### Metrics Mode Parameters

| Parameter | Java Backend Field | Required | Description |
|-----------|-------------------|----------|-------------|
| `timeout_seconds` | `ServerInfo.timeout_seconds` | ✅ | Overall timeout per device (seconds) |
| `address` | `ServerInfo.address` | ✅ | IP address of target device |
| `username` | `ServerInfo.username` | ✅ | SSH/WinRM username |
| `password` | `ServerInfo.password` | ✅ | SSH/WinRM password |
| `device_type` | `ServerInfo.device_type` | ✅ | "server linux" or "server windows" |
| `port` | `ServerInfo.port` | ✅ | SSH (22) or WinRM (5985) |

### Concurrency Control

**Global Concurrency**: Both discovery and metrics use the global `max_workers` parameter from YAML configuration to control concurrent processing. There is no per-request concurrency override - all operations respect the global limit.

| Parameter | YAML Configuration | Description |
|-----------|-------------------|-------------|
| `max_workers` | `max_workers: 30` | Maximum concurrent IP/device processing for both discovery and metrics |

### Parameter Validation Rules

1. **Java Backend MUST Provide**: All timeout values, credentials, device types, ports, and protocols
2. **YAML Only Controls**: Feature flags (`enabled`), timing, logging, and max_workers
3. **Error Response**: If Java backend doesn't provide required parameters, GoEngine returns clear error message

### Example Java Backend Configuration

```java
// Discovery with all required parameters
DiscoveryConfig config = new DiscoveryConfig();
config.setDeviceType("server linux");
config.setPort(22);
config.setTimeoutSeconds(45);        // Required - no YAML fallback
config.setConnectionTimeout(8);      // Required - no YAML fallback

DiscoveryRequest request = new DiscoveryRequest();
request.setTargetIps(Arrays.asList("10.0.0.1", "10.0.0.2"));
request.setCredentials(credentialsList);
request.setDiscoveryConfig(config);

// Metrics with all required parameters
ServerInfo server = new ServerInfo();
server.setAddress("10.0.0.1");
server.setDeviceType("server linux");
server.setUsername("admin");
server.setPassword("password");
server.setPort(22);
server.setTimeoutSeconds(30);        // Required - no YAML fallback
```

## Quick Reference

### **📋 Command Examples**

#### **Discovery (Batch with Hostname Retrieval)**
```bash
./goengine --mode discovery \
  --targets '[
    {"address":"10.0.0.1","device_type":"server linux","username":"admin","password":"pass","port":22,"timeout":"30s"},
    {"address":"10.0.0.2","device_type":"server windows","username":"admin","password":"pass","port":5985,"timeout":"30s"}
  ]' \
  --request-id "DISC_001"
```

#### **Metrics (Multiple Devices)**
```bash
./goengine --mode metrics \
  --devices '[
    {"address":"10.0.0.1","device_type":"server linux","username":"admin","password":"pass","port":22,"timeout":"45s"},
    {"address":"10.0.0.2","device_type":"server windows","username":"admin","password":"pass","port":5985,"timeout":"60s"}
  ]' \
  --request-id "METRICS_001"
```

### **🔧 Java Code Templates**

#### **Device Type Mapping**
```java
public static String mapDeviceType(String dbType) {
    return dbType.replace("_", " "); // "server_linux" → "server linux"
}
```

#### **Single Device Discovery**
```java
public Future<DiscoveryResult> discoverDevice(Device device) {
    JsonArray targets = new JsonArray().add(createDeviceJson(device));
    return executeGoEngine("discovery", targets);
}
```

#### **Batch Metrics Collection**
```java
public Future<List<MetricsResult>> collectMetrics(List<Device> devices) {
    JsonArray deviceArray = new JsonArray();
    devices.forEach(device -> deviceArray.add(createDeviceJson(device)));
    return executeGoEngine("metrics", deviceArray);
}
```

### **📊 JSON Formats**

#### **Input Device Object**
```json
{
  "address": "10.0.0.1",
  "device_type": "server linux",
  "username": "admin",
  "password": "password",
  "port": 22,
  "timeout": "30s"
}
```

#### **Discovery Output**
```json
{
  "ip_address": "10.0.0.1",
  "hostname": "server-01",
  "device_type": "server linux",
  "credential_id": "cred-1",
  "port": 22,
  "success": true
}
```

#### **Metrics Output**
```json
{
  "device_address": "10.0.0.1",
  "success": true,
  "cpu": {"usage_percent": 15.2},
  "memory": {"usage_percent": 48.37, "total_bytes": 16375091200, "used_bytes": 7920664576, "free_bytes": 802430976},
  "disk": {"usage_percent": 68.0, "total_bytes": 250375106560, "used_bytes": 161380077568, "free_bytes": 76202139648}
}
```

### **⚡ Performance Tips**
- **Discovery**: Use batch processing for multiple devices with hostname retrieval
- **Metrics**: Use batch processing for multiple devices with per-device timeouts
- **Streaming**: Process JSON line-by-line, don't buffer
- **Timeouts**: Set appropriate timeouts (same for all in discovery, per-device for metrics)
- **Concurrency**: Handle multiple GoEngine processes in parallel

### **🔍 Troubleshooting**

#### **Common Errors**
```bash
# Invalid device type
"has invalid device_type 'linux' (must be server linux or server windows)"

# Missing device type
"missing required device_type specification"

# Missing credentials
"username and password are required for server 10.0.0.1"
```

#### **Test Commands**
```bash
# Test installation
goengine --version

# Test discovery (with hostname retrieval)
goengine --mode discovery --targets '{"discovery_request":{"target_ips":["YOUR_IP"],"credentials":[{"credential_id":"test","username":"USER","password":"PASS"}],"discovery_config":{"device_type":"server linux","port":22,"timeout_seconds":30,"connection_timeout":10}}}'

# Test metrics
goengine --mode metrics --devices '[{"address":"YOUR_IP","device_type":"server linux","username":"USER","password":"PASS","port":22,"timeout_seconds":45}]'
```

**🎯 Ready for production deployment with GoEngine v8.0.0!**
