package org.kseries.instanceworld;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.instanceworld.api.InstanceWorldApi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class KsInstanceWorld extends JavaPlugin {
    private ExecutorService workers;
    private InstanceWorldService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int threadCount = Math.max(1, Math.min(4, getConfig().getInt("worker.threads", 2)));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "ks-instance-world-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        workers = Executors.newFixedThreadPool(threadCount, factory);
        service = new InstanceWorldService(this, workers);
        Bukkit.getServicesManager().register(InstanceWorldApi.class, service, this, ServicePriority.Normal);
        service.initialize();
        getLogger().info("ks-InstanceWorld enabled; storage initialization is running asynchronously.");
    }

    @Override
    public void onDisable() {
        if (service != null) service.shutdown();
        Bukkit.getServicesManager().unregisterAll(this);
        if (workers != null) workers.shutdown();
    }
}
