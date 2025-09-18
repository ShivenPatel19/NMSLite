# NMSLite - Network Monitoring System

A lightweight, event-driven network monitoring system built with Vert.x and PostgreSQL.

## 🏗️ Architecture

NMSLite uses a **4-verticle event-driven architecture**:

- **MainVerticle**: HTTP API + WebSocket real-time updates
- **DatabaseVerticle**: All database operations (PostgreSQL)
- **DiscoveryVerticle**: Device discovery workflow (fping + GoEngine)
- **PollingMetricsVerticle**: Continuous monitoring (60s intervals)

## 🚀 Quick Start

### Prerequisites

1. **Java 21+**
2. **PostgreSQL 12+**
3. **fping** (for network connectivity checks)
4. **GoEngine** (for SSH/WinRM metrics collection)

### Database Setup

1. Create PostgreSQL database:
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
java -jar target/NMSLite-1.0-SNAPSHOT-fat.jar
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

# Discovery Configuration
discovery {
  goengine.path = "./goengine/goengine"
  fping.path = "fping"
  timeout.seconds = 30
  batch.size = 100
}
```

## 📡 API Endpoints

### Health Check
```bash
GET /api/health
```

### Device Discovery
```bash
POST /api/devices/discover
Content-Type: application/json

{
  "ipRanges": [
    "192.168.1.0/24",
    "10.0.0.1-10.0.0.100",
    "172.16.1.50"
  ]
}
```

### Get Devices
```bash
GET /api/devices
```

### Get Device Metrics
```bash
GET /api/devices/{device_id}/metrics
```

### Manual Provision
```bash
POST /api/provision
Content-Type: application/json

{
  "profile_id": "uuid-here"
}
```

### Device Types & Credentials
```bash
GET /api/device-types
GET /api/credentials
POST /api/credentials
```

## 🔌 WebSocket Real-time Updates

Connect to WebSocket for real-time updates:

```javascript
const ws = new WebSocket('ws://localhost:8080/ws');

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  
  switch(message.type) {
    case 'discovery.result':
      console.log('Discovery result:', message.data);
      break;
    case 'discovery.completed':
      console.log('Discovery completed:', message.data);
      break;
    case 'connectivity.failed':
      console.log('Device unreachable:', message.data);
      break;
    case 'metrics.update':
      console.log('Metrics update:', message.data);
      break;
  }
};
```

## 🔧 Configuration

### Configuration File

All configuration is loaded from `src/main/resources/application.conf`. The application uses HOCON format for configuration.

| Setting | Default | Description |
|---------|---------|-------------|
| `database.host` | localhost | PostgreSQL host |
| `database.port` | 5432 | PostgreSQL port |
| `database.database` | nmslite | Database name |
| `database.user` | nmslite | Database user |
| `database.password` | nmslite | Database password |
| `main.http.port` | 8080 | HTTP server port |
| `discovery.goengine.path` | ./goengine/goengine | Path to GoEngine binary |
| `discovery.fping.path` | fping | Path to fping binary |
| `discovery.timeout.seconds` | 30 | Discovery timeout (seconds) |
| `polling.interval.seconds` | 60 | Polling interval (seconds) |

### Customizing Configuration

Edit `src/main/resources/application.conf` before building the application to customize settings.

## 📊 Workflow

### 1. Setup Phase
- Create device types (Linux Server, Windows Server, etc.)
- Create credential profiles (reusable username/password)
- Admin users manage the system

### 2. Discovery Phase
- Submit IP ranges for discovery
- System executes fping for connectivity
- Port scanning for alive devices
- GoEngine SSH/WinRM discovery for accessible devices
- Real-time results via WebSocket

### 3. Provision Phase
- Successful discoveries create devices for monitoring
- Devices stored with all necessary connection details

### 4. Monitoring Phase
- Continuous 60-second polling cycles
- Batch fping for connectivity validation
- GoEngine metrics collection for alive devices
- Availability tracking and historical metrics storage
- Real-time updates via WebSocket

## 🗄️ Database Schema

The system uses 8 normalized tables:

1. **users** - Admin users
2. **device_types** - Device templates with default ports
3. **credential_profiles** - Reusable credentials
4. **discovery_profiles** - Discovery form data
5. **devices** - Provisioned devices for monitoring
6. **discovery_attempts** - Discovery polling results
7. **metrics** - Current + historical metrics
8. **device_availability** - Availability counters

## 🔍 GoEngine Integration

NMSLite integrates with GoEngine for SSH/WinRM monitoring:

- **Discovery Mode**: Device connectivity and authentication validation
- **Metrics Mode**: CPU, memory, disk metrics collection
- **Real-time streaming**: JSON results via stdout
- **Platform support**: Linux (SSH) and Windows (WinRM)

## 📈 Monitoring Features

- **Real-time discovery**: Live updates during device discovery
- **Continuous monitoring**: 60-second polling intervals
- **Smart batching**: Efficient fping + GoEngine execution
- **Availability tracking**: Real-time availability percentages
- **Historical metrics**: Complete metrics history for graphing
- **Error tracking**: Comprehensive error logging and reporting
- **WebSocket updates**: Real-time UI notifications

## 🚀 Production Deployment

### Docker Deployment (Recommended)

```dockerfile
FROM openjdk:21-jre-slim

# Install fping
RUN apt-get update && apt-get install -y fping && rm -rf /var/lib/apt/lists/*

# Copy application
COPY target/NMSLite-1.0-SNAPSHOT-fat.jar /app/nmslite.jar
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
DB_HOST=your-postgres-host
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
# Start PostgreSQL
docker run -d --name postgres \
  -e POSTGRES_DB=nmslite \
  -e POSTGRES_USER=nmslite_user \
  -e POSTGRES_PASSWORD=nmslite \
  -p 5432:5432 postgres:15

# Run application
mvn compile exec:java -Dexec.mainClass="io.vertx.core.Launcher" \
  -Dexec.args="run com.nmslite.NMSLiteApplication"
```

### Hot Reload

```bash
mvn vertx:run
```

## 📝 Logs

Application logs are written to:
- **Console**: Real-time logging
- **File**: `logs/nmslite.log` (rotated daily)

Log levels can be configured in `src/main/resources/logback.xml`.

## 🎯 Key Features

✅ **Event-driven architecture** - Scalable and responsive
✅ **Real-time updates** - WebSocket integration
✅ **Smart batching** - Efficient network operations
✅ **Complete monitoring** - Discovery + continuous polling
✅ **Error resilience** - Comprehensive error handling
✅ **Historical data** - Full metrics history
✅ **Availability tracking** - Real-time availability counters
✅ **Production ready** - Logging, configuration, deployment

NMSLite provides enterprise-grade network monitoring in a lightweight, event-driven package! 🚀
