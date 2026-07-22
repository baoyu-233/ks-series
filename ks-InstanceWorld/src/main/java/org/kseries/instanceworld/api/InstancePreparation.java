package org.kseries.instanceworld.api;

import java.util.concurrent.CompletableFuture;

public record InstancePreparation(String instanceId, CompletableFuture<PreparedInstance> ready) { }
