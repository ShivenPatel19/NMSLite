package com.nmslite.models;

/**
 * Polling result enum for tracking device state during polling cycle

 * State Machine:
 * NOT_PROCESSED → CONNECTIVITY_FAILED (fping/port check failed)
 *               → SUCCESS (metrics collected successfully)
 *               → GOENGINE_FAILED (reachable but GoEngine failed)

 * Usage:
 * - Reset to NOT_PROCESSED at start of each polling cycle
 * - Mark as CONNECTIVITY_FAILED if device unreachable
 * - Mark as SUCCESS if metrics collected successfully
 * - Mark as GOENGINE_FAILED if GoEngine execution failed
 */
public enum PollingResult
{

    NOT_PROCESSED,           // Initial state - not yet processed

    CONNECTIVITY_FAILED,     // Failed fping or port check

    GOENGINE_FAILED,         // Reachable but GoEngine metrics collection failed

    SUCCESS                  // Successfully collected metrics

}

