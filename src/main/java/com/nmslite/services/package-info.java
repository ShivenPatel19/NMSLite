/**
 * NMSLite Database Services Package

 * This package contains all database service interfaces and implementations
 * for the NMSLite Network Management System.

 * Services:
 * - UserService - User management and authentication
 * - DeviceTypeService - Device type management
 * - CredentialProfileService - Credential profile management
 * - DiscoveryProfileService - Discovery profile management
 * - DeviceService - Device CRUD and monitoring
 * - MetricsService - Time-series metrics data collection
 * - AvailabilityService - Device availability tracking

 * All services use Vert.x ProxyGen for type-safe event bus communication.
 */
@ModuleGen(name = "nmslite-services", groupPackage = "com.nmslite.services")
package com.nmslite.services;

import io.vertx.codegen.annotations.ModuleGen;
