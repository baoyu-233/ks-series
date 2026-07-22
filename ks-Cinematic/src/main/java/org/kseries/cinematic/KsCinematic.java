package org.kseries.cinematic;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.instanceworld.api.InstanceWorldApi;

public final class KsCinematic extends JavaPlugin {
    private CinematicService service;
    @Override public void onEnable() {
        saveDefaultConfig();
        service = new CinematicService(this);
        service.reload();
        instanceWorld().registerSchematicRoot("ks-cinematic", service.root());
        PluginCommand command = getCommand("cinematic");
        if (command == null) throw new IllegalStateException("cinematic command missing");
        CinematicCommand handler = new CinematicCommand(service);
        command.setExecutor(handler); command.setTabCompleter(handler);
        getServer().getPluginManager().registerEvents(service, this);
    }
    @Override public void onDisable() {
        if (service != null) service.shutdown();
        RegisteredServiceProvider<InstanceWorldApi> registration = getServer().getServicesManager().getRegistration(InstanceWorldApi.class);
        if (registration != null) registration.getProvider().unregisterSchematicRoot("ks-cinematic");
    }

    private InstanceWorldApi instanceWorld() {
        RegisteredServiceProvider<InstanceWorldApi> registration = getServer().getServicesManager().getRegistration(InstanceWorldApi.class);
        if (registration == null) throw new IllegalStateException("ks-InstanceWorld service missing");
        return registration.getProvider();
    }
}
