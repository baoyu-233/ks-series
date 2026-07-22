package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.kscore.KsCore;
import org.kscore.KsPluginBridge;
import org.kseco.crossserver.CrossServerRuntimeGate;
import org.kseco.crossserver.cache.CacheInvalidationWireAdapter;
import org.kseco.crossserver.cache.CrossServerCacheInvalidationCoordinator;
import org.kseco.crossserver.runtime.CrossServerRuntime;
import org.kseco.crossserver.JdbcCrossServerRepository;
import org.kseco.crossserver.lock.ConnectionFactoryLeaseSqlExecutor;
import org.kseco.crossserver.lock.FencedExecutionResult;
import org.kseco.crossserver.lock.JdbcDistributedLeaseLock;
import org.kseco.crossserver.lock.LeaseAcquireResult;
import org.kseco.crossserver.sql.SqlDialect;
import org.kseco.crossserver.transport.DatabasePollingTransport;
import org.kseco.crossserver.transport.JdbcDatabaseTransportStore;
import org.kseco.crossserver.transport.PollBackoffPolicy;
import org.kseco.crossserver.assets.AssetSource;
import org.kseco.crossserver.assets.FederatedAssetRepository;
import org.kseco.crossserver.assets.FederatedAssetService;
import org.kseco.crossserver.assets.FederatedAssetSettings;
import org.kseco.crossserver.assets.FederatedAssetSettingsManager;
import org.kseco.crossserver.assets.FederatedSnapshot;
import org.kseco.crossserver.assets.FederatedSnapshotPublisher;
import org.kseco.crossserver.assets.JdbcFederatedAssetRepository;
import org.kseco.database.CentralBankBootstrap;
import org.kseco.database.CoreBusinessSchema;
import org.kseco.database.EcoDatabase;
import org.kseco.demand.JdbcDemandCampaignStore;
import org.kseco.extra.ExtraModuleLoader;
import org.kseco.extra.BankAccessProvider;
import org.kseco.extra.EnterpriseAccessProvider;
import org.kseco.extra.EnterpriseFundSettlementProvider;
import org.kseco.gui.BankGui;
import org.kseco.gui.BiddingGui;
import org.kseco.gui.BlindBoxGui;
import org.kseco.gui.CompensationGui;
import org.kseco.gui.EcoGuiMainMenu;
import org.kseco.gui.EntBlindBoxGui;
import org.kseco.gui.EnterpriseGui;
import org.kseco.gui.ExchangeGui;
import org.kseco.gui.InvitesGui;
import org.kseco.gui.ListingPreviewMenu;
import org.kseco.gui.LimitedSaleAdminGui;
import org.kseco.gui.LimitedSaleGui;
import org.kseco.gui.MarketMenu;
import org.kseco.gui.OfficialBuyAdminGui;
import org.kseco.gui.OfficialWarehouseGui;
import org.kseco.gui.PoliticGui;
import org.kseco.gui.PriceInputMenu;
import org.kseco.gui.PurchaseOrderMenu;
import org.kseco.gui.RealEstateGui;
import org.kseco.gui.StorageMenu;
import org.kseco.gui.TaxGui;
import org.kseco.gui.DeliveryMenu;
import org.kseco.gui.TransferGui;
import org.kseco.gui.GuiSafetyListener;
import org.kseco.scheduler.EcoScheduler;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * ks-Eco 经济核心主插件。
 * 依赖 ks-core 提供 Web 网关和 Token 鉴权。
 * 可选依赖 Vault 提供货币对接。
 */
public final class KsEco extends JavaPlugin {

    private static final boolean CROSS_SERVER_MUTATION_WIRING_COMPLETE = true;

    private KsCore ksCore;
    private KsPluginBridge bridge;
    private EcoDatabase ecoDatabase;
    private JdbcDemandCampaignStore demandCampaignStore;
    private volatile boolean demandCampaignsEnabled;
    private EcoConfig ecoConfig;
    private FeatureGateManager featureGate;
    private VaultHook vaultHook;
    private MarketManager marketManager;
    private PriceEngine priceEngine;
    private MarketValueService marketValueService;
    private ShulkerBoxParser shulkerBoxParser;
    private StorageManager storageManager;
    private ListingManager listingManager;
    private PurchaseOrderManager purchaseOrderManager;
    private OfficialBuyManager officialBuyManager;
    private OfficialWarehouseManager officialWarehouseManager;
    private OfficialWarehouseLiquidationManager officialWarehouseLiquidationManager;
    private OfficialMarketSweepManager officialMarketSweepManager;
    private TradeManager tradeManager;
    private TransportManager transportManager;
    private TransferManager transferManager;
    private EnterpriseLevelManager enterpriseLevelManager;
    private AsyncWorkPool asyncWorkPool;
    private BlindBoxManager blindBoxManager;
    private LimitedSaleManager limitedSaleManager;
    private CompensationManager compensationManager;
    private ExchangeManager exchangeManager;
    private EconomyResetManager economyResetManager;
    private MajorOrderManager majorOrderManager;
    private ExtraModuleLoader extraModuleLoader;
    private volatile BankAccessProvider bankAccessProvider;
    private volatile EnterpriseAccessProvider enterpriseAccessProvider;
    private volatile EnterpriseFundSettlementProvider enterpriseFundSettlementProvider;
    private EcoWebHandler webHandler;
    private BuiltinEconomy builtinEconomy;
    private BanManager banManager;
    private EcoScheduler scheduler;
    private EcoScheduler.TaskHandle priceRefreshTask;
    private EcoScheduler.TaskHandle pendingCreationExpiryTask;
    private EcoScheduler.TaskHandle officialSweepTask;
    private EcoScheduler.TaskHandle databaseHeartbeatTask;
    private EcoScheduler.TaskHandle crossServerCleanupTask;
    private EcoScheduler.TaskHandle moneySupplySnapshotTask;
    private EcoScheduler.TaskHandle volatilityReportTask;
    private CrossServerRuntime crossServerRuntime;
    private JdbcDistributedLeaseLock crossServerLeaseLock;
    private FederatedAssetSettingsManager federatedAssetSettings;
    private JdbcFederatedAssetRepository federatedAssetRepository;
    private FederatedAssetService federatedAssetService;
    private FederatedSnapshotPublisher federatedSnapshotPublisher;
    private CompletableFuture<Void> federatedAssetReady = CompletableFuture.failedFuture(
            new IllegalStateException("Federated asset runtime has not started"));
    private volatile Throwable federatedAssetFailure;
    private EcoScheduler.TaskHandle federatedAssetHeartbeatTask;
    private final Object priceRefreshMonitor = new Object();
    private volatile boolean shuttingDown;
    private int activePriceRefreshes;

    // GUI 监听器引用（用于注册/注销）
    private MarketMenu.Listener marketListener;
    private StorageMenu.Listener storageListener;
    private DeliveryMenu.Listener deliveryListener;
    private ListingPreviewMenu.Listener previewListener;
    private PriceInputMenu.Listener priceInputListener;
    private ExchangeGui.Listener exchangeListener;
    private ExchangeGui.DropListener exchangeDropListener;
    private ExchangeGui.ChatListener exchangeChatListener;
    private org.kseco.gui.SellAllMenu.Listener sellAllListener;

    // /eco gui 统一入口 - 新 GUI 监听器
    private EcoGuiMainMenu.Listener ecoGuiMainListener;
    private BankGui.Listener bankListener;
    private BlindBoxGui.Listener blindBoxListener;
    private LimitedSaleGui.Listener limitedSaleListener;
    private EntBlindBoxGui.Listener entBlindBoxListener;
    private EnterpriseGui.Listener enterpriseListener;
    private BiddingGui.Listener biddingListener;
    private InvitesGui.Listener invitesListener;
    private TaxGui.Listener taxListener;
    private PoliticGui.Listener politicListener;
    private RealEstateGui.Listener realEstateListener;
    // ChatListeners
    private BankGui.ChatListener bankChatListener;
    private EnterpriseGui.ChatListener enterpriseChatListener;
    private BiddingGui.ChatListener biddingChatListener;

    @Override
    public void onEnable() {
        this.scheduler = new EcoScheduler(this);
        saveDefaultConfig();

        // 检查 ks-core 依赖
        Plugin corePlugin = Bukkit.getPluginManager().getPlugin("ks-core");
        if (corePlugin instanceof KsCore core) {
            this.ksCore = core;
            this.bridge = core.bridge();
        } else {
            getLogger().severe("ks-core 未找到！ks-Eco 需要 ks-core 才能运行。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 初始化配置
        this.ecoConfig = new EcoConfig(this);
        this.featureGate = new FeatureGateManager(this);
        this.federatedAssetSettings = new FederatedAssetSettingsManager();
        try {
            federatedAssetSettings.reload(configurationMap("federated-assets"));
        } catch (RuntimeException failure) {
            getLogger().severe("federated-assets 配置无效: " + failure.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        long heartbeatSeconds = Math.max(5L, Math.min(300L,
                getConfig().getLong("database.heartbeat-interval-seconds", 30L)));
        long staleSeconds = Math.max(heartbeatSeconds * 2L, Math.min(86_400L,
                getConfig().getLong("database.server-stale-after-seconds", 120L)));
        try {
            this.ecoDatabase = new EcoDatabase(
                    getLogger(), () -> ksCore.dataStore().getConnection(),
                    getConfig().getString("database.server-id", "main"),
                    Duration.ofSeconds(heartbeatSeconds), Duration.ofSeconds(staleSeconds));
        } catch (IllegalArgumentException exception) {
            getLogger().severe("ks-Eco 数据库身份配置无效: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!ecoDatabase.initialize()) {
            getLogger().severe("ks-Eco 数据库边界初始化失败，插件将停用以避免跨服重复结算。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        CrossServerRuntimeGate.Decision crossServerDecision = CrossServerRuntimeGate.evaluate(
                getConfig().getBoolean("cross-server.enabled", false),
                ecoDatabase.dialect(),
                CROSS_SERVER_MUTATION_WIRING_COMPLETE);
        if (!crossServerDecision.pluginStartupAllowed()) {
            getLogger().severe("Unsafe cross-server configuration rejected: " + crossServerDecision.message());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            validateFederatedAssetEnvironment(federatedAssetSettings.current());
        } catch (IllegalStateException failure) {
            getLogger().severe("federated-assets 启动条件不满足: " + failure.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        // Business managers, Web recovery and extras assume these shared tables exist.
        // A partial schema is unsafe: fail startup before scheduling work or registering routes.
        if (!runMigrations()) {
            getLogger().severe("ks-Eco 业务数据库迁移失败，插件将停用以避免部分初始化和不完整结算。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        initializeDemandCampaignStore();

        // 对接 Vault
        this.vaultHook = new VaultHook(this);

        // 初始化核心组件
        this.priceEngine = new PriceEngine(this);
        this.marketValueService = new MarketValueService(this);
        this.shulkerBoxParser = new ShulkerBoxParser(this);
        this.storageManager = new StorageManager(this);
        this.listingManager = new ListingManager(this);
        this.purchaseOrderManager = new PurchaseOrderManager(this);
        this.marketManager = new MarketManager(this);
        this.officialBuyManager = new OfficialBuyManager(this, priceEngine);
        this.officialWarehouseManager = new OfficialWarehouseManager(this);
        this.officialWarehouseLiquidationManager = new OfficialWarehouseLiquidationManager(this);
        this.officialMarketSweepManager = new OfficialMarketSweepManager(this);
        this.tradeManager = new TradeManager(this);
        this.asyncWorkPool = new AsyncWorkPool(6, getLogger());
        initializeFederatedAssetRuntime();
        this.transportManager = new TransportManager(this);
        this.transferManager = new TransferManager(this);
        this.enterpriseLevelManager = new EnterpriseLevelManager(this);
        startPendingCreationExpiryTask();
        this.blindBoxManager = new BlindBoxManager(this);
        this.limitedSaleManager = new LimitedSaleManager(this);
        this.compensationManager = new CompensationManager(this);
        this.exchangeManager = new ExchangeManager(this);
        this.economyResetManager = new EconomyResetManager(this);
        this.majorOrderManager = new MajorOrderManager(this);
        this.majorOrderManager.init();
        this.banManager = new BanManager(this);
        startDatabaseHeartbeatTask();

        // 注册命令
        registerCommands();

        // 注册事件监听
        registerListeners();

        // 注册 Web 路由到 ks-core
        this.webHandler = new EcoWebHandler(this);
        if (bridge.isPluginRouteEnabled("ks-eco")) {
            String route = bridge.getPluginRoute("ks-eco");
            bridge.registerRoute("ks-eco", route, webHandler);
        }

        // 启动官方价格刷新任务
        startPriceRefreshTask();
        startOfficialMarketSweepTask();

        // 启动货币供应量快照任务（每5分钟）
        startMoneySupplySnapshotTask();

        // 启动市场波动报告任务
        startMarketVolatilityReportTask();

        // 加载 extra 子模块
        this.extraModuleLoader = new ExtraModuleLoader(this);
        extraModuleLoader.loadModules();

        // 内置经济系统（Vault 无外部经济插件时自动接管）
        this.builtinEconomy = new BuiltinEconomy(this);
        if (!vaultHook.isAvailable()) {
            if (isFoliaRuntime()) builtinEconomy.setupDirect();
            else builtinEconomy.setup();
            vaultHook.refresh(); // 重新检测（内置经济已注册到 Vault）
        }

        // 自动创建中央银行（全局唯一）
        autoCreateCentralBank();

        if (crossServerDecision.runtimeEnabled() && !startCrossServerRuntime()) {
            getLogger().severe("跨服运行时启动失败，插件将停用以避免节点状态分裂。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Vault providers and business tables are ready at this point.
        webHandler.startProjectSettlementRecovery();

        getLogger().info("ks-Eco 已启用。Vault: " +
                (vaultHook.isAvailable() ? "已对接" : (builtinEconomy.isRegistered() ? "内置经济" : "未找到")));
        getLogger().info("市场系统: " + (ecoConfig.isMarketGuiEnabled() ? "GUI 模式" : "Web 模式"));
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (priceRefreshTask != null) {
            priceRefreshTask.cancel();
            priceRefreshTask = null;
        }
        awaitPriceRefreshStop();
        if (crossServerRuntime != null) {
            try {
                crossServerRuntime.stop().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception failure) {
                getLogger().warning("停止跨服运行时超时或失败: " + failure.getMessage());
            }
            crossServerRuntime = null;
            crossServerLeaseLock = null;
        }
        // 卸载 extra 模块
        if (extraModuleLoader != null) {
            extraModuleLoader.disableAll();
        }
        if (pendingCreationExpiryTask != null) {
            pendingCreationExpiryTask.cancel();
        }
        if (officialSweepTask != null) {
            officialSweepTask.cancel();
        }
        if (databaseHeartbeatTask != null) {
            databaseHeartbeatTask.cancel();
        }
        if (crossServerCleanupTask != null) {
            crossServerCleanupTask.cancel();
        }
        if (federatedAssetHeartbeatTask != null) {
            federatedAssetHeartbeatTask.cancel();
            federatedAssetHeartbeatTask = null;
        }
        if (moneySupplySnapshotTask != null) moneySupplySnapshotTask.cancel();
        if (volatilityReportTask != null) volatilityReportTask.cancel();
        if (storageManager != null) {
            storageManager.flushPending();
        }
        if (asyncWorkPool != null) {
            asyncWorkPool.shutdown();
        }
        demandCampaignsEnabled = false;
        if (demandCampaignStore != null) {
            demandCampaignStore.close();
        }
        demandCampaignStore = null;
        if (ecoDatabase != null) {
            ecoDatabase.close();
        }

        // 取消注册内置经济
        if (builtinEconomy != null) {
            builtinEconomy.shutdown();
        }

        // 取消注册 Web 路由
        if (bridge != null) {
            bridge.unregisterRoute("ks-eco");
        }

        getLogger().info("ks-Eco 已停用。");
    }

    private void initializeDemandCampaignStore() {
        JdbcDemandCampaignStore candidate = new JdbcDemandCampaignStore(ecoDatabase::openConnection);
        try {
            candidate.initializeSchema();
            demandCampaignStore = candidate;
            demandCampaignsEnabled = getConfig().getBoolean("demand.enabled", false);
        } catch (SQLException exception) {
            demandCampaignStore = null;
            demandCampaignsEnabled = false;
            getLogger().warning("Demand campaign foundation is unavailable and remains disabled: "
                    + exception.getMessage());
        }
    }

    private void initializeFederatedAssetRuntime() {
        federatedAssetRepository = new JdbcFederatedAssetRepository(ecoDatabase::openConnection);
        federatedAssetService = new FederatedAssetService(federatedAssetRepository, federatedAssetSettings);
        federatedSnapshotPublisher = new FederatedSnapshotPublisher(federatedAssetRepository, federatedAssetSettings,
                asyncWorkPool::executeDatabase, ecoDatabase.instanceId());
        CompletableFuture<Void> ready = new CompletableFuture<>();
        federatedAssetReady = ready;
        try {
            asyncWorkPool.executeDatabase(() -> {
                try {
                    federatedAssetRepository.initialize();
                    federatedAssetFailure = null;
                    ready.complete(null);
                } catch (Throwable failure) {
                    federatedAssetFailure = failure;
                    ready.completeExceptionally(failure);
                    getLogger().warning("跨服资产投影表初始化失败，相关 API 保持关闭: " + failure.getMessage());
                }
            });
        } catch (RejectedExecutionException failure) {
            federatedAssetFailure = failure;
            ready.completeExceptionally(failure);
        }
        startFederatedAssetHeartbeatTask();
    }

    private void startFederatedAssetHeartbeatTask() {
        if (federatedAssetHeartbeatTask != null) federatedAssetHeartbeatTask.cancel();
        long seconds = Math.max(5L, Math.min(60L,
                Math.max(1L, federatedAssetSettings.current().offlineAfterMillis() / 3_000L)));
        federatedAssetHeartbeatTask = scheduler.runAsyncTimer(() -> {
            if (!federatedAssetSettings.current().enabled() || federatedAssetRepository == null) return;
            submitFederatedDatabase(() -> {
                federatedAssetRepository.heartbeat(ecoDatabase.serverId(), ecoDatabase.instanceId(),
                        System.currentTimeMillis());
                return null;
            }).exceptionally(failure -> {
                federatedAssetFailure = unwrapCompletionFailure(failure);
                return null;
            });
        }, Duration.ofSeconds(1), Duration.ofSeconds(seconds));
    }

    private void validateFederatedAssetEnvironment(FederatedAssetSettings settings) {
        if (!settings.enabled()) return;
        if (!getConfig().getBoolean("cross-server.enabled", false)) {
            throw new IllegalStateException("启用投影前必须先启用 restart-only 的 cross-server.enabled 并重启");
        }
        switch (ecoDatabase.dialect()) {
            case MYSQL, MARIADB, POSTGRESQL -> { }
            default -> throw new IllegalStateException("只允许共享 MySQL/MariaDB/PostgreSQL，禁止 SQLite/local fallback");
        }
    }

    private Map<String, Object> configurationMap(String path) {
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        return section == null ? Map.of() : configurationMap(section);
    }

    private static Map<String, Object> configurationMap(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) result.put(key, configurationMap(nested));
            else if (value instanceof List<?> list) result.put(key, List.copyOf(list));
            else if (value != null) result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static void writeConfigurationMap(ConfigurationSection section, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                ConfigurationSection child = section.createSection(entry.getKey());
                Map<String, Object> mapped = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nestedEntry : nested.entrySet()) {
                    if (!(nestedEntry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("配置键必须是字符串");
                    }
                    mapped.put(key, nestedEntry.getValue());
                }
                writeConfigurationMap(child, mapped);
            } else {
                section.set(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Validates first, then atomically swaps the active policy. Persistence must run on the global tick lane. */
    public long applyFederatedAssetSettings(Map<String, ?> candidate, boolean persist) {
        FederatedAssetSettings parsed = FederatedAssetSettings.fromMap(candidate);
        validateFederatedAssetEnvironment(parsed);
        if (parsed.enabled() && !federatedAssetSettings.current().enabled()
                && (crossServerRuntime == null || !crossServerRuntime.isHealthy())) {
            throw new IllegalStateException("跨服 transport 尚未运行；请先启用 cross-server.enabled 并完整重启");
        }
        if (persist) {
            if (!scheduler.isGlobalThread()) throw new IllegalStateException("配置持久化必须在 global tick lane");
            Map<String, Object> previous = configurationMap("federated-assets");
            getConfig().set("federated-assets", null);
            writeConfigurationMap(getConfig().createSection("federated-assets"), candidate);
            try {
                saveConfig();
            } catch (RuntimeException failure) {
                getConfig().set("federated-assets", null);
                writeConfigurationMap(getConfig().createSection("federated-assets"), previous);
                throw failure;
            }
        }
        long generation = federatedAssetSettings.reload(candidate);
        if (scheduler.isGlobalThread()) startFederatedAssetHeartbeatTask();
        return generation;
    }

    private <T> CompletableFuture<T> submitFederatedDatabase(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        federatedAssetReady.whenComplete((ignored, startupFailure) -> {
            if (startupFailure != null) {
                result.completeExceptionally(unwrapCompletionFailure(startupFailure));
                return;
            }
            try {
                asyncWorkPool.executeDatabase(() -> {
                    try {
                        result.complete(action.call());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (RejectedExecutionException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    public CompletableFuture<List<FederatedAssetService.AssetView>> queryFederatedAssets(
            FederatedAssetService.Query query, long now) {
        return submitFederatedDatabase(() -> federatedAssetService.query(query, now));
    }

    public CompletableFuture<FederatedAssetService.Aggregate> aggregateFederatedAssets(
            FederatedAssetService.Query query, long now) {
        return submitFederatedDatabase(() -> federatedAssetService.aggregate(query, now));
    }

    public CompletableFuture<List<FederatedAssetRepository.SnapshotHead>> listFederatedSnapshotHeads(
            FederatedSnapshot.Kind kind, long now, boolean includeStale, boolean includeOffline) {
        return submitFederatedDatabase(() -> federatedAssetService.listSnapshotHeads(kind, now,
                includeStale, includeOffline));
    }

    public CompletableFuture<Optional<FederatedSnapshot.ReadResult>> readFederatedSnapshot(
            FederatedSnapshot.Kind kind, AssetSource source, long now, boolean includeStale, boolean includeOffline) {
        return submitFederatedDatabase(() -> federatedAssetService.readSnapshot(kind, source, now,
                includeStale, includeOffline));
    }

    /** Modules publish only immutable local snapshots; the publisher never reads Bukkit World state. */
    public CompletableFuture<FederatedAssetRepository.PublishResult> publishFederatedSnapshot(
            FederatedSnapshotPublisher.PreparedSnapshot snapshot) {
        if (!ecoDatabase.serverId().equals(snapshot.source().nodeId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "只能发布当前 database.server-id 生成的本地快照"));
        }
        org.kseco.crossserver.assets.FederatedCapability capability = switch (snapshot.kind()) {
            case MAP -> org.kseco.crossserver.assets.FederatedCapability.MAP_VIEW;
            case PROPERTY -> org.kseco.crossserver.assets.FederatedCapability.PROPERTY_TRADE;
            case ASSET -> org.kseco.crossserver.assets.FederatedCapability.ASSET_AGGREGATE;
        };
        if (!federatedAssetSettings.current().policy().decide(capability, snapshot.source()).allowed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("当前世界/维度被跨服投影策略拒绝"));
        }
        return federatedAssetReady.thenCompose(ignored -> federatedSnapshotPublisher.publishAsync(snapshot));
    }

    public Map<String, Object> federatedAssetStatus() {
        FederatedAssetSettings current = federatedAssetSettings.current();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", current.enabled());
        result.put("ready", federatedAssetReady.isDone() && !federatedAssetReady.isCompletedExceptionally());
        result.put("generation", federatedAssetSettings.generation());
        result.put("nodeId", ecoDatabase.serverId());
        result.put("failure", federatedAssetFailure == null ? "" : String.valueOf(federatedAssetFailure.getMessage()));
        result.put("restartOnly", List.of("database.server-id", "cross-server.enabled", "database connection/pool"));
        return Map.copyOf(result);
    }

    public FederatedAssetSettings federatedAssetSettings() { return federatedAssetSettings.current(); }

    public Map<String, ?> federatedAssetConfiguration() { return federatedAssetSettings.currentConfiguration(); }

    private boolean startCrossServerRuntime() {
        java.util.concurrent.ScheduledExecutorService scheduler = null;
        try {
            boolean builtinWallet = builtinEconomy != null && builtinEconomy.isRegistered()
                    && "ks-Eco内置经济".equals(vaultHook.getName());
            if (!builtinWallet && !getConfig().getBoolean("cross-server.external-economy-shared", false)) {
                throw new IllegalStateException("外部 Vault 经济未声明为共享数据库模式；"
                        + "确认其跨服一致性后设置 cross-server.external-economy-shared=true");
            }
            SqlDialect dialect = switch (ecoDatabase.dialect()) {
                case MYSQL, MARIADB -> SqlDialect.MYSQL;
                case POSTGRESQL -> SqlDialect.POSTGRESQL;
                default -> throw new IllegalStateException("unsupported shared database: " + ecoDatabase.dialect());
            };
            JdbcDatabaseTransportStore store = new JdbcDatabaseTransportStore(ecoDatabase::openConnection, dialect);
            store.initialize();
            ThreadFactory threads = runnable -> {
                Thread thread = new Thread(runnable, "ks-eco-cross-server-poll");
                thread.setDaemon(true);
                return thread;
            };
            scheduler = Executors.newSingleThreadScheduledExecutor(threads);
            int batchSize = Math.max(1, Math.min(1000,
                    getConfig().getInt("cross-server.poll-batch-size", 128)));
            int maxPayloadBytes = Math.max(1024, Math.min(1_048_576,
                    getConfig().getInt("cross-server.max-payload-bytes", 65_536)));
            var transport = new DatabasePollingTransport(
                    ecoDatabase.serverId(),
                    getConfig().getString("cross-server.consumer-id", "ks-eco-runtime-v1"),
                    store,
                    asyncWorkPool::executeDatabase,
                    scheduler,
                    task -> this.scheduler.runGlobal(task),
                    batchSize,
                    maxPayloadBytes,
                    PollBackoffPolicy.defaults());
            var coordinator = new CrossServerCacheInvalidationCoordinator(ecoDatabase.serverId());
            var leaseLock = new JdbcDistributedLeaseLock(
                    new ConnectionFactoryLeaseSqlExecutor(ecoDatabase::openConnection),
                    new JdbcCrossServerRepository(dialect));
            leaseLock.initializeSchema();
            CrossServerRuntime runtime = new CrossServerRuntime(
                    transport,
                    coordinator,
                    new CacheInvalidationWireAdapter(),
                    scheduler,
                    Duration.ofDays(Math.max(1L, Math.min(30L,
                            getConfig().getLong("cross-server.event-retention-days", 7L)))),
                    failure -> getLogger().warning("跨服事件处理失败，将自动重试: " + failure.getMessage()));
            runtime.subscribeNamespace("enterprise-level", ignored -> enterpriseLevelManager.refreshLevelsAsync());
            runtime.subscribeNamespace("price", ignored -> asyncWorkPool.executeDatabase(() -> {
                int previousInterval = priceEngine.getPriceRefreshMinutes();
                priceEngine.reloadSharedState();
                if (previousInterval != priceEngine.getPriceRefreshMinutes()) {
                    this.scheduler.runGlobal(this::restartPriceRefreshTask);
                }
            }));
            runtime.subscribeNamespace("balance", ignored -> webHandler.invalidatePlayerRankingSnapshot());
            runtime.subscribeNamespace("real-estate", message ->
                    extraModuleLoader.dispatchCrossServerInvalidation(
                            message.namespace(), message.target().key()));
            runtime.subscribeNamespace("politic", message ->
                    extraModuleLoader.dispatchCrossServerInvalidation(
                            message.namespace(), message.target().key()));
            runtime.start();
            crossServerRuntime = runtime;
            crossServerLeaseLock = leaseLock;
            startCrossServerCleanupTask();
            getLogger().info("跨服运行时已启动: server-id=" + ecoDatabase.serverId()
                    + ", consumer=" + getConfig().getString("cross-server.consumer-id", "ks-eco-runtime-v1"));
            return true;
        } catch (Exception failure) {
            if (scheduler != null) scheduler.shutdownNow();
            getLogger().log(java.util.logging.Level.SEVERE, "初始化跨服运行时失败", failure);
            return false;
        }
    }

    public void publishCrossServerInvalidation(String namespace, String key) {
        CrossServerRuntime runtime = crossServerRuntime;
        if (runtime == null || !runtime.isRunning()) return;
        runtime.invalidate(namespace, key).whenComplete((ignored, failure) -> {
            if (failure != null) getLogger().warning("发布跨服缓存失效失败: " + failure.getMessage());
        });
    }

    /** Runs the stochastic price refresh once across the whole database cluster. */
    public void refreshPricesCoordinated() {
        if (scheduler.isGlobalThread()) {
            if (!shuttingDown) asyncWorkPool.executeDatabase(this::refreshPricesCoordinated);
            return;
        }
        synchronized (priceRefreshMonitor) {
            if (shuttingDown) return;
            activePriceRefreshes++;
        }
        try {
            refreshPricesCoordinatedNow();
        } finally {
            synchronized (priceRefreshMonitor) {
                activePriceRefreshes--;
                priceRefreshMonitor.notifyAll();
            }
        }
    }

    private void refreshPricesCoordinatedNow() {
        JdbcDistributedLeaseLock leaseLock = crossServerLeaseLock;
        if (leaseLock == null) {
            priceEngine.refreshAllPrices();
            return;
        }
        String owner = ecoDatabase.serverId() + ":" + ecoDatabase.instanceId();
        Duration duration = Duration.ofSeconds(Math.max(30L, Math.min(900L,
                getConfig().getLong("cross-server.price-refresh-lease-seconds", 180L))));
        try {
            var acquisition = leaseLock.tryAcquire("ks-eco:price-refresh", owner, duration);
            if (!(acquisition instanceof LeaseAcquireResult.Acquired acquired)) return;
            try {
                var execution = leaseLock.executeFenced(acquired.token(),
                        (connection, ignoredToken) -> priceEngine.stageRefresh(connection));
                if (execution instanceof FencedExecutionResult.Executed<PriceEngine.RefreshResult> executed) {
                    priceEngine.applyRefresh(executed.value());
                    publishCrossServerInvalidation("price", "all");
                }
            } finally {
                leaseLock.release(acquired.token());
            }
        } catch (SQLException failure) {
            getLogger().warning("跨服价格刷新失败: " + failure.getMessage());
        }
    }

    /**
     * Runs an idempotent maintenance workflow under one cluster-wide lease.
     * The caller must already be off the Paper server thread.
     */
    public boolean runClusterExclusiveTask(String resourceKey, Duration duration, Runnable task) {
        java.util.Objects.requireNonNull(task, "task");
        if (scheduler.isGlobalThread()) {
            throw new IllegalStateException("cluster maintenance must not run on the server thread");
        }
        JdbcDistributedLeaseLock leaseLock = crossServerLeaseLock;
        if (leaseLock == null) {
            task.run();
            return true;
        }
        String owner = ecoDatabase.serverId() + ":" + ecoDatabase.instanceId();
        try {
            var acquisition = leaseLock.tryAcquire(resourceKey, owner, duration);
            if (!(acquisition instanceof LeaseAcquireResult.Acquired acquired)) return false;
            try {
                task.run();
                return true;
            } finally {
                leaseLock.release(acquired.token());
            }
        } catch (SQLException failure) {
            getLogger().warning("跨服维护租约失败(" + resourceKey + "): " + failure.getMessage());
            return false;
        }
    }

    private void awaitPriceRefreshStop() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5L);
        synchronized (priceRefreshMonitor) {
            while (activePriceRefreshes > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    getLogger().warning("等待价格刷新事务结束超时；将继续执行安全停机。");
                    return;
                }
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(priceRefreshMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void startCrossServerCleanupTask() {
        if (crossServerCleanupTask != null) crossServerCleanupTask.cancel();
        long intervalTicks = 20L * 60L * 60L;
        crossServerCleanupTask = scheduler.runAsyncTimer(() -> {
            if (asyncWorkPool == null) return;
            asyncWorkPool.executeDatabase(() -> {
                try (var connection = ecoDatabase.openConnection();
                     var statement = connection.prepareStatement(
                             "DELETE FROM ks_crossserver_transport_events "
                                     + "WHERE expires_at_ms>0 AND expires_at_ms<=?")) {
                    statement.setLong(1, ecoDatabase.currentDatabaseTime(connection));
                    statement.executeUpdate();
                } catch (SQLException failure) {
                    getLogger().warning("清理过期跨服事件失败: " + failure.getMessage());
                }
            });
        }, Duration.ofMillis(intervalTicks * 50L), Duration.ofMillis(intervalTicks * 50L));
    }

    /** 自动创建全局唯一中央银行 */
    private void autoCreateCentralBank() {
        try (var conn = ksCore.dataStore().getConnection()) {
            if (conn == null) {
                getLogger().warning("数据库连接未就绪，跳过中央银行自动创建。");
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            insertSettingIfAbsent(conn, "enterprise_min_capital", "50000", now);
            insertSettingIfAbsent(conn, "enterprise_max_owners", "4", now);
            insertSettingIfAbsent(conn, "enterprise_max_members", "50", now);
            CentralBankBootstrap.Result result = CentralBankBootstrap.ensure(conn,
                    () -> "CB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), now);
            getLogger().info(result.created()
                    ? "中央银行已自动创建（ID: " + result.bankId() + "）"
                    : "中央银行已存在（ID: " + result.bankId() + "），跳过创建。");
        } catch (java.sql.SQLException e) {
            getLogger().warning("中央银行自动创建失败: " + e.getMessage());
        }
    }

    private static void insertSettingIfAbsent(java.sql.Connection connection, String key, String value, long now)
            throws java.sql.SQLException {
        try (var check = connection.prepareStatement("SELECT 1 FROM ks_eco_settings WHERE config_key=?")) {
            check.setString(1, key);
            try (var rows = check.executeQuery()) {
                if (rows.next()) return;
            }
        }
        try (var insert = connection.prepareStatement(
                "INSERT INTO ks_eco_settings (config_key, config_value, updated_at) VALUES (?,?,?)")) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.setLong(3, now);
            insert.executeUpdate();
        } catch (java.sql.SQLException failure) {
            if (!isUniqueConstraintViolation(failure)) throw failure;
        }
    }

    private static boolean isUniqueConstraintViolation(java.sql.SQLException failure) {
        String sqlState = failure.getSQLState();
        return (sqlState != null && sqlState.startsWith("23"))
                || failure.getErrorCode() == 19
                || failure.getErrorCode() == 1062;
    }

    /** 运行数据库迁移 - 创建所有必需的表 */
    private boolean runMigrations() {
        try (var conn = ksCore.dataStore().getConnection()) {
            if (conn == null) {
                getLogger().severe("数据库连接未就绪，无法执行业务迁移。");
                return false;
            }
            CoreBusinessSchema.initialize(conn);
            EcoWebBusinessSchema.initialize(conn);
            getLogger().info("数据库迁移完成（所有核心表已创建）");
            return true;
        } catch (java.sql.SQLException e) {
            getLogger().severe("数据库迁移失败: " + e.getMessage());
            return false;
        }
    }

    /** Periodically expire unfinished joint-venture requests without touching Bukkit state. */
    private void startPendingCreationExpiryTask() {
        pendingCreationExpiryTask = scheduler.runAsyncTimer(() -> asyncWorkPool.executeDatabase(() -> {
            try (var conn = ksCore.dataStore().getConnection();
                 var ps = conn == null ? null : conn.prepareStatement(
                         "UPDATE ks_ent_pending_creations SET status='EXPIRED' WHERE status IN ('PENDING','FINALIZING') AND expires_at<?")) {
                if (ps != null) {
                    ps.setLong(1, System.currentTimeMillis() / 1000);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                getLogger().warning("Failed to expire pending enterprise creations: " + e.getMessage());
            }
        }), Duration.ofMinutes(1), Duration.ofMinutes(5));
    }

    private void startDatabaseHeartbeatTask() {
        if (databaseHeartbeatTask != null) databaseHeartbeatTask.cancel();
        long intervalTicks = Math.max(20L, ecoDatabase.heartbeatInterval().toSeconds() * 20L);
        databaseHeartbeatTask = scheduler.runAsyncTimer(() -> {
            if (getConfig().getBoolean("cross-server.enabled", false)) {
                CrossServerRuntime runtime = crossServerRuntime;
                if (runtime == null || !runtime.isHealthy() || !ecoDatabase.identityHealthy()) {
                    getLogger().severe("跨服运行时或数据库节点身份不健康；为避免节点状态分裂，ks-Eco 将停用。");
                    scheduler.runGlobal(() -> Bukkit.getPluginManager().disablePlugin(this));
                    return;
                }
            }
            if (asyncWorkPool == null) return;
            try {
                asyncWorkPool.executeDatabase(ecoDatabase::heartbeat);
            } catch (RejectedExecutionException exception) {
                getLogger().warning("数据库队列繁忙，ks-Eco 跨服身份心跳未能入队。");
            }
        }, Duration.ofMillis(intervalTicks * 50L), Duration.ofMillis(intervalTicks * 50L));
    }

    private void registerCommands() {
        if (getCommand("kseco") != null) getCommand("kseco").setExecutor(this);
        if (getCommand("kseco-admin") != null) getCommand("kseco-admin").setExecutor(this);
        if (getCommand("market") != null) getCommand("market").setExecutor(this);
        if (getCommand("trade") != null) getCommand("trade").setExecutor(this);
        if (getCommand("storage") != null) getCommand("storage").setExecutor(this);
        if (getCommand("exchange") != null) getCommand("exchange").setExecutor(this);
        if (getCommand("exchangeadmin") != null) getCommand("exchangeadmin").setExecutor(this);
        if (getCommand("blindboxadmin") != null) getCommand("blindboxadmin").setExecutor(this);
        if (getCommand("limitedsale") != null) getCommand("limitedsale").setExecutor(this);
        if (getCommand("limitedsaleadmin") != null) getCommand("limitedsaleadmin").setExecutor(this);
        if (getCommand("balance") != null) getCommand("balance").setExecutor(this);
        if (getCommand("mo") != null) getCommand("mo").setExecutor(new MajorOrderCommand(this));
    }

    private void registerListeners() {
        marketListener = new MarketMenu.Listener(this);
        storageListener = new StorageMenu.Listener(this);
        deliveryListener = new DeliveryMenu.Listener(this);
        previewListener = new ListingPreviewMenu.Listener(this);
        priceInputListener = new PriceInputMenu.Listener(this);
        exchangeListener = new ExchangeGui.Listener(this);
        exchangeDropListener = new ExchangeGui.DropListener(this);
        exchangeChatListener = new ExchangeGui.ChatListener(this);
        sellAllListener = new org.kseco.gui.SellAllMenu.Listener(this);
        getServer().getPluginManager().registerEvents(marketListener, this);
        getServer().getPluginManager().registerEvents(storageListener, this);
        getServer().getPluginManager().registerEvents(deliveryListener, this);
        getServer().getPluginManager().registerEvents(previewListener, this);
        getServer().getPluginManager().registerEvents(priceInputListener, this);
        getServer().getPluginManager().registerEvents(new PriceInputMenu.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new PurchaseOrderMenu.Listener(this), this);
        getServer().getPluginManager().registerEvents(new PurchaseOrderMenu.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(exchangeListener, this);
        getServer().getPluginManager().registerEvents(exchangeDropListener, this);
        getServer().getPluginManager().registerEvents(exchangeChatListener, this);
        getServer().getPluginManager().registerEvents(sellAllListener, this);

        // /eco gui 统一入口 — 新 GUI 监听器
        ecoGuiMainListener = new EcoGuiMainMenu.Listener(this);
        bankListener = new BankGui.Listener(this);
        blindBoxListener = new BlindBoxGui.Listener(this);
        limitedSaleListener = new LimitedSaleGui.Listener(this);
        entBlindBoxListener = new EntBlindBoxGui.Listener(this);
        enterpriseListener = new EnterpriseGui.Listener(this);
        biddingListener = new BiddingGui.Listener(this);
        invitesListener = new InvitesGui.Listener(this);
        taxListener = new TaxGui.Listener(this);
        politicListener = new PoliticGui.Listener(this);
        realEstateListener = new RealEstateGui.Listener(this);
        bankChatListener = new BankGui.ChatListener(this);
        enterpriseChatListener = new EnterpriseGui.ChatListener(this);
        biddingChatListener = new BiddingGui.ChatListener(this);

        getServer().getPluginManager().registerEvents(ecoGuiMainListener, this);
        getServer().getPluginManager().registerEvents(bankListener, this);
        getServer().getPluginManager().registerEvents(blindBoxListener, this);
        getServer().getPluginManager().registerEvents(limitedSaleListener, this);
        getServer().getPluginManager().registerEvents(entBlindBoxListener, this);
        getServer().getPluginManager().registerEvents(enterpriseListener, this);
        getServer().getPluginManager().registerEvents(biddingListener, this);
        getServer().getPluginManager().registerEvents(invitesListener, this);
        getServer().getPluginManager().registerEvents(taxListener, this);
        getServer().getPluginManager().registerEvents(politicListener, this);
        getServer().getPluginManager().registerEvents(realEstateListener, this);
        getServer().getPluginManager().registerEvents(bankChatListener, this);
        getServer().getPluginManager().registerEvents(enterpriseChatListener, this);
        getServer().getPluginManager().registerEvents(biddingChatListener, this);
        getServer().getPluginManager().registerEvents(new PoliticGui.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new TransferGui.Listener(this), this);
        getServer().getPluginManager().registerEvents(new TransferGui.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiSafetyListener(), this);

        // BlindBoxAdminGui 监听器
        getServer().getPluginManager().registerEvents(new org.kseco.gui.BlindBoxAdminGui.Listener(this), this);
        getServer().getPluginManager().registerEvents(new org.kseco.gui.BlindBoxAdminGui.DropListener(this), this);
        getServer().getPluginManager().registerEvents(new org.kseco.gui.BlindBoxAdminGui.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new LimitedSaleAdminGui.Listener(this), this);
        getServer().getPluginManager().registerEvents(new LimitedSaleAdminGui.DropListener(this), this);
        getServer().getPluginManager().registerEvents(new LimitedSaleAdminGui.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new CompensationGui.Listener(this), this);
        getServer().getPluginManager().registerEvents(new CompensationGui.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new OfficialBuyAdminGui.GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new OfficialBuyAdminGui.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new OfficialWarehouseGui.Listener(this), this);
    }

    private void startPriceRefreshTask() {
        long interval = priceEngine.getPriceRefreshMinutes() * 60 * 20L;
        priceRefreshTask = scheduler.runAsyncTimer(() -> {
            refreshPricesCoordinated();
        }, Duration.ofMillis(interval * 50L), Duration.ofMillis(interval * 50L));
    }

    private void startOfficialMarketSweepTask() {
        if (officialSweepTask != null) officialSweepTask.cancel();
        long interval = Math.max(20L, ecoConfig.getOfficialSweepIntervalSeconds() * 20L);
        officialSweepTask = scheduler.runGlobalTimer(
                () -> officialMarketSweepManager.runSweep(), interval, interval);
    }

    /** 价格刷新间隔在 web 端被修改后调用：取消旧的定时任务并按新间隔重新调度，无需重启服务器。 */
    public void restartPriceRefreshTask() {
        if (priceRefreshTask != null) {
            priceRefreshTask.cancel();
            priceRefreshTask = null;
        }
        startPriceRefreshTask();
    }

    /** 启动货币供应量快照任务（每5分钟记录一次 M0/M1/M2） */
    private void startMoneySupplySnapshotTask() {
        // 首次快照延迟 30 秒执行，之后每 5 分钟一次
        moneySupplySnapshotTask = scheduler.runGlobalTimer(() -> {
            try {
                if (extraModuleLoader != null) {
                    var bankModule = extraModuleLoader.getModule("ks-eco-bank");
                    if (bankModule != null) {
                        var getterMethod = bankModule.getClass().getMethod("moneySupplyTracker");
                        Object tracker = getterMethod.invoke(bankModule);
                        if (tracker != null) {
                            tracker.getClass().getMethod("snapshotAsync").invoke(tracker);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 模块未加载或反射失败，静默跳过
            }
        }, 600L, 6000L); // 30s 初始延迟，5min 间隔（20 ticks/sec * 30 = 600, * 300 = 6000）
    }

    /** 启动市场波动报告任务：定期把涨跌幅最大的几个物品汇总成公示，发到公告栏。 */
    private void startMarketVolatilityReportTask() {
        long intervalTicks = ecoConfig.getVolatilityReportIntervalHours() * 3600L * 20L;
        volatilityReportTask = scheduler.runAsyncTimer(() -> {
            try {
                postMarketVolatilityReport();
            } catch (Exception e) {
                getLogger().warning("生成市场波动报告失败: " + e.getMessage());
            }
        }, Duration.ofMillis(intervalTicks * 50L), Duration.ofMillis(intervalTicks * 50L));
    }

    private double volatilityPctChange(java.util.Map<String, Object> item) {
        double base = ((Number) item.get("basePrice")).doubleValue();
        double buy = ((Number) item.get("buyPrice")).doubleValue();
        return base > 0 ? (buy - base) / base : 0.0;
    }

    private void postMarketVolatilityReport() {
        var snapshot = priceEngine.getVolatilitySnapshot();
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> items =
                (java.util.List<java.util.Map<String, Object>>) snapshot.get("items");
        if (items == null || items.isEmpty()) return;

        var sorted = new java.util.ArrayList<>(items);
        sorted.sort((a, b) -> Double.compare(Math.abs(volatilityPctChange(b)), Math.abs(volatilityPctChange(a))));

        int topN = ecoConfig.getVolatilityReportTopN();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
            var item = sorted.get(i);
            double pct = volatilityPctChange(item) * 100;
            if (Math.abs(pct) < 0.05) continue; // 几乎无变化的不列进报告
            String material = (String) item.get("material");
            String trend = (String) item.get("trend");
            String emoji = "UP".equals(trend) ? "↑" : "DOWN".equals(trend) ? "↓" : "→";
            body.append(MaterialNames.get(material)).append(" ").append(emoji).append(' ')
                    .append(String.format("%+.1f%%", pct)).append('\n');
        }
        if (body.length() == 0) return;

        ksCore.announcementManager().post("SYSTEM", "market_volatility_report",
                "📈 市场波动报告", body.toString(), "市场调控中心", 1, 0);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String cmd = command.getName().toLowerCase();

        switch (cmd) {
            case "kseco":
                return handlePlayerCommand(sender, args);
            case "kseco-admin":
                return handleAdminCommand(sender, args);
            case "market":
                return handleMarketCommand(sender);
            case "trade":
                return handleTradeCommand(sender, args);
            case "storage":
                return handleStorageCommand(sender);
            case "exchange":
                return handleExchangeCommand(sender);
            case "exchangeadmin":
                return handleExchangeAdminCommand(sender);
            case "blindboxadmin":
                return handleBlindBoxAdminCommand(sender);
            case "limitedsale":
                return handleLimitedSaleCommand(sender);
            case "limitedsaleadmin":
                return handleLimitedSaleAdminCommand(sender);
            case "balance":
                return handleBalanceCommand(sender);
            default:
                return false;
        }
    }

    /** /kseco — 玩家面板（web / gui / prices 子命令） */
    private boolean handlePlayerCommand(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("gui")) {
            return handleGuiCommand(sender);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("web")) {
            return handlePlayerWebCommand(sender);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("prices")) {
            return handlePricesCommand(sender);
        }
        // 帮助信息
        sender.sendMessage("§6[ks-Eco] §e/kseco gui §7— 打开统一经济 GUI 面板");
        sender.sendMessage("§6[ks-Eco] §e/kseco web §7— 打开玩家经济面板");
        sender.sendMessage("§6[ks-Eco] §e/kseco prices §7— 查看官方收购价格与税率");
        if (sender.hasPermission("kseco.admin")) {
            sender.sendMessage("§6[ks-Eco] §e/kseco-admin web §7— 打开管理员面板");
            sender.sendMessage("§6[ks-Eco] §e/kseco-admin reload|status|force-price|void-trade §7— 管理员操作");
        }
        return true;
    }

    /** /kseco prices — 聊天框列出官方收购价 + 各税率 */
    private boolean handlePricesCommand(CommandSender sender) {
        // ---- 官方收购价格表 ----
        sender.sendMessage("§6§l━━━ 官方收购价格表 ━━━");
        var items = ecoConfig.getDefaultBuyItems();
        if (items.isEmpty()) {
            sender.sendMessage("§7  （暂无配置）");
        } else {
            for (var item : items) {
                double price = priceEngine.getOfficialBuyPrice(item.material());
                String cn = MaterialNames.get(item.material());
                String trend = switch (priceEngine.getTrend(item.material())) {
                    case "UP" -> "§a▲";
                    case "DOWN" -> "§c▼";
                    default -> "§7—";
                };
                sender.sendMessage(String.format("§e  %-14s §7%-18s §a¥%.2f %s",
                        cn, "(" + item.material().toLowerCase() + ")", price, trend));
            }
        }

        // ---- 税率总览 ----
        sender.sendMessage("§6§l━━━ 当前税率一览 ━━━");
        String[] categories = {
            "MARKET_TRADE", "PROPERTY_TRADE", "OFFICIAL_TRADE",
            "ENTERPRISE_SMALL", "ENTERPRISE_MEDIUM", "ENTERPRISE_LARGE",
            "DIVIDEND_TAX", "BANK_INTEREST", "TAX_PENALTY"
        };
        for (String cat : categories) {
            double rate = getCategoryTaxRate(cat);
            String cn = MaterialNames.getTaxCategoryName(cat);
            sender.sendMessage(String.format("§e  %-16s §7%.1f%%", cn, rate * 100));
        }
        sender.sendMessage("§7提示：网页端可查看完整价格图表，输入 §e/kseco web §7打开。");
        return true;
    }

    /** /eco gui — 打开统一经济 GUI 主菜单 */
    private boolean handleGuiCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        new EcoGuiMainMenu(this).open(player);
        return true;
    }

    /** /kseco-admin — 管理员命令（需要 kseco.admin 权限） */
    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kseco.admin")) {
            sender.sendMessage("§c权限不足。需要 kseco.admin 权限。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§6[ks-Eco 管理员]");
            sender.sendMessage("§e/kseco-admin web §7— 打开管理员面板");
            sender.sendMessage("§e/kseco-admin reload [extras] §7— 重载配置；extras 会安全重载 ks-Eco 附属模块");
            sender.sendMessage("§e/kseco-admin status §7— 查看状态");
            sender.sendMessage("§e/kseco-admin force-price <物品> <价格> §7— 强制限价");
            sender.sendMessage("§e/kseco-admin void-trade <物品> <数量> <单价> <BUY|SELL> §7— 虚空交易干预市场");
            sender.sendMessage("§e/kseco-admin give <玩家> <金额> §7— 给玩家发钱");
            sender.sendMessage("§e/kseco-admin take <玩家> <金额> §7— 扣除玩家余额");
            sender.sendMessage("§e/kseco-admin set <玩家> <金额> §7— 设置玩家余额");
            sender.sendMessage("§e/kseco-admin economyreset §7— 查看可清空的类别列表");
            sender.sendMessage("§e/kseco-admin economyreset preview <类别1,类别2,...|all> §7— 预览所选类别的数据量");
            sender.sendMessage("§e/kseco-admin economyreset confirm <类别1,类别2,...|all> §7— §c执行§7清空所选类别（会先自动备份）");
            sender.sendMessage("§e/kseco-admin economyreset backups §7— 查看可回档的备份列表");
            sender.sendMessage("§e/kseco-admin economyreset rollback <备份文件名> §7— §c从备份回档§7（会先备份当前状态）");
            return true;
        }

        if (args[0].equalsIgnoreCase("web")) {
            return handleAdminWebCommand(sender);
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                boolean reloadExtras = args.length >= 2 && args[1].equalsIgnoreCase("extras");
                scheduler.runGlobal(() -> {
                    reloadRuntime(reloadExtras);
                    sender.sendMessage("§a[ks-Eco] 配置已重载"
                            + (reloadExtras ? "，Extra 模块已安全重载。" : "。"));
                });
                break;
            case "status":
                sender.sendMessage("§6[ks-Eco 状态]");
                sender.sendMessage("§7  活跃挂单: §f" + listingManager.activeListingCount());
                sender.sendMessage("§7  暂存箱物品: §f" + storageManager.totalStoredItems());
                sender.sendMessage("§7  Vault: " + (vaultHook.isAvailable() ? "§a已对接" : "§c未找到"));
                sender.sendMessage("§7  Extra 模块: §f" + extraModuleLoader.loadedModuleCount());
                sender.sendMessage("§7  跨服运行时: "
                        + (crossServerRuntime != null && crossServerRuntime.isHealthy() ? "§a运行中" : "§8未启用/不健康"));
                sender.sendMessage("§7  数据库节点: §f" + ecoDatabase.serverId()
                        + " §8(" + ecoDatabase.dialect() + ")");
                break;
            case "force-price":
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /kseco force-price <物品材质> <价格>");
                } else {
                    priceEngine.forcePrice(args[1].toUpperCase(), Double.parseDouble(args[2]));
                    sender.sendMessage("§a已更新 " + args[1] + " 的官方价格。");
                }
                break;
            case "void-trade":
                if (args.length < 5) {
                    sender.sendMessage("§c用法: /kseco void-trade <物品材质> <数量> <单价> <BUY|SELL>");
                } else {
                    String material = args[1].toUpperCase();
                    int quantity = Integer.parseInt(args[2]);
                    double price = Double.parseDouble(args[3]);
                    String type = args[4].toUpperCase();
                    boolean testMode = priceEngine.isTestModeEnabled();
                    // 虚空交易：测试模式下只做预览计算（不影响真实价格，流水打 is_test 标记，
                    // 永久排除出真实供需统计）；关闭测试模式时是一次真实交易，会真的改变价格。
                    // 注意：新定价模型 type=BUY 不影响价格（官方只收购不直售，没有真实的"需求上涨"事件；
                    // 上涨只能来自近期卖量低于历史基线的"供不应求"，或 web 端"大手导向"/自动波动）。
                    double resultPrice = priceEngine.recordAdminTrade(material, quantity, price, type, "CONSOLE", "VOID");
                    sender.sendMessage("§a[虚空交易] 已注入 " + quantity + "x " + material +
                            " @ " + price + " (" + type + ")" + (testMode ? " §e[测试模式-仅预览]" : ""));
                    if ("BUY".equals(type)) {
                        sender.sendMessage("§7  提示: BUY 类型不影响价格，仅写入流水");
                    }
                    sender.sendMessage("§7  " + (testMode ? "预览价格" : "当前官方收购价") + ": " +
                            String.format("%.2f", resultPrice));
                    if (testMode) {
                        sender.sendMessage("§7  （测试模式未写入真实状态，下面是当前真实值，不含本次模拟量）");
                    }
                    sender.sendMessage("§7  真实供需压力: " + String.format("%.4f", priceEngine.getSupplyPressure(material)) +
                            "（正=供过于求打折，负=供不应求加价）" +
                            "  趋势: " + priceEngine.getTrend(material));
                }
                break;
            case "give":
                handleEcoAdjust(sender, args, "give");
                break;
            case "take":
                handleEcoAdjust(sender, args, "take");
                break;
            case "set":
                handleEcoAdjust(sender, args, "set");
                break;
            case "economyreset":
                handleEconomyReset(sender, args);
                break;
            default:
                sender.sendMessage("§c未知参数。");
        }
        return true;
    }

    /**
     * /kseco-admin economyreset                          — 显示可选类别
     * /kseco-admin economyreset preview <类别|all>        — 预览所选类别数据量
     * /kseco-admin economyreset confirm <类别|all>        — 清空所选类别（自动先备份）
     * /kseco-admin economyreset backups                  — 列出可回档的备份
     * /kseco-admin economyreset rollback <备份文件名>      — 从备份回档（自动先备份当前状态）
     */
    private void handleEconomyReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§6[ks-Eco 经济重置] 可选类别：");
            for (var cat : EconomyResetManager.Category.values()) {
                sender.sendMessage("§e  " + cat.name().toLowerCase() + " §7— " + cat.label + "：" + cat.description);
            }
            sender.sendMessage("§7用 §eall §7代表全部类别，多个类别用逗号分隔，例如: §fcore,bank,tax");
            sender.sendMessage("§e/kseco-admin economyreset preview <类别|all> §7— 预览数据量");
            sender.sendMessage("§e/kseco-admin economyreset confirm <类别|all> §7— §c执行清空§7（自动先备份）");
            sender.sendMessage("§e/kseco-admin economyreset backups §7— 查看可回档的备份");
            sender.sendMessage("§e/kseco-admin economyreset rollback <备份文件名> §7— §c从备份回档");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "backups": {
                var backups = economyResetManager.listBackups();
                if (backups.isEmpty()) { sender.sendMessage("§7暂无可用备份。"); return; }
                sender.sendMessage("§6[ks-Eco 可回档备份] 共 " + backups.size() + " 个：");
                for (var f : backups) {
                    sender.sendMessage("§e  " + f.getName() + " §7(" + (f.length() / 1024) + " KB)");
                }
                sender.sendMessage("§7回档: §f/kseco-admin economyreset rollback <文件名>");
                return;
            }
            case "rollback": {
                if (args.length < 3) { sender.sendMessage("§c用法: /kseco-admin economyreset rollback <备份文件名>"); return; }
                sender.sendMessage("§e正在回档，请稍候...");
                var restored = economyResetManager.rollback(sender, args[2]);
                if (restored == null) return; // 失败信息已在 rollback() 内发送
                sender.sendMessage("§a[ks-Eco] 回档完成，共还原 §f" + restored.size() + " §a张表: " + String.join(", ", restored));
                sender.sendMessage("§7建议重启服务器以确保所有模块状态一致。");
                return;
            }
            case "preview":
            case "confirm": {
                boolean confirm = args[1].equalsIgnoreCase("confirm");
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /kseco-admin economyreset " + args[1] + " <类别1,类别2,...|all>");
                    return;
                }
                var categories = parseCategories(sender, args[2]);
                if (categories == null) return; // 解析失败信息已发送
                if (!confirm) {
                    var preview = economyResetManager.preview(categories);
                    int totalRows = preview.values().stream().mapToInt(Integer::intValue).sum();
                    sender.sendMessage("§6[ks-Eco 经济重置 - 预览] 类别: " + categories);
                    sender.sendMessage("§7将清空 §f" + preview.size() + " §7张表，共 §f" + totalRows + " §7行数据：");
                    for (var entry : preview.entrySet()) {
                        if (entry.getValue() > 0) sender.sendMessage("§7  - " + entry.getKey() + ": §f" + entry.getValue() + " 行");
                    }
                    sender.sendMessage("§c确认后请执行: /kseco-admin economyreset confirm " + args[2]);
                    sender.sendMessage("§7（执行前会先自动备份完整数据库；清空后随时可用 backups/rollback 回档）");
                    return;
                }
                sender.sendMessage("§e正在清空所选类别的数据，请稍候...");
                var deleted = economyResetManager.reset(sender, categories);
                if (deleted.isEmpty()) return; // 失败信息已在 reset() 内发送
                int totalRows = deleted.values().stream().mapToInt(Integer::intValue).sum();
                sender.sendMessage("§a[ks-Eco] 清空完成，共清空 §f" + totalRows + " §a行（" + deleted.size() + " 张表）。" +
                        "建议重启服务器以确保所有模块状态一致。如需撤销可用 §f/kseco-admin economyreset backups §a查看备份后回档。");
                return;
            }
            default:
                sender.sendMessage("§c未知子命令，输入 /kseco-admin economyreset 查看用法。");
        }
    }

    /** 解析 "core,bank,tax" 或 "all" 形式的类别参数；非法类别名会报错并返回 null */
    private java.util.Set<EconomyResetManager.Category> parseCategories(CommandSender sender, String raw) {
        if (raw.equalsIgnoreCase("all")) {
            return new java.util.LinkedHashSet<>(java.util.List.of(EconomyResetManager.Category.values()));
        }
        java.util.Set<EconomyResetManager.Category> result = new java.util.LinkedHashSet<>();
        for (String key : raw.split(",")) {
            var cat = EconomyResetManager.Category.byKey(key.trim());
            if (cat == null) {
                sender.sendMessage("§c未知类别: " + key + "§7（输入 /kseco-admin economyreset 查看可用类别）");
                return null;
            }
            result.add(cat);
        }
        return result;
    }

    /** 玩家 web 链接 — 生成普通 token，指向 /ks-Eco/player */
    private boolean handlePlayerWebCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!bridge.isPluginRouteEnabled("ks-eco")) {
            player.sendMessage("§c经济 Web 面板未启用。");
            return true;
        }
        String link = bridge.createWebLink(player, false, "/ks-Eco/player");
        player.sendMessage("§a经济 Web 玩家面板: " + link);
        player.sendMessage(net.kyori.adventure.text.Component.text("§e点击打开玩家经济面板")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link)));
        return true;
    }

    /** 管理员 web 链接 — 生成 admin token，指向 /ks-Eco/admin */
    private boolean handleAdminWebCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!bridge.isPluginRouteEnabled("ks-eco")) {
            player.sendMessage("§c经济 Web 面板未启用。");
            return true;
        }
        String link = bridge.createWebLink(player, true, "/ks-Eco/admin");
        player.sendMessage("§a经济 Web 管理面板: " + link);
        player.sendMessage(net.kyori.adventure.text.Component.text("§e点击打开管理员面板")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link)));
        return true;
    }

    /** 该功能是否对此 sender 开放（kseco.admin 自动豁免）。 */
    public boolean isFeatureOpen(CommandSender sender, String featureId) {
        return sender.hasPermission("kseco.admin") || featureGate.isOpen(featureId);
    }

    private boolean handleMarketCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!player.hasPermission("kseco.market")) {
            player.sendMessage("§c权限不足。");
            return true;
        }
        if (!isFeatureOpen(player, "market")) {
            player.sendMessage("§c该功能暂未开放，敬请期待。");
            return true;
        }
        new MarketMenu(this).open(player);
        return true;
    }

    private boolean handleTradeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!isFeatureOpen(player, "trade")) {
            player.sendMessage("§c该功能暂未开放，敬请期待。");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§c用法: /trade <玩家名> | /trade quote <玩家名> | /trade send <玩家名> [数量]");
            return true;
        }
        boolean logistics = args[0].equalsIgnoreCase("quote") || args[0].equalsIgnoreCase("send");
        if (logistics && args.length < 2) {
            player.sendMessage("§c用法: /trade " + args[0].toLowerCase() + " <玩家名>" +
                    (args[0].equalsIgnoreCase("send") ? " [数量]" : ""));
            return true;
        }
        String targetName = logistics && args.length >= 2 ? args[1] : args[0];
        org.bukkit.entity.Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            player.sendMessage("§c玩家不在线。");
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage("§c不能与自己交易或传输。");
            return true;
        }
        if (logistics) {
            TransportManager.Quote quote = transportManager.quote(player, target);
            if (args[0].equalsIgnoreCase("quote")) {
                player.sendMessage("§e物流报价: §f" + String.format(java.util.Locale.ROOT, "%.1f", quote.distance()) + " 格"
                        + (quote.crossWorld() ? " §d(跨世界)" : "")
                        + (quote.free() ? " §a| 免费范围内" : " §7| 费用 §6" + vaultHook.format(quote.fee())));
                return true;
            }
            int amount = 0;
            if (args.length >= 3) {
                try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) { player.sendMessage("§c数量必须是整数。"); return true; }
                if (amount < 1) { player.sendMessage("§c数量必须大于 0。"); return true; }
            }
            transportManager.sendHeldItem(player, target, amount);
            return true;
        }
        new DeliveryMenu(this, player, target).open();
        return true;
    }

    private boolean handleStorageCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        new StorageMenu(this, player).open();
        return true;
    }

    /** /exchange — 玩家物品兑换 GUI */
    private boolean handleExchangeCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!isFeatureOpen(player, "exchange")) {
            player.sendMessage("§c该功能暂未开放，敬请期待。");
            return true;
        }
        new ExchangeGui(this).openPlayerView(player);
        return true;
    }

    /** /exchangeadmin — 管理兑换规则 GUI */
    private boolean handleExchangeAdminCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c权限不足。需要 kseco.admin。");
            return true;
        }
        new ExchangeGui(this).openAdminView(player);
        return true;
    }

    /** /blindboxadmin — 盲盒卡池与战利品管理 GUI */
    private boolean handleBlindBoxAdminCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        new org.kseco.gui.BlindBoxAdminGui(this).open(player);
        return true;
    }

    private boolean handleLimitedSaleCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!player.hasPermission("kseco.limitedsale")) {
            player.sendMessage("§c权限不足。");
            return true;
        }
        if (!isFeatureOpen(player, "limited_sale")) {
            player.sendMessage("§c该功能暂未开放。");
            return true;
        }
        new LimitedSaleGui(this).open(player);
        return true;
    }

    private boolean handleLimitedSaleAdminCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c权限不足。需要 kseco.admin。");
            return true;
        }
        new LimitedSaleAdminGui(this).open(player);
        return true;
    }

    /** /kseco-admin give|take|set <玩家> <金额> — 调整玩家余额（需要 kseco.admin 权限） */
    private void handleEcoAdjust(CommandSender sender, String[] args, String mode) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /kseco-admin " + mode + " <玩家> <金额>");
            return;
        }
        if (!vaultHook.isAvailable()) {
            sender.sendMessage("§c经济系统未就绪，无法操作。");
            return;
        }
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage("§c未找到玩家: " + args[1]);
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c金额必须是数字。");
            return;
        }
        if (amount < 0) {
            sender.sendMessage("§c金额不能为负数。");
            return;
        }

        boolean ok;
        switch (mode) {
            case "give":
                ok = vaultHook.deposit(target, amount);
                break;
            case "take":
                ok = vaultHook.withdraw(target, amount);
                break;
            case "set":
                double current = vaultHook.getBalance(target);
                ok = amount >= current ? vaultHook.deposit(target, amount - current)
                                        : vaultHook.withdraw(target, current - amount);
                break;
            default:
                ok = false;
        }

        if (ok) {
            double newBalance = vaultHook.getBalance(target);
            sender.sendMessage("§a[ks-Eco] 操作成功。" + target.getName() + " 当前余额: " + vaultHook.format(newBalance));
        } else {
            sender.sendMessage("§c操作失败（余额不足或经济系统异常）。");
        }
    }

    /** /balance — 查询自己的余额（所有人可用） */
    private boolean handleBalanceCommand(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!vaultHook.isAvailable()) {
            player.sendMessage("§c经济系统未就绪，请稍后再试。");
            return true;
        }
        if (vaultHook.directBuiltinActive()) {
            UUID playerUuid = player.getUniqueId();
            try {
                asyncWorkPool.executeDatabase(() -> {
                    double balance = builtinEconomy.getBalance(playerUuid);
                    scheduler.runEntity(player,
                            () -> player.sendMessage("§6[ks-Eco] §7你当前的余额: §e" + vaultHook.format(balance)),
                            () -> { });
                });
            } catch (RejectedExecutionException busy) {
                player.sendMessage("§c经济数据库繁忙，请稍后再试。");
            }
        } else {
            double balance = vaultHook.getBalance(player);
            player.sendMessage("§6[ks-Eco] §7你当前的余额: §e" + vaultHook.format(balance));
        }
        return true;
    }

    /**
     * 反射调用 extra 模块管理器方法。
     * 沿袭 EcoWebHandler.callExtraManager 的反射模式，供 GUI 类使用。
     *
     * @param extraId  extra 模块 ID（如 "ks-eco-bank"）
     * @param getter   模块上暴露管理器的 getter 方法名（如 "bankManager"）
     * @param method   要调用的方法名
     * @param argTypes 参数类型数组
     * @param args     参数值
     * @return 方法返回值，失败或模块缺失返回 null
     */
    public Object callExtraManager(String extraId, String getter, String method,
                                    Class<?>[] argTypes, Object... args) {
        try {
            var module = extraModuleLoader.getModule(extraId);
            if (module == null) return null;
            var getterMethod = module.getClass().getMethod(getter);
            Object manager = getterMethod.invoke(module);
            if (manager == null) return null;
            var methodObj = manager.getClass().getMethod(method, argTypes);
            return methodObj.invoke(manager, args);
        } catch (Exception e) {
            getLogger().warning("反射调用 [" + extraId + ":" + getter + ":" + method + "] 失败: " + e.getMessage());
            return null;
        }
    }

    /** 政治立法模式开启时，经济参数只能由提案变更。返回 null 表示允许直接管理。 */
    public String politicGovernanceError() {
        Object mod = extraModuleLoader != null ? extraModuleLoader.getModule("ks-eco-politic") : null;
        if (mod == null) return null;
        try {
            Object manager = mod.getClass().getMethod("politicManager").invoke(mod);
            if (manager != null && Boolean.TRUE.equals(
                    manager.getClass().getMethod("isLegislativeMode").invoke(manager))) {
                return "经济参数变更需通过元老院提案，请使用 /politic gui";
            }
        } catch (ReflectiveOperationException e) {
            getLogger().warning("读取政治立法模式失败: " + e.getMessage());
        }
        return null;
    }

    // ---- Getters ----

    public KsCore ksCore() { return ksCore; }
    public EcoScheduler scheduler() { return scheduler; }
    public boolean foliaRuntime() { return isFoliaRuntime(); }
    public KsPluginBridge bridge() { return bridge; }
    public EcoDatabase ecoDatabase() { return ecoDatabase; }
    public JdbcDemandCampaignStore demandCampaignStore() { return demandCampaignStore; }
    public boolean demandCampaignsEnabled() { return demandCampaignsEnabled && demandCampaignStore != null; }
    public EcoConfig ecoConfig() { return ecoConfig; }
    public FeatureGateManager featureGate() { return featureGate; }
    public VaultHook vaultHook() { return vaultHook; }
    public MarketManager marketManager() { return marketManager; }
    public PriceEngine priceEngine() { return priceEngine; }
    public ShulkerBoxParser shulkerBoxParser() { return shulkerBoxParser; }
    public MarketValueService marketValueService() { return marketValueService; }
    public StorageManager storageManager() { return storageManager; }
    public ListingManager listingManager() { return listingManager; }
    public PurchaseOrderManager purchaseOrderManager() { return purchaseOrderManager; }
    public OfficialBuyManager officialBuyManager() { return officialBuyManager; }
    public OfficialWarehouseManager officialWarehouseManager() { return officialWarehouseManager; }
    public OfficialWarehouseLiquidationManager officialWarehouseLiquidationManager() {
        return officialWarehouseLiquidationManager;
    }
    public OfficialMarketSweepManager officialMarketSweepManager() { return officialMarketSweepManager; }
    public TradeManager tradeManager() { return tradeManager; }
    public TransportManager transportManager() { return transportManager; }
    public AsyncWorkPool asyncWorkPool() { return asyncWorkPool; }

    /** Reload ks-Eco configuration and, optionally, only the managed Extra modules. */
    public void reloadRuntime(boolean reloadExtras) {
        if (!scheduler.isGlobalThread()) {
            throw new IllegalStateException("ks-Eco runtime reload must run on the global tick thread");
        }
        reloadConfig();
        Map<String, Object> federatedCandidate = configurationMap("federated-assets");
        FederatedAssetSettings parsedFederated = FederatedAssetSettings.fromMap(federatedCandidate);
        validateFederatedAssetEnvironment(parsedFederated);
        if (parsedFederated.enabled() && !federatedAssetSettings.current().enabled()
                && (crossServerRuntime == null || !crossServerRuntime.isHealthy())) {
            throw new IllegalStateException("启用 federated-assets 前必须先让 restart-only 跨服 transport 完整启动");
        }
        ecoConfig = new EcoConfig(this);
        featureGate.reload();
        enterpriseLevelManager.reload();
        federatedAssetSettings.reload(federatedCandidate);
        startFederatedAssetHeartbeatTask();
        restartPriceRefreshTask();
        startOfficialMarketSweepTask();
        if (reloadExtras && extraModuleLoader != null) {
            extraModuleLoader.reloadModules();
        }
        marketValueService.refreshRecipeSnapshot();
    }
    public BlindBoxManager blindBoxManager() { return blindBoxManager; }
    public LimitedSaleManager limitedSaleManager() { return limitedSaleManager; }
    public CompensationManager compensationManager() { return compensationManager; }
    public ExchangeManager exchangeManager() { return exchangeManager; }
    public EconomyResetManager economyResetManager() { return economyResetManager; }
    public MajorOrderManager majorOrderManager() { return majorOrderManager; }
    public TransferManager transferManager() { return transferManager; }
    public EnterpriseLevelManager enterpriseLevelManager() { return enterpriseLevelManager; }
    public BuiltinEconomy builtinEconomy() { return builtinEconomy; }
    public BanManager banManager() { return banManager; }
    public ExtraModuleLoader extraModuleLoader() { return extraModuleLoader; }
    public void registerBankAccessProvider(BankAccessProvider provider) { this.bankAccessProvider = provider; }
    public BankAccessProvider bankAccessProvider() { return bankAccessProvider; }
    public void registerEnterpriseAccessProvider(EnterpriseAccessProvider provider) { this.enterpriseAccessProvider = provider; }
    public EnterpriseAccessProvider enterpriseAccessProvider() { return enterpriseAccessProvider; }
    public void registerEnterpriseFundSettlementProvider(EnterpriseFundSettlementProvider provider) {
        this.enterpriseFundSettlementProvider = provider;
    }
    public EnterpriseFundSettlementProvider enterpriseFundSettlementProvider() {
        return enterpriseFundSettlementProvider;
    }

    /**
     * 获取指定税种的动态税率。
     * 通过反射从 TaxExtra → TaxRateManager 获取动态税率（运行时加载），
     * 如果模块未加载则回退到配置文件中的全局税率。
     *
     * @param category 税种（MARKET_TRADE, OFFICIAL_TRADE, ENTERPRISE_SMALL 等）
     * @param fallback 模块未加载时的默认回退值
     */
    public double getCategoryTaxRate(String category, double fallback) {
        if (extraModuleLoader != null) {
            var taxModule = extraModuleLoader.getModule("ks-eco-tax");
            if (taxModule != null) {
                try {
                    // 反射调用 taxRateManager().getRate(category)
                    var getterMethod = taxModule.getClass().getMethod("taxRateManager");
                    Object rateManager = getterMethod.invoke(taxModule);
                    if (rateManager != null) {
                        var getRateMethod = rateManager.getClass().getMethod("getRate", String.class);
                        Object result = getRateMethod.invoke(rateManager, category);
                        if (result instanceof Number n) return n.doubleValue();
                    }
                } catch (Exception ignored) {
                    // 反射失败，回退到全局税率
                }
            }
        }
        // 调用方为不同税种提供独立回退值，不能统一套用市场税率。
        if (Double.isFinite(fallback)) return Math.max(0.0, Math.min(1.0, fallback));
        if (ecoConfig != null) return ecoConfig.getTaxRate();
        return fallback;
    }

    /**
     * 获取指定税种的动态税率（使用 0.02 即 2% 作为最终回退）。
     */
    public double getCategoryTaxRate(String category) {
        return getCategoryTaxRate(category, 0.02);
    }

    private static boolean isFoliaRuntime() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false,
                    KsEco.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
