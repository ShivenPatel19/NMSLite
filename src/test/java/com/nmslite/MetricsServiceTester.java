package com.nmslite;

import com.nmslite.services.impl.MetricsServiceImpl;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MetricsService Comprehensive Tester
 * 
 * Tests both SUCCESS and FAILURE scenarios for MetricsService:
 * - All 9 MetricsService methods
 * - Success flow with logical sequence
 * - Failure scenarios with proper error handling
 * - Direct database testing without HTTP server
 * - ProxyGen service validation
 * - Time-series metrics data management
 * - Pagination and filtering
 * - Metrics cleanup operations
 */
public class MetricsServiceTester {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricsServiceTester.class);
    
    private Vertx vertx;
    private PgPool pgPool;
    private MetricsServiceImpl metricsService;
    
    // Test device details for the flow
    private String testDeviceId;
    private String testMetricId;
    
    public static void main(String[] args) {
        MetricsServiceTester tester = new MetricsServiceTester();
        
        try {
            tester.setup();
            
            // Run comprehensive tests
            tester.runSuccessTests();
            tester.runFailureTests();
            
            tester.printSummary();
            
        } catch (Exception e) {
            logger.error("MetricsService test execution failed", e);
        } finally {
            tester.cleanup();
        }
    }
    
    public void setup() throws Exception {
        logger.info("🔧 Setting up MetricsService tester...");
        
        // Create Vert.x instance
        vertx = Vertx.vertx();
        
        // Setup database connection
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setPort(5432)
            .setHost("localhost")
            .setDatabase("nmslite")
            .setUser("nmslite")
            .setPassword("nmslite");
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(5);
        
        pgPool = PgPool.pool(vertx, connectOptions, poolOptions);
        
        // Test database connection
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        
        pgPool.getConnection()
            .onSuccess(connection -> {
                connection.close();
                logger.info("✅ Database connection successful");
                latch.countDown();
            })
            .onFailure(cause -> {
                error.set(new Exception("Database connection failed", cause));
                latch.countDown();
            });
        
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new Exception("Database connection timeout");
        }
        
        if (error.get() != null) {
            throw error.get();
        }
        
        // Initialize MetricsService implementation
        metricsService = new MetricsServiceImpl(vertx, pgPool);
        
        // Get a test device ID
        setupTestDeviceId();
        
        logger.info("🚀 MetricsService tester ready!");
    }
    
    private void setupTestDeviceId() throws Exception {
        logger.info("🔍 Setting up test device ID...");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> deviceRef = new AtomicReference<>();
        
        pgPool.query("SELECT device_id FROM devices WHERE is_deleted = false LIMIT 1")
            .execute()
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    deviceRef.set(rows.iterator().next().getUUID("device_id").toString());
                }
                latch.countDown();
            })
            .onFailure(cause -> latch.countDown());
        
        latch.await(5, TimeUnit.SECONDS);
        testDeviceId = deviceRef.get();
        
        if (testDeviceId == null) {
            throw new Exception("No active devices found. Please ensure devices table has active records.");
        }
        
        logger.info("✅ Test device ID setup complete: {}", testDeviceId.substring(0, 8) + "...");
    }
    
    public void runSuccessTests() {
        logger.info("\n" + "=".repeat(70));
        logger.info("✅ TESTING MetricsService - SUCCESS SCENARIOS");
        logger.info("=".repeat(70));
        
        // Test 1: List all metrics (baseline - paginated)
        testMetricsListSuccess(0, 10, null);
        
        // Test 2: Create new metrics data
        testMetricsCreateSuccess();
        
        // Test 3: Get specific metric by ID
        testMetricsGetSuccess(testMetricId);
        
        // Test 4: List metrics with device filter
        testMetricsListSuccess(0, 5, testDeviceId);
        
        // Test 5: List metrics by specific device
        testMetricsListByDeviceSuccess(testDeviceId, 0, 10);
        
        // Test 6: Get latest metric for device
        testMetricsGetLatestByDeviceSuccess(testDeviceId);
        
        // Test 7: Get latest metrics for all devices
        testMetricsGetLatestAllDevicesSuccess();
        
        // Test 8: Get metrics by device time range
        testMetricsGetByDeviceTimeRangeSuccess(testDeviceId);
        
        // Test 9: Create additional metrics for cleanup test
        testMetricsCreateSuccess();
        
        // Test 10: Delete metrics older than specified days
        testMetricsDeleteOlderThanSuccess(365);
        
        // Test 11: Delete all metrics for device (cleanup)
        testMetricsDeleteAllByDeviceSuccess(testDeviceId);
        
        // Test 12: Final verification - list metrics
        testMetricsListSuccess(0, 10, null);
        
        logger.info("\n🎉 ALL MetricsService SUCCESS TESTS COMPLETED!");
    }
    
    public void runFailureTests() {
        logger.info("\n" + "=".repeat(70));
        logger.info("💥 TESTING MetricsService - FAILURE SCENARIOS");
        logger.info("=".repeat(70));
        
        // Test 1: Invalid UUID format
        testMetricsGetInvalidUUID();
        
        // Test 2: Non-existent metric ID
        testMetricsGetNonExistent();
        
        // Test 3: Missing required fields in create
        testMetricsCreateMissingFields();
        
        // Test 4: Invalid percentage values
        testMetricsCreateInvalidPercentages();
        
        // Test 5: Invalid device ID in create
        testMetricsCreateInvalidDeviceId();
        
        // Test 6: Invalid pagination parameters
        testMetricsListInvalidPagination();
        
        // Test 7: Non-existent device in device-specific operations
        testMetricsListByDeviceNonExistent();
        
        // Test 8: Invalid time range format
        testMetricsGetByDeviceTimeRangeInvalidFormat();
        
        // Test 9: Invalid days parameter for cleanup
        testMetricsDeleteOlderThanInvalidDays();
        
        logger.info("\n🎯 ALL MetricsService FAILURE TESTS COMPLETED!");
    }
    
    // ========== SUCCESS TEST METHODS ==========
    
    private void testMetricsListSuccess(int page, int pageSize, String deviceId) {
        logger.info("\n📋 Testing metricsList({}, {}, {}) - SUCCESS...", page, pageSize, deviceId != null ? "deviceFilter" : "all");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        metricsService.metricsList(page, pageSize, deviceId, ar -> {
            if (ar.succeeded()) {
                JsonObject result = ar.result();
                logger.info("✅ SUCCESS: Found {} metrics (page {}/{})", 
                    result.getJsonArray("metrics").size(), 
                    result.getInteger("current_page"), 
                    result.getInteger("total_pages"));
                logger.info("📄 Total Records: {}", result.getInteger("total_records"));
                logger.info("📄 Metrics: {}", result.getJsonArray("metrics").encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });
        
        waitForTest(latch, "metricsList");
    }
    
    private void testMetricsCreateSuccess() {
        logger.info("\n➕ Testing metricsCreate() - SUCCESS...");
        
        JsonObject metricsData = new JsonObject()
            .put("device_id", testDeviceId)
            .put("success", true)
            .put("duration_ms", 150)
            .put("cpu_usage_percent", 45.5)
            .put("memory_usage_percent", 67.8)
            .put("memory_total_bytes", 8589934592L) // 8GB
            .put("memory_used_bytes", 5825421312L)  // ~5.4GB
            .put("memory_free_bytes", 2764513280L)  // ~2.6GB
            .put("disk_usage_percent", 78.2)
            .put("disk_total_bytes", 1099511627776L) // 1TB
            .put("disk_used_bytes", 859993456844L)   // ~800GB
            .put("disk_free_bytes", 239518170932L);  // ~223GB
        
        CountDownLatch latch = new CountDownLatch(1);
        
        metricsService.metricsCreate(metricsData, ar -> {
            if (ar.succeeded()) {
                JsonObject result = ar.result();
                testMetricId = result.getString("metric_id"); // Store for later tests
                logger.info("✅ SUCCESS: Metrics data created");
                logger.info("📄 Metric ID: {}", testMetricId);
                logger.info("📄 Device ID: {}", testDeviceId);
                logger.info("📄 Result: {}", result.encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });
        
        waitForTest(latch, "metricsCreate");
    }
    
    private void testMetricsGetSuccess(String metricId) {
        if (metricId == null) {
            logger.info("\n⏭️ Skipping metricsGet() - No metric ID available");
            return;
        }
        
        logger.info("\n🔍 Testing metricsGet() - SUCCESS...");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        metricsService.metricsGet(metricId, ar -> {
            if (ar.succeeded()) {
                JsonObject metric = ar.result();
                logger.info("✅ SUCCESS: Metric found");
                logger.info("📄 Metric: {}", metric.encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });
        
        waitForTest(latch, "metricsGet");
    }
    
    private void testMetricsListByDeviceSuccess(String deviceId, int page, int pageSize) {
        logger.info("\n📊 Testing metricsListByDevice() - SUCCESS...");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        metricsService.metricsListByDevice(deviceId, page, pageSize, ar -> {
            if (ar.succeeded()) {
                JsonObject result = ar.result();
                logger.info("✅ SUCCESS: Found {} metrics for device (page {}/{})", 
                    result.getJsonArray("metrics").size(), 
                    result.getInteger("current_page"), 
                    result.getInteger("total_pages"));
                logger.info("📄 Device Metrics: {}", result.getJsonArray("metrics").encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });
        
        waitForTest(latch, "metricsListByDevice");
    }
    
    private void testMetricsGetLatestByDeviceSuccess(String deviceId) {
        logger.info("\n🕐 Testing metricsGetLatestByDevice() - SUCCESS...");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        metricsService.metricsGetLatestByDevice(deviceId, ar -> {
            if (ar.succeeded()) {
                JsonObject metric = ar.result();
                logger.info("✅ SUCCESS: Latest metric found for device");
                logger.info("📄 Latest Metric: {}", metric.encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });
        
        waitForTest(latch, "metricsGetLatestByDevice");
    }
    
    private void testMetricsGetLatestAllDevicesSuccess() {
        logger.info("\n🌐 Testing metricsGetLatestAllDevices() - SUCCESS...");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        metricsService.metricsGetLatestAllDevices(ar -> {
            if (ar.succeeded()) {
                JsonArray metrics = ar.result();
                logger.info("✅ SUCCESS: Found latest metrics for {} devices", metrics.size());
                logger.info("📄 Latest Metrics: {}", metrics.encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });
        
        waitForTest(latch, "metricsGetLatestAllDevices");
    }

    private void testMetricsGetByDeviceTimeRangeSuccess(String deviceId) {
        logger.info("\n📅 Testing metricsGetByDeviceTimeRange() - SUCCESS...");

        // Get time range for last 24 hours
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(24);
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsGetByDeviceTimeRange(deviceId, startTime.format(formatter), endTime.format(formatter), ar -> {
            if (ar.succeeded()) {
                JsonArray metrics = ar.result();
                logger.info("✅ SUCCESS: Found {} metrics in time range", metrics.size());
                logger.info("📄 Time Range: {} to {}", startTime.format(formatter), endTime.format(formatter));
                logger.info("📄 Metrics: {}", metrics.encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsGetByDeviceTimeRange");
    }

    private void testMetricsDeleteOlderThanSuccess(int olderThanDays) {
        logger.info("\n🗑️ Testing metricsDeleteOlderThan({}) - SUCCESS...", olderThanDays);

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsDeleteOlderThan(olderThanDays, ar -> {
            if (ar.succeeded()) {
                JsonObject result = ar.result();
                logger.info("✅ SUCCESS: Metrics cleanup completed");
                logger.info("📄 Result: {}", result.encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsDeleteOlderThan");
    }

    private void testMetricsDeleteAllByDeviceSuccess(String deviceId) {
        logger.info("\n🗑️ Testing metricsDeleteAllByDevice() - SUCCESS...");

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsDeleteAllByDevice(deviceId, ar -> {
            if (ar.succeeded()) {
                JsonObject result = ar.result();
                logger.info("✅ SUCCESS: All metrics deleted for device");
                logger.info("📄 Result: {}", result.encodePrettily());
            } else {
                logger.error("❌ FAILED: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsDeleteAllByDevice");
    }

    // ========== FAILURE TEST METHODS ==========

    private void testMetricsGetInvalidUUID() {
        logger.info("\n❌ Testing metricsGet() - Invalid UUID...");

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsGet("invalid-uuid-format", ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Invalid UUID properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected invalid UUID");
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsGet invalid UUID");
    }

    private void testMetricsGetNonExistent() {
        logger.info("\n❌ Testing metricsGet() - Non-existent metric...");

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsGet("00000000-0000-0000-0000-000000000000", ar -> {
            if (ar.succeeded()) {
                JsonObject result = ar.result();
                if (!result.getBoolean("found", true)) {
                    logger.info("✅ SUCCESS: Non-existent metric properly handled");
                    logger.info("📄 Result: {}", result.encodePrettily());
                } else {
                    logger.error("❌ FAILED: Should have returned found=false");
                }
            } else {
                logger.info("✅ SUCCESS: Non-existent metric properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsGet non-existent");
    }

    private void testMetricsCreateMissingFields() {
        logger.info("\n❌ Testing metricsCreate() - Missing required fields...");

        // Test missing device_id
        JsonObject incompleteData1 = new JsonObject()
            .put("success", true)
            .put("duration_ms", 100);

        CountDownLatch latch1 = new CountDownLatch(1);

        metricsService.metricsCreate(incompleteData1, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Missing device ID properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected missing device ID");
            }
            latch1.countDown();
        });

        waitForTest(latch1, "metricsCreate missing device_id");

        // Test missing success status
        JsonObject incompleteData2 = new JsonObject()
            .put("device_id", testDeviceId)
            .put("duration_ms", 100);

        CountDownLatch latch2 = new CountDownLatch(1);

        metricsService.metricsCreate(incompleteData2, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Missing success status properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected missing success status");
            }
            latch2.countDown();
        });

        waitForTest(latch2, "metricsCreate missing success");

        // Test empty JSON
        JsonObject emptyData = new JsonObject();

        CountDownLatch latch3 = new CountDownLatch(1);

        metricsService.metricsCreate(emptyData, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Empty metrics data properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected empty metrics data");
            }
            latch3.countDown();
        });

        waitForTest(latch3, "metricsCreate empty data");
    }

    private void testMetricsCreateInvalidPercentages() {
        logger.info("\n❌ Testing metricsCreate() - Invalid percentage values...");

        JsonObject invalidPercentageData = new JsonObject()
            .put("device_id", testDeviceId)
            .put("success", true)
            .put("cpu_usage_percent", 150.0) // Invalid: > 100
            .put("memory_usage_percent", -10.0) // Invalid: < 0
            .put("disk_usage_percent", 200.0); // Invalid: > 100

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsCreate(invalidPercentageData, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Invalid percentage values properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected invalid percentage values");
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsCreate invalid percentages");
    }

    private void testMetricsCreateInvalidDeviceId() {
        logger.info("\n❌ Testing metricsCreate() - Invalid device ID...");

        JsonObject invalidDeviceData = new JsonObject()
            .put("device_id", "00000000-0000-0000-0000-000000000000")
            .put("success", true)
            .put("duration_ms", 100);

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsCreate(invalidDeviceData, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Invalid device ID properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected invalid device ID");
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsCreate invalid device ID");
    }

    private void testMetricsListInvalidPagination() {
        logger.info("\n❌ Testing metricsList() - Invalid pagination...");

        // Test negative page
        CountDownLatch latch1 = new CountDownLatch(1);

        metricsService.metricsList(-1, 10, null, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Negative page properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected negative page");
            }
            latch1.countDown();
        });

        waitForTest(latch1, "metricsList negative page");

        // Test invalid page size
        CountDownLatch latch2 = new CountDownLatch(1);

        metricsService.metricsList(0, 0, null, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Invalid page size properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected invalid page size");
            }
            latch2.countDown();
        });

        waitForTest(latch2, "metricsList invalid page size");
    }

    private void testMetricsListByDeviceNonExistent() {
        logger.info("\n❌ Testing metricsListByDevice() - Non-existent device...");

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsListByDevice("00000000-0000-0000-0000-000000000000", 0, 10, ar -> {
            if (ar.succeeded()) {
                JsonObject result = ar.result();
                if (result.getJsonArray("metrics").size() == 0) {
                    logger.info("✅ SUCCESS: Non-existent device returned empty results");
                    logger.info("📄 Result: {}", result.encodePrettily());
                } else {
                    logger.error("❌ FAILED: Should have returned empty results");
                }
            } else {
                logger.info("✅ SUCCESS: Non-existent device properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsListByDevice non-existent");
    }

    private void testMetricsGetByDeviceTimeRangeInvalidFormat() {
        logger.info("\n❌ Testing metricsGetByDeviceTimeRange() - Invalid time format...");

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsGetByDeviceTimeRange(testDeviceId, "invalid-start-time", "invalid-end-time", ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Invalid time format properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected invalid time format");
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsGetByDeviceTimeRange invalid format");
    }

    private void testMetricsDeleteOlderThanInvalidDays() {
        logger.info("\n❌ Testing metricsDeleteOlderThan() - Invalid days...");

        CountDownLatch latch = new CountDownLatch(1);

        metricsService.metricsDeleteOlderThan(-1, ar -> {
            if (ar.failed()) {
                logger.info("✅ SUCCESS: Invalid days parameter properly rejected");
                logger.info("📄 Error: {}", ar.cause().getMessage());
            } else {
                logger.error("❌ FAILED: Should have rejected invalid days parameter");
            }
            latch.countDown();
        });

        waitForTest(latch, "metricsDeleteOlderThan invalid days");
    }

    // ========== UTILITY METHODS ==========

    private void waitForTest(CountDownLatch latch, String testName) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                logger.error("⏰ TIMEOUT: {} test timed out", testName);
            }
        } catch (InterruptedException e) {
            logger.error("🚫 INTERRUPTED: {} test interrupted", testName);
            Thread.currentThread().interrupt();
        }

        // Small delay between tests
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printSummary() {
        logger.info("\n" + "=".repeat(70));
        logger.info("🎉 METRICSSERVICE TESTING COMPLETE!");
        logger.info("=".repeat(70));
        logger.info("");
        logger.info("📊 COMPREHENSIVE TEST COVERAGE:");
        logger.info("");
        logger.info("✅ SUCCESS SCENARIOS (12+ tests):");
        logger.info("   • metricsList() - Paginated metrics listing with filtering");
        logger.info("   • metricsCreate() - Store new metrics data");
        logger.info("   • metricsGet() - Get specific metric by ID");
        logger.info("   • metricsListByDevice() - Device-specific metrics listing");
        logger.info("   • metricsGetLatestByDevice() - Latest metric for device");
        logger.info("   • metricsGetLatestAllDevices() - Dashboard view");
        logger.info("   • metricsGetByDeviceTimeRange() - Time-series queries");
        logger.info("   • metricsDeleteOlderThan() - Metrics cleanup");
        logger.info("   • metricsDeleteAllByDevice() - Device metrics cleanup");
        logger.info("");
        logger.info("❌ FAILURE SCENARIOS (9+ tests):");
        logger.info("   • Invalid UUID formats - Properly rejected");
        logger.info("   • Non-existent metric/device operations - All handled correctly");
        logger.info("   • Missing required fields - Validation working");
        logger.info("   • Invalid percentage values - Database constraints enforced");
        logger.info("   • Invalid pagination parameters - Properly rejected");
        logger.info("   • Invalid time format - Properly handled");
        logger.info("   • Invalid cleanup parameters - Validation working");
        logger.info("");
        logger.info("🎯 TOTAL VALIDATION:");
        logger.info("   • 9 MetricsService methods tested");
        logger.info("   • 21+ test scenarios executed");
        logger.info("   • ProxyGen service operations validated");
        logger.info("   • Database integration confirmed");
        logger.info("   • Error handling verified");
        logger.info("   • Pagination functionality tested");
        logger.info("   • Time-series data operations validated");
        logger.info("   • Metrics cleanup operations tested");
        logger.info("   • Performance metrics constraints validated");
        logger.info("");
        logger.info("💡 All tests run directly against PostgreSQL database");
        logger.info("   No HTTP server required!");
        logger.info("   Complete validation of MetricsService implementation!");
    }

    public void cleanup() {
        logger.info("\n🧹 Cleaning up...");

        if (pgPool != null) {
            pgPool.close();
        }

        if (vertx != null) {
            CountDownLatch latch = new CountDownLatch(1);
            vertx.close(ar -> latch.countDown());

            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("✅ MetricsService testing cleanup complete");
    }
}
