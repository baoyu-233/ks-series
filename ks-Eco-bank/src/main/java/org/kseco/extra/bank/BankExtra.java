package org.kseco.extra.bank;

import org.kseco.KsEco;
import org.kseco.extra.KsEcoExtraModule;

import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

/**
 * 现代中央银行与商业银行系统 — Extra 模块入口。
 *
 * 功能：
 * - 玩家银行（合资/独资成立，需资质要求）
 * - 存贷业务（存款利息、贷款发放）
 * - 央行宏观调控（准备金率、基准利率）
 * - M0/M1/M2 货币范围划分
 * - 资产负债表
 * - Web UI（借贷审批、央行控制） + GUI（基础存取款）
 */
public final class BankExtra implements KsEcoExtraModule {

    private KsEco eco;
    private BankManager bankManager;
    private BankAccessProviderImpl bankAccessProvider;
    private CentralBankManager centralBankManager;
    private CbLoanManager cbLoanManager;
    private EnterpriseFinanceManager enterpriseFinanceManager;
    private MoneySupplyTracker moneySupplyTracker;
    private BankGameplayManager gameplayManager;
    private BankEquityManager equityManager;
    private BankResolutionManager resolutionManager;
    private org.bukkit.scheduler.BukkitTask interestTask;
    private final AtomicBoolean maintenanceRunning = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    @Override
    public String getId() { return "ks-eco-bank"; }

    @Override
    public String getName() { return "现代中央银行与商业银行系统"; }

    @Override
    public void onLoad(KsEco eco) {
        this.eco = eco;
        this.bankManager = new BankManager(eco);
        this.bankAccessProvider = new BankAccessProviderImpl(eco, bankManager);
        this.centralBankManager = new CentralBankManager(eco);
        this.cbLoanManager = new CbLoanManager(eco);
        this.enterpriseFinanceManager = new EnterpriseFinanceManager(eco);
        this.moneySupplyTracker = new MoneySupplyTracker(eco);
        this.gameplayManager = new BankGameplayManager(eco, bankManager);
        this.equityManager = new BankEquityManager(eco, bankManager);
        this.resolutionManager = new BankResolutionManager(eco, bankManager);
        eco.getLogger().info("[银行系统] 模块已加载。");
    }

    @Override
    public void onEnable() {
        enabled.set(true);
        bankManager.init();
        bankAccessProvider.init();
        eco.registerBankAccessProvider(bankAccessProvider);
        centralBankManager.init();
        cbLoanManager.init();
        enterpriseFinanceManager.init();
        moneySupplyTracker.init();
        gameplayManager.init();
        equityManager.init();
        resolutionManager.init();
        // 利息结算 + 逾期标记 + 央行到期贷款自动回收：启动 1 分钟后首跑，之后每 30 分钟一次。
        // 结算按账户时间戳锚点推进（周期天数见 ks_bank_cb_config.interest_period_days），
        // 任务跑得勤不会重复发息，停服跨周期会在下次运行补齐。
        interestTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(eco, () -> {
            if (!enabled.get() || !maintenanceRunning.compareAndSet(false, true)) return;
            try {
                eco.asyncWorkPool().executeDatabase(() -> {
                    boolean maintenanceOwner = false;
                    try {
                        if (!enabled.get()) return;
                        maintenanceOwner = eco.runClusterExclusiveTask(
                                "ks-eco:bank-maintenance", Duration.ofMinutes(20), () -> {
                                    enterpriseFinanceManager.settleExpiredAuctions();
                                    bankManager.settleInterestAndOverdue();
                                    gameplayManager.maintain();
                                    resolutionManager.collectPremiums();
                                    cbLoanManager.collectDueLoans();
                                    enterpriseFinanceManager.maintainDefaults();
                                });
                    } finally {
                        maintenanceRunning.set(false);
                        if (maintenanceOwner && enabled.get()) {
                            org.bukkit.Bukkit.getScheduler().runTask(eco,
                                    enterpriseFinanceManager::retryAuctionRefunds);
                        }
                    }
                });
            } catch (RuntimeException rejected) {
                maintenanceRunning.set(false);
                eco.getLogger().warning("[银行系统] 维护任务提交失败: " + rejected.getMessage());
            }
        }, 20L * 60, 20L * 60 * 30);
        eco.getLogger().info("[银行系统] 模块已启用（利息结算任务每 30 分钟运行）。");
    }

    @Override
    public void onDisable() {
        enabled.set(false);
        if (interestTask != null) { interestTask.cancel(); interestTask = null; }
        if (eco.bankAccessProvider() == bankAccessProvider) eco.registerBankAccessProvider(null);
        maintenanceRunning.set(false);
        eco.getLogger().info("[银行系统] 模块已停用。");
    }

    public BankManager bankManager() { return bankManager; }
    public BankAccessProviderImpl bankAccessProvider() { return bankAccessProvider; }
    public CentralBankManager centralBankManager() { return centralBankManager; }
    public CbLoanManager cbLoanManager() { return cbLoanManager; }
    public EnterpriseFinanceManager enterpriseFinanceManager() { return enterpriseFinanceManager; }
    public MoneySupplyTracker moneySupplyTracker() { return moneySupplyTracker; }
    public BankGameplayManager gameplayManager() { return gameplayManager; }
    public BankEquityManager equityManager() { return equityManager; }
    public BankResolutionManager resolutionManager() { return resolutionManager; }
}
