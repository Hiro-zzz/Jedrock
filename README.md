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

- ✅ **Java 1.12.2 client joins** into a flat world (login → join game → chunks → spawn).
- ✅ **Bedrock 1.1.5 client joins** the same server over real RakNet (offline handshake →
  MCPE Login → Resource Packs → StartGame → chunks → spawn).
- ✅ **One shared world** — Java and Bedrock render the **same blocks**, serialized from a single
  `CoreWorld` (canonical block ids mapped per protocol).
- ✅ **Cross-platform chat** — a message typed on Java shows up on Bedrock and vice versa.
- ✅ **Presence** — join/leave announcements reach every player, on both platforms.
- ✅ **Shared player registry** — Java and Bedrock players live in the same core state and fire
  the same `PlayerJoinEvent` / `PlayerQuitEvent`.
- ✅ **Java tab list** shows every online player, Java and Bedrock alike.
- ✅ **Real gamertags** for Bedrock players (extracted from the MCPE Login JWT chain).

Not yet: block placing/breaking, player avatars/movement, the Bedrock-side player list (needs
skins), movement validation, config files, scripting. See [Roadmap](#roadmap).

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

## Module structure

```
jedrock
├── jedrock-api          # Pure contracts: Server, Player, World, events. No implementation deps.
├── jedrock-utils        # Lazy<T>, LazyPacket, ByteBufUtils (VarInt/VarLong/zigzag), logging, ticks
├── jedrock-network      # Transport + protocol handling for both editions
│   ├── handler/         # ProtocolHandler strategy (je/JavaEditionProtocolHandler)
│   ├── je/packet/       # Java Edition packets (Serverbound* / Clientbound*)
│   ├── pipeline/        # Netty codecs: VarInt framing, lazy packet decoding
│   └── pe/              # Bedrock: PeRakNetServer + McpeCompression (0xFE zlib batches)
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
`CoreWorld` exposes canonical, protocol-agnostic block ids (`World.getBlockId`, see `Blocks`) over
a procedural flat floor, with `BlockStorage` — a lazily-allocated flat matrix of `short` ids — as
the edit overlay. Each protocol maps canonical ids to its own palette when serializing chunks
(Java global state vs. Bedrock id + meta), so both clients see identical terrain.

---

## Key abstractions

| Concept | Where | Purpose |
|---------|-------|---------|
| `LazyPacket` / `Lazy<T>` | utils | Hold raw bytes; parse only on demand |
| `ProtocolHandler` | network/handler | Per-edition inbound state machine; keeps `JedrockConnection` thin |
| `PlayerConnection` | api | Protocol-agnostic handle the core talks to (message, tab, close) |
| `World` / `BlockStorage` | api / core | Flat block matrix; canonical ids; the "illusion" |
| `PlayerRegistry` | core | Thread-safe roster indexed by uuid / name / connection |
| `EventBus` | api | Zero-reflection listener registration |
| `GameLoop` / `Scheduler` | gameloop | 20 TPS heartbeat, run-later / repeating tasks |

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
which binds:

- **Java Edition** on TCP `0.0.0.0:25565`
- **Bedrock** on UDP `0.0.0.0:19132`

The RakNet protocol version defaults to `8` (MCPE 1.1.5) and can be overridden with
`-Djedrock.pe.raknetProtocolVersion=N` for other client builds.

> **Testing a Bedrock client locally (Windows 10 Edition):** UWP apps cannot reach `localhost` by
> default. Add a loopback exemption once:
> `CheckNetIsolation LoopbackExempt -a -n=Microsoft.MinecraftUWP_8wekyb3d8bbwe`

Tests are plain JUnit 5 (`mvn test`) covering the block matrix, player registry, chunk encoding
and MCPE compression — no client required.

---

## Roadmap

- **Block editing** — place/break on both editions → update `BlockStorage` → broadcast the change.
- **Player visibility** — spawn other players as entities and relay movement across editions.
- **Bedrock player list** — `PlayerList` packet with skin data (the Java tab already works).
- **The blind judge** — movement-delta and interaction-sphere validation.
- **Config** — bind addresses, world settings, MOTD from a file.
- **Scripting** — embedded JS (GraalJS) plugins with hot reload.

---

*Jedrock — do as little as possible, as late as possible.*
