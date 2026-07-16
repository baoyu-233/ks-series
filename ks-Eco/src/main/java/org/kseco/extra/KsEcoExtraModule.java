package org.kseco.extra;

import org.kseco.KsEco;

/**
 * ks-Eco Extra 子模块接口。
 * 所有银行、企业、税法等附属插件必须实现此接口。
 *
 * 模块 JAR 放置在 plugins/ks-Eco/extra/ 目录下，
 * 包含 META-INF/ks-eco-extra.properties 文件指定主类：
 * <pre>
 * main-class=org.kseco.extra.bank.BankExtra
 * </pre>
 */
public interface KsEcoExtraModule {

    /** 模块唯一标识（如 "ks-eco-bank"） */
    String getId();

    /** 模块显示名称（如 "现代中央银行与商业银行系统"） */
    String getName();

    /** 模块被加载时调用（在 ks-Eco onEnable 期间） */
    void onLoad(KsEco eco);

    /** 模块被启用时调用 */
    void onEnable();

    /** 模块被停用时调用 */
    void onDisable();
}
