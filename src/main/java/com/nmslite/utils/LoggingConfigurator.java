package com.nmslite.utils;

import ch.qos.logback.classic.Level;

import ch.qos.logback.classic.Logger;

import ch.qos.logback.classic.LoggerContext;

import io.vertx.core.json.JsonObject;

import org.slf4j.LoggerFactory;

/**
 * LoggingConfigurator - Configures application logging based on application.conf
 *
 * Features:
 * - Enable/disable logging globally
 * - Set log level (TRACE, DEBUG, INFO, WARN, ERROR, OFF)
 * - Enable/disable file logging
 * - Enable/disable console logging
 * - Configure log file path
 *
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
        JsonObject loggingConfig = config.getJsonObject("logging", new JsonObject());

        // Check if logging is enabled
        boolean loggingEnabled = loggingConfig.getBoolean("enabled", true);

        String logLevel = loggingConfig.getString("level", "INFO");

        boolean fileEnabled = loggingConfig.getBoolean("file.enabled", true);

        boolean consoleEnabled = loggingConfig.getBoolean("console.enabled", true);

        String filePath = loggingConfig.getString("file.path", "logs/nmslite.log");

        // Set system properties for logback.xml
        System.setProperty("nmslite.log.level", loggingEnabled ? logLevel : "OFF");

        System.setProperty("nmslite.log.file.path", filePath);

        // Configure appenders based on enabled flags
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
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Set root logger level
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);

        if (loggingEnabled)
        {
            rootLogger.setLevel(Level.toLevel(logLevel, Level.INFO));
        }
        else
        {
            rootLogger.setLevel(Level.OFF);
        }

        // Set application logger level
        Logger appLogger = loggerContext.getLogger("com.nmslite");

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
            java.io.File logFile = new java.io.File(filePath);

            java.io.File logDir = logFile.getParentFile();

            if (logDir != null && !logDir.exists())
            {
                logDir.mkdirs();
            }
        }

        // Log configuration summary
        if (loggingEnabled)
        {
            org.slf4j.Logger logger = LoggerFactory.getLogger(LoggingConfigurator.class);

            logger.info("=".repeat(60));

            logger.info("NMSLite Logging Configuration");

            logger.info("=".repeat(60));

            logger.info("Logging Enabled: {}", loggingEnabled);

            logger.info("Log Level: {}", logLevel);

            logger.info("Console Logging: {}", consoleEnabled);

            logger.info("File Logging: {}", fileEnabled);

            if (fileEnabled)
            {
                logger.info("Log File Path: {}", filePath);
            }

            logger.info("=".repeat(60));
        }
    }

    /**
     * Create a NULL appender for disabling specific appenders
     *
     * @param loggerContext logger context
     */
    private static void createNullAppender(LoggerContext loggerContext)
    {
        ch.qos.logback.core.helpers.NOPAppender<ch.qos.logback.classic.spi.ILoggingEvent> nullAppender =
            new ch.qos.logback.core.helpers.NOPAppender<>();

        nullAppender.setName("NULL");

        nullAppender.setContext(loggerContext);

        nullAppender.start();
    }

}

