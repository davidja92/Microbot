package net.runelite.client.plugins.microbot.huntertrapper;

import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Skill;
import net.runelite.api.Experience;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.EnumSet;

public class HunterTrapperScript extends Script
{
    // ===== Fixed internal loop delay (no config) =====
    private static final int LOOP_DELAY_MS = 1000;

    // Swamp lizard trap object IDs
    private static final int FULL_TRAP_ID = 9004;   // net+rope with lizard caught
    private static final int EMPTY_TRAP_ID = 9341;  // collapsed / needs reset

    // Items
    private static final String ITEM_ROPE = "Rope";
    private static final String ITEM_NET  = "Small fishing net";

    // Trap tiles (priority order)
    private final WorldPoint northTrapLocation = new WorldPoint(3536, 3451, 0); // #1
    private final WorldPoint southTrapLocation = new WorldPoint(3538, 3445, 0); // #2 (offset check 3537,3445,0)
    private final WorldPoint westTrapLocation  = new WorldPoint(3532, 3447, 0); // #3 (offset check 3532,3446,0)
    private final WorldPoint eastTrapLocation  = new WorldPoint(3550, 3449, 0); // #4 (offset check 3549,3449,0)
    private final WorldPoint east2TrapLocation = new WorldPoint(3553, 3450, 0); // #5 (offset check 3552,3450,0)

    // Center tile
    private final WorldPoint swampHomeTile = new WorldPoint(3537, 3448, 0);

    // Loot rope/net near traps (10 tiles)
    private final LootingParameters supplyLootParams = new LootingParameters(
            10, 1, 1, 1, false, true, new String[] {ITEM_ROPE, ITEM_NET}
    );

    private enum Mode { TRAPPING, GOING_TO_BANK, BANKING, RETURNING }
    private volatile Mode mode = Mode.TRAPPING;

    // Banking lock + snapshot (don’t interrupt banking once started)
    private volatile boolean bankingLock = false;
    private volatile int snapshotSetCount = 0;

    private enum BankPhase { OPEN, DEPOSIT, RESTOCK, CLOSE }
    private volatile BankPhase bankPhase = BankPhase.OPEN;
    private volatile int depositAttempts = 0;

    // Need calc (every tick)
    private volatile boolean needRestock = false;
    private volatile int needRope = 0;
    private volatile int needNet  = 0;

    // Loot-before-bank cooldown
    private volatile long lastLootAttemptMs = 0;
    private static final long LOOT_GRACE_MS = 2500;

    // Startup helpers
    private volatile boolean startupSupplyChecked = false;
    private volatile boolean startupReturnHomePending = false;

    // World-hop control
    private static final long HOP_COOLDOWN_MS = 15000;
    private volatile long lastHopMs = 0;
    private volatile int rehopAttempts = 0;

    // ===== Metrics (XP, actions-to-next, profit via GE) =====
    private volatile long sessionStartMs = 0L;
    private volatile int startHunterXp = -1;
    private volatile long lastPriceCheckMs = 0L;
    private static final long PRICE_REFRESH_MS = 300_000L; // 5 min
    private volatile int cachedLizardPrice = 0; // gp each
    private volatile long profitGp = 0L;

    private volatile String status = "Idle";
    private volatile int catches = 0;

    // --------- PUBLIC GETTERS (overlay) ---------
    public String getStatus() { return status; }
    public int getCatches() { return catches; }
    public long getSessionRuntimeMs() { return Math.max(0L, System.currentTimeMillis() - Math.max(1L, sessionStartMs)); }
    public int getXpGained()
    {
        try { return Math.max(0, Microbot.getClient().getSkillExperience(Skill.HUNTER) - Math.max(0, startHunterXp)); }
        catch (Throwable t) { return 0; }
    }
    public long getXpPerHour()
    {
        long elapsed = Math.max(1L, getSessionRuntimeMs());
        return (long) getXpGained() * 3600000L / elapsed;
    }
    public int getNextLevelTarget()
    {
        try { return Math.min(99, Math.max(1, Microbot.getClient().getRealSkillLevel(Skill.HUNTER)) + 1); }
        catch (Throwable t) { return 2; }
    }
    public int getActionsToNext()
    {
        try {
            int currXp = Microbot.getClient().getSkillExperience(Skill.HUNTER);
            int nextLevel = getNextLevelTarget();
            int xpForNext = Experience.getXpForLevel(nextLevel);
            int xpToNext = Math.max(0, xpForNext - currXp);
            double xpPerAction = (catches > 0 && getXpGained() > 0) ? (double) getXpGained() / (double) catches : 34.0;
            return (int) Math.ceil(xpToNext / Math.max(1.0, xpPerAction));
        } catch (Throwable t) { return 0; }
    }
    public long getProfitGp() { return Math.max(0L, profitGp); }
    public int getEachPrice() { return Math.max(0, getSwampLizardPrice()); }
    // ----------------------------------------------------

    public boolean run(HunterTrapperConfig config)
    {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis();
                if (startHunterXp < 0) {
                    try { startHunterXp = Microbot.getClient().getSkillExperience(Skill.HUNTER); } catch (Throwable ignored) { startHunterXp = 0; }
                }

                switch (mode) {
                    case TRAPPING:      trapTick(config);   break;
                    case GOING_TO_BANK: goToBankTick();     break;
                    case BANKING:       bankingTick();      break;
                    case RETURNING:     returnTick();       break;
                }
            } catch (Exception ex) {
                System.out.println("[HunterTrapper] " + ex.getMessage());
            }
        }, 0, LOOP_DELAY_MS, TimeUnit.MILLISECONDS);
        return true;
    }

    /* =========================  TRAPPING FLOW  ========================= */

    private void trapTick(HunterTrapperConfig config)
    {
        if (bankingLock) { setStatusWithMetrics("Pathing to bank (locked)"); mode = Mode.GOING_TO_BANK; return; }

        // --- WORLD HOP GUARD ---
        if (rehopAttempts > 0 && isBadWorld()) {
            if (System.currentTimeMillis() - lastHopMs > 2000) {
                setStatusWithMetrics("Re-hopping out of PvP/Speedrun world");
                tryCloseBankBeforeHop();
                if (hopRandomViaReflection()) {
                    lastHopMs = System.currentTimeMillis();
                    rehopAttempts--;
                }
            }
            return;
        }

        // --- WORLD HOP TRIGGER: other player near any trap tile ---
        if (System.currentTimeMillis() - lastHopMs > HOP_COOLDOWN_MS && otherPlayerAtTraps())
        {
            setStatusWithMetrics("Player nearby → dismantle & world hop");
            dismantleIfSet(northTrapLocation);
            dismantleIfSet(southTrapLocation);
            dismantleIfSet(westTrapLocation);
            dismantleIfSet(eastTrapLocation);
            dismantleIfSet(east2TrapLocation);

            tryCloseBankBeforeHop();
            if (hopRandomViaReflection()) {
                lastHopMs = System.currentTimeMillis();
                rehopAttempts = 5;
            }
            return;
        }

        // Full → bank
        if (Rs2Inventory.isFull() && cfgBankOnFull(config)) {
            beginBankingWithSnapshot();
            return;
        }

        tickSwampLizard(config);
    }

    /* =========================  SWAMP LIZARD LOGIC ========================= */

    private void tickSwampLizard(HunterTrapperConfig config)
    {
        int allowed = getAllowedTraps();

        // Startup: if already stocked, move to trap center
        startupCenterIfStocked(allowed, swampHomeTile, ITEM_ROPE, ITEM_NET);

        // Resolve traps
        GameObject northTrap = Rs2GameObject.getGameObject(northTrapLocation);
        GameObject southTrap = Rs2GameObject.getGameObject(southTrapLocation);
        GameObject westTrap  = Rs2GameObject.getGameObject(westTrapLocation);
        GameObject eastTrap  = Rs2GameObject.getGameObject(eastTrapLocation);
        GameObject east2Trap = Rs2GameObject.getGameObject(east2TrapLocation);

        int northTrapID = (northTrap != null) ? northTrap.getId() : 0;
        int southTrapID = (southTrap != null) ? southTrap.getId() : 0;
        int westTrapID  = (westTrap  != null) ? westTrap.getId()  : 0;
        int eastTrapID  = (eastTrap  != null) ? eastTrap.getId()  : 0;
        int east2TrapID = (east2Trap != null) ? east2Trap.getId() : 0;

        computeSupplyNeedsSwamp(allowed, northTrapID, southTrapID, westTrapID, eastTrapID, east2TrapID);

        if (needRestock) {
            int ropeInv = getCountSafe(ITEM_ROPE);
            int netInv  = getCountSafe(ITEM_NET);
            int setAllowed = countSetAmongAllowedSwamp(allowed, northTrapID, southTrapID, westTrapID, eastTrapID, east2TrapID);

            if (ropeInv == 0 && netInv == 0 && setAllowed == 0) {
                setStatusWithMetrics("No supplies & no traps set → banking");
                beginBankingWithSnapshot();
                return;
            }

            if (lastLootAttemptMs == 0) {
                setStatusWithMetrics("Short on supplies → looting MY dropped nets/rope");
                lootMySuppliesOwnedOnly();
                lastLootAttemptMs = System.currentTimeMillis();
                return;
            }
            if (System.currentTimeMillis() - lastLootAttemptMs >= LOOT_GRACE_MS) {
                setStatusWithMetrics("Loot grace over → banking");
                beginBankingWithSnapshot();
            } else {
                setStatusWithMetrics("Waiting briefly before banking...");
            }
            return;
        } else {
            lastLootAttemptMs = 0;
        }

        // Process traps in priority order, only up to 'allowed'
        int processed = 0;

        if (processed < allowed) {
            if (isTrapSet(northTrapID)) {
                if (northTrapID == FULL_TRAP_ID) {
                    if (Rs2GameObject.interact(northTrapLocation, "Check")) { catches++; recalcProfit(); }
                    setStatusWithMetrics("Checking north trap"); return;
                }
            } else if (northTrapID == EMPTY_TRAP_ID && haveSuppliesSwamp()) {
                Rs2GameObject.interact(northTrapLocation, "Set-trap");
                setStatusWithMetrics("Resetting north trap"); return;
            }
            processed++;
        }

        if (processed < allowed) {
            if (isTrapSet(southTrapID)) {
                if (southTrapID == FULL_TRAP_ID) {
                    if (Rs2GameObject.interact(new WorldPoint(3537, 3445, 0), "Check")) { catches++; recalcProfit(); }
                    setStatusWithMetrics("Checking south trap"); return;
                }
            } else if (southTrapID == EMPTY_TRAP_ID && haveSuppliesSwamp()) {
                Rs2GameObject.interact(southTrapLocation, "Set-trap");
                setStatusWithMetrics("Resetting south trap"); return;
            }
            processed++;
        }

        if (processed < allowed) {
            if (isTrapSet(westTrapID)) {
                if (westTrapID == FULL_TRAP_ID) {
                    // WEST uses offset tile for check (3532,3446,0)
                    if (Rs2GameObject.interact(new WorldPoint(3532, 3446, 0), "Check")) { catches++; recalcProfit(); }
                    setStatusWithMetrics("Checking west trap"); return;
                }
            } else if (westTrapID == EMPTY_TRAP_ID && haveSuppliesSwamp()) {
                Rs2GameObject.interact(westTrapLocation, "Set-trap");
                setStatusWithMetrics("Resetting west trap"); return;
            }
            processed++;
        }

        if (processed < allowed) {
            if (isTrapSet(eastTrapID)) {
                if (eastTrapID == FULL_TRAP_ID) {
                    // EAST offset check (3549,3449,0)
                    if (Rs2GameObject.interact(new WorldPoint(3549, 3449, 0), "Check")) { catches++; recalcProfit(); }
                    setStatusWithMetrics("Checking east trap"); return;
                }
            } else if (eastTrapID == EMPTY_TRAP_ID && haveSuppliesSwamp()) {
                Rs2GameObject.interact(eastTrapLocation, "Set-trap");
                setStatusWithMetrics("Resetting east trap"); return;
            }
            processed++;
        }

        if (processed < allowed) {
            if (isTrapSet(east2TrapID)) {
                if (east2TrapID == FULL_TRAP_ID) {
                    // EAST-2 offset check (3552,3450,0)
                    if (Rs2GameObject.interact(new WorldPoint(3552, 3450, 0), "Check")) { catches++; recalcProfit(); }
                    setStatusWithMetrics("Checking east-2 trap"); return;
                }
            } else if (east2TrapID == EMPTY_TRAP_ID && haveSuppliesSwamp()) {
                Rs2GameObject.interact(east2TrapLocation, "Set-trap");
                setStatusWithMetrics("Resetting east-2 trap"); return;
            }
        }

        if (isMovingOrInteracting()) {
            setStatusWithMetrics("Moving/Interacting");
            return;
        }

        if (cfgEnableLooting(config)) {
            lootMySuppliesOwnedOnly();
            setStatusWithMetrics("Looting my supplies (owned only)");
        } else {
            setStatusWithMetrics("Idle");
        }

        computeSupplyNeedsSwamp(allowed, northTrapID, southTrapID, westTrapID, eastTrapID, east2TrapID);
        if (needRestock && (lastLootAttemptMs == 0 || System.currentTimeMillis() - lastLootAttemptMs >= LOOT_GRACE_MS)) {
            beginBankingWithSnapshot();
            lastLootAttemptMs = 0;
        }
    }

    /* =========================  BANKING FLOW  ========================= */

    private void beginBankingWithSnapshot()
    {
        int allowed = getAllowedTraps();
        int setCount = 0;

        GameObject n = Rs2GameObject.getGameObject(northTrapLocation);
        GameObject s = Rs2GameObject.getGameObject(southTrapLocation);
        GameObject w = Rs2GameObject.getGameObject(westTrapLocation);
        GameObject e = Rs2GameObject.getGameObject(eastTrapLocation);
        GameObject e2= Rs2GameObject.getGameObject(east2TrapLocation);

        if (allowed >= 1 && n != null && isTrapSet(n.getId())) setCount++;
        if (allowed >= 2 && s != null && isTrapSet(s.getId())) setCount++;
        if (allowed >= 3 && w != null && isTrapSet(w.getId())) setCount++;
        if (allowed >= 4 && e != null && isTrapSet(e.getId())) setCount++;
        if (allowed >= 5 && e2!= null && isTrapSet(e2.getId())) setCount++;

        snapshotSetCount = setCount;

        bankingLock = true;
        bankPhase = BankPhase.OPEN;
        depositAttempts = 0;
        setStatusWithMetrics("Banking (locked)");
        mode = Mode.GOING_TO_BANK;
    }

    private void goToBankTick()
    {
        setStatusWithMetrics("Pathing to bank (locked)");
        Rs2Bank.walkToBank();
        try { Rs2Bank.openBank(); } catch (Throwable ignored) {}
        mode = Mode.BANKING;
    }

    private void bankingTick()
    {
        switch (bankPhase) {
            case OPEN:
                setStatusWithMetrics("Opening bank (locked)");
                if (!Rs2Bank.isOpen()) {
                    Rs2Bank.walkToBank();
                    Rs2Bank.openBank();
                    return;
                }
                bankPhase = BankPhase.DEPOSIT;
                return;

            case DEPOSIT:
                setStatusWithMetrics("Depositing inventory (locked)");
                if (depositEverythingRobust()) {
                    bankPhase = BankPhase.RESTOCK;
                    return;
                }
                depositAttempts++;
                if (depositAttempts >= 5) {
                    bankPhase = BankPhase.RESTOCK;
                }
                return;

            case RESTOCK:
                setStatusWithMetrics("Restocking supplies (locked)");

                int allowed = getAllowedTraps();
                int ropeInv = getCountSafe(ITEM_ROPE);
                int netInv  = getCountSafe(ITEM_NET);

                int ropeShort = Math.max(0, allowed - (snapshotSetCount + ropeInv));
                int netShort  = Math.max(0, allowed - (snapshotSetCount + netInv));

                boolean ok = true;
                if (ropeShort > 0 && !Rs2Bank.withdrawX(ITEM_ROPE, ropeShort)) ok = false;
                if (netShort  > 0 && !Rs2Bank.withdrawX(ITEM_NET,  netShort))  ok = false;

                if (!ok) setStatusWithMetrics("Not enough supplies in bank (locked)");
                bankPhase = BankPhase.CLOSE;
                return;

            case CLOSE:
                setStatusWithMetrics("Closing bank (locked)");
                Rs2Bank.closeBank();
                mode = Mode.RETURNING;
                return;
        }
    }

    private void returnTick()
    {
        setStatusWithMetrics("Returning to center (locked)");
        if (swampHomeTile.distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) <= 3) {
            bankingLock = false;
            snapshotSetCount = 0;
            mode = Mode.TRAPPING;
            setStatusWithMetrics("At center");
            return;
        }
        Rs2Walker.walkTo(swampHomeTile);
    }

    /* =========================  HELPERS ========================= */

    private void startupCenterIfStocked(int allowed, WorldPoint home, String itemA, String itemB)
    {
        if (!startupSupplyChecked) {
            int a = getCountSafe(itemA);
            int b = getCountSafe(itemB);
            startupSupplyChecked = true;
            if (a >= allowed && b >= allowed) {
                if (!isNearLocation(home, 3)) {
                    startupReturnHomePending = true;
                }
            }
        }
        if (startupReturnHomePending) {
            if (!isNearLocation(home, 3)) {
                Rs2Walker.walkTo(home);
                setStatusWithMetrics("Heading to center (startup)");
                return;
            } else {
                startupReturnHomePending = false;
                setStatusWithMetrics("At center (startup)");
            }
        }
    }

    private boolean haveSuppliesSwamp()
    {
        return Rs2Inventory.count(ITEM_ROPE) > 0 && Rs2Inventory.count(ITEM_NET) > 0;
    }

    /** A net trap is "set" if there is a trap object present that isn’t the collapsed id. */
    private boolean isTrapSet(int trapId)
    {
        return trapId != 0 && trapId != EMPTY_TRAP_ID;
    }

    private void computeSupplyNeedsSwamp(int allowed,
                                         int northTrapID, int southTrapID, int westTrapID, int eastTrapID, int east2TrapID)
    {
        int setCount = 0;
        if (allowed >= 1 && isTrapSet(northTrapID)) setCount++;
        if (allowed >= 2 && isTrapSet(southTrapID)) setCount++;
        if (allowed >= 3 && isTrapSet(westTrapID))  setCount++;
        if (allowed >= 4 && isTrapSet(eastTrapID))  setCount++;
        if (allowed >= 5 && isTrapSet(east2TrapID)) setCount++;

        int ropeInv = getCountSafe(ITEM_ROPE);
        int netInv  = getCountSafe(ITEM_NET);

        int ropeShort = Math.max(0, allowed - (setCount + ropeInv));
        int netShort  = Math.max(0, allowed - (setCount + netInv));

        needRope = ropeShort;
        needNet  = netShort;
        needRestock = (needRope > 0) || (needNet > 0);
    }

    private int countSetAmongAllowedSwamp(int allowed, int northTrapID, int southTrapID, int westTrapID, int eastTrapID, int east2TrapID)
    {
        int setCount = 0;
        if (allowed >= 1 && isTrapSet(northTrapID)) setCount++;
        if (allowed >= 2 && isTrapSet(southTrapID)) setCount++;
        if (allowed >= 3 && isTrapSet(westTrapID))  setCount++;
        if (allowed >= 4 && isTrapSet(eastTrapID))  setCount++;
        if (allowed >= 5 && isTrapSet(east2TrapID)) setCount++;
        return setCount;
    }

    private int getAllowedTraps()
    {
        int lvl = 1;
        try { lvl = Math.max(1, Microbot.getClient().getRealSkillLevel(Skill.HUNTER)); } catch (Throwable ignored) {}
        if (lvl >= 80) return 5;
        if (lvl >= 60) return 4;
        if (lvl >= 40) return 3;
        if (lvl >= 20) return 2;
        return 1;
    }

    private void dismantleIfSet(WorldPoint wp)
    {
        GameObject obj = Rs2GameObject.getGameObject(wp);
        if (obj != null) {
            tryInteractAny(wp, "Dismantle");
        }
    }

    private boolean tryInteractAny(WorldPoint wp, String action)
    {
        try {
            return Rs2GameObject.interact(wp, action);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean otherPlayerAtTraps()
    {
        try {
            List<Player> players = Microbot.getClient().getPlayers();
            Player me = Microbot.getClient().getLocalPlayer();
            if (players == null || me == null) return false;

            for (Player p : players) {
                if (p == null || p == me) continue;
                WorldPoint pw = p.getWorldLocation();
                if (pw == null) continue;

                if (isNear(pw, northTrapLocation) || isNear(pw, southTrapLocation) ||
                        isNear(pw, westTrapLocation)  || isNear(pw, eastTrapLocation) ||
                        isNear(pw, east2TrapLocation)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isNear(WorldPoint a, WorldPoint b)
    {
        return a.getPlane() == b.getPlane() && a.distanceTo(b) <= 1;
    }

    private boolean isNearLocation(WorldPoint wp, int radius)
    {
        try {
            WorldPoint me = Microbot.getClient().getLocalPlayer().getWorldLocation();
            return me != null && me.getPlane() == wp.getPlane() && me.distanceTo(wp) <= Math.max(0, radius);
        } catch (Throwable t) { return false; }
    }

    /* =========================  BANK HELPERS ========================= */

    /** Strong deposit with fallbacks so we don't idle at the bank. */
    private boolean depositEverythingRobust()
    {
        boolean invoked = false;
        try { Rs2Bank.depositAll(); invoked = true; } catch (Throwable ignored) {}
        if (Rs2Inventory.isEmpty()) return true;

        // Fallbacks for forks where depositAll can be partial
        try { Rs2Bank.depositAll("Swamp lizard"); invoked = true; } catch (Throwable ignored) {}
        try { Rs2Bank.depositAll("Rope"); invoked = true; } catch (Throwable ignored) {}
        try { Rs2Bank.depositAll("Small fishing net"); invoked = true; } catch (Throwable ignored) {}

        // Consider success if empty OR no lizards & no supplies remain
        boolean cleared = Rs2Inventory.isEmpty()
                || (!Rs2Inventory.hasItem("Swamp lizard") && getCountSafe(ITEM_ROPE) == 0 && getCountSafe(ITEM_NET) == 0);

        return cleared || invoked;
    }

    private int getCountSafe(String name)
    {
        try { return Rs2Inventory.count(name); }
        catch (Throwable t) { return Rs2Inventory.hasItem(name) ? 1 : 0; }
    }

    /**
     * Loot only *your* Rope / Small fishing net using owned-only where available.
     */
    private boolean lootMySuppliesOwnedOnly()
    {
        enableOwnedOnlyGlobal(true);
        try {
            Rs2GroundItem.lootItemsBasedOnNames(
                    new LootingParameters(supplyLootParams.getRange(), 1, 1, 1, false, true, new String[] {ITEM_ROPE})
            );
            Rs2GroundItem.lootItemsBasedOnNames(
                    new LootingParameters(supplyLootParams.getRange(), 1, 1, 1, false, true, new String[] {ITEM_NET})
            );
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            enableOwnedOnlyGlobal(false);
        }
    }

    private boolean enableOwnedOnlyOnParams(LootingParameters params)
    {
        try {
            for (String m : new String[]{
                    "setLootMyItemsOnly", "setLootOnlyMyItems", "setOnlyLootMyItems",
                    "setOwnedOnly", "setOnlyOwnedItems"
            }) {
                try { LootingParameters.class.getMethod(m, boolean.class).invoke(params, true); return true; }
                catch (NoSuchMethodException ignored) {}
            }
            for (String f : new String[]{
                    "lootMyItemsOnly", "lootOnlyMyItems", "onlyLootMyItems",
                    "ownedOnly", "onlyOwnedItems"
            }) {
                try {
                    Field fld = LootingParameters.class.getDeclaredField(f);
                    fld.setAccessible(true);
                    fld.setBoolean(params, true);
                    return true;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean enableOwnedOnlyGlobal(boolean value)
    {
        try {
            for (String m : new String[]{
                    "setLootMyItemsOnly", "setLootOnlyMyItems", "setOnlyLootMyItems",
                    "setOwnedOnly", "setOnlyOwnedItems"
            }) {
                try {
                    Rs2GroundItem.class.getMethod(m, boolean.class).invoke(null, value);
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /* =========================  WORLD HOP HELPERS ========================= */

    private boolean isBadWorld() {
        try {
            EnumSet<WorldType> types = Microbot.getClient().getWorldType();
            if (types == null) return false;
            for (WorldType t : types) {
                if (t == null) continue;
                String n = t.name();
                if (n == null) continue;
                n = n.toUpperCase();
                if (n.contains("PVP")) return true;
                if (n.contains("DEADMAN") || n.contains("DMM")) return true;
                if (n.contains("SPEEDRUN")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void tryCloseBankBeforeHop() {
        try { if (Rs2Bank.isOpen()) Rs2Bank.closeBank(); } catch (Throwable ignored) {}
    }

    private boolean hopRandomViaReflection() {
        tryCloseBankBeforeHop();

        String[] classCandidates = new String[] {
                "net.runelite.client.plugins.microbot.util.worldhopper.Rs2WorldHopper",
                "net.runelite.client.plugins.microbot.util.world.Rs2WorldHopper",
                "net.runelite.client.plugins.microbot.util.Rs2WorldHopper"
        };
        String[] methodCandidatesNoArgs = new String[] {
                "hopToRandomWorld", "hopRandom", "hopToRandom", "hopToRandomP2PWorld", "hopToP2PWorld"
        };

        for (String clsName : classCandidates) {
            try {
                Class<?> c = Class.forName(clsName);
                for (String m : methodCandidatesNoArgs) {
                    try {
                        java.lang.reflect.Method meth = c.getMethod(m);
                        meth.setAccessible(true);
                        meth.invoke(null);
                        return true;
                    } catch (NoSuchMethodException ignored) {
                    } catch (Throwable invokeErr) {
                    }
                }
            } catch (Throwable ignored) {}
        }

        for (String clsName : classCandidates) {
            try {
                Class<?> c = Class.forName(clsName);
                for (java.lang.reflect.Method meth : c.getMethods()) {
                    if (!meth.getName().toLowerCase().contains("hop")) continue;
                    Class<?>[] params = meth.getParameterTypes();
                    if (params.length == 0) continue;
                    boolean allBoolean = true;
                    for (Class<?> p : params) {
                        if (p != boolean.class && p != Boolean.class) { allBoolean = false; break; }
                    }
                    if (!allBoolean) continue;
                    Object[] args = new Object[params.length];
                    Arrays.fill(args, Boolean.TRUE);
                    try {
                        meth.setAccessible(true);
                        meth.invoke(null, args);
                        return true;
                    } catch (Throwable invokeErr) {}
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /* =========================  METRICS ========================= */

    private void recalcProfit()
    {
        int price = getSwampLizardPrice();
        if (price > 0) {
            profitGp = (long) price * (long) catches;
        }
    }

    private int getSwampLizardPrice()
    {
        long now = System.currentTimeMillis();
        if (now - lastPriceCheckMs < PRICE_REFRESH_MS && cachedLizardPrice > 0) {
            return cachedLizardPrice;
        }
        lastPriceCheckMs = now;

        try {
            Method getIM = Microbot.class.getMethod("getItemManager");
            Object itemManager = getIM.invoke(null);
            if (itemManager != null) {
                try {
                    Method getPrice = itemManager.getClass().getMethod("getItemPrice", int.class);
                    int id = ItemID.SWAMP_LIZARD;
                    int price = (Integer) getPrice.invoke(itemManager, id);
                    if (price > 0) { cachedLizardPrice = price; return price; }
                } catch (Throwable ignored) {}

                try {
                    Method getPriceByName = itemManager.getClass().getMethod("getItemPrice", String.class);
                    int price = (Integer) getPriceByName.invoke(itemManager, "Swamp lizard");
                    if (price > 0) { cachedLizardPrice = price; return price; }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        return cachedLizardPrice;
    }

    private String metricsString()
    {
        long now = System.currentTimeMillis();
        int currXp = 0, currLvl = 1;
        try {
            currXp = Microbot.getClient().getSkillExperience(Skill.HUNTER);
            currLvl = Math.max(1, Microbot.getClient().getRealSkillLevel(Skill.HUNTER));
        } catch (Throwable ignored) {}

        long elapsed = Math.max(1L, now - Math.max(sessionStartMs, 1L));
        int gained = Math.max(0, currXp - Math.max(0, startHunterXp));
        long xpPerHour = (long) gained * 3600000L / elapsed;

        int nextLevel = Math.min(99, currLvl + 1);
        int xpForNext = Experience.getXpForLevel(nextLevel);
        int xpToNext = Math.max(0, xpForNext - currXp);

        double xpPerAction = (catches > 0 && gained > 0) ? (double) gained / (double) catches : 34.0;
        int actionsToNext = (int) Math.ceil(xpToNext / Math.max(1.0, xpPerAction));

        int each = getSwampLizardPrice();
        if (each > 0) profitGp = (long) each * (long) catches;

        return String.format("| XP/hr: %,d | To Lvl %d: %s actions | Profit: %,d gp",
                xpPerHour,
                nextLevel,
                (xpToNext == 0 ? "0" : String.valueOf(actionsToNext)),
                Math.max(0L, profitGp));
    }

    private void setStatusWithMetrics(String base)
    {
        try {
            status = base + " " + metricsString();
        } catch (Throwable t) {
            status = base;
        }
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        bankingLock = false;
        snapshotSetCount = 0;
        bankPhase = BankPhase.OPEN;
        depositAttempts = 0;
        status = "Stopped";
    }

    /* =========================  SAFE CONFIG ACCESSORS ========================= */

    private boolean cfgBankOnFull(Object cfg)
    {
        try {
            if (cfg == null) return true;
            Method m = cfg.getClass().getMethod("bankOnFull");
            Object r = m.invoke(cfg);
            return r instanceof Boolean ? (Boolean) r : true;
        } catch (Throwable ignored) { return true; }
    }

    private boolean cfgEnableLooting(Object cfg)
    {
        try {
            if (cfg == null) return true;
            Method m = cfg.getClass().getMethod("enableLooting");
            Object r = m.invoke(cfg);
            return r instanceof Boolean ? (Boolean) r : true;
        } catch (Throwable ignored) { return true; }
    }

    /* =========================  PLAYER MOVEMENT SAFE CHECK ========================= */

    private boolean isMovingOrInteracting()
    {
        try {
            Method mw = Rs2Player.class.getMethod("isWalking");
            boolean walking = (boolean) mw.invoke(null);
            try {
                Method mi = Rs2Player.class.getMethod("isInteracting");
                boolean interacting = (boolean) mi.invoke(null);
                return walking || interacting;
            } catch (NoSuchMethodException e) {
                return walking;
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
