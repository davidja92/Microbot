package net.runelite.client.plugins.microbot.lizardtrapper;

import net.runelite.api.GameObject;
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
    private static final int FULL_TRAP_ID  = 9004;
    private static final int EMPTY_TRAP_ID = 9341;

    private static final String ROPE   = "Rope";
    private static final String NET    = "Small fishing net";
    private static final String LIZARD = "Swamp lizard";
    private static final int REQUIRED_ROPES = 4;
    private static final int REQUIRED_NETS  = 4;

    private static final WorldPoint NORTH      = new WorldPoint(3536, 3451, 0);
    private static final WorldPoint SOUTH      = new WorldPoint(3538, 3445, 0);
    private static final WorldPoint NORTHEAST  = new WorldPoint(3532, 3446, 0);
    private static final WorldPoint SOUTHWEST  = new WorldPoint(3549, 3449, 0);
    private static final WorldPoint START_TILE = NORTH;

    private volatile boolean positionedAtStart = false;
    private volatile String status = "Idle";

    private long startMillis = 0L;
    private int startXp = 0;
    private long totalLizBanked = 0L;

    // GE price cache (5 min)
    private Integer gePriceCache = null;
    private long gePriceCachedAt = 0L;

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
        status = "Booting";

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                // 1) Startup: ensure supplies, then go to start tile
                if (config.autowalkOnEnable() && !positionedAtStart) {
                    status = "Ensuring supplies…";
                    if (!ensureSupplies()) return;

                    WorldPoint me = Rs2Player.getWorldLocation();
                    if (me == null || me.distanceTo(START_TILE) > 1) {
                        status = "Walking to start…";
                        Rs2Walker.walkTo(START_TILE);
                        return;
                    } else {
                        positionedAtStart = true;
                        status = "Ready";
                    }
                }

                // 2) If full, bank ONLY lizards
                if (Rs2Inventory.isFull() && Rs2Inventory.hasItem(LIZARD)) {
                    status = "Banking lizards…";
                    bankLizAndReturn();
                    return;
                }

                // 3) PRIORITY PHASE A: PLACE ALL TRAPS FIRST (maximize uptime/xp)
                List<WorldPoint> targets = orderedEnabledTraps(config);
                for (WorldPoint tile : targets) {
                    if (placeIfEmpty(tile)) return; // issued an action; let the next tick continue
                }

                // 4) PHASE B: after all set, sweep to CHECK any full traps
                for (WorldPoint tile : targets) {
                    if (checkIfFull(tile)) return; // issued an action; next tick
                }

                // 5) Loot nets/ropes if dropped (recovery)
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

    // ===== Trap helpers =====

    /** Build the enabled trap list in the order we want to place them. */
    private List<WorldPoint> orderedEnabledTraps(LizardTrapperConfig cfg) {
        List<WorldPoint> list = new ArrayList<>(4);
        if (cfg.useNorth())     list.add(NORTH);
        if (cfg.useSouth())     list.add(SOUTH);
        if (cfg.useNorthEast()) list.add(NORTHEAST);
        if (cfg.useSouthWest()) list.add(SOUTHWEST);
        return list;
    }

    /** If a tile is empty and we have supplies, walk there (if needed) and set the trap. */
    private boolean placeIfEmpty(WorldPoint tile) {
        GameObject trap = Rs2GameObject.getGameObject(tile);
        int id = (trap != null) ? trap.getId() : 0;

        if (id == EMPTY_TRAP_ID && hasSuppliesInInv()) {
            status = "Placing trap @ " + tile;
            // If the object isn't interactable from here, step to the tile first
            if (!safeInteract(tile, "Set-trap")) {
                Rs2Walker.walkTo(tile);
            }
            return true;
        }
        return false;
    }

    /** If a tile has a full trap, walk/offset click and check it. */
    private boolean checkIfFull(WorldPoint tile) {
        GameObject trap = Rs2GameObject.getGameObject(tile);
        int id = (trap != null) ? trap.getId() : 0;

        if (id == FULL_TRAP_ID) {
            status = "Checking trap @ " + tile;
            WorldPoint clickPoint = tile;
            // tiny offset only for SOUTH to avoid self-clicks (as before)
            if (tile.equals(SOUTH)) clickPoint = new WorldPoint(3537, 3445, 0);

            if (!safeInteract(clickPoint, "Check")) {
                Rs2Walker.walkTo(clickPoint);
            }
            return true;
        }
        return false;
    }

    /** Try interacting directly; returns true if we *attempted* an interact (even if your API doesn't give a success flag). */
    private boolean safeInteract(WorldPoint tile, String action) {
        try { Rs2GameObject.interact(tile, action); return true; }
        catch (Throwable t) { return false; }
    }

    // ===== Supplies / Banking =====

    private boolean ensureSupplies()
    {
        if (hasSuppliesInInv()) return true;

        if (!Rs2Bank.isOpen()) {
            Rs2Bank.walkToBank();
            Rs2Bank.openBank();
            return false;
        }

        depositAllCompat();
        jitter(150, 250);

        int needRopes = Math.max(0, REQUIRED_ROPES - getAmount(ROPE));
        int needNets  = Math.max(0, REQUIRED_NETS  - getAmount(NET));

        withdrawAmountCompat(ROPE, needRopes);
        withdrawAmountCompat(NET,  needNets);

        Rs2Bank.closeBank();
        return hasSuppliesInInv();
    }

    private void bankLizAndReturn()
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
            withdrawAmountCompat(ROPE, Math.max(0, REQUIRED_ROPES - getAmount(ROPE)));
            withdrawAmountCompat(NET,  Math.max(0, REQUIRED_NETS  - getAmount(NET)));
        }

        if (lizInInv > 0) totalLizBanked += lizInInv;

        Rs2Bank.closeBank();
        Rs2Walker.walkTo(START_TILE);
    }

    private boolean hasSuppliesInInv()
    {
        return getAmount(ROPE) >= REQUIRED_ROPES && getAmount(NET) >= REQUIRED_NETS;
    }

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

    private int getHunterXpSafe()
    {
        try { return Microbot.getClient().getSkillExperience(Skill.HUNTER); }
        catch (Throwable t) { return 0; }
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

    @Override
    public void shutdown()
    {
        super.shutdown();
        positionedAtStart = false;
        status = "Idle";
    }
}
