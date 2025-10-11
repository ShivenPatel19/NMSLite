package com.nmslite.core;

import ch.qos.logback.classic.Level;

import ch.qos.logback.classic.Logger;

import ch.qos.logback.classic.LoggerContext;

import io.vertx.core.json.JsonObject;

import org.slf4j.LoggerFactory;

/**
 * LoggingConfigurator - Configures application logging based on application.conf

 * Features:
 * - Enable/disable logging globally
 * - Set log level (TRACE, DEBUG, INFO, WARN, ERROR)
 * - Enable/disable file logging
 * - Enable/disable console logging
 * - Configure log file path

 * Configuration in application.conf:
 * logging {
 *   enabled = true                    # Enable/disable all logging
 *   level = "INFO"                    # Log level
 *   file.path = "logs/nmslite.log"   # Log file path
 *   file.enabled = true               # Enable file logging
 *   console.enabled = true            # Enable console logging
 * }
 */
public class LoggingConfigurator
{

    /**
     * Configure logging based on application configuration
     *
     * @param config Application configuration JsonObject
     */
    public static void configure(JsonObject config)
    {
        var loggingConfig = config.getJsonObject("logging", new JsonObject());

        var loggingEnabled = loggingConfig.getBoolean("enabled", true);

        var logLevel = loggingConfig.getString("level", "INFO");

        var fileEnabled = loggingConfig.getBoolean("file.enabled", true);

        var consoleEnabled = loggingConfig.getBoolean("console.enabled", true);

        var filePath = loggingConfig.getString("file.path", "logs/nmslite.log");

        // Set system properties for logback.xml
        System.setProperty("nmslite.log.level", loggingEnabled ? logLevel : "OFF");

        System.setProperty("nmslite.log.file.path", filePath);

        // Configure appends based on enabled flags
        if (!consoleEnabled)
        {
            System.setProperty("nmslite.log.console.appender", "NULL");
        }
        else
        {
            System.setProperty("nmslite.log.console.appender", "CONSOLE");
        }

        if (!fileEnabled)
        {
            System.setProperty("nmslite.log.file.appender", "NULL");
        }
        else
        {
            System.setProperty("nmslite.log.file.appender", "FILE");
        }

        // Programmatically configure logback
        var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Set root logger level
        var rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);

        if (loggingEnabled)
        {
            rootLogger.setLevel(Level.toLevel(logLevel, Level.INFO));
        }
        else
        {
            rootLogger.setLevel(Level.OFF);
        }

        // Set application logger level
        var appLogger = loggerContext.getLogger("com.nmslite");

        if (loggingEnabled)
        {
            appLogger.setLevel(Level.toLevel(logLevel, Level.INFO));
        }
        else
        {
            appLogger.setLevel(Level.OFF);
        }

        // Create logs directory if file logging is enabled
        if (fileEnabled && loggingEnabled)
        {
            var logFile = new java.io.File(filePath);

            var logDir = logFile.getParentFile();

            if (logDir != null && !logDir.exists())
            {
                logDir.mkdirs();
            }
        }
    }

}

