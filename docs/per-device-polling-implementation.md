# Per-Device Polling Implementation Guide

## Current vs Proposed Architecture

### Current (Global Interval):
```java
// All devices polled every 60 seconds
vertx.setPeriodic(60000, timerId -> pollAllDevices());
```

### Proposed (Per-Device Intervals):
```java
// Check every minute which devices need polling based on their individual intervals
vertx.setPeriodic(60000, timerId -> pollDevicesDueForPolling());

private void pollDevicesDueForPolling() {
    String sql = """
        SELECT d.device_id, d.device_name, d.ip_address, d.polling_interval_seconds,
               COALESCE(m.last_poll_time, d.created_at) as last_poll_time
        FROM devices d
        LEFT JOIN (
            SELECT device_id, MAX(timestamp) as last_poll_time
            FROM metrics
            GROUP BY device_id
        ) m ON d.device_id = m.device_id
        WHERE d.is_monitoring_enabled = true 
          AND d.is_deleted = false
          AND (
              m.last_poll_time IS NULL OR 
              EXTRACT(EPOCH FROM (NOW() - m.last_poll_time)) >= d.polling_interval_seconds
          )
        """;
    
    // Poll only devices that are due for polling
}
```

## Database Schema Changes

### Updated devices table:
```sql
polling_interval_seconds INTEGER DEFAULT 60 
    CHECK (polling_interval_seconds >= 30 AND polling_interval_seconds <= 31536000)
```

### Constraints:
- Minimum: 30 seconds (reasonable minimum for network monitoring)
- Maximum: 31,536,000 seconds (1 year)
- Default: 60 seconds (current behavior)

## Common Interval Values

| Interval | Seconds | Use Case |
|----------|---------|----------|
| 30 seconds | 30 | Critical infrastructure |
| 1 minute | 60 | Standard monitoring |
| 5 minutes | 300 | Regular servers |
| 15 minutes | 900 | Non-critical devices |
| 1 hour | 3,600 | Backup systems |
| 1 day | 86,400 | Archive systems |
| 1 week | 604,800 | Rarely used devices |
| 1 month | 2,592,000 | Long-term storage |
| 6 months | 15,552,000 | Disaster recovery systems |

## Implementation Steps

1. **Update database schema** (already done above)
2. **Modify PollingMetricsVerticle** to use per-device intervals
3. **Add API endpoints** to manage device polling intervals
4. **Update UI** to allow setting custom intervals per device
5. **Add validation** to ensure reasonable interval values

## Performance Considerations

- **Memory**: More efficient than individual timers per device
- **Database**: Single query to find devices due for polling
- **Scalability**: Handles thousands of devices with different intervals
- **Flexibility**: Easy to adjust intervals without restarting application
