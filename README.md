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
| Bedrock / Pocket Edition | **1.1.5** | 113 | RakNet over UDP |

> ⚠️ **Status: early but real.** This is a from-scratch experiment, not a production server.
> What works today is listed below — and it genuinely works with real, unmodified clients.

---

## What works today

- ✅ **Java 1.12.2 client joins** into a procedurally generated world (login → join game → chunks → spawn).
- ✅ **Procedural terrain** — a deterministic value-noise heightmap (rolling hills, grass/dirt/stone
  layers) generated as a pure function of a seed; players spawn standing on the surface.
- ✅ **Collision** — comes for free: the client collides against the solid ground we serialize to it;
  the server runs no physics (see the philosophy below).
- ✅ **Bedrock 1.1.5 client joins** the same server over real RakNet (offline handshake →
  MCPE Login → Resource Packs → StartGame → chunks → spawn).
- ✅ **One shared world** — Java and Bedrock render the **same blocks** from a single `CoreWorld`;
  the Bedrock side serializes chunks in the MCPE 1.0/1.1 network format (blocks + metadata + sky/block
  light + heightmap), so a Bedrock client stands on exactly the terrain a Java client sees.
- ✅ **Cross-platform chat** — a message typed on Java shows up on Bedrock and vice versa.
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
- ✅ **Bedrock creative inventory + flight** — the PE creative menu is filled with the full standard
  legacy block palette (via the protocol-113 `ContainerSetContent` packet), the player can fly (fixed
  `AdventureSettings`), and a movement-speed attribute kills the runaway acceleration.
- ✅ **Block metadata (variants)** — the world stores a packed `(id << 4) | meta` state per cell, so
  wool colours, wood/stone types and the like are preserved and rendered distinctly on both editions.
  Placement reads the variant from the held item (JE creative damage, Bedrock item aux); chunks carry
  it (JE global-palette id, Bedrock's 4-bit nibble array), as do single-block edits.
- ✅ **Wide JE chunk palette** — each JE section picks the smallest legal bits-per-block (4–8) for its
  palette, so a section with more than 16 distinct states no longer overflows and corrupts.
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
- ✅ **Player animations (sneak, sprint + arm swing)** — crouch, sprint and arm swings are decoded
  from each edition (JE Entity Action / Animation, PE PlayerAction / Animate) and relayed cross-edition
  (JE Entity Metadata / Animation, PE SetEntityData / Animate), so a phone player sees a PC player
  crouch, sprint and swing, and vice versa. Sneak and sprint share one flags field, so they're sent
  together; a late joiner is synced to anyone already crouching or sprinting.

Not yet: cross-edition skin fidelity (a signed-texture limit, see above), movement validation, scripting.
See [Roadmap](#roadmap).

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
   approximation at the points that matter (movement deltas, interaction spheres) — *planned*.
4. **The two-headed monster.** The network layer isolates the core from both protocols' nightmares
   (RakNet, zlib batches, differing block palettes). To the core, a PC player and a phone player
   are identical `Player` objects.
5. **Scriptable API.** Custom logic is meant to live in fast, hot-reloadable scripts rather than
   compiled jars — *planned*.

Concretely, the codebase holds to three rules: **lightweight** (few deps, few allocations),
**absolute abstraction** (the `api` module knows nothing about packets or wire formats), and
**lazy parsing**.

---

## Performance

The illusionist design spends almost nothing per player. A quick smoke test — **101 players**
(100 bots) on one default world, read straight off the server's own `status` command:

```
TPS 20.0 | MSPT 0.04 (peak 4.01) | players 101 | mem 78/4004 MB | up 1m39s
```

- **TPS 20.0** — the loop never falls behind.
- **~0.04 ms per tick** — the game loop does almost no per-player work: movement and edits are
  *relayed* (on the network threads), not simulated, so the tick thread stays essentially idle.
- **~78 MB heap for 101 connections** — the world is a lazily-allocated id matrix, not a live
  simulation, and inbound bytes stay raw until something needs a value.

Numbers are from bots (lighter than humans exploring fresh terrain), so treat them as a floor, not a
benchmark — but the shape is the point: **"system requirements" is, generously, a formality.** Watch
it live with the `status` command or `-Djedrock.status.seconds=N` (see [Console & diagnostics](#console--diagnostics)).

---

## Module structure

```
jedrock
├── jedrock-api          # Pure contracts: Server, Player, World, events. No implementation deps.
├── jedrock-utils        # Lazy<T>, LazyPacket, ByteBufUtils (VarInt/VarLong/zigzag), logging, ticks
├── jedrock-network      # Transport + protocol handling for both editions
│   ├── handler/         # ProtocolHandler strategy (je/JavaEditionProtocolHandler)
│   ├── je/packet/       # Java Edition packets (Serverbound* / Clientbound*)
│   ├── pipeline/        # Netty codecs: VarInt framing, lazy packet decoding
│   └── pe/              # Bedrock, split by concern: PeRakNetServer (RakNet transport) +
│                        #   PeSession (per-session MCPE game layer) delegating to McpeProtocol,
│                        #   McpeCodec, McpeChunkSerializer, McpeLoginIdentity, McpeSkin,
│                        #   PeBlockEditDecoder, McpeCompression (0xFE zlib batches)
├── jedrock-gameloop     # Dedicated 20 TPS drift-correcting loop + Scheduler (Tickable)
└── jedrock-core         # The server: PlayerRegistry, CoreWorld/BlockStorage, JedrockServer
```

Dependency direction: `network → api`, `core → api + network + gameloop + utils`. The network
layer never depends on the core; it reaches it only through the `ConnectionListener` hook.

---

## How it works

### Java Edition path
Raw TCP → `VarintFrameDecoder` → `LazyPacketDecoder` (id + raw payload) → `JedrockConnection`,
which delegates to `JavaEditionProtocolHandler`. On login it sends the packets a vanilla client
needs to spawn (Join Game, Player Abilities, chunk data, Position & Look) and hands the player up
to the core.

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
value both legacy protocols use (`World.getBlockId`, see `Blocks`) — over a procedural heightmap
(`TerrainGenerator` — deterministic value noise, computed on demand, never stored), with
`BlockStorage` — a lazily-allocated flat matrix of `short` states — as the edit overlay. Each
protocol maps that state to its own wire form when serializing chunks (Java global-palette id vs.
Bedrock id + a 4-bit meta nibble), so both clients see — and collide against — the same terrain.

For the chunk hot path, both editions bulk-read a section through `World.fillSection`, which resolves
terrain + overlay for a whole 16³ section with a single storage lookup and one height evaluation per
column (no per-block map lookup or boxing). Serializers reuse per-thread scratch buffers, so encoding
a chunk allocates nothing per section.

---

## Key abstractions

| Concept | Where | Purpose |
|---------|-------|---------|
| `LazyPacket` / `Lazy<T>` | utils | Hold raw bytes; parse only on demand |
| `ProtocolHandler` | network/handler | Per-edition inbound state machine; keeps `JedrockConnection` thin |
| `PlayerConnection` | api | Protocol-agnostic handle the core talks to (message, tab, close) |
| `World` / `BlockStorage` | api / core | Flat block matrix; canonical ids; the "illusion" |
| `World.fillSection` | api / core | Bulk 16³ section read for zero-allocation chunk serialization |
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
- **Bedrock** on UDP `0.0.0.0:19132`

The first run writes a `jedrock.properties` next to the process with the bind host/ports, server
name, MOTD, max players, world seed, tick rate and view distance; edit and restart to apply, or
override a single key with `-Dkey=value`. The RakNet protocol version defaults to `8` (MCPE 1.1.5)
and can be overridden with `-Djedrock.pe.raknetProtocolVersion=N` for other client builds.

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

- **More animations** — sneak, sprint and arm swing relay today; eating/blocking poses and hurt
  animations are the same relay pattern extended with more action ids and metadata flags.
- **The blind judge** — movement-delta and interaction-sphere validation, now that movement is live.
- **Scripting** — embedded JS (GraalJS) plugins with hot reload.

---

*Jedrock — do as little as possible, as late as possible.*
