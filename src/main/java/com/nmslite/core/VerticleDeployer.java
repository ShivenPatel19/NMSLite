package com.nmslite.core;

import com.nmslite.Bootstrap;

import com.nmslite.verticles.AvailabilityVerticle;

import com.nmslite.verticles.DiscoveryVerticle;

import com.nmslite.verticles.PollingMetricsVerticle;

import com.nmslite.verticles.ServerVerticle;

import io.vertx.core.AbstractVerticle;

import io.vertx.core.Future;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import java.util.Collections;

import java.util.List;

/**
 * VerticleDeployer - Centralized Verticle Deployment Manager
 *
 * <p>Deploys all application verticles in sequence and tracks deployment IDs for cleanup.
 * Verticles are deployed in the order defined in the constructor.</p>
 */
public class VerticleDeployer
{

    private static final Logger logger = LoggerFactory.getLogger(VerticleDeployer.class);

    private final List<String> deployedVerticleIds;

    private final List<AbstractVerticle> verticles;

    /**
     * Creates a new VerticleDeployer instance.
     *
     * <p>IMPORTANT: AvailabilityVerticle MUST be deployed BEFORE PollingMetricsVerticle
     * because it creates the shared "availability-cache" LocalMap that PollingMetricsVerticle reads.</p>
     */
    public VerticleDeployer()
    {
        this.deployedVerticleIds = new ArrayList<>();

        // Define all verticles to deploy in order
        // CRITICAL: AvailabilityVerticle MUST come before PollingMetricsVerticle (shared cache dependency)
        this.verticles = List.of(
            new ServerVerticle(),
            new AvailabilityVerticle(),        // Creates "availability-cache" LocalMap
            new PollingMetricsVerticle(),      // Reads from "availability-cache" LocalMap
            new DiscoveryVerticle()
        );
    }

    /**
     * Deploys all verticles in sequence.

     * Note: Database services are already initialized before this method is called.
     *
     * @return Future containing list of deployment IDs (for cleanup)
     */
    public Future<Void> deployAll()
    {
        try
        {
            logger.info("Starting deployment of {} verticles", verticles.size());

            return deployVerticlesSequentially(0)
                .onSuccess(v -> logger.info("All {} verticles deployed successfully", deployedVerticleIds.size()))
                .onFailure(cause -> logger.error("Failed to deploy verticles: {}", cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in deployAll: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Recursively deploys verticles sequentially from the verticles list.
     * This ensures verticles are deployed in order, one after another.
     *
     * @param index Current index in the verticles list
     * @return Future that completes when all remaining verticles are deployed
     */
    private Future<Void> deployVerticlesSequentially(int index)
    {
        try
        {
            // Base case: all verticles deployed
            if (index >= verticles.size())
            {
                return Future.succeededFuture();
            }

            var verticle = verticles.get(index);

            // Deploy current verticle, then recursively deploy the next one
            return deploySingleVerticle(verticle)
                .compose(deploymentId -> deployVerticlesSequentially(index + 1));
        }
        catch (Exception exception)
        {
            logger.error("Error in deployVerticlesSequentially at index {}: {}", index, exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Deploys a single verticle.
     * This is the ONLY method that contains actual deployment logic.
     * Note: Verticles access configuration via Bootstrap.getConfig() directly, so no config is passed via DeploymentOptions.
     *
     * @param verticle The verticle instance to deploy
     * @return Future containing deployment ID
     */
    private Future<String> deploySingleVerticle(AbstractVerticle verticle)
    {
        try
        {
            var vertx = Bootstrap.getVertx();

            return vertx.deployVerticle(verticle)
                .onSuccess(deploymentId ->
                {
                    deployedVerticleIds.add(deploymentId);

                    logger.info("{} deployed: {}", verticle.getClass().getSimpleName(), deploymentId);
                })
                .onFailure(cause -> logger.error("Failed to deploy {}: {}",
                        verticle.getClass().getSimpleName(), cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error deploying {}: {}", verticle.getClass().getSimpleName(), exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Undeploy all deployed verticles in REVERSE order (last deployed, first undeployed).
     * This ensures proper cleanup sequence - verticles are undeployed in the opposite order they were deployed.
     * Uses Direct Future pattern - delegates to undeployVerticlesSequentially().
     *
     * @return Future that completes when all verticles are undeployed
     */
    public Future<Void> undeployAll()
    {
        try
        {
            if (deployedVerticleIds.isEmpty())
            {
                logger.debug("No verticles to undeploy");

                return Future.succeededFuture();
            }

            logger.info("Undeploying {} verticles in reverse order", deployedVerticleIds.size());

            // Undeploy in reverse order (last deployed, first undeployed)
            var reversedIds = new ArrayList<>(deployedVerticleIds);

            Collections.reverse(reversedIds);

            // Chain undeploy operations sequentially to maintain order
            return undeployVerticlesSequentially(reversedIds, 0)
                    .onSuccess(v -> logger.info("All verticles undeployed successfully"))
                    .onFailure(cause -> logger.error("Failed to undeploy all verticles: {}", cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error in undeployAll: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Recursively undeploy verticles sequentially from the reversed deployment IDs list.
     * This ensures verticles are undeployed in reverse order, one after another.
     * Mirrors the deployVerticlesSequentially() pattern for consistency.
     *
     * @param reversedIds List of deployment IDs in reverse order
     * @param index Current index in the reversedIds list
     * @return Future that completes when all remaining verticles are undeployed
     */
    private Future<Void> undeployVerticlesSequentially(List<String> reversedIds, int index)
    {
        try
        {
            // Base case: all verticles undeployed
            if (index >= reversedIds.size())
            {
                return Future.succeededFuture();
            }

            var deploymentId = reversedIds.get(index);

            // Undeploy current verticle, then recursively undeploy the next one
            return undeploySingleVerticle(deploymentId)
                    .compose(v -> undeployVerticlesSequentially(reversedIds, index + 1));
        }
        catch (Exception exception)
        {
            logger.error("Error in undeployVerticlesSequentially at index {}: {}", index, exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Undeploy a single verticle by its deployment ID.
     * This is the ONLY method that contains actual undeployment logic.
     * Mirrors the deploySingleVerticle() pattern for consistency.
     *
     * @param deploymentId The deployment ID of the verticle to undeploy
     * @return Future that completes when the verticle is undeployed
     */
    private Future<Void> undeploySingleVerticle(String deploymentId)
    {
        try
        {
            logger.debug("Undeploying verticle: {}", deploymentId);

            var vertx = Bootstrap.getVertx();

            return vertx.undeploy(deploymentId)
                    .onSuccess(v -> logger.debug("Verticle undeployed: {}", deploymentId))
                    .onFailure(cause -> logger.error("Failed to undeploy verticle {}: {}", deploymentId, cause.getMessage()));
        }
        catch (Exception exception)
        {
            logger.error("Error undeploying verticle {}: {}", deploymentId, exception.getMessage());

            return Future.failedFuture(exception);
        }
    }
    
}

