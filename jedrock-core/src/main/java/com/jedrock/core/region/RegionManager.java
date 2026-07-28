package com.jedrock.core.region;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.EventPriority;
import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.block.BlockPlaceEvent;
import com.jedrock.api.event.block.PlayerInteractBlockEvent;
import com.jedrock.api.event.player.DamageCause;
import com.jedrock.api.event.player.PlayerDamageEvent;
import com.jedrock.api.event.player.PlayerRegionEnterEvent;
import com.jedrock.api.event.player.PlayerRegionLeaveEvent;
import com.jedrock.api.player.Player;
import com.jedrock.api.region.Region;
import com.jedrock.api.region.RegionFlag;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.JLogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Every {@linkplain Region region} on the server: the registry, the rule lookups, the enforcement, and the
 * file they live in.
 *
 * <h2>What makes this cheap</h2>
 *
 * <p>A server with no regions must pay <b>nothing</b>, because the places a region has an opinion about are
 * the busiest paths there are — a step, a block edit, a hit. Two things buy that:
 *
 * <ul>
 *   <li>Every query starts with {@link #isEmpty()}, one read of a snapshot array's length. On a server with
 *       no regions that is the entire cost.</li>
 *   <li>The flag rules are enforced by <b>event listeners this class registers only while regions exist</b>
 *       and unregisters when the last one is deleted. That matters because the core builds a
 *       {@code BlockBreakEvent} only when something is listening — registering unconditionally would make
 *       every block edit on every server allocate an event for rules nobody wrote.</li>
 * </ul>
 *
 * <p>Movement is the one rule <em>not</em> enforced through a listener, for the same reason: a permanent
 * {@code PlayerMoveEvent} listener would defeat the {@code hasListeners} fast path that keeps movement
 * allocation-free. The core asks this class directly instead (see {@code ConnectionBridge}), gated on the
 * same emptiness check.
 *
 * <h2>Overlap</h2>
 *
 * <p><b>Deny wins</b>, the rule this server already uses for permissions: a point inside several regions
 * allows something only if every one of them allows it. No priorities, no ordering, and a small no-build
 * region dropped inside a big free-build one does what it looks like it does.
 *
 * <h2>Exceptions</h2>
 *
 * <p>A denial can be waived for a player holding that region's
 * {@linkplain Region#bypassPermission bypass node}, which is how per-player and per-group exceptions work
 * without regions growing a roster of their own — the permission system already answers "may this player do
 * this", with groups, inheritance, wildcards and an explicit deny. The lookup only happens on a region that
 * has actually denied something, so the ordinary answer never touches the permission store.
 *
 * <p>The registry is a {@link CopyOnWriteArrayList} snapshot: reads are the overwhelming majority (several
 * per player per second) and writes are a human typing {@code /region create}, so readers get a stable
 * array to scan with no locking and no allocation.
 */
public final class RegionManager {

    private static final JLogger LOGGER = JLogger.getLogger(RegionManager.class);

    private static final byte[] MAGIC = {'J', 'D', 'R', 'G'};
    /** v2 records each region's world; a v1 file's regions are all in the default world. */
    private static final int FORMAT_VERSION = 2;

    /** Scanned on every lookup; a snapshot array so a reader never locks and never allocates. */
    private final CopyOnWriteArrayList<CoreRegion> regions = new CopyOnWriteArrayList<>();
    /** Name (lower-case) → region, so a lookup by name doesn't scan. */
    private final Map<String, CoreRegion> byName = new ConcurrentHashMap<>();

    private final EventBus events;
    /** Which world a region from a pre-v2 file belongs to — the only world that existed when it was written. */
    private final String defaultWorldName;
    /** Live only while at least one region exists — see the class doc. */
    private final List<EventBus.Subscription> enforcement = new ArrayList<>();

    private volatile boolean dirty;

    public RegionManager(EventBus events) {
        this(events, "world");
    }

    public RegionManager(EventBus events, String defaultWorldName) {
        this.events = events;
        this.defaultWorldName = defaultWorldName;
    }

    // ===== The registry =====

    /** True when there is nothing to check — the fast path every caller starts with. */
    public boolean isEmpty() {
        return regions.isEmpty();
    }

    public int size() {
        return regions.size();
    }

    /** Every region, in creation order. A copy, so a caller may hold it while regions change. */
    public List<Region> all() {
        return List.copyOf(regions);
    }

    /** The region called {@code name} (case-insensitive), or {@code null}. */
    public CoreRegion get(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Letters, digits, {@code _} and {@code -}; see {@link #create} for why the alphabet is closed. */
    private static final java.util.regex.Pattern VALID_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** Whether {@code name} may be a region name — the same test {@link #create} applies. */
    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name.trim()).matches();
    }

    /**
     * Create a region from two opposite corners in any order.
     *
     * <p>The name has to be letters, digits, {@code _} or {@code -}, at most 32 of them. Not fussiness: the
     * name is half of the {@linkplain Region#bypassPermission bypass permission node}, and a dot in it
     * would silently invent a wildcard level while a space would make the node untypeable.
     *
     * @return the new region, or {@code null} if the name is unusable or already taken — a caller that
     *         silently replaced a region would lose whatever rules were on it
     */
    public CoreRegion create(String name, com.jedrock.api.world.World world,
                             int x1, int y1, int z1, int x2, int y2, int z2) {
        if (!isValidName(name) || world == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        CoreRegion region = new CoreRegion(name.trim(), world.getName(), x1, y1, z1, x2, y2, z2);
        if (byName.putIfAbsent(key, region) != null) {
            return null; // taken
        }
        regions.add(region);
        enforcementFollowsPopulation();
        dirty = true;
        return region;
    }

    /** Delete a region. @return {@code true} if there was one to delete */
    public boolean remove(String name) {
        CoreRegion region = get(name);
        if (region == null) {
            return false;
        }
        byName.remove(region.getName().toLowerCase(Locale.ROOT));
        regions.remove(region);
        enforcementFollowsPopulation();
        dirty = true;
        return true;
    }

    /** Mark the set changed so the next autosave writes it — for a flag edit, which mutates in place. */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    // ===== The rule lookups =====

    /**
     * Whether {@code flag} is allowed at this point <em>for anyone</em> — the rule as the world states it,
     * before any player's exemptions. {@code true} where no region has an opinion.
     */
    public boolean allows(com.jedrock.api.world.World world, double x, double y, double z, RegionFlag flag) {
        return allows(null, world, x, y, z, flag);
    }

    /** The player's own world is the one their point is in — the overload the core actually calls. */
    public boolean allows(Player player, double x, double y, double z, RegionFlag flag) {
        return allows(player, player == null ? null : player.getWorld(), x, y, z, flag);
    }

    /**
     * Whether {@code player} may do {@code flag} at this point — the question the core actually asks.
     *
     * <p>Deny wins across overlapping regions, but a player {@linkplain Region#bypassPermission exempted}
     * from a given region's denial passes it as if it weren't there. The exemption is per region <em>and</em>
     * per flag, so being allowed to build in a plot doesn't also let you through a wall around it.
     *
     * <p>Allocation-free and short-circuiting: it stops at the first region that denies and can't be
     * excused, and on a server with no regions it is a single length check. The permission lookup only
     * happens on a region that <em>has</em> denied — the ordinary "nothing forbids this" answer never
     * touches the permission store.
     */
    public boolean allows(Player player, com.jedrock.api.world.World world,
                          double x, double y, double z, RegionFlag flag) {
        List<CoreRegion> snapshot = regions;
        for (int i = 0, n = snapshot.size(); i < n; i++) {
            CoreRegion region = snapshot.get(i);
            if (region.inWorld(world) && region.contains(x, y, z) && !region.allows(flag)
                    && !isExempt(player, region, flag)) {
                return false; // deny wins, and nothing excused this player from it
            }
        }
        return true;
    }

    /** Whether this player carries the node that excuses them from {@code region}'s denial of {@code flag}. */
    private static boolean isExempt(Player player, Region region, RegionFlag flag) {
        return player != null && player.hasPermission(region.bypassPermission(flag));
    }

    /** The regions containing this point, or an empty list. Allocates — not for the movement path. */
    public List<Region> at(com.jedrock.api.world.World world, double x, double y, double z) {
        if (regions.isEmpty()) {
            return List.of();
        }
        List<Region> found = new ArrayList<>(2);
        for (CoreRegion region : regions) {
            if (region.inWorld(world) && region.contains(x, y, z)) {
                found.add(region);
            }
        }
        return found;
    }

    /**
     * Fill {@code into} with the regions containing this point and return how many there were.
     *
     * <p>The movement path's version of {@link #at}: it writes into a buffer the caller keeps, so walking
     * around inside a region allocates nothing at all. Returns {@code -1} if the buffer is too small, which
     * tells the caller to grow it and ask again (regions are few, so this happens approximately once).
     */
    public int fillAt(com.jedrock.api.world.World world, double x, double y, double z, CoreRegion[] into) {
        int found = 0;
        List<CoreRegion> snapshot = regions;
        for (int i = 0, n = snapshot.size(); i < n; i++) {
            CoreRegion region = snapshot.get(i);
            if (region.inWorld(world) && region.contains(x, y, z)) {
                if (found >= into.length) {
                    return -1; // caller grows and retries
                }
                into[found++] = region;
            }
        }
        return found;
    }

    // ===== Membership: the crossings =====

    /**
     * Bring {@code player}'s remembered membership in line with where they now are, firing one
     * {@link PlayerRegionEnterEvent} / {@link PlayerRegionLeaveEvent} per region actually crossed.
     *
     * <p>Called from the movement path (and once on join), so the overwhelmingly common outcome is "nothing
     * changed", which costs a scan of the region list and no allocation at all. Only a real crossing
     * allocates, and only then are any events built.
     *
     * <p>A crossing can be <b>refused</b> — by a denied {@link RegionFlag#ENTRY}, or by a listener
     * cancelling either event. Everything is decided before anything is committed, so a refusal leaves the
     * membership exactly as it was and the player never half-entered. A listener therefore sees the
     * membership as it stood <em>before</em> the step it is being asked about, which is the only state that
     * is meaningful while the answer is still open.
     *
     * <p>Server-side teleports are not hooked: membership reconciles itself from the client's next movement
     * report, which follows a teleport immediately. That keeps one code path instead of two, at the cost of
     * a no-entry region bouncing a teleported player a packet later rather than instantly.
     *
     * @return {@code true} if the player may be where they are; {@code false} if the caller should put them
     *         back where they came from
     */
    public boolean updateMembership(CorePlayer player, double x, double y, double z) {
        RegionMembership membership = player.getRegionMembership();
        CoreRegion[] candidate = membership.scratch(regions.size());
        int found = fillAt(player.getWorld(), x, y, z, candidate);
        if (found < 0) { // the list grew under us — take the bigger buffer and ask again
            candidate = membership.scratch(regions.size());
            found = fillAt(player.getWorld(), x, y, z, candidate);
            if (found < 0) {
                return true; // still racing; let the step through and settle on the next report
            }
        }
        if (membership.matches(candidate, found)) {
            return true; // no crossing — the path everything but the moment of entry takes
        }
        // Entering: a wall refuses outright, and a listener may too.
        for (int i = 0; i < found; i++) {
            CoreRegion region = candidate[i];
            if (membership.holds(region)) {
                continue;
            }
            if (!region.allows(RegionFlag.ENTRY) && !isExempt(player, region, RegionFlag.ENTRY)) {
                return false; // a wall, and this player is not one of the people it opens for
            }
            if (events.hasListeners(PlayerRegionEnterEvent.class)
                    && events.post(new PlayerRegionEnterEvent(player, region)).isCancelled()) {
                return false;
            }
        }
        // Leaving: cancelling keeps the player in, which is how a round holds somebody in an arena.
        if (events.hasListeners(PlayerRegionLeaveEvent.class)) {
            for (CoreRegion region : membership.inside()) {
                if (containsRegion(candidate, found, region)) {
                    continue;
                }
                if (events.post(new PlayerRegionLeaveEvent(player, region)).isCancelled()) {
                    return false;
                }
            }
        }
        membership.commit(candidate, found);
        return true;
    }

    private static boolean containsRegion(CoreRegion[] array, int count, CoreRegion region) {
        for (int i = 0; i < count; i++) {
            if (array[i] == region) {
                return true;
            }
        }
        return false;
    }

    // ===== Enforcement =====

    /**
     * Keep the event listeners in step with whether any region exists at all. Called after every create and
     * remove; idempotent, so it doesn't matter that both call it unconditionally.
     */
    private synchronized void enforcementFollowsPopulation() {
        boolean wanted = !regions.isEmpty();
        if (wanted == !enforcement.isEmpty()) {
            return; // already in the right state
        }
        if (wanted) {
            // HIGH, so a script listening at the default NORMAL has already had its say and a script that
            // wants the last word can take HIGHEST or MONITOR. A region is a rule, not an opinion, but it
            // should still be overridable by the code that owns the server.
            enforcement.add(events.register(BlockBreakEvent.class, EventPriority.HIGH, event -> {
                if (!allows(event.getPlayer(), event.getX(), event.getY(), event.getZ(), RegionFlag.BUILD)) {
                    event.setCancelled(true);
                }
            }));
            enforcement.add(events.register(BlockPlaceEvent.class, EventPriority.HIGH, event -> {
                if (!allows(event.getPlayer(), event.getX(), event.getY(), event.getZ(), RegionFlag.BUILD)) {
                    event.setCancelled(true);
                }
            }));
            enforcement.add(events.register(PlayerInteractBlockEvent.class, EventPriority.HIGH, event -> {
                if (!allows(event.getPlayer(), event.getX(), event.getY(), event.getZ(), RegionFlag.INTERACT)) {
                    event.setCancelled(true);
                }
            }));
            enforcement.add(events.register(PlayerDamageEvent.class, EventPriority.HIGH, event -> {
                // DAMAGE is the whole safe zone; PVP narrows it to being hit by somebody. The victim's own
                // position is what counts — an arena is safe because of where you stand, not where the
                // hitter does — and so does the victim's own exemption: bypassing `damage` means this
                // player isn't the one the safe zone protects.
                Player hurt = event.getPlayer();
                var at = hurt.getLocation();
                boolean denied = !allows(hurt, at.x(), at.y(), at.z(), RegionFlag.DAMAGE)
                        || (event.getCause() == DamageCause.ATTACK
                            && !allows(hurt, at.x(), at.y(), at.z(), RegionFlag.PVP));
                if (denied) {
                    event.setCancelled(true);
                }
            }));
        } else {
            for (EventBus.Subscription subscription : enforcement) {
                subscription.remove();
            }
            enforcement.clear();
        }
    }

    // ===== Persistence =====

    /**
     * File layout: {@code JDRG} + version, then a DEFLATE stream of the region count and, per region, its
     * name, six bounds and its denied-flag mask. Strings are length-prefixed UTF-8, as everywhere else here.
     *
     * <p>Written to a temp file and moved into place, so a crash mid-write leaves the previous set intact
     * rather than a truncated one.
     */
    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp);
             DataOutputStream header = new DataOutputStream(raw)) {
            header.write(MAGIC);
            header.writeInt(FORMAT_VERSION);
            header.flush();
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(raw, deflater))) {
                // Snapshot first: a command on another thread may add a region while this runs, and the
                // count has to match what follows it.
                List<CoreRegion> written = List.copyOf(regions);
                out.writeInt(written.size());
                for (CoreRegion region : written) {
                    writeString(out, region.getName());
                    writeString(out, region.getWorldName()); // v2

                    out.writeInt(region.getMinX());
                    out.writeInt(region.getMinY());
                    out.writeInt(region.getMinZ());
                    out.writeInt(region.getMaxX());
                    out.writeInt(region.getMaxY());
                    out.writeInt(region.getMaxZ());
                    out.writeInt(region.deniedMask());
                }
            } finally {
                deflater.end();
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    /** Save only when something changed; log rather than throw, so a bad disk never takes the server down. */
    public void saveIfDirty(Path file) {
        if (!dirty) {
            return;
        }
        try {
            save(file);
            LOGGER.info("Saved " + regions.size() + " region(s) to " + file.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to save regions to " + file.toAbsolutePath(), e);
        }
    }

    /** Read regions back. A missing file is not an error — it means none have ever been created. */
    public void load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream header = new DataInputStream(raw)) {
            byte[] magic = new byte[MAGIC.length];
            header.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Not a Jedrock region file: " + file);
            }
            int version = header.readInt();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IOException("Unsupported region format " + version + " in " + file);
            }
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(raw))) {
                regions.clear();
                byName.clear();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    String name = readString(in);
                    // A v1 file predates worlds having names worth recording: everything in it was in
                    // the one world there was.
                    String worldName = version < 2 ? defaultWorldName : readString(in);
                    CoreRegion region = new CoreRegion(name, worldName,
                            in.readInt(), in.readInt(), in.readInt(),
                            in.readInt(), in.readInt(), in.readInt());
                    region.setDeniedMask(in.readInt());
                    regions.add(region);
                    byName.put(name.toLowerCase(Locale.ROOT), region);
                }
            } catch (EOFException e) {
                throw new IOException("Region file ended early — it was written by a crash: " + file, e);
            }
        }
        enforcementFollowsPopulation();
        dirty = false;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1 << 16) {
            throw new IOException("Implausible string length in region file: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
