# Changelog

All notable changes to Jedrock are recorded here. This is an internal project log; the format
loosely follows [Keep a Changelog](https://keepachangelog.com/). The project is pre-1.0 and
unstable — anything may change between entries.

## [Unreleased]

### Added

- **Survival mode, in-game commands, and a minimal survival inventory.** A player can play in survival;
  the mode is persisted per player, so a reconnect keeps it (the only way MCPE 0.14, which has no live
  game-mode packet, ever switches). **In-game commands** work cross-edition — `/help`, `/gamemode`, `/tp`,
  `/spawn` — through one `CommandManager`: Java sends `/…` straight through as chat, and Bedrock is handed
  an `AvailableCommands` manifest at spawn (protocol-113 `versions`-wrapped JSON), so its client parses the
  line and returns a `CommandStep` the server turns back into a `/…` line. A deliberately minimal **survival
  inventory** (36 slots, 0-8 hotbar / 9-35 main) tracks only a survival player's mined and placed blocks —
  mining drops the block into the hotbar, placing consumes it — with the changed slot pushed live
  (`SetSlot` / `ContainerSetSlot`) so the HUD refreshes. Break timing is per game mode on every edition
  (creative mines instantly; survival removes the block only on completion).

- **Combat and an expanded, cross-edition damage model.** All damage — fall, void and PvP — funnels through
  one server-authoritative `JedrockServer.hurt(...)` path (survival-only: clamps, pushes health, primitive
  death). **Fall damage now works on every edition**: Java and PE 0.14 have no client fall-report packet, so
  the server tracks the descent and applies it on landing (`SetHealth` wired for 0.14, previously a no-op);
  PE 1.1.5 reports its own `EntityFall`. The finite world's **void** hurts a survival player who drops past
  its floor (a game-loop damage tick, not per-move). **PvP melee** lets a player left-click another on any
  edition — decoded from JE Use Entity (0x0A on 1.12.2 / 0x02 on 1.8) and PE `Interact` (0x21 on 1.1.5 /
  0xa9 on 0.14), all verified against PocketMine — resolved to the victim by avatar entity id, gated by the
  blind judge's reach sphere and vanilla-style half-second invulnerability frames. Every hit relays a **hurt
  animation** (the red damage flash) to onlookers on all four editions (JE Entity Status, PE `EntityEvent`
  0x1c / 0xa4). Death is a **silent instant respawn** at spawn (no death screen) on Java and 0.14; the 1.1.5
  client shows its own death screen for a client-side death, so its Respawn button is answered with a
  `Respawn` handshake. Unit-tested (`ServerboundUseEntityTest`, `AnimationPacketsTest`,
  `PeAnimationEncodingTest`, `Mcpe014EntityEventTest`, `PeRespawnEncodingTest`, plus core `hurt` / void /
  i-frame / fall-reset coverage).

- **Richer Bedrock creative menu, and a creative menu on 0.14.** The creative palette now carries
  per-meta **variants** — all 16 wool / terracotta / carpet colours, every wood and leaf type, and the
  stone / stone-brick / sandstone / quartz / dirt / sand variants — instead of one item per block id
  (~170 states). The 1.1.5 item `writeSlot` packs the variant into the protocol-113 aux field
  (`meta << 8 | count`), which placement already decodes, so a variant placed from the menu round-trips
  and renders distinctly cross-edition. **MCPE 0.14, which previously had no creative menu at all, now
  gets one too** via a protocol-45 `ContainerSetContent` (0xb9, creative window 0x79) in its login
  sequence — item slot `short id, byte count, short meta, short nbtLen`, matching what the client already
  sends inbound, and sent as a zlib **batch** (0x92, like chunks) since it's too large to go raw. The
  ancient 0.14 client has no "unknown item" fallback — an id it can't render *crashes* it — so 0.14 is
  hard-limited to a conservative classic block set (`Pe014Blocks`) on **both** paths: the creative menu
  is filled only from it, and the 0.14 chunk serializer maps any other id (a block a Java/1.1.5 player
  placed that 0.14 doesn't know) to **air**, so an unsupported block never reaches the client at all.
  1.1.5 keeps the full rich palette. Unit-tested (`PeCreativePaletteTest`, `Mcpe014ContainerContentTest`,
  `Pe014BlocksTest`).

- **Finite-world edge wall (world-generation Phase 4).** The bounded world now has an edge. A move whose
  target column falls outside the bounds is refused and the player snapped back to their last in-bounds
  spot (an invisible wall, keeping their look angles); if a player is somehow already outside, they're
  teleported home to spawn. Block edits outside the bounds are likewise refused and the client corrected
  with the real (void = air) block, so the world can't grow past its edge. Enforced on the network
  thread regardless of the anti-cheat toggle — the edge is a world constraint, not a cheat check.
  `CoreWorld` exposes the bounds (`minBound` / `maxBound` / `isInsideBounds`, unit-tested in
  `WorldBoundsTest`). This completes the finite "bake once, then serve" world — terrain, biomes,
  decoration and now a bounded edge. (Bounds stay the fixed 48×48; making them a config key is a
  possible follow-up.)

- **World decoration — trees, lakes, caves (world-generation Phase 3).** The finite world is no longer
  bare terrain: a `WorldDecorator` runs three deterministic passes at bake time (after terrain + biomes,
  with `generated == true`, so they read/write the same storage the client sees). **Caves** — a 3D-noise
  "cheese" carve below the surface; **lakes** — shallow still-water ponds, one candidate per chunk, with
  a keep-out around spawn; **trees** — per-column, biome-weighted density (dense forest, medium taiga,
  sparse plains/savanna), oak logs+leaves (spruce in taiga), planted only on a grass surface a lake or
  cave didn't take. Every placement is position-hashed (no sequential RNG), so a seed always yields the
  same world; because the whole finite world is in memory at bake time, features cross chunk borders
  with no populate-ordering. Since all of this is baked into storage, it costs nothing at runtime — the
  server still simulates nothing. New block ids `WATER` / `LEAVES` in the api. Measured on a real boot:
  a decorated 48×48 bake is ~11 500 sections in ~3.5 s and a ~1 MB level file; later boots just load it.
  Unit-tested (`WorldDecorationTest`: features appear + determinism).

- **Biomes (world-generation Phase 2).** The finite world now has biomes instead of plains everywhere.
  A deterministic `BiomeGenerator` (two broad temperature/humidity noise fields) assigns one of four
  grass-surfaced biomes — plains, forest, taiga, savanna — in large blobby regions; the bake freezes a
  per-column biome map into a `BiomeStorage` (2D companion to `BlockStorage`) that is served at runtime
  and persisted in the level file (format bumped to v2 — a 256-byte biome map per chunk after the block
  sections; DEFLATE keeps it to ~17 KB for a 48×48 world). The biome id is exposed protocol-agnostically
  via `World.getBiome` / `World.fillBiomes` and wired through **all four** chunk serializers, replacing
  the hardcoded plains: JE 1.12.2 / JE 1.8 / PE 1.1.5 send the per-column biome-id byte, and PE 0.14
  (which sends grass *colours*, not ids) maps each biome to a grass tint, so a player sees the biome
  render — grass/foliage tint — correctly on every edition. Blocks are unchanged (all four biomes are
  grass-surfaced); per-biome terrain and decoration (trees / lakes / caves) come with Phase 3.
  Unit-tested (`BiomeGeneratorTest`, `BiomeStorageTest`, biome round-trip in `LevelIOTest` /
  `CoreWorldBakeTest`); existing chunk-serializer tests still pin plains via the default biome.

- **Finite "bake once" world (world-generation Phase 1).** On first run the world is now generated once
  over a bounded region (48×48 chunks, `CoreWorld.BOUNDS_CHUNKS`) and **frozen into storage**; from then
  on the terrain generator is never consulted at runtime — `CoreWorld` serves blocks straight from the
  baked `BlockStorage`, matching the "generate once, then serve static decoration" design. `bake()`
  resolves every cell of the bounds (procedural terrain plus any pre-bake overlay edits) into full
  sections, so it also cleanly migrates a pre-bake world; a coordinate outside the bounds reads air (the
  world is finite). Serving switches on the persisted `generated` flag: a fresh/pre-bake world keeps the
  on-demand terrain + edit-overlay path (with the explicit-air sentinel), while a baked world reads
  storage only and a break stores genuine air. `JedrockServer` bakes-or-loads before any listener binds
  and saves the freshly baked world; a `dirty` flag lets autosave and the shutdown save skip an
  unchanged world instead of rewriting it. Measured on a real boot: 48×48 bakes to 10 240 sections in
  ~0.3 s and saves to a ~420 KB level file (DEFLATE collapses the uniform terrain); the second boot just
  loads it, no re-bake. Biomes, decoration (trees / lakes / caves) and the edge wall are the next phases.
  Unit-tested (`CoreWorldBakeTest`).

- **World persistence (world-generation foundation, Phase 0).** The world now survives a restart. A
  compact, Jedrock-specific level file (`world/level.jdw`) stores an uncompressed header (format
  version, seed, the finite 48×48-chunk bounds, spawn, a `generated` flag) followed by every allocated
  16³ `BlockStorage` section in a single DEFLATE stream — close to a raw dump of the in-memory
  `short`-per-block model, so DEFLATE collapses the uniform stone/air runs to a small file. `LevelIO`
  reads/writes it; saves are atomic (write a sibling `.tmp`, then move into place, so a crash
  mid-write can't corrupt an existing world). The world is loaded once at startup **before any
  listener binds** (a joining player sees the persisted edits), saved on shutdown, and autosaved every
  `-Djedrock.world.save-seconds` (default 300; `0` disables) on a background thread that skips if a
  save is still running. Terrain itself stays procedural — only player edits are stored, and a broken
  natural block persists via the explicit-air sentinel, which round-trips byte-exact. This is the
  groundwork for the finite "bake once, then serve" world (bounds enforcement, biomes and decoration
  to follow); the `generated` flag and bounds are recorded but not yet enforced. Unit-tested
  (`LevelIOTest`, `CoreWorldPersistenceTest`) and validated with a real boot → restart cycle.

- **Unified chat markup.** Chat and system messages are authored once in an edition-agnostic format —
  `{color}` braces for the 16 Minecraft colours (+ aliases) and `{reset}`, plus Markdown styles
  (`**bold**`, `*italic*` / `_italic_`, `__underline__`, `~~strike~~`; style names also work in braces)
  — and `ChatText` (in `jedrock-utils`) renders it to the legacy `§` codes every version understands
  (Java inside the JSON text component, Bedrock in a raw `TextPacket`). It is colour/style aware (a
  legacy colour code clears styles on the client, so it emits colour first, re-applies styles, and adds
  `§r` only when a style must actually turn off), so one rendered string formats identically on every
  edition. Rendering happens once in `CorePlayer.sendMessage`; raw `§` and unknown `{tags}` pass through.
  Players can use the markup in chat too. Unit-tested.
- **MCPE 0.14 player list + crouch pose.** Other players now appear in the 0.14 pause-menu list
  (`PlayerList` 0xc3, with a synthetic `Mcpe014Skin` — the client crashes on an empty skin), and the
  crouch pose is relayed onto 0.14 avatars via `SetEntityData` 0xad (the DATA_FLAGS byte; sprint /
  item-use the client already draws itself).
- **Bedrock / MCPE 0.14 support (protocol 45), from scratch.** A second, older Bedrock edition now
  joins the same shared world as 1.12.2 / 1.8 / 1.1.5. 0.14 predates the modern Bedrock protocol, so it
  is a parallel implementation rather than a tweak of the 1.1.5 layer: a big-endian wire (not VarInt), a
  one-byte `0x8e` game wrapper on every packet, a `0x92` zlib `BatchPacket` for large packets, plaintext
  login (no Xbox/JWT chain), and 128-tall full-column `FullChunkData` chunks (ORDER_COLUMNS: block ids +
  metadata / sky / block-light nibbles + heightmap + biome). It lives in `network/pe/v014`
  (`Mcpe014Codec` / `Login` / `Packets` / `ChunkSerializer` / `Batch`, a `PeSession014` that is both the
  RakNet session and the core's `PlayerConnection`, and a `Pe014RakNetServer`). Because a Bedrock client
  negotiates a RakNet protocol version in its offline handshake and one UDP socket serves exactly one of
  them, 0.14 (RakNet v7) binds its **own port** (`server.port.pe014`, default 19133) next to 1.1.5
  (v8). The Bedrock listeners now bind best-effort — a busy UDP port (the Minecraft Bedrock client
  itself holds 19132 for LAN discovery) disables just that edition instead of aborting the server.
  Validated with a real 0.14 phone client: login → spawn on the shared procedural terrain → move / dig /
  build, cross-play with a Java client in one world (blocks, chat, avatars). The 0.14 client draws
  nametags, skins and the sprint / item-use poses itself, so the server sends less than 1.1.5 needs. The
  wire was reverse-checked against PocketMine-MP at `CURRENT_PROTOCOL = 45`.
- **Multiversion framework for Java Edition.** One TCP listener now
  serves several JE protocol versions at once. A version-neutral `JedrockConnection` owns only what is
  stable across versions (channel + framing, movement merge, chunk streaming, keep-alive, lifecycle)
  and delegates every version-specific encode to a `JavaProtocol` strategy. A shared
  `JavaHandshakeHandler` bootstraps each connection, reads the client's protocol number from the
  handshake, and installs the matching `JavaProtocol` from the `JavaProtocols` registry (or refuses an
  unsupported version); the server-list ping echoes the client's own protocol so it always shows as
  compatible. All legacy JE versions share the world's canonical `(id<<4)|meta` model, so no block
  translation is needed between them. 1.12.2 became the first `JavaProtocol` implementation with its
  wire format unchanged.
- **Java Edition 1.8 support (protocol 47).** A `Java1_8ProtocolHandler`
  plugs into the framework above: full login → play, join sequence, movement, chat, block break/place,
  cross-platform avatars and the sneak/sprint/item-use pose. The 1.8 deltas from 1.12.2 are handled — a
  byte dimension in Join Game, VarInt keep-alive ids, fixed-point entity coordinates, the old
  header-tagged entity-metadata format (`(type<<5)|index`, list terminated by `0x7F`), no
  teleport-confirm handshake, and the 1.8 grouped chunk layout (`Java1_8ChunkData`: little-endian
  `(id<<4)|meta` shorts, then block light, then sky light, then a biome map). Placement reads the held
  block straight out of the 1.8 placement packet (legacy id + damage), so it lands in the shared world
  cross-edition. The full pose (crouch / sprint / item-use) travels in one shared entity-flags byte, so
  the cross-edition item-use pose renders on 1.8 avatars too. Packet ids and formats are centralised in
  `Java1_8Protocol`; unit tests pin the 1.8 chunk bytes. Confirmed with a real 1.8 client (an
  unsupported version is refused at handshake, so it can't destabilise 1.12.2 or Bedrock).
- **Crash-packet guard (blind judge, wire layer).** A new `PacketGuard` hardens the Bedrock inbound
  path against malicious packets that aim to crash or stall the server: `McpeCompression` now caps how
  far a `0xFE` batch may inflate (rejecting a tiny "zip bomb" that would balloon to gigabytes and OOM
  the process), the batch dispatcher caps inner-packet count, and the item / inventory-transaction
  decoders reject an out-of-bounds wire-driven list length before looping on it. Generous limits, far
  above anything a real 1.1.5 client sends. (The Java pipeline was already frame- and array-length
  capped.)
- **The blind judge — lazy anti-cheat.** A dependency-free `BlindJudge` runs two cheap, allocation-free
  checks on the network threads instead of a physics engine: an **interaction sphere** rejects a block
  edit farther than `judge.max-reach` from the editor (the client is corrected by re-sending the real
  block), and a **movement-delta** check rejects a position jump larger than `judge.max-move-delta`
  between two reports (the client is snapped back to its last valid spot via a new
  `PlayerConnection.teleport`). Thresholds are generous by design and come from config
  (`judge.enabled` / `judge.max-reach` / `judge.max-move-delta`). (Closes a roadmap item.)
- **Item-use pose animation (eat / drink / block / draw bow).** The Java client's item use is decoded
  (start = Use Item `0x20`, stop = Player Digging release, status 5) and relayed cross-edition as an
  entity pose (JE Entity Metadata index 6 hand-states `0x01`; PE `DATA_FLAG_ACTION`, bit 4). Crouch,
  sprint and item-use now travel through one unified `setPose(sneaking, sprinting, usingItem)` — the PE
  side shares a single `DATA_FLAGS` long, so sending them together (with the base nametag flags) keeps
  one from clearing another. A late joiner is synced to the full pose. Bedrock-initiated item-use isn't
  decoded yet (the 1.1.5 signal is under-documented), and the pose stays generic until held items are
  relayed. PE flag verified against PocketMine-MP at protocol 113.
- **Nametags above Bedrock avatars.** The `AddPlayer` metadata now carries `DATA_NAMETAG` (the
  player's name) plus the `CAN_SHOW_NAMETAG` / `ALWAYS_SHOW_NAMETAG` flags, so every player's name —
  Java players included — floats above their avatar on Bedrock the way it does on Java (previously the
  metadata was empty and no nametag showed). Those flag bits are folded into `BASE_ENTITY_FLAGS` and
  re-sent on every pose update, so crouching/sprinting no longer clears the name.
- **Player animations — sneak, sprint + arm swing (cross-edition).** Crouch, sprint and arm-swing are
  decoded from each edition (JE Entity Action `0x15` / Animation `0x1D`; PE PlayerAction actions
  9/10/11/12 / Animate `0x2c`), relayed through `ConnectionListener.onSneak` / `onSprint` / `onSwingArm`
  + `PlayerConnection.setPose` / `swingArm`, and re-encoded per edition (JE Entity Metadata `0x3c` /
  Animation `0x06`; PE SetEntityData `0x27` / Animate). Sneak and sprint share one flags field per
  edition (JE flags byte, PE DATA_FLAGS long), so `setPose` sends both bits together rather than one
  clearing the other. A late joiner is synced to anyone already crouching or sprinting. PE wire formats
  verified against PocketMine-MP at protocol 113.
- **Real Bedrock skins.** A Bedrock player's `SkinId` + `SkinData` are pulled from the Login JWT
  (`McpeLoginIdentity`) and relayed into the PE `PlayerList` via a shared uuid→skin registry, so
  Bedrock players see each other's real skins instead of the synthetic placeholder. A malformed or
  wrong-sized texture falls back to synthetic. Cross-edition stays limited by Java's signed-texture
  model (Bedrock players remain Steve/Alex on Java). (Advances a roadmap item.)
- **Block metadata (block variants).** The world now stores a packed `(id << 4) | meta` state per
  cell instead of a bare id (`Blocks.state/idOf/metaOf`; the `BlockStorage` short already had room).
  Metadata flows end to end: placement reads the variant from the held item (JE creative slot damage,
  Bedrock item aux `meta << 8 | count`), chunks serialize it (JE global-palette id — now an identity
  map — and the Bedrock sub-chunk's 4-bit nibble array, previously written as zeros), and single-block
  edits carry it (JE Block Change value, PE `UpdateBlock` meta). Wool colours, wood/stone types, etc.
  now render distinctly and cross-edition. (Closes a roadmap item.)
- **Java Edition server-list ping.** The server now answers the JE status flow (`handleStatus`):
  Request → Response (a JSON blob with version, MOTD and player counts) and Ping → Pong (echoing the
  client's latency probe). Jedrock now shows up in the Java multiplayer list instead of failing to
  respond. MOTD and max players come from config; the live online count is surfaced to both editions'
  pings via a new `ConnectionListener.getOnlinePlayerCount()` hook — so the Bedrock query's formerly
  hard-coded `0` online now reflects reality too. (Closes a roadmap item.)
- **File-based configuration.** Settings now load from `jedrock.properties` in the working directory
  (`JedrockConfig` → the protocol-agnostic `ServerProperties` record in `jedrock-api`). On first run
  the bundled template is written to disk; missing or malformed keys fall back to defaults with a
  warning rather than failing, and any key can be overridden at launch with `-Dkey=value`. Wired
  through the server: bind host + JE/PE ports, server name, world seed (`random` / numeric / hashed
  text, Minecraft's rule), tick rate, view distance, and the server-list advertisement (MOTD + max
  players on the PE ping, max players in JE Join Game). (Closes a roadmap item.)
- **Bedrock creative inventory.** The PE creative menu is populated with the full set of standard
  legacy MCPE 1.1.5 blocks (base variants — the world stores a single block id, so per-meta variants
  aren't distinct yet). The fix was using the protocol-113 `ContainerSetContent` packet (0x34, with
  its `targetEid` and trailing hotbar-link count) rather than the 1.2+ `InventoryContent` (0x31);
  the item serialization itself already matched. Verified against PocketMine-MP at
  `CURRENT_PROTOCOL = 113`.
- **Bedrock flight.** Creative players can now fly (double-tap jump). `AdventureSettings`'
  `entityUniqueId` is written as a little-endian long, not a VarInt — the short write truncated the
  packet, so its `ALLOW_FLIGHT` flag never reached the client.
- **Server status, TPS monitoring and an optional extended-debug system.** The game loop tracks live
  TPS and mean / peak MSPT (`TickMetrics`), surfaced with uptime, memory and player count via
  `Server.getStatus()`. A daemon stdin console exposes `status`/`tps`, `players`, `debug`, `gc` and
  `stop`. Extended debug logging stays off by default (the message supplier is never invoked, so it
  costs nothing) and can be enabled globally or scoped to logger-name tags — via `-Djedrock.debug=...`
  or the `debug` command. `-Djedrock.status.seconds=N` logs a periodic status line.

### Changed

- **Wider JE chunk-section palette.** Each 1.12.2 section now picks the smallest legal bits-per-block
  (4–8) for its palette instead of a fixed 4, packing the indices with the 1.12.2 straddling bit
  layout (entries may cross a long boundary). A section with more than 16 distinct block states no
  longer overflows the palette and corrupts (indices beyond 256 states — unreachable with our block
  set — still clamp safely). (Closes a roadmap item.)
- **Typed PE `UpdateBlock`.** A block edit is now reflected to Bedrock as a single `UpdateBlock`
  (0x16) packet — a few bytes — instead of re-serializing and re-sending the whole affected chunk
  (~10 KB). Block position layout matches the inbound edit decoder; verified against PocketMine-MP
  at protocol 113. (Closes a roadmap item.)
- **PE network layer split into functional parts.** The ~1000-line `PeRakNetServer` is now just the
  RakNet transport plus the server-level listener (ping / accept / session creation). The per-session
  MCPE game layer moved to `PeSession`, which delegates wire concerns to focused units:
  - `McpeProtocol` — packet ids and protocol constants (one source of truth).
  - `McpeCodec` — UUID + network Item read/write helpers.
  - `McpeChunkSerializer` — chunk-column serialization (pure `world, cx, cz → byte[]`).
  - `McpeLoginIdentity` — gamertag + UUID extraction from the Login JWT chain.
  - `McpeSkin` — placeholder avatar skin.
  - `PeBlockEditDecoder` — inbound break/place packets → a canonical `BlockEdit`.
- **Chunk serialization is allocation-free on the hot path.** New bulk `World.fillSection` reads a
  whole 16³ section with one storage lookup and one height evaluation per column (no per-block map
  lookup or boxing); a default implementation keeps existing `World`s working. Both serializers use
  it with reused per-thread scratch buffers:
  - JE `ClientboundChunkData` — replaced the `List<Integer>` palette + `indexOf` (boxed linear scan
    per block) and the per-section `int[4096]` with a primitive palette and reused buffers; light and
    biomes now write via `writeZero` / `writeBytes` instead of per-byte loops.
  - PE `McpeChunkSerializer` — removed the double section scan (`sectionHasBlocks` + re-read).
- **Removed the `CoreWorld` height cache.** With the hot path computing heights directly, the
  `ConcurrentHashMap<Long,Integer>` only boxed keys and leaked memory; surface height is now
  recomputed on demand from the allocation-free noise generator.
- **Per-packet allocation trimmed on the movement/chat paths.** `PlayerRegistry` gained
  `getByConnectionOrNull` and a live `online()` view; `JedrockServer.onMove` / `onChat` no longer
  allocate an `Optional`, a capturing lambda, or an unmodifiable-collection wrapper per packet.
- **PE outbound batching writes packets straight into the batch.** Packets are encoded into a reused
  scratch buffer (measured for the length prefix) instead of a fresh `byte[]` each, and the batch is
  deflated directly from its backing array — no intermediate `uncompressed` copy.
- **`GameLoop` ticks without per-tick allocation.** Swapped the `CopyOnWriteArrayList` of tickables
  for a hand-rolled copy-on-write array iterated by index (same visibility guarantees, no iterator).

### Fixed

- **Bedrock 1.1.5 players ran too fast (runaway acceleration).** The movement-speed attribute
  (`minecraft:movement` = 0.1) was byte-correct but sent under the wrong packet id — `UpdateAttributes`
  is **0x1E** at protocol 113, not the 0x1D we used — so the client never recognized it and stayed on its
  buggy accelerating default. (0x1E had been mislabeled `ID_INVENTORY_TRANSACTION`; that inbound case was
  dead — no InventoryTransaction packet exists at 113, block edits arrive via UseItem / PlayerAction — and
  was removed. The attribute is now also sent after the spawn `PlayStatus`, matching PocketMine.)
- **1.12.2 survival players couldn't place their mined blocks.** The 1.12.2 Block Placement packet carries
  no held item, so the server reads the placed block from its hotbar mirror — which was only populated by
  creative inventory actions, leaving it empty/stale in survival, so placements used the wrong block or
  none (and the item was never consumed, so it appeared to "stack" and the ghost block couldn't be broken).
  The mirror is now kept in step with the survival inventory on every slot / inventory sync. (PE 1.1.5 and
  JE 1.8 were unaffected — their placement packets carry the item.)
- **Bedrock 1.1.5 death left the player stuck on an endless death screen.** A client-side death (e.g. a
  fatal fall the 1.1.5 client reports and applies itself) shows a death screen the silent core respawn
  can't dismiss, and the Respawn button did nothing because the server never answered it. The client's
  `PlayerAction RESPAWN` is now answered with a `Respawn` packet (0x2d) + full health, closing the menu;
  the respawn Y is eye-level (sending feet spawned the player embedded in the ground). Java and 0.14 are
  unaffected — their health is server-driven, so they never show a death screen.
- **PE server-list ping advertised the wrong port.** `onQuery` used the querying client's port for
  the advertised IPv4/IPv6 port fields instead of the server's own bind port.
- **Usernames leaked into chat markup.** A username was embedded into the `{color}` markup of the
  chat, join and leave lines without escaping, so an ordinary `_` in a name (e.g. `Steve_123`) toggled
  italic mid-line, and a self-named 0.14 client (plaintext login) could inject `{color}` tags or raw
  `§` codes. Added `ChatText.escape` (backslash-escapes `\ { * _ ~`, drops raw `§`) and apply it to the
  three name-in-markup sites; the chat message body stays raw (the documented player-markup feature).
  The username is also sanitized once at ingress (`ChatText.stripCodes` — drops raw `§` / control chars)
  so it can't colour the nametag / tab entry either, which render the name verbatim (not via markup).
  Unit-tested.

### Removed

- Creative palette's first-`MovePlayer` fallback send (and its one-shot guard): the creative content
  is now sent once, right after the spawn `PlayStatus`, matching PocketMine.
- Unused `ByteBufUtils` helpers (`varIntSize`, `readPosition`, `writePosition`).

## 0.1.0 — baseline (pre-changelog)

The state before this log began (see git history and the README for detail):

- Java Edition 1.12.2 and Bedrock/PE 1.1.5 clients join the **same** shared world.
- Procedural value-noise terrain both editions render and collide against.
- Cross-platform chat, presence, a shared player registry, and the JE tab / PE player list.
- Cross-platform avatars with client-authoritative movement relayed between editions.
- Dynamic chunk streaming around each player, and cross-platform block placing / breaking.
