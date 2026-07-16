package org.kseco.extra.realestatedungeon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 副本组队 + 指令邀请（纯内存，不落库）。
 *
 * 设计：
 * - 一个 party 由队长持有；members 集合含队长本人。
 * - 邀请有 TTL（2 分钟），被邀请者 /dungeon accept 才入队。
 * - 人数上下限（min/max）不在此校验，由开本时按模板校验（见 DungeonCommand.cmdStart）。
 */
public final class DungeonPartyManager {

    public static final long INVITE_TTL_MS = 120_000L;

    public record Invite(UUID leader, long expireAt) {}

    private final Map<UUID, LinkedHashSet<UUID>> parties = new ConcurrentHashMap<>(); // leader -> members(incl leader)
    private final Map<UUID, UUID> memberToLeader = new ConcurrentHashMap<>();          // member -> leader
    private final Map<UUID, Invite> pending = new ConcurrentHashMap<>();               // invitee -> invite

    /** 队长邀请玩家。返回 null 成功，否则错误信息。 */
    public synchronized String invite(UUID leader, UUID target) {
        if (leader.equals(target)) return "不能邀请自己";
        if (memberToLeader.containsKey(target)) return "对方已在某个副本队伍中";
        UUID myLeader = memberToLeader.get(leader);
        if (myLeader != null && !myLeader.equals(leader)) return "你不是队长，无法邀请";
        parties.computeIfAbsent(leader, k -> {
            LinkedHashSet<UUID> set = new LinkedHashSet<>();
            set.add(leader);
            return set;
        });
        memberToLeader.putIfAbsent(leader, leader);
        pending.put(target, new Invite(leader, System.currentTimeMillis() + INVITE_TTL_MS));
        return null;
    }

    /** 被邀请者接受。返回队长 UUID（成功），或 null（无有效邀请）。 */
    public synchronized UUID accept(UUID target) {
        Invite inv = pending.remove(target);
        if (inv == null || inv.expireAt() < System.currentTimeMillis()) return null;
        if (memberToLeader.containsKey(target)) return null;
        LinkedHashSet<UUID> party = parties.get(inv.leader());
        if (party == null) return null;
        party.add(target);
        memberToLeader.put(target, inv.leader());
        return inv.leader();
    }

    public UUID leaderOf(UUID member) { return memberToLeader.get(member); }

    public List<UUID> membersOf(UUID leader) {
        LinkedHashSet<UUID> p = parties.get(leader);
        return p == null ? List.of(leader) : new ArrayList<>(p);
    }

    /** 离队；队长离队则解散。返回 true 表示有变化。 */
    public synchronized boolean leave(UUID uuid) {
        UUID leader = memberToLeader.get(uuid);
        if (leader == null) return false;
        if (leader.equals(uuid)) { disband(leader); return true; }
        LinkedHashSet<UUID> p = parties.get(leader);
        if (p != null) p.remove(uuid);
        memberToLeader.remove(uuid);
        return true;
    }

    public synchronized void disband(UUID leader) {
        LinkedHashSet<UUID> p = parties.remove(leader);
        if (p != null) for (UUID m : p) memberToLeader.remove(m);
        pending.entrySet().removeIf(e -> e.getValue().leader().equals(leader));
    }
}
