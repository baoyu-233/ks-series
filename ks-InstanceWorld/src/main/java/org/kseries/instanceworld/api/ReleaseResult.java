package org.kseries.instanceworld.api;

public record ReleaseResult(String instanceId, boolean released, boolean alreadyReleased, String message) { }
