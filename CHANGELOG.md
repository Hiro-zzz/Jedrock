# Changelog

All notable changes to Jedrock are recorded here. This is an internal project log; the format
loosely follows [Keep a Changelog](https://keepachangelog.com/). The project is pre-1.0 and
unstable — anything may change between entries.

## [Unreleased]

### Added

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

- **PE server-list ping advertised the wrong port.** `onQuery` used the querying client's port for
  the advertised IPv4/IPv6 port fields instead of the server's own bind port.

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
