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
| Bedrock / Pocket Edition | **1.1.5** ⚠️ | 113 | RakNet over UDP |
| Bedrock / Pocket Edition | **0.14** | 45 | RakNet over UDP |

> ⚠️ **1.1.5 is experimental / known-buggy.** Join, movement, chat, block edits and cross-play work, but
> the retail 1.1.5 client (confirmed on **both PC and mobile**) double-fires place/break (mitigated
> server-side, not eliminated) and chests can't be opened on it (see [Known limits](#roadmap)). **0.14**
> and **Java** are the clean Bedrock/PC targets. The problems are specific to the protocol-113 client
> across platforms — not the input method, and not the core.

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
  had no creative menu, gets a palette that **mirrors PocketMine-MP's own 0.14 creative list**
  (~323 states: stone/quartz variants, all stairs and slabs, fences + gates, trapdoors, walls, flowers,
  saplings, carpets, torch, ladder, spawner… plus the item half — all five tool/weapon tiers, four
  armor sets, bow, food and materials) — every id/meta battle-tested against this exact client
  generation, because the old client crashes on an id it can't render; anything item-shaped sent to
  0.14 passes one crash gate that turns an unknown id into an empty slot. Items are inert (held /
  stored — no durability, crafting or eating; a door doesn't place). The **player-inventory sync**
  (window 0 + the hotbar-link table) landed with it, so the inventory API (`giveItem`, `/inv`, survival
  pickup) now works on 0.14 too. The player can fly (fixed `AdventureSettings`), and a movement-speed
  attribute kills the runaway acceleration.
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
- ✅ **Survival mode, in-game commands and a minimal inventory.** A player can run in survival; the mode is
  remembered per player, so a reconnect keeps it (the only way MCPE 0.14, which can't switch mode live,
  ever changes). **In-game commands** work cross-edition — Java sends `/…` straight through as chat, and
  Bedrock is handed an `AvailableCommands` manifest so its client parses the line and sends it back. The
  built-in set: `/help [cmd]`, `/list`, `/tps`, `/say`, `/me`, `/msg`, `/gamemode`, `/tp`, `/tphere`,
  `/tpall`, `/spawn`, `/heal`, `/kill`, `/clear`, `/op`, `/deop`, `/perm`. A deliberately minimal **survival inventory** (36 slots)
  tracks only what a survival player mines and places: mining a block drops it into the hotbar, placing
  consumes it, and the changed slot is pushed live so the HUD refreshes. On PE 1.1.5 the player window is
  serialized PMMP-exact (45 slots + a 9-entry hotbar-link array), without which mined items filled storage
  but the on-screen hotbar stayed empty.
- ✅ **One command surface, unified console and permissions.** A command is written against a
  `CommandSender` — a player *or* the server console — so the same command runs from chat and from stdin:
  type `op alice` or `gamemode creative bob` straight into the console (it acts as an operator), and a
  player-only command like `/spawn` is refused there with a clear message. **Operators** persist to
  `ops.txt` (an op holds every permission; the console is always an op, so the first `/op` is granted from
  the console). On top sits a **native group permission system** (`permissions.txt`): named groups with
  inheritance, a default group new players fall into, and permission nodes supporting `*` / `a.b.*`
  wildcards and `-node` explicit deny (deny wins). Each group can carry a chat **prefix** (`{red}[Admin] `)
  shown via the `%prefix%` slot in the chat format. Manage it live with `/perm` (create/delete groups, grant
  or deny nodes, set inheritance, prefix and the default group, assign players) — every change persists.
  Guarded commands are gated on their node, and `/help` hides what the sender can't run.
- ✅ **Typed command arguments and tab-completion.** A command can declare its arguments as typed
  `CommandArg`s instead of parsing a raw `String[]`: a name, an `ArgType` (`WORD`, `GREEDY`, `INTEGER`,
  `NUMBER`, `BOOLEAN`, `PLAYER`, `GAME_MODE`, `choice(…)`), and required/optional. The core then parses
  the tokens once — with uniform error messages, a usage line generated from the signature, and the same
  "player not found" / "not a whole number" reported everywhere — before the command body runs (`ArgCommand`;
  `/gamemode` is migrated to it). The same declaration drives **tab-completion**: a Java client's
  serverbound Tab-Complete is answered with the online roster for a `PLAYER` argument, a `choice`'s
  literals, or matching command names (permission-gated, so a sender is only offered what it can run),
  encoded per version (1.12.2 `0x0E`, 1.8 `0x3A`). Scripts get it too — a command's optional
  `complete(player, args)` returns candidates the core narrows to the partial (`/kit` in `plugins/example.js`).
  Bedrock keeps its client-side completion from the AvailableCommands manifest, so this is a Java-side
  feature; the retail 1.1.5 client's known bugs are reason enough not to enrich that manifest.
- ✅ **Sidebar scoreboard and boss bar (Java), plus virtual chests for scripts.** Three illusion-toolkit
  additions. **`player.setSidebar(title, [lines])`** shows a titled panel down the right of the screen; it
  updates by diffing so a timer refresh never flickers, and keeps up to 16 lines (the pre-1.13 client draws
  the vanilla red score number beside each). **`player.setBossBar(title, progress[, color])`** shows the
  bar across the top — cross-edition where a client can draw one: Java 1.12.2 (dedicated packet), Java 1.8
  (an invisible wither ridden by the player, the classic illusion) and Bedrock 1.1.5 (native BossEvent).
  The scoreboard is Java-only (1.8 has it, the legacy Bedrock clients don't); 0.14 predates both. And
  **`menus`** gives scripts a **virtual chest**: `menus.create(title, rows)`, laid out with `setItem`,
  opened with `open(player)` — with an `onClick` it's a read-only **button menu** (a class picker, a shop),
  without one a transient **storage chest**. Java and Bedrock **0.14** open a real chest window; on **1.1.5**
  (which crashes on one) a button menu degrades to a text **list** — labelled buttons (`menu.button(slot,
  item, label)`) become options the player chooses with a built-in **`/pick <label>`**. Packet ids are from
  minecraft-data / PocketMine; on-client behaviour isn't verified here. Try `/sb on`, `/boss 50 red` and
  `/menu` in `plugins/example.js`.
- ✅ **Player-facing UI — titles, subtitles and the action bar.** `player.sendTitle(title, subtitle[, fadeIn,
  stay, fadeOut])`, `sendActionBar(text)` and `clearTitle()` show a large centred title or a line above the
  hotbar, authored in the unified markup and rendered per edition: the JE Title packet (id 0x45 on 1.8, 0x48
  on 1.12.2; action bar via chat position 2), and the native Bedrock **SetTitle** on 1.1.5 (byte-verified
  against PocketMine at protocol 113). PE 0.14 predates the packet and falls back to chat. Pure illusionist
  feedback — the server asks the client to draw text. Handy QoL came with it: `Server.getPlayer(UUID)`,
  `Player.teleport(x, y, z[, yaw, pitch])`, `World.getHighestBlockY(x, z)`, `World.setSpawnLocation(...)`, and
  a full **inventory API** for scripts — `getItem` / `setItem` / `giveItem(state, count)` / `removeItem` /
  `countItem` / `clearInventory` over the 36 storage slots, each change synced to the client. Later joined
  by `Player.getPing()` (JE: the keep-alive round trip; Bedrock: RakNet's own estimate) and **chat display
  names** — `setDisplayName('{gold}Nick')` restyles `%name%` in chat (authored markup renders raw; the
  default, client-controlled name stays escaped), while identity everywhere else keeps the real name.
  Try `/nick` in `plugins/example.js`.
- ✅ **Damage — fall, void and PvP, cross-edition.** Survival players take damage, all funnelled through one
  server-authoritative path. **Fall damage** works on every edition: Java and PE 0.14 have no client
  fall-report packet, so the server tracks the descent and applies it on landing; PE 1.1.5 reports its own
  fall (`EntityFall`). The finite world's **void** hurts a player who drops past its floor. **PvP melee**
  lets a player attack another on any edition (JE Use Entity, PE `Interact`), gated by a reach check and
  vanilla-style half-second invulnerability frames. Every hit relays a **hurt animation** — the red damage
  flash — to onlookers on all four editions. Death is a **silent instant respawn** at spawn (no death
  screen) on Java and 0.14; the 1.1.5 client insists on its own death screen for a client-side death, so
  its Respawn button is answered with a proper respawn handshake.
- ✅ **Interactive inventories and chests (Java).** The player inventory is a real, server-authoritative
  container — open it and drag / stack / split / shift-click items (left, right and shift clicks), with
  armor and off-hand slots. **Chests** are a placeable block (right-click to open) backed by a 27-slot
  container: move items in and out, shift to quick-transfer, in survival <em>and</em> creative (the
  creative inventory is mirrored server-side so the chest's player half is tracked). Chest contents
  **persist** in the level file (format v3, back-compatible with v2 worlds). Wired for JE 1.12.2 and 1.8.
  True to the model, the server only <em>stores and moves</em> items — no crafting or smelting simulation,
  no item entities (a dropped / overflow item simply vanishes).
- ✅ **Chests on Bedrock 1.1.5 — click-transfer.** The retail 1.1.5 client crashes on a real chest window,
  so chests there use a **click-transfer** instead: a right-click withdraws the first stack, a sneaking
  right-click deposits the held hotbar slot. Works in survival and creative (creative deposits its held
  item without consuming and never mints items on withdrawal). The server stays authoritative for the
  survival inventory — the client's own inventory echo is ignored — so a deposit→withdraw cycle can't
  duplicate items.
- ✅ **Puppets and holograms — visuals the server drives, cross-edition.** A **puppet** is a mob / NPC the
  server puppeteers and never simulates: spawn it, move it, turn it to face a player (`lookAt`), give it a
  floating **name tag**, set it alight / invisible / crouching, make it swing or flinch — and hitting one
  fires an interaction callback. A **hologram** is the same idea with the body removed: floating lines of
  text, authored once in the shared markup, rendered on Java as an invisible marker armor stand and on
  Bedrock as an item entity with no item (neither legacy Bedrock era has an armor stand). Both work on all
  four editions. `/puppet` and `/hologram` place them by hand; their real life is the scripting API below,
  which drives the same primitive as **programmable entities** and as **props**.

- ✅ **Props and scenes — decoration a real block can't do.** Three ways to put a block or item exactly
  where no block can go, all without a resource pack (vanilla entity types doing their normal jobs, so
  they render on unmodified clients across all four protocols): `entities.spawnItem(...)` for a small
  floating model, `entities.spawnBlock(...)` for a **full-size** block (JE Spawn Object 70 / PE
  `FallingSand`), and `entity.setArmor('helmet', ...)` on an invisible body — a block worn at any height
  with nothing holding it up. Props sit at fractional positions, hang unsupported and overlap freely.
  `entities.spawnText(...)` gives them **labels** (a hologram line, but an ordinary entity), and
  `entities.group()` makes a **scene**: add props, then `move` / `rotate(degrees)` / `remove` the whole
  arrangement at once, with `circle` / `line` / `grid` helpers to place a callback's output along a
  shape. Entities can also hold and wear things for their own sake: a script guard with a sword and
  armour. Try `/decor`, then `/decor spin`.

- ✅ **Programmable entities.** Puppets are the scripting API's mob primitive: `entities.spawn('zombie',
  x, y, z)` hands a script a body it can move (`moveToward(target, speed)`), aim (`lookAt`), dress
  (`setNameTag`, `setFlag`), animate (`swing`, `hurt`) and query (`nearestPlayer(12)`, `distanceTo`) —
  plus a state bag and, the point of it all, **`onTick(fn)`: the mob's brain, written in JavaScript**.
  The server simulates nothing; a fifteen-line handler is a guard that watches, follows and returns to
  its post. Entities belong to the plugin that spawned them and despawn on hot-reload, and a whole
  plugin's ticking costs one scheduled task. Try `/guard`.

- ✅ **Script plugins (JavaScript, hot-reloadable).** Custom gameplay lives in `plugins/*.js` on a Rhino
  backend, not the compiled core: a script gets ten globals — `server` / `events` / `scheduler` /
  `commands` / `packets` / `world` / `entities` / `menus` / `storage` / `console` — and wires behaviour with `events.on('PlayerJoin', e => …)`,
  the handler receiving the real event to read and cancel. Every one of the events above is scriptable by
  name; scripts can also `events.emit` their own custom events, register real `/slash` commands, schedule
  work (`setTimeout` / `runTimer`), and tap raw packets on every protocol. Permission state is reachable too
  — `player.isOp()`, `player.hasPermission('node')`, `player.getPrefix()`. A saved edit reloads within a
  second. Rhino (~1.5 MB, pure Java, zero transitive deps) was chosen over GraalJS for weight; it lives only
  in `core`. See `plugins/example.js`.

- ✅ **Persistent script storage — the one thing that outlives the process.** A ninth global, `storage`,
  gives every plugin a private key/value store that survives a restart *and* a hot-reload: `get(key[,
  fallback])` / `set` / `has` / `remove` / `keys` / `size` / `clear`, plus `forPlayer(player)` for the
  shape most script state actually has (keyed by uuid, so it follows a rename). Strings, numbers and
  booleans are stored as themselves; a JS object or array goes through the script's own `JSON.stringify`
  and comes back through `JSON.parse`, so a saved arrangement returns as a real value rather than text
  that looks like one — and a function is refused loudly instead of persisted as nonsense. Data is
  bucketed per plugin name, so two scripts can both keep a `count` without meeting, and editing a script
  never costs it its memory. Written like the world is: a compact DEFLATE file (`plugin-storage.jdb`),
  atomic temp-and-move, a dirty flag that skips rewriting an untouched store, flushed by the same autosave
  and once more at shutdown. Try `/seen` and `/forget` in `plugins/example.js`. It also closed a real
  scripting-layer bug on the way: a `String` returned *from Java* used to reach scripts wrapped, and a
  wrapper is never `===` a JS literal, so `player.getName() === 'Alice'` was silently false. Script scopes
  now hand Java strings, numbers and booleans over as JS primitives — which the command-args path had
  already done by hand for exactly this reason.

- ✅ **World-interaction API.** The shared world is editable from code exactly like a player edits it:
  `CoreWorld` publishes every committed block write to a change listener the server wires to all online
  connections, so a `setBlockId` from a script or command renders live on every client, cross-edition, and
  persists through autosave. Scripts get the `world` global — `getBlock` / `getMeta` /
  `setBlock(x, y, z, id[, meta])` / `fill(corner, corner, id[, meta])` (skips unchanged cells) /
  `getHighestY` / `getBiome` / `getSpawn` / `setSpawn` / `isInside` — and the Java API gained
  `Server.getDefaultWorld()`, `World.setBlock` / `fill` / `isInsideBounds`. Writes outside the finite bounds
  (or the 0–255 Y range) are dropped at the storage boundary, so no API path can grow the world past its
  edge. Try `/deck` and `/pillar` in `plugins/example.js`.

- ✅ **Held items and armor, cross-edition.** The block or weapon in your hotbar is drawn in your
  avatar's hand on every other client — JE Entity Equipment, both PE eras' MobEquipment — updating when
  you switch slots *and* when the stack itself changes (mine, place, a script `setItem`), plus once when
  an avatar spawns so nobody appears empty-handed. **Worn armor** renders the same way
  (`player.setArmor(ArmorSlot.HELMET, …)`, or just drag it into your armor slots in creative): JE numbers
  its equipment slots differently before and after 1.9, while both PE eras dress the avatar with a single
  `MobArmorEquipment`. Visual only — no protection is simulated. Try `/armor`.

- ✅ **Weather, cross-edition.** `/weather clear|rain|thunder` (or `world.setWeather('rain')` from a
  script) changes the sky for every player — JE via Change Game State (+ the darkness fade for
  thunder), both PE eras via the LevelEvent 3001-series. Pure scenery: no timer, no simulation; a late
  joiner walks into the current sky, and cold biomes render the same rain as snow because the client
  decides — the illusion doing its job.

- ✅ **Sounds and particles, cross-edition.** Canonical `Sound` (12) and `Particle` (20) enums render
  natively on every protocol: JE uses Named Sound Effect with each era's own sound names and the shared
  World Particles id table; PE 1.1.5 uses LevelEvent 1000-series ids (plus LevelSoundEvent for explode /
  level-up / note); PE 0.14 uses its big-endian LevelEvent with its own particle table — sounds it
  predates fall back to the closest available id, so a call always makes *a* sound. The API is
  `world.playSound(...)` / `world.spawnParticle(...)` (broadcast) and `player.playSound(...)` (a private
  ding); scripts pass names: `world.spawnParticle('heart', x, y, z, 12, 0.8)`. Try `/fx boom`.

Not yet: a real chest <em>window</em> on Bedrock 1.1.5 (click-transfer is the interim), cross-edition skin
fidelity (a signed-texture limit, see above), knockback (deliberately — the server simulates no physics).
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
5. **Scriptable API.** Custom logic lives in hot-reloadable JavaScript plugins (`plugins/*.js`, a Rhino
   backend) rather than the compiled core — subscribe to events, read and cancel them, no restart to iterate.

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
└── jedrock-core         # The server: JedrockServer, PlayerRegistry, CoreWorld/BlockStorage
    ├── plugin/          #   the Rhino script host (the only non-api dep besides network) + its globals
    ├── entity/          #   CorePuppet / PuppetRegistry: the entity behind mobs, holograms and props
    ├── command/         #   CommandManager + the built-ins, on one CommandSender surface
    ├── permission/      #   OpList + PermissionManager (groups, wildcards, prefixes)
    └── world/           #   the bake (terrain, biomes, decoration), storage and level persistence
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
| `EventBus` / `EventPriority` | api | Cancellable, priority-ordered events the core routes decisions through; reflection-free, with a `hasListeners` hot-path gate |
| `PluginManager` / `ScriptPlugin` | core/plugin | The Rhino host: loads `plugins/*.js`, injects the globals, and owns each script's listeners, tasks, commands, taps and entities so a hot-reload tears them all down |
| `PuppetEntity` / `CorePuppet` | api / core | The server-driven entity: a mob, an NPC, a hologram line or a decoration prop — moved and dressed, never simulated |
| `ScriptEntity` / `ScriptEntities` | core/plugin | That primitive as scripts see it: movement, state, spatial queries and an `onTick` brain, owned per plugin |
| `EntityTypeIds` / `EntityFlagIds` | network | The entity counterpart of the block palette: canonical type / flag → each edition's wire ids |
| `CommandManager` / `CommandSender` | core | One command surface for players and the console, gated by `PermissionManager` (groups, wildcards, deny-wins) |
| `PacketTapRegistry` | core/net | Raw in/out packet taps on all four protocols, with cancel and inject |
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
| `say <message>` | broadcast a server message to every online player |
| `kick <player> [reason]` | disconnect a player by name |
| `kill <player>` / `heal <player>` | kill or fully heal a survival player |
| `plugins [reload]` | list loaded script plugins; `reload` hot-reloads changed files now |
| `debug [all\|off\|<tags>]` | toggle extended debug logging; scope by logger-name tags, e.g. `debug pe,chunk` |
| `gc` | request a GC, then print status |
| `stop` | graceful shutdown |

Anything the console doesn't recognise is run as an **operator** through the in-game command registry, so
every `/`-command works from stdin too — e.g. `op alice`, `gamemode creative bob`, `perm user alice add mod`
(a player-only command like `spawn` is refused with a clear message).

Extended debug is **off by default** — the `LOGGER.debug(...)` calls never invoke their message
supplier, so they cost nothing. Turn it on at startup with `-Djedrock.debug=all` (or scoped, e.g.
`-Djedrock.debug=pe,chunk`), or at runtime with the `debug` command. A periodic status line can be
logged with `-Djedrock.status.seconds=30`.

> **Testing a Bedrock client locally (Windows 10 Edition):** UWP apps cannot reach `localhost` by
> default. Add a loopback exemption once:
> `CheckNetIsolation LoopbackExempt -a -n=Microsoft.MinecraftUWP_yourid`

Tests are plain JUnit 5 (`mvn test`) — ~340 of them, no client required. Beyond the block matrix,
player registry, chunk encoding and MCPE compression, they pin the things that are expensive to get
wrong: the **byte layout** of packets that were ground-truthed against PocketMine or minecraft-data
(titles, sounds, particles, equipment, inventories, props), the per-edition **id tables**, the
scripting layer end-to-end (a real script loads, cancels events, registers commands, hot-reloads and
tears down), and world persistence round-trips.

---

## Roadmap

Jedrock isn't chasing a faithful simulator — it's a cross-edition **illusionist** and a **platform to
script illusions**. So the roadmap grows three things: the *content* the server can show cheaply, the
*tools* to author it, and the *scripting layer* that drives it. Anything that smells like world
simulation stays out (see non-goals).

- **Finite "bake once" world — landed.** A bounded (48×48-chunk) world generated once on first run then
  frozen (all generation disabled, served as static decoration): persistence, the terrain bake, biomes,
  tree/lake/cave decoration and the edge wall are all in, and the block matrix is palette-compressed
  (per-section palette + bit-packed indices) so the whole world stays cheap in RAM (~13 MB for 48×48).
- **The platform API — the centrepiece (in progress).** Turn `api` from a thin contract into a real
  extension surface. **The event engine is in:** a cancellable, priority-ordered **event model** the core
  actually routes its decisions through — cancel `BlockBreakEvent` and the block stays; cancel
  `PlayerChatEvent` and the line never sends; cancel `PlayerMoveEvent` and the player is snapped back. The
  set spans the player's whole arc — login gate / join / quit / chat / command / move / teleport / block
  break / place / right-click / interact-entity / item-pickup / damage / death / respawn / sneak / sprint /
  use-item / game-mode / armor-change / held-item-change — plus server lifecycle (start / stop / per-tick
  heartbeat), world save and weather change, each
  honoured by the core (cancel a `PlayerLoginEvent` to reject a connection, a `PlayerDamageEvent` for
  invulnerability, redirect a `GameModeChangeEvent` or `PlayerRespawnEvent`, suppress a `PlayerDeathEvent`).
  `EventBus` gained priorities (LOWEST…MONITOR), `ignoreCancelled` listeners, precise removal handles, and a
  `hasListeners` fast-path so the hottest paths (movement) allocate nothing when unlistened — reflection-free
  and dependency-free by design. **The script loader landed too:** custom gameplay now lives in
  hot-reloadable **JavaScript** plugins (`plugins/*.js`) on a **Rhino** backend, not the compiled core.
  Rhino (`rhino-runtime`, ~1.5 MB, pure Java, zero transitive deps) was chosen over GraalJS (tens of MB
  incl. ICU4J) to keep the tree lean; it lives only in `core`, so the `api` stays runtime-neutral. That
  gate is what the rest of the roadmap waited on, and the surface behind it has kept growing — nine
  globals now (`server` / `events` / `scheduler` / `commands` / `packets` / `world` / `entities` /
  `storage` / `console`), covering custom events, `/slash` commands, scheduling, raw packet taps, block editing,
  weather, sounds and particles, and **programmable entities** (see [What works today](#what-works-today)).
  The event model has since caught up with the newer features: **weather** (`WeatherChange`, cancellable and
  redirectable, posted by the world itself so `/weather`, a script and the api all pass through it) and
  **equipment** (`PlayerArmorChange` and `PlayerHeldItemChange`, whatever the piece came from — a creative
  drag, a survival window click, or `setArmor` from code). And **script state now survives a restart** —
  the `storage` global, the last thing this section was waiting on (see [What works today](#what-works-today)).
  **Typed command arguments and tab-completion** landed too — a command declares its arguments and the core
  parses them and completes them (Java clients), scripts included. And the **illusion toolkit** grew a
  **sidebar scoreboard** (Java), a **boss bar** (Java 1.8 + 1.12.2 and Bedrock 1.1.5), and **virtual
  chests** for scripts (the `menus` global — a window on Java and 0.14, a `/pick` list on 1.1.5). What it
  still wants: the scoreboard on Bedrock, and a real-client pass on the unverified PE wire — **forms** stay
  out, since the legacy PE clients predate them.
- **Puppet entities — landed (mobs, NPCs, holograms).** The illusionist take on mobs: a mob is a
  **server-puppeteered entity**, not a simulated one — the server spawns a visual, moves it and relays it
  cross-edition, and that's all. The primitive is **in**: a canonical `EntityType` + per-edition id registry
  (`EntityTypeIds` — the block palette's counterpart, the two-headed monster's entity tax), `spawnEntity` /
  `moveEntity` / `removeEntity` on all four editions, a `CorePuppet` / `PuppetRegistry` with cross-edition
  spawn/move/despawn relay, and an **interaction hook** (hitting a puppet fires a callback).
  A puppet can now **act**: a **name tag** (floating text in the unified markup), **`lookAt`** — the whole
  "it noticed me" illusion, trigonometry rather than pathfinding — **flags** (`ON_FIRE` / `INVISIBLE` /
  `SNEAKING`, a canonical set holding only what maps to one bit on *every* edition, mapped by `EntityFlagIds`),
  and **swing / hurt** animations. **Holograms** are the purest form of it — a name tag with the body taken
  away: each line is its own invisible entity (Java: a marker armor stand; Bedrock: an item entity with no
  item, PocketMine's own floating-text hack), authored once in the shared markup. `/puppet` and `/hologram`
  place them by hand, but the **API drives them now**: the same primitive is what scripts spawn as
  programmable entities (with a JS `onTick` for a brain) and as decoration props, so a mob *appears* alive
  without the server ever running AI or pathfinding.
- **Decoration — the accidental discovery.** That a puppet can stand anywhere, at any fraction of a block,
  in mid-air and inside walls, turned out to be the feature rather than the limitation: entities are how
  this server does **scenery**. Three ways to pose a block or item where no block can go are in (a small
  item model, a full-size block, or a block worn on an invisible head), on all four protocols and with no
  resource pack, plus **labels** and **groups** so an arrangement is authored and moved as one, and a
  cast of **23 mob types** to pose. What would grow it further: **splitting head yaw from body yaw** (the
  packets already exist), **saving a scene** so it survives a restart without a script rebuilding it, and
  a `/pose` in-game editor that exports one as a committable file. Known limits: no armor stands in
  either PE era, no per-entity scale, no limb posing, and 0.14 renders only the mobs it is old enough to
  know. And the one number to watch as scenes grow — a static prop costs no ticks but one spawn packet
  per joining player, and a 0.14 client will find that ceiling first.
- **Final touch-ups.** Smaller polish, mostly unlocked by the API. Landed since: the **held-item /
  equipment relay** (what a player holds and wears, on avatars and puppets alike), **titles / action
  bars**, **sounds and particles**, **weather**, `getPing` and chat **display names**, and a fuller
  **command framework** (a `CommandSender` abstraction, a unified console, op + group **permissions**).
  Typed command args and tab-completion have since landed, as have a **sidebar scoreboard**, a **boss bar**
  and script-driven **virtual chests** (`menus`) — all Java for now. Still open: those illusions on
  **Bedrock 1.1.5** (against a real client; **forms** stay out — the legacy PE clients predate them), and a
  **sharper judge** (per-axis limits, interaction ray-casts).
- **Non-goals (by design).** No mob AI / pathfinding, no redstone, no crafting / smelting mechanics, no
  runtime world simulation or physics, no 1.13+ flattening. Knockback is deliberately excluded for the
  same reason — the server simulates no physics. Custom logic that wants any of these lives in a script
  as an *illusion*, not in the core.

---

*Jedrock — do as little as possible, as late as possible.*
