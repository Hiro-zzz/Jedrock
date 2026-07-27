package com.jedrock.core.entity;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Named <b>scenes</b> — arrangements of props that outlive the script that built them, and the process.
 *
 * <p>Decoration was the one part of this server that had to be rebuilt from scratch on every boot: a
 * script spawned its lanterns and statues, and a restart (or a hot reload) took them away, so the scene
 * only existed for as long as the code that described it kept running. That is fine for a guard with a
 * brain and wrong for a lamp post. A saved scene is the other thing: a list of props frozen exactly as
 * they stood, restored by the server at startup with no script involved at all.
 *
 * <p>So a saved scene is <b>server-owned</b>, unlike everything in the {@code entities} global, which
 * belongs to its plugin and dies with it. A script authors a scene and may load, list or delete one; it
 * does not own it afterwards. Loading a scene that is already standing hands back what is standing rather
 * than spawning it twice, so a script can ask for its scene on every reload without breeding copies.
 *
 * <p>Stored the way the world and the script store already are: one compact DEFLATE file written
 * atomically (temp then move), with a dirty flag so an untouched set of scenes is never rewritten.
 * What a prop carries is what a prop <em>is</em> here — type, where it stands, what it is called, what it
 * holds and wears, and its flags. Nothing about behaviour, because a saved prop has none: an
 * {@code onTick} brain belongs to the plugin that wrote it, and a scene is scenery.
 */
public final class SceneManager {

    private static final JLogger LOGGER = JLogger.getLogger(SceneManager.class);

    private static final byte[] MAGIC = {'J', 'D', 'S', 'C'};
    private static final int FORMAT_VERSION = 1;

    /** One prop, flattened: everything needed to put it back exactly where it was. */
    public record Prop(String type, double x, double y, double z, float yaw, float pitch,
                       String name, String nameTag, int itemState, int heldItem,
                       int helmet, int chestplate, int leggings, int boots, int flags) {}

    private final EntityDirector entities;
    private final World world;

    /** The saved definitions, by name. */
    private final Map<String, List<Prop>> scenes = new ConcurrentHashMap<>();
    /** What is standing right now, by scene name — so a second load doesn't spawn a second copy. */
    private final Map<String, List<PuppetEntity>> live = new ConcurrentHashMap<>();

    private volatile boolean dirty;

    public SceneManager(EntityDirector entities, World world) {
        this.entities = entities;
        this.world = world;
    }

    // ===== Authoring =====

    /**
     * Freeze {@code props} under {@code name}, replacing any scene already saved with it. The entities
     * handed in are only read: they keep belonging to whoever spawned them, and the scene is a copy of
     * how they looked at this moment.
     */
    public void save(String name, List<PuppetEntity> props) {
        List<Prop> frozen = new ArrayList<>(props.size());
        for (PuppetEntity puppet : props) {
            if (puppet != null && puppet.isAlive()) {
                frozen.add(capture(puppet));
            }
        }
        scenes.put(name, frozen);
        dirty = true;
    }

    private static Prop capture(PuppetEntity puppet) {
        Location at = puppet.getLocation();
        int flags = 0;
        for (PuppetFlag flag : PuppetFlag.values()) {
            if (puppet.hasFlag(flag)) {
                flags |= flag.bit();
            }
        }
        int itemState = puppet instanceof CorePuppet core ? core.getItemState() : 0;
        return new Prop(puppet.getEntityType().name(), at.x(), at.y(), at.z(), at.yaw(), at.pitch(),
                puppet.getName(), puppet.getNameTag(), itemState, puppet.getHeldItem(),
                puppet.getArmor(ArmorSlot.HELMET), puppet.getArmor(ArmorSlot.CHESTPLATE),
                puppet.getArmor(ArmorSlot.LEGGINGS), puppet.getArmor(ArmorSlot.BOOTS), flags);
    }

    /** The names of every saved scene, whether standing or not. */
    public List<String> names() {
        return new ArrayList<>(scenes.keySet());
    }

    public boolean has(String name) {
        return scenes.containsKey(name);
    }

    /** How many props a saved scene holds, or -1 if there is no such scene. */
    public int size(String name) {
        List<Prop> props = scenes.get(name);
        return props == null ? -1 : props.size();
    }

    // ===== Standing them up =====

    /**
     * Put a saved scene in the world, or hand back the one already standing. An unknown name yields an
     * empty list rather than an error — a script asking for a scene it never saved gets nothing, not a
     * crash halfway through its startup.
     */
    public List<PuppetEntity> spawn(String name) {
        List<PuppetEntity> standing = live.get(name);
        if (standing != null) {
            return new ArrayList<>(standing);
        }
        List<Prop> props = scenes.get(name);
        if (props == null) {
            return List.of();
        }
        List<PuppetEntity> spawned = new ArrayList<>(props.size());
        for (Prop prop : props) {
            PuppetEntity puppet = restore(prop);
            if (puppet != null) {
                spawned.add(puppet);
            }
        }
        live.put(name, spawned);
        return new ArrayList<>(spawned);
    }

    /** Stand up every saved scene — what the server does once, at startup. */
    public int spawnAll() {
        int total = 0;
        for (String name : scenes.keySet()) {
            total += spawn(name).size();
        }
        return total;
    }

    private PuppetEntity restore(Prop prop) {
        EntityType type;
        try {
            type = EntityType.valueOf(prop.type());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Scene prop has an unknown entity type '" + prop.type() + "' — skipped");
            return null;
        }
        Location at = new Location(world, prop.x(), prop.y(), prop.z(), prop.yaw(), prop.pitch());
        PuppetEntity puppet = switch (type) {
            case ITEM -> entities.spawnItem(at, prop.itemState());
            case FALLING_BLOCK -> entities.spawnFallingBlock(at, prop.itemState());
            case TEXT -> entities.spawnText(at, prop.nameTag());
            default -> entities.spawnPuppet(type, at, prop.name());
        };
        // A text prop carries its line as its name tag already; everything else gets dressed here.
        if (type != EntityType.TEXT && prop.nameTag() != null && !prop.nameTag().isEmpty()) {
            puppet.setNameTag(prop.nameTag());
        }
        if (prop.heldItem() != 0) {
            puppet.setHeldItem(prop.heldItem());
        }
        applyArmor(puppet, prop);
        for (PuppetFlag flag : PuppetFlag.values()) {
            if (flag.isSet(prop.flags())) {
                puppet.setFlag(flag, true);
            }
        }
        return puppet;
    }

    private static void applyArmor(PuppetEntity puppet, Prop prop) {
        if (prop.helmet() != 0) {
            puppet.setArmor(ArmorSlot.HELMET, prop.helmet());
        }
        if (prop.chestplate() != 0) {
            puppet.setArmor(ArmorSlot.CHESTPLATE, prop.chestplate());
        }
        if (prop.leggings() != 0) {
            puppet.setArmor(ArmorSlot.LEGGINGS, prop.leggings());
        }
        if (prop.boots() != 0) {
            puppet.setArmor(ArmorSlot.BOOTS, prop.boots());
        }
    }

    /** Take a scene out of the world and forget it was ever saved. */
    public boolean remove(String name) {
        despawn(name);
        boolean existed = scenes.remove(name) != null;
        if (existed) {
            dirty = true;
        }
        return existed;
    }

    /** Take a scene out of the world but keep its definition, so it can be stood up again. */
    public void despawn(String name) {
        List<PuppetEntity> standing = live.remove(name);
        if (standing != null) {
            for (PuppetEntity puppet : standing) {
                puppet.remove();
            }
        }
    }

    // ===== Persistence =====

    /**
     * File layout: {@code JDSC} + version, then a DEFLATE stream of scene count, and per scene its name,
     * prop count and props. Strings are length-prefixed UTF-8 (not {@code writeUTF}, whose two-byte length
     * would cap a name tag at 64 KB for no reason).
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
                // Snapshot first: a script on another thread may be saving a scene while this runs, and the
                // count has to match what follows it.
                Map<String, List<Prop>> written = new LinkedHashMap<>(scenes);
                out.writeInt(written.size());
                for (Map.Entry<String, List<Prop>> scene : written.entrySet()) {
                    writeString(out, scene.getKey());
                    List<Prop> props = scene.getValue();
                    out.writeInt(props.size());
                    for (Prop prop : props) {
                        writeProp(out, prop);
                    }
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
            LOGGER.info("Saved " + scenes.size() + " scene(s) to " + file.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to save scenes to " + file.toAbsolutePath(), e);
        }
    }

    /** Read scenes back. A missing file is not an error — it means nothing has been saved yet. */
    public void load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream header = new DataInputStream(raw)) {
            byte[] magic = new byte[MAGIC.length];
            header.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Not a Jedrock scene file: " + file);
            }
            int version = header.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported scene format " + version + " in " + file);
            }
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(raw))) {
                scenes.clear();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    String name = readString(in);
                    int propCount = in.readInt();
                    List<Prop> props = new ArrayList<>(propCount);
                    for (int p = 0; p < propCount; p++) {
                        props.add(readProp(in));
                    }
                    scenes.put(name, props);
                }
            } catch (EOFException e) {
                throw new IOException("Scene file ended early — it was written by a crash: " + file, e);
            }
        }
        dirty = false;
    }

    private static void writeProp(DataOutputStream out, Prop prop) throws IOException {
        writeString(out, prop.type());
        out.writeDouble(prop.x());
        out.writeDouble(prop.y());
        out.writeDouble(prop.z());
        out.writeFloat(prop.yaw());
        out.writeFloat(prop.pitch());
        writeString(out, prop.name());
        writeString(out, prop.nameTag());
        out.writeInt(prop.itemState());
        out.writeInt(prop.heldItem());
        out.writeInt(prop.helmet());
        out.writeInt(prop.chestplate());
        out.writeInt(prop.leggings());
        out.writeInt(prop.boots());
        out.writeInt(prop.flags());
    }

    private static Prop readProp(DataInputStream in) throws IOException {
        return new Prop(readString(in), in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat(), readString(in), readString(in),
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1 << 20) {
            throw new IOException("Implausible string length in scene file: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
