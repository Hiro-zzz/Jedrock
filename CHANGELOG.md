# Changelog

All notable changes to Jedrock are recorded here. This is an internal project log; the format
loosely follows [Keep a Changelog](https://keepachangelog.com/). The project is pre-1.0 and
unstable — anything may change between entries.

## [Unreleased]

### Added

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
