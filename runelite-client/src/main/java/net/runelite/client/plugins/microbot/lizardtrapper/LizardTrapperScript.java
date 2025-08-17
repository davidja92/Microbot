package net.runelite.client.plugins.microbot.lizardtrapper;

import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class LizardTrapperScript extends Script
{
    // Trap object IDs
    private static final int FULL_TRAP_ID  = 9004;
    private static final int EMPTY_TRAP_ID = 9341;

    // Items
    private static final String ROPE   = "Rope";
    private static final String NET    = "Small fishing net";
    private static final String LIZARD = "Swamp lizard";

    // XP per catch (adjust if your server differs)
    private static final int CATCH_XP_DEFAULT = 34;

    // Trap tiles (priority order for placement)
    private static final WorldPoint NORTH      = new WorldPoint(3536, 3451, 0);
    private static final WorldPoint SOUTH      = new WorldPoint(3538, 3445, 0);
    private static final WorldPoint NORTHEAST  = new WorldPoint(3532, 3446, 0);
    private static final WorldPoint SOUTHWEST  = new WorldPoint(3549, 3449, 0);

    // Start/return tile (anchor)
    private static final WorldPoint START_TILE = NORTH;

    // Placement waiting thresholds
    private static final int MIN_SETTLE_MS       = 1400; // minimum idle after a placement click
    private static final int MAX_PLACE_WAIT_MS   = 6000; // after this, retry the click
    private static final int PLACE_RETRY_LIMIT   = 3;    // avoid infinite loops

    // Contest detection
    private static final int CONTEST_RADIUS_TILES = 1;   // another player within 1 tile of a trap tile
    private static final int POST_HOP_WAIT_MS     = 2000;

    // State & tracking
    private volatile boolean positionedAtStart = false;
    private volatile boolean bankingMode = false;       // persist through bank+return
    private volatile boolean bankingSkipRestock = false; // NEW: don't restock if traps are deployed when entering bank
    private volatile String status = "Idle";

    // "Pending placement" guard
    private volatile WorldPoint pendingPlace = null;
    private volatile long pendingStartAt = 0L;
    private volatile int pendingRetries = 0;

    private long startMillis = 0L;
    private int startXp = 0;
    private long totalLizBanked = 0L;

    // GE price cache (5 min)
    private Integer gePriceCache = null;
    private long gePriceCachedAt = 0L;

    // Loot params (recover rope/net if dropped)
    private final LootingParameters lootParams = new LootingParameters(
            10, 1, 1, 1, false, true,
            "Rope,Small fishing net".split(",")
    );

    // ===== Public getters used by overlay =====
    public String statusLine() { return status; }
    public long   getTotalLizBanked() { return totalLizBanked; }
    public String getFormattedUptime() {
        long ms = Math.max(0, System.currentTimeMillis() - startMillis);
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s/3600, (s%3600)/60, s%60);
    }
    public long getTotalProfit(int priceEach) { return priceEach <= 0 ? 0L : totalLizBanked * (long) priceEach; }
    public int getXpGained() { return Math.max(0, getHunterXpSafe() - startXp); }
    public long getXpPerHour() {
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startMillis);
        return (long)(getXpGained() / (elapsedMs / 3600000.0));
    }
    public int getXpToNextLevel() {
        int curLvl = getHunterLevelSafe();
        if (curLvl >= 99) return 0;
        int curXp  = getHunterXpSafe();
        int nextXp = xpForLevel(curLvl + 1);
        return Math.max(0, nextXp - curXp);
    }
    public int getActionsToNextLevel() {
        int xpEach = Math.max(1, CATCH_XP_DEFAULT);
        int xpLeft = getXpToNextLevel();
        return (int)Math.ceil(xpLeft / (double)xpEach);
    }
    public Integer getGePriceSwampLizard() {
        if (gePriceCache != null && System.currentTimeMillis() - gePriceCachedAt < 5 * 60_000) return gePriceCache;
        try {
            Class<?> itemIdCls = Class.forName("net.runelite.api.ItemID");
            Field fld = itemIdCls.getField("SWAMP_LIZARD");
            int itemId = fld.getInt(null);

            Object itemManager = null;
            try { itemManager = Microbot.class.getMethod("getItemManager").invoke(null); } catch (Throwable ignored) {}
            if (itemManager == null) { try { itemManager = Microbot.class.getField("itemManager").get(null); } catch (Throwable ignored) {} }
            if (itemManager == null) return null;

            try {
                Object res = itemManager.getClass().getMethod("getItemPrice", int.class).invoke(itemManager, itemId);
                if (res instanceof Integer) { gePriceCache = (Integer) res; gePriceCachedAt = System.currentTimeMillis(); return gePriceCache; }
            } catch (Throwable ignored) {}
            try {
                Object fut = itemManager.getClass().getMethod("getItemPriceAsync", int.class).invoke(itemManager, itemId);
                Object res = fut.getClass().getMethod("get").invoke(fut);
                if (res instanceof Integer) { gePriceCache = (Integer) res; gePriceCachedAt = System.currentTimeMillis(); return gePriceCache; }
            } catch (Throwable ignored) {}
        } catch (Throwable ignoredOuter) {}
        return null;
    }

    // ===== Main run loop =====
    public boolean run(LizardTrapperConfig config)
    {
        Microbot.enableAutoRunOn = false;

        startMillis = System.currentTimeMillis();
        startXp = getHunterXpSafe();
        totalLizBanked = 0L;
        positionedAtStart = false;
        bankingMode = false;
        bankingSkipRestock = false;
        status = "Booting";
        clearPending();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                final int hunterLvl = getHunterLevelSafe();
                final int trapLimit = trapLimitFromLevel(hunterLvl);

                WorldPoint me = Rs2Player.getWorldLocation();

                // If we're in bankingMode, keep banking & returning until we're back at start (don't place/check during this)
                if (bankingMode) {
                    // Exit banking mode only once we've reached START_TILE and inventory is not full
                    if (me != null && me.distanceTo(START_TILE) <= 1 && !Rs2Inventory.isFull()) {
                        bankingMode = false;
                        positionedAtStart = true; // anchor again
                        status = "Ready";
                    } else {
                        status = "Banking…";
                        bankLizAndReturn(config, trapLimit, bankingSkipRestock);
                        return;
                    }
                }

                // Start only if we have mats for trapLimit
                if (!positionedAtStart) {
                    if (me != null && me.distanceTo(START_TILE) <= 1 && hasMaterialsForTrapLimit(trapLimit)) {
                        positionedAtStart = true;
                        status = "Ready";
                    } else if (config.autowalkOnEnable() && hasMaterialsForTrapLimit(trapLimit)) {
                        status = "Walking to start…";
                        Rs2Walker.walkTo(START_TILE);
                        return;
                    } else if (config.autowalkOnEnable()) {
                        status = "Ensuring supplies…";
                        if (!ensureSupplies(trapLimit)) return;
                        if (me == null || me.distanceTo(START_TILE) > 1) {
                            status = "Walking to start…";
                            Rs2Walker.walkTo(START_TILE);
                            return;
                        } else {
                            positionedAtStart = true;
                            status = "Ready";
                        }
                    } else {
                        status = "Waiting for player at start…";
                        return;
                    }
                }

                // If inv full, enter persistent banking mode (ignore trap collapses etc.)
                if (Rs2Inventory.isFull() && Rs2Inventory.hasItem(LIZARD)) {
                    // NEW: compute whether traps are deployed NOW; if so, skip restock from bank
                    List<WorldPoint> targetsNow = orderedEnabledTraps(config, trapLimit);
                    bankingSkipRestock = hasDeployedTraps(targetsNow);
                    bankingMode = true;
                    status = "Banking lizards…";
                    // Clear any pending placement (collapsed traps won't interrupt banking)
                    clearPending();
                    bankLizAndReturn(config, trapLimit, bankingSkipRestock);
                    return;
                }

                // Contest check (only if not banking)
                List<WorldPoint> targetsNow = orderedEnabledTraps(config, trapLimit);
                if (isContested(targetsNow, CONTEST_RADIUS_TILES)) {
                    status = "Contested: dismantling & hopping…";
                    handleContested(targetsNow, trapLimit);
                    return;
                }

                // Pending placement guard
                if (pendingPlace != null) {
                    if (Rs2Player.isWalking() || Rs2Player.isInteracting()) {
                        status = "Waiting for placement animation…";
                        return;
                    }

                    long elapsed = System.currentTimeMillis() - pendingStartAt;
                    GameObject obj = Rs2GameObject.getGameObject(pendingPlace);
                    int id = (obj != null) ? obj.getId() : 0;

                    if (elapsed < MIN_SETTLE_MS) {
                        status = "Settling…";
                        return;
                    }

                    if (id != EMPTY_TRAP_ID && id != 0) {
                        clearPending(); // success
                        jitter(150, 250);
                    } else if (elapsed >= MAX_PLACE_WAIT_MS) {
                        if (pendingRetries < PLACE_RETRY_LIMIT && hasMaterialsForOneTrap()) {
                            status = "Retrying placement @ " + pendingPlace + "…";
                            if (!safeInteract(pendingPlace, "Set-trap")) {
                                Rs2Walker.walkTo(pendingPlace);
                            }
                            pendingStartAt = System.currentTimeMillis();
                            pendingRetries++;
                        } else {
                            status = "Placement timed out, moving on…";
                            clearPending();
                        }
                    } else {
                        status = "Waiting for trap to appear…";
                    }
                    return;
                }

                // PHASE A: place traps up to limit (don’t start new one if pending)
                for (WorldPoint tile : targetsNow) {
                    if (attemptPlace(tile)) return; // started a placement; next tick confirms
                }

                // PHASE B: check any full traps
                for (WorldPoint tile : targetsNow) {
                    if (checkIfFull(tile)) return;
                }

                // Recovery: loot rope/net if dropped
                if (!Rs2Player.isWalking() && !Rs2Player.isInteracting()) {
                    Rs2GroundItem.lootItemsBasedOnNames(lootParams);
                }

                status = "Looping";

            } catch (Exception ex) {
                Microbot.log("LizardTrapper error: " + ex.getMessage());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);

        return true;
    }

    // ===== Contest handling =====

    private boolean isContested(List<WorldPoint> traps, int radius) {
        try {
            Player me = Microbot.getClient().getLocalPlayer();
            List<Player> players = Microbot.getClient().getPlayers();
            if (players == null) return false;

            for (Player p : players) {
                if (p == null || p == me) continue;
                WorldPoint pw = p.getWorldLocation();
                if (pw == null) continue;

                for (WorldPoint t : traps) {
                    if (pw.distanceTo(t) <= radius) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Dismantle our traps, loot rope/net, and world hop (members world). */
    private void handleContested(List<WorldPoint> traps, int trapLimit) {
        try {
            // Cancel any pending placement
            clearPending();

            // Dismantle any trap objects we can at our tiles
            for (WorldPoint t : traps) {
                GameObject obj = Rs2GameObject.getGameObject(t);
                if (obj == null) continue;
                int id = obj.getId();
                if (id == EMPTY_TRAP_ID || id == FULL_TRAP_ID) {
                    status = "Dismantling @ " + t;
                    if (!safeInteract(t, "Dismantle")) {
                        Rs2Walker.walkTo(t);
                        safeInteract(t, "Dismantle");
                    }
                    jitter(250, 400);
                }
            }

            // Loot back rope/net if they pop to the ground
            Rs2GroundItem.lootItemsBasedOnNames(lootParams);
            jitter(150, 250);

            // Hop to a different members world
            status = "World hopping (members)…";
            hopToRandomMembersCompat();
            jitter(POST_HOP_WAIT_MS, POST_HOP_WAIT_MS + 500);

            // After hop, we’ll re-check supplies and walk to start next loop
            positionedAtStart = false;

        } catch (Throwable t) {
            Microbot.log("Contest handler error: " + t.getMessage());
        }
    }

    // ===== Trap helpers =====

    private List<WorldPoint> orderedEnabledTraps(LizardTrapperConfig cfg, int trapLimit) {
        List<WorldPoint> list = new ArrayList<>(4);
        if (cfg.useNorth())     list.add(NORTH);
        if (cfg.useSouth())     list.add(SOUTH);
        if (cfg.useNorthEast()) list.add(NORTHEAST);
        if (cfg.useSouthWest()) list.add(SOUTHWEST);
        if (list.size() > trapLimit) list = new ArrayList<>(list.subList(0, trapLimit));
        return list;
    }

    /** Are there any traps currently deployed (tile not EMPTY)? */
    private boolean hasDeployedTraps(List<WorldPoint> traps) {
        for (WorldPoint t : traps) {
            GameObject obj = Rs2GameObject.getGameObject(t);
            int id = (obj != null) ? obj.getId() : 0;
            if (id != 0 && id != EMPTY_TRAP_ID) {
                return true; // includes FULL_TRAP_ID or any set-trap object id
            }
        }
        return false;
    }

    /** Begin a placement if tile is currently EMPTY and we have mats; sets pending state and idles until it's actually placed. */
    private boolean attemptPlace(WorldPoint tile) {
        GameObject trap = Rs2GameObject.getGameObject(tile);
        int id = (trap != null) ? trap.getId() : 0;

        if (id == EMPTY_TRAP_ID && hasMaterialsForOneTrap()) {
            status = "Placing trap @ " + tile;
            if (!safeInteract(tile, "Set-trap")) {
                Rs2Walker.walkTo(tile);
            }
            pendingPlace = tile;
            pendingStartAt = System.currentTimeMillis();
            pendingRetries = 0;
            jitter(250, 400);
            return true;
        }
        return false;
    }

    private boolean checkIfFull(WorldPoint tile) {
        GameObject trap = Rs2GameObject.getGameObject(tile);
        int id = (trap != null) ? trap.getId() : 0;

        if (id == FULL_TRAP_ID) {
            status = "Checking trap @ " + tile;
            WorldPoint clickPoint = tile;
            if (tile.equals(SOUTH)) clickPoint = new WorldPoint(3537, 3445, 0);

            if (!safeInteract(clickPoint, "Check")) {
                Rs2Walker.walkTo(clickPoint);
            }
            jitter(150, 250);
            return true;
        }
        return false;
    }

    private boolean safeInteract(WorldPoint tile, String action) {
        try { Rs2GameObject.interact(tile, action); return true; }
        catch (Throwable t) { return false; }
    }

    private void clearPending() {
        pendingPlace = null;
        pendingStartAt = 0L;
        pendingRetries = 0;
    }

    // ===== Supplies / Banking =====
    private boolean ensureSupplies(int trapLimit)
    {
        if (hasMaterialsForTrapLimit(trapLimit)) return true;

        if (!Rs2Bank.isOpen()) {
            Rs2Bank.walkToBank();
            Rs2Bank.openBank();
            return false;
        }

        depositAllCompat();
        jitter(150, 250);

        // Top up to trapLimit (1 rope + 1 net per trap)
        int needRopes = Math.max(0, trapLimit - getAmount(ROPE));
        int needNets  = Math.max(0, trapLimit - getAmount(NET));

        withdrawAmountCompat(ROPE, needRopes);
        withdrawAmountCompat(NET,  needNets);

        Rs2Bank.closeBank();
        return hasMaterialsForTrapLimit(trapLimit);
    }

    /** Banking that optionally skips restocking if traps are currently deployed. */
    private void bankLizAndReturn(LizardTrapperConfig cfg, int trapLimit, boolean skipRestock)
    {
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.walkToBank();
            Rs2Bank.openBank();
            return;
        }

        int lizInInv = getAmount(LIZARD);

        boolean deposited = tryDepositAllByName(LIZARD);
        if (!deposited) {
            depositAllCompat();
            jitter(150, 250);
        }

        if (lizInInv > 0) totalLizBanked += lizInInv;

        // Only restock if requested AND we actually need mats
        if (!skipRestock) {
            int needRopes = Math.max(0, trapLimit - getAmount(ROPE));
            int needNets  = Math.max(0, trapLimit - getAmount(NET));
            if (needRopes > 0) withdrawAmountCompat(ROPE, needRopes);
            if (needNets  > 0) withdrawAmountCompat(NET,  needNets);
        }

        Rs2Bank.closeBank();
        Rs2Walker.walkTo(START_TILE);
    }

    private boolean hasMaterialsForOneTrap() {
        return getAmount(ROPE) >= 1 && getAmount(NET) >= 1;
    }
    private boolean hasMaterialsForTrapLimit(int trapLimit) {
        return getAmount(ROPE) >= trapLimit && getAmount(NET) >= trapLimit;
    }

    // ===== Level / XP helpers =====
    private int trapLimitFromLevel(int lvl) {
        if (lvl >= 60) return 4;
        if (lvl >= 40) return 3;
        if (lvl >= 20) return 2;
        return 1;
    }

    /** RuneScape XP curve (levels 1..99). */
    private int xpForLevel(int level) {
        if (level <= 1) return 0;
        if (level > 99) level = 99;
        double points = 0.0;
        for (int lvl = 1; lvl < level; lvl++) {
            points += Math.floor(lvl + 300.0 * Math.pow(2.0, lvl / 7.0));
        }
        return (int)Math.floor(points / 4.0);
    }

    private int getHunterLevelSafe()
    {
        try { return Microbot.getClient().getRealSkillLevel(Skill.HUNTER); }
        catch (Throwable t) {
            try { return Microbot.getClient().getBoostedSkillLevel(Skill.HUNTER); }
            catch (Throwable t2) { return 1; }
        }
    }

    private int getHunterXpSafe()
    {
        try { return Microbot.getClient().getSkillExperience(Skill.HUNTER); }
        catch (Throwable t) { return 0; }
    }

    // ===== Inventory counting with broad compatibility =====
    private int getAmount(String name)
    {
        // Try common direct counters first
        String[] direct = {"getItemAmount", "getAmount", "getCount", "getItemCount", "getQuantityOf", "getQuantity", "count"};
        for (String m : direct) {
            try {
                Method mm = Rs2Inventory.class.getMethod(m, String.class);
                Object out = mm.invoke(null, name);
                if (out instanceof Integer) return (Integer) out;
                if (out instanceof Long)    return ((Long) out).intValue();
            } catch (Throwable ignored) {}
        }

        // Try scanning any item collection
        String[] lists = {"getAllItems", "getItems", "getInventory", "items", "all"};
        for (String m : lists) {
            try {
                Method mm = Rs2Inventory.class.getMethod(m);
                Object out = mm.invoke(null);
                if (out instanceof Iterable) {
                    int total = 0;
                    for (Object item : (Iterable<?>) out) {
                        if (item == null) continue;
                        String itemName = null; Integer qty = null;
                        try {
                            Method getName = item.getClass().getMethod("getName");
                            Object n = getName.invoke(item);
                            if (n != null) itemName = String.valueOf(n);
                        } catch (Throwable ignored2) {}
                        try {
                            Method getQty = item.getClass().getMethod("getQuantity");
                            Object q = getQty.invoke(item);
                            if (q instanceof Integer) qty = (Integer) q;
                            else if (q instanceof Long) qty = ((Long) q).intValue();
                        } catch (Throwable ignored2) {}
                        if (itemName != null && itemName.equalsIgnoreCase(name)) {
                            total += (qty != null ? qty : 1);
                        }
                    }
                    return total;
                }
            } catch (Throwable ignored) {}
        }

        // Fallback: boolean presence -> 1/0
        try {
            Method hasItem = Rs2Inventory.class.getMethod("hasItem", String.class);
            Object out = hasItem.invoke(null, name);
            if (out instanceof Boolean && (Boolean) out) return 1;
        } catch (Throwable ignored) {}

        return 0;
    }

    // ===== World hop (compat via reflection) =====
    private void hopToRandomMembersCompat() {
        String[] classes = new String[] {
                "net.runelite.client.plugins.microbot.util.worldhopper.Rs2WorldHopper",
                "net.runelite.client.plugins.microbot.util.world.Rs2WorldHopper",
                "net.runelite.client.plugins.microbot.util.worlds.Rs2WorldHopper"
        };
        for (String cls : classes) {
            try {
                Class<?> c = Class.forName(cls);
                // no-arg methods first
                for (String m : new String[]{"hopToRandomMembers", "hopToRandomP2P"}) {
                    try { c.getMethod(m).invoke(null); return; } catch (Throwable ignored) {}
                }
                // boolean-arg methods
                for (String m : new String[]{"hopToRandomWorld", "hopToRandom"}) {
                    try { c.getMethod(m, boolean.class).invoke(null, true); return; } catch (Throwable ignored) {}
                }
            } catch (Throwable ignoredOuter) {}
        }
        Microbot.log("World hop compat: could not find a hopper method.");
    }

    // ===== Bank compatibility helpers =====
    private boolean depositAllCompat() {
        String[] candidates = {"depositAll", "depositInventory", "depositEverything"};
        for (String m : candidates) {
            try {
                Method method = Rs2Bank.class.getMethod(m);
                Object result = method.invoke(null);
                if (result instanceof Boolean) return (Boolean) result;
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean tryDepositAllByName(String name) {
        String[] candidates = {"depositAll", "depositAllItemsOfName", "deposit"};
        for (String m : candidates) {
            try {
                Method method = Rs2Bank.class.getMethod(m, String.class);
                Object result = method.invoke(null, name);
                if (result instanceof Boolean) return (Boolean) result;
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void withdrawAmountCompat(String name, int amount) {
        if (amount <= 0) return;
        try {
            Method m = Rs2Bank.class.getMethod("withdrawX", String.class, int.class);
            m.invoke(null, name, amount);
            jitter(100, 200);
            return;
        } catch (Throwable ignored) {}
        try {
            Method m = Rs2Bank.class.getMethod("withdraw", String.class, int.class);
            m.invoke(null, name, amount);
            jitter(100, 200);
            return;
        } catch (Throwable ignored) {}
        try {
            Method m = Rs2Bank.class.getMethod("withdraw", String.class);
            for (int i = 0; i < amount; i++) {
                m.invoke(null, name);
                jitter(80, 150);
            }
        } catch (Throwable ignored) {}
    }

    private void jitter(int min, int max) {
        int d = ThreadLocalRandom.current().nextInt(min, max + 1);
        try { Thread.sleep(d); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ===== Misc =====
    @Override
    public void shutdown()
    {
        super.shutdown();
        positionedAtStart = false;
        bankingMode = false;
        bankingSkipRestock = false;
        status = "Idle";
    }
}
