# Jedrock

**A lightweight, cross-platform Minecraft server core written from scratch in Java.**

Jedrock speaks **two protocols natively at once** — Java Edition and Bedrock (Pocket) Edition —
and treats them as one world. A player on a PC and a player on a phone join the same server,
share the same chat, the same player list, and the same terrain. The core never learns which
protocol a player speaks; that stays behind the network layer.

Target versions:

| Edition | Version | Protocol | Transport |
|---------|---------|----------|-----------|
| Java Edition | **1.12.2** | 340 | Netty TCP |
| Java Edition | **1.8** | 47 | Netty TCP |
| Bedrock / Pocket Edition | **1.1.5** | 113 | RakNet over UDP |
| Bedrock / Pocket Edition | **0.14** | 45 | RakNet over UDP |

Java Edition is **multi-version on one port**: the client's handshake protocol selects the encoder,
so 1.8 and 1.12.2 share the listener (see [Multiversion](#multiversion)). Bedrock spans two eras that
can't share a socket (they negotiate different RakNet versions), so **0.14** — the pre-VarInt,
128-tall-world, no-Xbox-login era — runs on its own UDP port alongside 1.1.5.

> ⚠️ **Status: early but real.** This is a from-scratch experiment, not a production server. What
> works today is listed below — and every version in the table above has been confirmed with a real,
> unmodified client (a 1.8 PC client, a 1.1.5 Win10 client, a 0.14 phone client — all sharing one
> world with 1.12.2).

---

## What works today

- ✅ **Java 1.12.2 client joins** into a procedurally generated world (login → join game → chunks → spawn).
- ✅ **Java 1.8 client joins too** (protocol 47) — the same listener serves it, picking the encoder from
  the handshake; a real 1.8 client shares the world, chat and avatars with 1.12.2 (see [Multiversion](#multiversion)).
- ✅ **Procedural terrain** — a deterministic value-noise heightmap (rolling hills, grass/dirt/stone
  layers) generated as a pure function of a seed; players spawn standing on the surface.
- ✅ **Collision** — comes for free: the client collides against the solid ground we serialize to it;
  the server runs no physics (see the philosophy below).
- ✅ **Bedrock 1.1.5 client joins** the same server over real RakNet (offline handshake →
  MCPE Login → Resource Packs → StartGame → chunks → spawn).
- ✅ **Bedrock 0.14 client joins too** (protocol 45, on its own UDP port) — the pre-VarInt era, spoken
  from scratch: big-endian wire, a one-byte game wrapper, plaintext (no-Xbox) login, a `0x92` zlib
  batch and 128-tall full-column chunks. A real 0.14 phone client logs in, spawns on the shared
  procedural terrain, and moves / digs / builds — cross-play with a Java 1.12.2 client in one world
  (shared blocks, chat, avatars). Other players show in the 0.14 **pause-menu list** (`PlayerList`
  with a synthetic skin) and the crouch pose is relayed onto avatars; nametags, skins and the sprint /
  item-use poses are drawn by the 0.14 client itself, so the server sends less than 1.1.5 needs.
- ✅ **One shared world** — Java and Bedrock render the **same blocks** from a single `CoreWorld`;
  the Bedrock side serializes chunks in the MCPE 1.0/1.1 network format (blocks + metadata + sky/block
  light + heightmap), so a Bedrock client stands on exactly the terrain a Java client sees.
- ✅ **Cross-platform chat** — a message typed on Java shows up on Bedrock and vice versa.
- ✅ **Unified chat markup** — messages are authored once in an edition-agnostic format (`{color}` tags
  plus Markdown `**bold**` / `*italic*` / `__underline__` / `~~strike~~`) and rendered to the legacy
  `§` codes every version understands, so one string formats identically on Java and Bedrock alike
  (`ChatText`, unit-tested). Players can use it in chat too.
- ✅ **Presence** — join/leave announcements reach every player, on both platforms.
- ✅ **Shared player registry** — Java and Bedrock players live in the same core state and fire
  the same `PlayerJoinEvent` / `PlayerQuitEvent`.
- ✅ **Java tab list** shows every online player, Java and Bedrock alike.
- ✅ **Real gamertags** for Bedrock players (extracted from the MCPE Login JWT chain).
- ✅ **Cross-platform avatars** — every player spawns as a visible entity on both editions;
  a Java player and a Bedrock player see each other and each other's movement in real time.
- ✅ **Client-authoritative movement** — clients report position; the core relays it to everyone
  and never simulates physics (see the philosophy below).
- ✅ **Bedrock player list** — Bedrock players now appear in the PE pause-menu list.
- ✅ **Dynamic chunk streaming** — each connection has a `ChunkView` that loads chunks around the
  player and unloads them behind, so the world follows you instead of ending a few chunks out.
- ✅ **Cross-platform block editing** — a player on **either** edition places and breaks any block in
  the palette; the change lands in the shared world and shows up on every client, cross-edition (a
  broken natural block stays broken via an explicit-air overlay marker). Java speaks Player Digging /
  Block Placement; Bedrock's Win10 1.1.5 client reports breaks via `PlayerAction` and places via its
  own use-item packet, both decoded from captured bytes. Each edit is reflected to Bedrock as a single
  typed `UpdateBlock` packet (not a whole-chunk resend).
- ✅ **Bedrock creative inventory + flight** — the PE creative menu is filled via `ContainerSetContent`:
  **1.1.5** (protocol-113, 0x34) gets a variant-rich legacy palette (~170 states — every wool /
  terracotta / carpet colour, wood and stone type), and **0.14** (protocol-45, 0xb9), which previously
  had no creative menu, gets its own narrower, 0.14-safe block set (the old client crashes on an id it
  can't render). The player can fly (fixed `AdventureSettings`), and a movement-speed attribute kills the
  runaway acceleration.
- ✅ **Block metadata (variants)** — the world stores a packed `(id << 4) | meta` state per cell, so
  wool colours, wood/stone types and the like are preserved and rendered distinctly on both editions.
  Placement reads the variant from the held item (JE creative damage, Bedrock item aux); chunks carry
  it (JE global-palette id, Bedrock's 4-bit nibble array), as do single-block edits.
- ✅ **Wide JE chunk palette** — each JE section picks the smallest legal bits-per-block (4–8) for its
  palette, so a section with more than 16 distinct states no longer overflows and corrupts.
- ✅ **Finite "bake once" world** — on first run the world is generated once over a bounded region
  (48×48 chunks) and **frozen into storage**; from then on the terrain generator is never consulted at
  runtime — blocks are served straight from the baked matrix, so the world is static "decoration" with
  no server-side simulation. A coordinate outside the bounds reads air (the world is finite). Measured:
  48×48 bakes to ~11 500 sections in ~3.5 s on first boot; every later boot just loads it. The world
  has an **edge**: stepping past the bounds hits an invisible wall (the move is refused and the player
  snapped back; a player found outside is sent home to spawn), and edits outside the bounds are refused.
- ✅ **Biomes** — the world spans four grass biomes (plains, forest, taiga, savanna), assigned by a
  deterministic temperature/humidity noise into broad regions, baked into a per-column biome map and
  served to **every edition**: JE 1.12.2 / 1.8 and PE 1.1.5 get the biome-id byte, PE 0.14 gets the
  matching grass-tint colour — so grass/foliage renders per biome cross-edition.
- ✅ **Decoration — trees, lakes, caves** — three deterministic bake-time passes shape the frozen world:
  a 3D-noise cave network under the surface, shallow water lakes (one candidate per chunk, kept clear of
  spawn), and biome-weighted trees (dense in forest, sparse on plains; spruce in taiga, oak elsewhere).
  All position-hashed, so a seed always yields the same world, and all baked into storage — no runtime
  simulation. Features cross chunk borders freely (the whole finite world is in memory at bake time).
- ✅ **World persistence** — the world (baked terrain + player edits) survives a restart, written to a
  compact Jedrock level file (`world/level.jdw` — an uncompressed metadata header plus every allocated
  16³ section in one DEFLATE stream; ~420 KB for a 48×48 world). Loaded before any client can join,
  saved on shutdown, and autosaved every `-Djedrock.world.save-seconds` (default 300, `0` = off) — a
  dirty flag skips rewriting an unchanged world. Saves are atomic (temp + move).
- ✅ **File-based config** — `jedrock.properties` (auto-created on first run) sets the bind host and
  ports, server name, world seed, tick rate, view distance and the server-list MOTD / max players;
  any key is overridable with `-Dkey=value`, and bad values fall back to defaults instead of failing.
- ✅ **Server-list ping, both editions** — Java shows the server in its multiplayer list (version,
  MOTD, live player count, and a latency ping); the Bedrock query now reports the real online count
  too. MOTD and max players come from config.
- ✅ **Real Bedrock skins** — a Bedrock player's actual skin is pulled from their Login JWT and
  relayed into the PE `PlayerList`, so Bedrock players see each other's real skins (not the coloured
  placeholder). Cross-edition is a hard protocol limit: Java requires Mojang-signed textures, so a
  Bedrock player still shows as Steve/Alex on Java, and JE players use the placeholder on Bedrock.
- ✅ **Player animations (sneak, sprint, arm swing, item-use)** — crouch, sprint, arm swings and the
  item-use pose (eat / drink / block / draw bow) are decoded from each edition and relayed
  cross-edition (JE Entity Action / Animation / Use Item → Entity Metadata / Animation; PE PlayerAction
  / Animate → SetEntityData / Animate). Crouch, sprint and item-use share a flags field, so the full
  pose is always sent together (one can't clear another) and a late joiner is synced to it. Item-use is
  reported cleanly by the Java client (start via Use Item, stop via Player Digging release); a
  Bedrock-initiated item-use isn't decoded yet, and the pose is generic until held items are relayed.
- ✅ **The blind judge (lazy anti-cheat + crash-packet guard)** — instead of a physics engine, cheap
  checks that catch the egregious. Gameplay: a **reach sphere** rejects block edits farther than
  `judge.max-reach` from the editor (their client is corrected with the real block), and a
  **movement-delta** check refuses a teleport/speed jump larger than `judge.max-move-delta` between two
  reports (the client is snapped back). Wire: a **`PacketGuard`** bounds the Bedrock packet layer so a
  malicious client can't crash the server — a zlib "zip bomb" batch is rejected once it inflates past a
  cap, and batch / inventory-action / string-list counts are capped so a huge wire-driven count can't
  spin a parse loop or exhaust memory. (Java's frame + array reads were already length-capped.)

Not yet: cross-edition skin fidelity (a signed-texture limit, see above), movement validation, scripting.
See [Roadmap](#roadmap).

---

## Multiversion

Adding a Java Edition version no longer means forking the connection. A version-neutral
`JedrockConnection` owns only what is stable across versions (channel + framing, movement merge,
chunk streaming, keep-alive, lifecycle); everything whose bytes differ is a `JavaProtocol` strategy.
Each connection starts on the shared `JavaHandshakeHandler`, which reads the client's protocol from
the handshake and installs the matching `JavaProtocol` from `JavaProtocols` (or refuses an
unsupported version). The server-list ping echoes the client's own protocol, so the server always
shows as compatible.

This works cleanly for **legacy** versions because they all share the world's canonical
`(id << 4) | meta` block model — no per-version block translation is needed. **1.8** (protocol 47) is
the first added alongside 1.12.2: login → play, join sequence, movement, chat, block break/place,
cross-edition avatars and the sneak/sprint pose, handling the 1.8 deltas (byte dimension in Join
Game, VarInt keep-alive ids, fixed-point entity coordinates, the old header-tagged entity-metadata
format, no teleport-confirm, and the 1.8 grouped chunk layout). Packet ids/formats are centralised in
`Java1_8Protocol`; unit tests pin the chunk bytes.

> Status: the framework, 1.12.2 and 1.8 are all confirmed with real clients (an unsupported version is
> refused at handshake, so it can't destabilise the others). Modern versions (1.13+ flattening,
> Bedrock's current palette) are deliberately out of scope — they invert the legacy world model.

**Bedrock is multi-version too — but across sockets, not one port.** A Bedrock client negotiates a
RakNet protocol version in its offline handshake, and one UDP socket serves exactly one of them, so
1.1.5 (RakNet v8) and 0.14 (RakNet v7) each bind their own port. The two are otherwise different
protocols, not deltas: 0.14 is the pre-VarInt era — big-endian fields, a one-byte `0x8e` game wrapper,
a `0x92` zlib batch, plaintext (no Xbox/JWT) login, and 128-tall full-column chunks — so it has its own
`PeSession014` / `Pe014RakNetServer` and codec (`network/pe/v014`) rather than sharing the 1.1.5 layer.
Both map the same canonical `(id << 4) | meta` world, so a 0.14 phone, a 1.1.5 Win10 client, a 1.8 PC
and a 1.12.2 PC all stand in one world. The 0.14 wire was reverse-checked against PocketMine-MP at
`CURRENT_PROTOCOL = 45`.

---

## Philosophy — the illusionist server

Jedrock is not a faithful re-implementation of Mojang's world simulator. It is a **high-throughput
packet switch that spends CPU and memory only when it absolutely must**. Five pillars:

1. **Lazy everything.** The fastest code is code that never runs. Inbound bytes stay as raw
   `ByteBuf`; nothing is parsed until game logic actually needs a value (`LazyPacket`, `Lazy<T>`).
2. **The world is an illusion.** A block is just an id. The world is a flat matrix of primitive
   ids addressed with bit operations, lazily allocated. Physics, lighting, pathfinding and
   collisions are left to the client. We don't simulate an honest world — we render a convincing
   one in the player's mind.
3. **The blind judge.** Instead of a heavy server physics engine, validation is a lazy
   approximation at the points that matter — movement deltas and interaction spheres — done cheaply
   on the network threads (`BlindJudge`), catching the egregious rather than simulating an honest world.
4. **The two-headed monster.** The network layer isolates the core from both protocols' nightmares
   (RakNet, zlib batches, differing block palettes). To the core, a PC player and a phone player
   are identical `Player` objects.
5. **Scriptable API.** Custom logic is meant to live in fast, hot-reloadable scripts rather than
   compiled jars — *planned*.

Concretely, the codebase holds to three rules: **lightweight** (few deps, few allocations),
**absolute abstraction** (the `api` module knows nothing about packets or wire formats), and
**lazy parsing**.

---

## Module structure

```
jedrock
├── jedrock-api          # Pure contracts: Server, Player, World, events. No implementation deps.
├── jedrock-utils        # Lazy<T>, LazyPacket, ByteBufUtils (VarInt/VarLong/zigzag), logging, ticks
├── jedrock-network      # Transport + protocol handling for both editions
│   ├── handler/je/      # JavaProtocol per JE version; JavaHandshakeHandler picks 1.8 / 1.12.2
│   ├── je/packet/       # Java Edition packets (Serverbound* / Clientbound*)
│   ├── pipeline/        # Netty codecs: VarInt framing, lazy packet decoding
│   └── pe/              # Bedrock 1.1.5: PeRakNetServer (RakNet transport) + PeSession (MCPE game
│                        #   layer) delegating to McpeProtocol, McpeCodec, McpeChunkSerializer,
│                        #   McpeLoginIdentity, McpeSkin, PeBlockEditDecoder, McpeCompression
│       └── v014/         # Bedrock 0.14 (protocol 45): Pe014RakNetServer + PeSession014 + the
│                         #   pre-VarInt codec (Mcpe014Codec/Login/Packets/ChunkSerializer/Batch)
├── jedrock-gameloop     # Dedicated 20 TPS drift-correcting loop + Scheduler (Tickable)
└── jedrock-core         # The server: PlayerRegistry, CoreWorld/BlockStorage, JedrockServer
```

Dependency direction: `network → api`, `core → api + network + gameloop + utils`. The network
layer never depends on the core; it reaches it only through the `ConnectionListener` hook.

---

## How it works

### Java Edition path
Raw TCP → `VarintFrameDecoder` → `LazyPacketDecoder` (id + raw payload) → a version-neutral
`JedrockConnection`. Every connection starts on the shared `JavaHandshakeHandler`, which reads the
client's protocol number from the handshake and installs the matching `JavaProtocol` (1.12.2 or 1.8)
from `JavaProtocols`, or refuses an unsupported version. From there the version handler drives login
(Join Game, Player Abilities, chunk data, Position & Look), every clientbound encode and the hand-off
to the core — so the connection itself carries no version-specific bytes. Framing is identical across
JE versions.

### Bedrock path
`PeRakNetServer` runs the RakNet transport (via the proven `com.nukkitx.network:raknet` library),
which handles the offline handshake, datagram reliability, ACKs and split reassembly. On top of it
Jedrock implements just enough of the MCPE 1.1.5 game layer to reach the world:

```
Login (0x01)                -> PlayStatus(LOGIN_SUCCESS) + ResourcePacksInfo
ResourcePackResponse (0x08) -> StartGame + PlayStatus(PLAYER_SPAWN)
RequestChunkRadius (0x45)   -> ChunkRadiusUpdated + AdventureSettings + chunks + PlayStatus(spawn)
```

Game packets travel as a `0xFE` wrapper around a zlib-compressed batch of VarInt-length-prefixed
packets.

### The bridge to the core
Both connection types implement the api `PlayerConnection` and fire a shared `ConnectionListener`
(`onLogin` / `onDisconnect` / `onChat`). The core registers every player — regardless of edition —
in one `PlayerRegistry`, so broadcasting a chat line or a tab update is a single loop over
`Player`s; each `PlayerConnection` serializes it in its own protocol.

### The shared world
`CoreWorld` exposes a canonical, protocol-agnostic block **state** — the packed `(id << 4) | meta`
value both legacy protocols use (`World.getBlockId`, see `Blocks`). The world is **finite and baked
once**: on first run a bounded 48×48-chunk region is generated — a deterministic value-noise heightmap
(`TerrainGenerator`), a temperature/humidity biome map (`BiomeGenerator`), and decoration passes
(`WorldDecorator` — caves, lakes, trees) — and frozen into a flat `short`-per-cell matrix
(`BlockStorage`, lazily allocated per 16³ section) plus a per-column biome map (`BiomeStorage`). From
then on **the generator is never consulted again**: `CoreWorld` serves blocks straight from storage,
player edits write straight to it, and the whole world is persisted to a compact level file (`LevelIO`,
`world/level.jdw`) so later boots just load it. A column outside the bounds is void (air), enforced as
an invisible edge wall on movement and edits. Each protocol maps a state to its own wire form when
serializing chunks (Java global-palette id vs. Bedrock id + a 4-bit meta nibble) and reads the biome
per column, so both clients see — and collide against — the same terrain, biomes and trees.

For the chunk hot path, both editions bulk-read a section through `World.fillSection` (a single copy
out of the baked matrix) and the biome map through `World.fillBiomes`. Serializers reuse per-thread
scratch buffers, so encoding a chunk allocates nothing per section.

---

## Key abstractions

| Concept | Where | Purpose |
|---------|-------|---------|
| `LazyPacket` / `Lazy<T>` | utils | Hold raw bytes; parse only on demand |
| `ProtocolHandler` | network/handler | Per-edition inbound state machine; keeps `JedrockConnection` thin |
| `JavaProtocol` / `JavaHandshakeHandler` | network/handler/je | Per-JE-version strategy (inbound + clientbound); the handshake picks it, so one port serves 1.8 + 1.12.2 |
| `PlayerConnection` | api | Protocol-agnostic handle the core talks to (message, tab, close) |
| `World` / `BlockStorage` | api / core | Flat block matrix; canonical ids; the "illusion" |
| `World.fillSection` | api / core | Bulk 16³ section read for zero-allocation chunk serialization |
| `TerrainGenerator` / `BiomeGenerator` / `WorldDecorator` | core | One-time bake: heightmap, biomes, trees / lakes / caves — then frozen |
| `LevelIO` / `BiomeStorage` | core | Persist the baked world + biome map to a compact `world/level.jdw` |
| `PlayerRegistry` | core | Thread-safe roster indexed by uuid / name / connection |
| `EventBus` | api | Zero-reflection listener registration |
| `GameLoop` / `Scheduler` | gameloop | 20 TPS heartbeat, run-later / repeating tasks |
| `TickMetrics` / `ServerStatus` | gameloop / api | Live TPS, MSPT (+ peak), uptime and memory — via `Server.getStatus()` |
| `Debug` | utils | Optional category-scoped verbose logging (off by default, zero cost) |

```java
// Lazy: if you never materialize, the expensive payload is never parsed.
ServerboundHandshake hs = incoming.materialize(ServerboundHandshake::fromBuffer);

// Events: no annotations, no reflection in the hot path.
server.getEventBus().register(PlayerJoinEvent.class, e -> log(e.getPlayer().getName() + " joined"));
```

---

## Building & running

Requires **JDK 21**. Multi-module Maven build:

```bash
mvn clean install      # build + run tests
mvn -o clean test      # offline (deps are cached after the first resolve)
```

The Bedrock RakNet dependency comes from the OpenCollab repository (not Maven Central) — the
network module declares it. Run the server from your IDE (`com.jedrock.core.JedrockServer#main`),
which binds (defaults, configurable in `jedrock.properties`):

- **Java Edition** on TCP `0.0.0.0:25565`
- **Bedrock 1.1.5** on UDP `0.0.0.0:19132`
- **Bedrock 0.14** on UDP `0.0.0.0:19133` (`server.port.pe014`; disable with `pe014.enabled=false`)

The first run writes a `jedrock.properties` next to the process with the bind host/ports, server
name, MOTD, max players, world seed, tick rate, view distance and the blind-judge limits
(`judge.enabled`, `judge.max-reach`, `judge.max-move-delta`); edit and restart to apply, or override
a single key with `-Dkey=value`. The RakNet protocol version defaults to `8` (MCPE 1.1.5)
and can be overridden with `-Djedrock.pe.raknetProtocolVersion=N` for other client builds. The Bedrock
listeners bind best-effort — a busy UDP port (the Minecraft Bedrock client itself holds 19132 for LAN
discovery) disables just that edition, never the whole server.

### Console & diagnostics

Once running, the server reads commands on stdin (headless-safe — it runs fine with stdin closed):

| Command | Effect |
|---------|--------|
| `status` / `tps` | one-line health: TPS, MSPT (+ all-time peak), players, memory, uptime |
| `players` | list online players and their edition |
| `debug [all\|off\|<tags>]` | toggle extended debug logging; scope by logger-name tags, e.g. `debug pe,chunk` |
| `gc` | request a GC, then print status |
| `stop` | graceful shutdown |

Extended debug is **off by default** — the `LOGGER.debug(...)` calls never invoke their message
supplier, so they cost nothing. Turn it on at startup with `-Djedrock.debug=all` (or scoped, e.g.
`-Djedrock.debug=pe,chunk`), or at runtime with the `debug` command. A periodic status line can be
logged with `-Djedrock.status.seconds=30`.

> **Testing a Bedrock client locally (Windows 10 Edition):** UWP apps cannot reach `localhost` by
> default. Add a loopback exemption once:
> `CheckNetIsolation LoopbackExempt -a -n=Microsoft.MinecraftUWP_yourid`

Tests are plain JUnit 5 (`mvn test`) covering the block matrix, player registry, chunk encoding
and MCPE compression — no client required.

---

## Roadmap

- **Finite "bake once" world — landed.** A bounded (48×48-chunk) world generated once on first run
  then frozen (all generation disabled, served as static decoration): persistence, the terrain bake,
  biomes, tree/lake/cave decoration and the edge wall are all in. Optional follow-ups: per-biome ground
  blocks (e.g. desert sand), configurable bounds, and disk-paged chunks if the world ever grows past a
  comfortable in-RAM size (48×48 is tens of MB, so this isn't pressing).
- **Held-item / equipment relay** — show what each player holds in-hand on their avatar; this also
  makes the item-use pose render as the specific eat / drink / block animation, and unblocks a
  Bedrock-initiated item-use signal.
- **More animations** — sneak, sprint, arm swing and item-use relay today; hurt / death animations
  need a damage model, and elytra gliding needs a clean cross-edition stop signal.
- **Sharper judge** — the blind judge lands today (reach + move-delta); per-axis limits, a knockback
  allowance and interaction ray-casts would tighten it without turning into a physics engine.
- **Scripting** — embedded JS (GraalJS) plugins with hot reload.

---

*Jedrock — do as little as possible, as late as possible.*
