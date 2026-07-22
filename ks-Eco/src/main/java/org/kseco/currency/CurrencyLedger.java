package org.kseco.currency;

import java.sql.SQLException;

/**
 * Synchronous database API. Callers must invoke it on ks-Eco's database lane,
 * never while holding Bukkit, inventory, item metadata, GUI or Vault state.
 */
public interface CurrencyLedger {
    void initializeSchema() throws SQLException;

    Money balance(CurrencyAccount account, CurrencyId currency) throws SQLException;

    LedgerResult apply(LedgerMutation mutation) throws SQLException;
}
