package org.kseco.extra.realestate;

import org.bukkit.Bukkit;
import org.kseco.KsEco;
import org.kseco.extra.KsEcoExtraModule;
import org.kseco.extra.realestate.gui.PlotListMenu;
import org.kseco.extra.realestate.gui.PlotTrustMenu;

public final class RealEstateExtra implements KsEcoExtraModule {

    private KsEco eco;
    private RealEstateManager realEstateManager;
    private LandPerkManager landPerkManager;

    @Override
    public String getId() { return "ks-eco-realestate"; }

    @Override
    public String getName() { return "土地与房地产系统"; }

    @Override
    public void onLoad(KsEco eco) {
        this.eco = eco;
        this.realEstateManager = new RealEstateManager(eco);
        this.landPerkManager = new LandPerkManager(eco, realEstateManager);
        eco.getLogger().info("[房地产] 模块已加载");
    }

    @Override
    public void onEnable() {
        realEstateManager.init();
        landPerkManager.init();
        landPerkManager.startCropGrowthTask();

        Bukkit.getPluginManager().registerEvents(new PlotProtectionListener(eco, realEstateManager), eco);
        Bukkit.getPluginManager().registerEvents(new LandPerkListener(landPerkManager), eco);
        Bukkit.getPluginManager().registerEvents(new PlotListMenu.Listener(eco, realEstateManager), eco);
        Bukkit.getPluginManager().registerEvents(new PlotTrustMenu.Listener(eco, realEstateManager), eco);
        Bukkit.getPluginManager().registerEvents(new PlotTrustMenu.ChatListener(eco, realEstateManager), eco);

        try {
            var cmd = eco.getCommand("land");
            if (cmd != null) cmd.setExecutor(new LandCommand(eco, realEstateManager, landPerkManager));
            else eco.getLogger().warning("[房地产] 未找到 land 命令定义");
        } catch (Exception e) {
            eco.getLogger().warning("[房地产] 注册 land 命令失败: " + e.getMessage());
        }

        HouseWandListener wand = new HouseWandListener(eco, realEstateManager);
        Bukkit.getPluginManager().registerEvents(wand, eco);
        try {
            var cmd = eco.getCommand("house");
            if (cmd != null) cmd.setExecutor(new HouseCommand(eco, realEstateManager, wand));
            else eco.getLogger().warning("[房地产] 未找到 house 命令定义");
        } catch (Exception e) {
            eco.getLogger().warning("[房地产] 注册 house 命令失败: " + e.getMessage());
        }
        eco.getLogger().info("[房地产] 模块已启用");
    }

    @Override
    public void onDisable() {
        if (landPerkManager != null) landPerkManager.stopCropGrowthTask();
        eco.getLogger().info("[房地产] 模块已停用");
    }

    public RealEstateManager realEstateManager() { return realEstateManager; }
    public LandPerkManager landPerkManager() { return landPerkManager; }
}
