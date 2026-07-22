package org.kseco;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultHookFallbackTest {
    @Test
    void directBuiltinMakesWalletAvailableWithoutVault() {
        assertTrue(VaultHook.walletAvailable(false, true));
        assertTrue(VaultHook.walletAvailable(true, false));
        assertFalse(VaultHook.walletAvailable(false, false));
    }
}
