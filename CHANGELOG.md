# Changelog

All notable changes to Jedrock are recorded here. This is an internal project log; the format
loosely follows [Keep a Changelog](https://keepachangelog.com/). The project is pre-1.0 and
unstable — anything may change between entries.

## [Unreleased]

### Changed

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

## 0.1.0 — baseline (pre-changelog)

The state before this log began (see git history and the README for detail):

- Java Edition 1.12.2 and Bedrock/PE 1.1.5 clients join the **same** shared world.
- Procedural value-noise terrain both editions render and collide against.
- Cross-platform chat, presence, a shared player registry, and the JE tab / PE player list.
- Cross-platform avatars with client-authoritative movement relayed between editions.
- Dynamic chunk streaming around each player, and cross-platform block placing / breaking.
