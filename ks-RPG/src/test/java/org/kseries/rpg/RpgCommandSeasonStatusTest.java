package org.kseries.rpg;

import org.junit.jupiter.api.Test;
import org.kseries.rpg.api.RpgSeasonStatusApi;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpgCommandSeasonStatusTest {
    @Test
    void disabledPlayerFeedbackExplicitlySaysFeatureIsNotEnabled() {
        var status = new RpgSeasonStatusApi.RuntimeStatus(false, false,
                RpgSeasonStatusApi.RuntimeState.DISABLED, "disabled", "C:/private/season.db");

        List<String> lines = RpgCommand.seasonStatusLines(status, false);

        assertTrue(lines.stream().anyMatch(line -> line.contains("未启用")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("C:/private")));
    }

    @Test
    void readyFeedbackSaysNoEventsOrAutomaticPlayerMutation() {
        var status = new RpgSeasonStatusApi.RuntimeStatus(true, true,
                RpgSeasonStatusApi.RuntimeState.READY, "ready", "C:/server/season.db");

        List<String> player = RpgCommand.seasonStatusLines(status, false);
        List<String> admin = RpgCommand.seasonStatusLines(status, true);

        assertTrue(player.stream().anyMatch(line -> line.contains("未启动事件")));
        assertTrue(player.stream().anyMatch(line -> line.contains("不会自动改动玩家数据")));
        assertTrue(admin.stream().anyMatch(line -> line.contains("storage=C:/server/season.db")));
    }
}
