/**
 * NMSLite Database Services Package
 *
 * This package contains all database service interfaces and implementations
 * for the NMSLite Network Management System.
 *
 * Active Services:
 * - UserService - User management and authentication
 * - DeviceTypeService - Device type management
 * - CredentialService - Credential profile management
 * - DiscoveryService - Discovery profile management
 * - DeviceService - Device CRUD and monitoring
 *
 * Disabled Services (commented out):
 * - MetricsService - Time-series metrics data (polling disabled)
 * - AvailabilityService - Device availability tracking (polling disabled)
 *
 * All services use Vert.x ProxyGen for type-safe event bus communication.
 */
@ModuleGen(name = "nmslite-services", groupPackage = "com.nmslite.services")
package com.nmslite.services;

import io.vertx.codegen.annotations.ModuleGen;
