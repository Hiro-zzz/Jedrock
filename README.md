# Jedrock

**A lightweight, cross-platform Minecraft server core written from scratch in Java.**

Jedrock speaks **two protocols natively at once** — Java Edition and Bedrock (Pocket) Edition —
and treats them as one server. A player on a PC and a player on a phone join the same world,
share the same chat, the same player list, and the same terrain — and can walk into a nether
together. The core never learns which
protocol a player speaks; that stays behind the network layer.

Target versions:

| Edition | Version | Protocol | Transport |
|---------|---------|----------|-----------|
| Java Edition | **1.12.2** | 340 | Netty TCP |
| Java Edition | **1.8** | 47 | Netty TCP |
| Bedrock / Pocket Edition | **1.1.5** ⚠️ | 113 | RakNet over UDP |
| Bedrock / Pocket Edition | **0.14** | 45 | RakNet over UDP |

> ⚠️ **1.1.5 is experimental / known-buggy.** Join, movement, chat, block edits, the survival inventory,
> named custom items and cross-play all work, but the retail 1.1.5 client (confirmed on **both PC and
> mobile**) double-fires place/break (mitigated server-side, not eliminated) and **will not raise a chest
> window at all** — every route was tried and the client either crashes or shows nothing, so chests and
> storage menus trade through click-transfer and `/pick` lists instead (see [Known limits](#known-limits)).
> **0.14** and **Java** are the clean Bedrock/PC targets. The problems are specific to the protocol-113
> client across platforms — not the input method, and not the core.

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
  0.14 passes one crash gate that turns an unknown id into an empty slot. A *vanilla* item is inert (held /
  stored — no durability, crafting or eating; a door doesn't place); behaviour is what
  [custom items](#what-works-today) add on top of one. The **player-inventory sync**
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
- ✅ **The nether** — the second kind of world, generated the way the overworld is: one enormous cavern
  between a netherrack floor and a netherrack roof, bedrock-capped top and bottom, with a lava sea in its
  low ground. It is two height fields rather than one, not a carve — the floor rolls through the lava
  line, so the same noise that makes hills makes *shores*, and a column whose floor sits under it is a
  lake. Four bake-time passes dress it (a spawn platform, glowstone clusters under the roof, soul sand on
  the shore, quartz and gravel in the floor), all position-hashed like the overworld's and sharing its
  mixer, so a seed always yields the same nether. It is **128 tall**, which is not a style choice: MCPE
  0.14 has no taller world, so 128 is the one shape every target edition renders identically.
- ✅ **Several worlds, and travel between them** — a world is a folder (`<name>/level.jdw`), and since the
  level file records its own dimension the set of worlds is **discovered, not registered**: every folder
  with a level file comes back at boot, so copying one in is all it takes to add a world. New ones are
  created from a **template** — a recipe (kind, size, decoration, optionally a fixed seed), not a saved
  world, so two worlds from one template share its rules and nothing else. Five are built in
  (`overworld`, `nether`, `overworld_small`, `nether_small`, `bare`) and a script can register more.
  Scripts reach the rest of it the way they reach the world they start in: `worlds.get('hell')` hands
  back the same object the `world` global is, `entities.in('hell')` the same object `entities` is, and
  regions grew `createIn` / `atIn` / `allowsIn` beside their existing short forms — so no global had to
  learn a world argument and no script that predates worlds had to change.
  Travel is `/world tp`, `worlds.send(player, 'hell')` or simply teleporting to a `Location` in another
  world; the terrain, the avatars, the props and the world's roster all change together. **Java** gets a
  Respawn packet (with the same-dimension bounce every server has used since 1.8, since the client only
  rebuilds when the dimension changes); **1.1.5** gets ChangeDimension; **0.14** gets a chunk resend and
  keeps the overworld sky, which is as much as that era can be told (see [Known limits](#known-limits)).
  Everything that used to mean "the world" now means "the world this player is in" — blocks, chests, the
  edge wall, the void floor, damage, avatars, props and regions.
- ✅ **You come back where you left** — a player who logs out in the nether rejoins the nether, across a
  restart of the server. The world is recorded (in `player-worlds.txt`, keyed by uuid) the moment someone
  *crosses* between worlds, so a player who never leaves the default one has no entry and a crash can't
  lose what a clean shutdown would have written. It is answered **during the join**, in the same breath as
  the game mode and before a single chunk is serialized, so the client is shown the right terrain from the
  first packet rather than being walked across after arriving — Java's Join Game and 1.1.5's StartGame
  simply name that world's dimension. Only the world is remembered, never a spot in it: they arrive at
  that world's spawn, which is somewhere the server can always put a person. A world that went away while
  they were offline is forgotten and they join the default. `player.remember-world=false` turns the whole
  thing off.
- ✅ **World persistence** — each world (baked terrain + player edits) survives a restart, written to a
  compact Jedrock level file (`<world>/level.jdw` — an uncompressed metadata header plus every allocated
  16³ section in one DEFLATE stream; ~420 KB for a 48×48 world). Loaded before any client can join,
  saved on shutdown, and autosaved every `-Djedrock.world.save-seconds` (default 300, `0` = off) — a
  dirty flag skips rewriting an unchanged world. Saves are atomic (temp + move). The header carries the
  world's seed, extent and dimension, so a world folder is self-describing: it can be moved or recovered
  on its own, and a mismatch is caught instead of quietly serving the wrong terrain.
- ✅ **A jar you can hand to someone** — `java -jar jedrock.jar` in an empty folder is a running server.
  It lays itself out on first boot (`worlds/`, `plugins/`, `logs/`, `data/`, plus both config files),
  migrates an older flat install into that layout without overwriting anything, and writes its console
  output to `logs/latest.log` with the previous runs rotated beside it. One jar runs several servers:
  `java -jar jedrock.jar ./survival` lays out and runs that folder.
- ✅ **Two config files, split by who edits them** — `jedrock.properties` for running a server (folders,
  world, game, plugins, logging, judge, sidebar) and `pipeline.yml` for the wire itself (Netty threads and
  socket options, keep-alive, per-era Bedrock view radius and repaint cadence, the packet guards). Every
  key has a default, any key is overridable with `-Dkey=value`, and a bad value falls back with a warning
  instead of failing to start. See [Configuration](#configuration).
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
  `/tpall`, `/spawn`, `/heal`, `/kill`, `/clear`, `/give`, `/op`, `/deop`, `/perm`, `/region`, `/world`, `/pick`,
  `/puppet`, `/hologram`, `/pose`, `/time`, `/about`, plus the [moderation set](#moderation). A deliberately minimal **survival inventory** (36 slots)
  tracks only what a survival player mines and places: mining a block drops it into the hotbar, placing
  consumes it, and the changed slot is pushed live so the HUD refreshes. On PE 1.1.5 the player window is
  serialized PMMP-exact (45 slots + a 9-entry hotbar-link array), without which mined items filled storage
  but the on-screen hotbar stayed empty.
- ✅ <a id="moderation"></a>**Moderation — bans, ip-bans, mutes and a whitelist.** `/ban`, `/ban-ip`,
  `/kick`, `/mute`, `/pardon` (every kind at once, or one named), `/banlist`, `/whitelist`, `/seen` and
  `/playerinfo`. Almost none of it is new machinery: a ban **is** a cancelled `PlayerLoginEvent`, which is
  what that event was built for, and a mute is a suppressed chat line — so both decisions are made at
  points the core already routes through, and a script can watch or overrule either at a higher priority
  like any other rule here. A punishment carries an **expiry**, so there is no `/tempban`:
  `/ban alice 2d spam` is the same command, and a duration needs a unit precisely so
  `/ban alice 30 spam` cannot silently mean thirty of something. Expiry is lazy — a lapsed entry reads as
  absent and is dropped on the next write, with nothing ticking.
  **Targets are names**, like `ops.txt`: there is no Mojang authentication here (a 0.14 client picks its
  own name), and a ban has to work on somebody who has never connected, so a uuid would be the weaker
  identity. The cost is that a rename walks around one — which is what `/ban-ip` is for, and why it exists
  despite catching whole households. State goes through the [storage layer](#storage) rather than into its
  own text file, so a network of servers can share one ban list; `ops.txt` and `permissions.txt` stay text
  because those two are the ones edited by hand. A mute covers `/me`, `/msg` and `/say` as well as chat,
  and the whitelist waives itself for operators (a ban does not).
- ✅ **One command surface, unified console and permissions.** A command is written against a
  `CommandSender` — a player *or* the server console — so the same command runs from chat and from stdin:
  type `op alice` or `gamemode creative bob` straight into the console (it acts as an operator), and a
  player-only command like `/spawn` is refused there with a clear message. **Operators** persist to
  `ops.txt` (an op holds every permission; the console is always an op, so the first `/op` is granted from
  the console). On top sits a **native group permission system** (`permissions.txt`): named groups with
  inheritance, a default group new players fall into, and permission nodes supporting `*` / `a.b.*`
  wildcards and `-node` explicit deny (deny wins). A player can also carry **nodes of their own**, on top of
  whatever their groups give them — because a group answers "what may this *kind* of player do" and some
  exceptions are genuinely about one person (the owner of one plot), which shouldn't need a throwaway group
  each. Deny wins between the two either way round. Each group can carry a chat **prefix** (`{red}[Admin] `)
  shown via the `%prefix%` slot in the chat format. Manage it live with `/perm` (create/delete groups, grant
  or deny nodes, set inheritance, prefix and the default group, assign players, `user <name> addnode`) —
  every change persists. Scripts get the same surface through the `permissions` global.
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
- ✅ **Sidebar, boss bar and virtual chests for scripts — cross-edition.** Three illusion-toolkit
  additions. **`player.setSidebar(title, [lines])`** shows a titled panel of text, up to 16 lines, on
  **every edition** — but by two different illusions. On **Java** (1.8 + 1.12.2) it's a real sidebar
  scoreboard down the right of the screen, updated by diffing so a timer refresh never flickers (the
  pre-1.13 client draws the vanilla red score number beside each line). Neither legacy **Bedrock** era has
  a scoreboard at all, so there the same text goes to the one persistent field those clients do have — the
  **popup**, drawn in the HUD slot where a held item's name appears, displaced upward (`TextPacket`
  `TYPE_POPUP`, byte-checked against PocketMine at both protocol 113 and protocol 45, and confirmed on a
  real client). That line fades on its own, so the connection declares how often it needs repainting and
  the loop obliges; a script sets the sidebar once and it stays up. The client decides *where* it draws —
  centred, on top of the hotbar — so placement is padding, tuned with `pe.sidebar.raise` /
  `pe.sidebar.shift` in the config (`0`/`0` = the raw centred position). **`player.setBossBar(title, progress[, color])`** shows the
  bar across the top — cross-edition where a client can draw one: Java 1.12.2 (dedicated packet), Java 1.8
  (an invisible wither ridden by the player, the classic illusion) and Bedrock 1.1.5 (native BossEvent).
  0.14 predates boss bars entirely and ignores that one. And
  **`menus`** gives scripts a **virtual chest**: `menus.create(title, rows)`, laid out with `setItem`,
  opened with `open(player)` — with an `onClick` it's a read-only **button menu** (a class picker, a shop),
  without one a transient **storage chest**. **Java** opens a real chest window; **neither Bedrock era does**
  (1.1.5 crashes on one, 0.14 doesn't bring it up), so there a button menu degrades to a text **list** —
  labelled buttons (`menu.button(slot, item, label)`) become options the player chooses with a built-in
  **`/pick <label>`**, which fires the same handler. A **storage** menu takes the same route with its
  *contents* as the options: `/pick <n>` takes the stack in slot n, `/pick put` puts the held one in,
  `/pick close` is done, and the list redraws after every transfer so it stays up the way a window would.
  So **both menu shapes now work on all four editions** — the transfer moved into the list rather than
  waiting for a window these clients won't raise, the same trade world chests already make on 1.1.5. The
  one case still refused is a button menu whose buttons carry no labels: a list has nothing to offer and,
  unlike storage, there's no content to fall back on. Try `/sb on`, `/boss 50 red` and `/menu` in
  `plugins/example.js`.
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
  **persist** in the level file (format v6, which loads a v2–v5 world in place and rewrites it on the
  next save; v3 added chests, v4 the custom-item key each stack carries, v6 that stack's own data).
  A custom item keeps its name, its lore and its own state through every one of those moves, and the
  window draws the name. Wired for JE 1.12.2 and 1.8.
  True to the model, the server only <em>stores and moves</em> items — no crafting or smelting simulation,
  no item entities (a dropped / overflow item simply vanishes).
- ✅ **Chests on Bedrock 1.1.5 — click-transfer.** The retail 1.1.5 client crashes on a real chest window,
  so chests there use a **click-transfer** instead: a right-click withdraws the first stack, a sneaking
  right-click deposits the held hotbar slot. Works in survival and creative (creative deposits its held
  item without consuming and never mints items on withdrawal). Bedrock owns its own inventory window, so a
  survival player's moves there are applied as the client reports them — except for the **echo** that
  client sends of a slot the *server* just changed, which is what would duplicate a deposit. The two are
  told apart by timing: a freshly pushed slot is guarded for a moment and the server's value re-asserted
  (`SlotEchoGuard`), so a deposit→withdraw cycle can't duplicate items and a rearranged inventory still
  sticks.
- ✅ **Puppets and holograms — visuals the server drives, cross-edition.** A **puppet** is a mob / NPC the
  server puppeteers and never simulates: spawn it, move it, turn it to face a player (`lookAt`) or just
  **glance** at one with its head while its body stays put (`glanceAt` / `setHeadYaw` — every edition has
  carried the two angles separately all along), give it a
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

- ✅ **Regions — named boxes with rules.** The primitive every game mode needs: a lobby, an arena, a shop
  floor, a spawn nobody can dig up. A region is six numbers and a set of allowances — `build`, `interact`,
  `pvp`, `damage`, `entry` — every one **on** until it's denied, so a new region changes nothing until you
  say so. Where regions overlap, **deny wins** (the rule permissions already use), which means dropping a
  small no-build box inside a big free-build one does what it looks like it does. Nothing is simulated and
  nothing ticks: each flag is enforced by **cancelling the event the core already routes that decision
  through**, so there is no second rulebook and a script can overrule one by listening at a higher
  priority. Crossing a border fires **`PlayerRegionEnter` / `PlayerRegionLeave`** — once per crossing, not
  per movement packet — and cancelling those refuses the step, which is how an arena holds someone in
  until a round ends. **Exceptions are per player and per group**, and they're permissions rather than a
  roster on the region — "may *this* player do *this*" already has a whole subsystem here. A denial is
  waived for anyone holding **`jedrock.region.<name>.<flag>`**: one player, or a group and everyone in it;
  `jedrock.region.plot7.*` covers a whole region, `-jedrock.region.plot7.build` takes it back, and an op is
  exempt everywhere. (The permission system gained **per-player nodes** for this — `/perm user <name>
  addnode <node>` — so "let this one player build in their own plot" doesn't need a throwaway group.)
  Regions are **server-owned** like saved scenes: created by `/region` or a script,
  persisted to `world/regions.jdb`, and **in force from boot before the first login**. A server with no
  regions pays one array-length read per movement packet and nothing else — the enforcement listeners
  aren't even registered until the first region exists. `/region pos1` / `pos2` / `create <name>`, or
  `/region here <name> <radius>`; then `list`, `info`, `flag <name> <flag> allow|deny`, `remove`. Scripts
  get the `regions` global. Try `/zone` in `plugins/example.js`.

- ✅ **Custom items — a name, lore and programmable behaviour on a vanilla item.** There is **no resource
  pack** by design (it would break the promise that any unmodified client can join, and 0.14 barely
  supports one), so a custom item is *drawn* as whatever vanilla item it is built on — a diamond sword
  still looks like a diamond sword. What is custom is everything else: `items.define('frostblade',
  Blocks.state(276, 0)).setName('{aqua}Frostblade').setLore([…])` plus behaviours — **`onUse`**,
  **`onHit`**, **`onBreak`**, **`onHold`**, each returning `true` to *consume* the action, which it does by
  cancelling the event the core already routes that decision through.
  Identity is the **key**, and a stack carries the key rather than a copy of the definition. That is what
  makes a custom item survive what a reference could not: a hot reload re-points every existing stack at
  the new definition, the level file (v6) restores a chest full of them long before any plugin exists, and
  an item whose plugin was removed simply behaves as the vanilla one it is drawn as until its script comes
  back. A custom stack never merges with an ordinary one of the same state. Dispatch listeners are
  registered only while some item actually has a behaviour, so a purely cosmetic item — or none at all —
  costs nothing. Try `/forge` in `plugins/example.js`.
  **A stack also carries its own state** — `items.heldData(player)` / `setHeldData` — because a definition
  is shared by every stack that names it and has nowhere to put "this particular wand has one charge
  left". Strings and numbers go in as themselves; an object goes through the script's own `JSON` and comes
  back as a value rather than as text that looks like one. Two stacks whose data differs do not merge, so
  a spent wand never dissolves into a full one, and it persists in the level file beside the key. And the
  item itself can carry a **cooldown**: `setCooldown(ms)` and it stops answering that player until it
  elapses, with an optional `onCooldown` hook fired in the behaviour's place (`ctx.getRemaining()`) whose
  `true` swallows the action and whose absence lets it fall through as the vanilla item. The wait belongs
  to the item; *when a given player last used one* belongs to the server, so editing a plugin no longer
  hands everyone a fresh wand.
  **The name and lore reach the client on all four protocols**, as item NBT in the Slot's own NBT field —
  two dialects: **Java** is big-endian named NBT written inline (plain §-coded strings, since text
  components in `Name` arrived in 1.13), and **both Bedrock eras** are length-prefixed *little-endian* NBT.
  Note that protocol 113 speaks two dialects and the choice is per *call site* — a chunk's block-entity tail
  is *network* NBT (varint lengths, zigzag ints), an item's is not. Getting that backwards cost one client
  test: the 1.1.5 client neither crashed nor complained, it just kept showing the vanilla name. An ordinary
  item still writes the exact bytes it always did, so nothing changed for a server that defines no items.
  **Confirmed on a real client on every one of the four**, which is what it took to trust either dialect.

- ✅ **Items have names you can type — and `/give`.** A block is an id and always will be, but nobody
  should have to *say* 574 at a chat prompt. `ItemNames` names the canonical states — one table over
  blocks and items alike, since there is one model for both — so `/give <player> <item> [count]` takes
  `red_wool`, `wool:14`, `35:14` or `276`, tab-completes the lot, and hands over a **custom item** by its
  key ahead of any of them. The table is written against the two creative palettes and a test in the
  network module (the only place both are visible) fails if a palette gains a state nobody named.
  It is deliberately *incomplete*: where the legacy Java and Bedrock numberings disagree about what an id
  means — 158 is a dropper on one and a wooden slab on the other — the state stays unnamed rather than
  carry a name that would be wrong on half the server, and remains reachable as `id:meta`. `/pose` parses
  a prop's block the same way, `/pick` prints names instead of numbers, and a script can resolve either
  direction (`items.state('red_wool')`, `items.nameOf(574)`).

- ✅ **Permissions from a script.** A script could always *read* rights (`player.hasPermission`); now it can
  set them. The **`permissions`** global builds groups (`createGroup(n).inherit('default').add(node)
  .setPrefix('{aqua}[Builder] ')`) and edits one player's rights (`permissions.forPlayer(p).addGroup('builders')`,
  `.add(node)`, `.remove(node)`, `.isOp()` / `.setOp(true)`) — by `Player` **or by name**, so somebody's
  rights can be prepared before they ever log in. Server state, written to `permissions.txt` / `ops.txt`
  immediately and not torn down with the plugin, and `createGroup` returns the group that already exists,
  so a script declaring its roles on every load is idempotent. Before this the only way to *change* a right
  from a script was to build a `/perm …` string and hand it to `dispatchCommand` — which is exactly what
  the region demo had to do until this landed.

- ✅ **Script plugins (JavaScript, hot-reloadable).** Custom gameplay lives in `plugins/*.js` on a Rhino
  backend, not the compiled core — see the **[scripting reference](docs/SCRIPTING.md)**. A script gets
  sixteen globals — `server` / `events` / `scheduler` /
  `commands` / `packets` / `world` / `worlds` / `entities` / `regions` / `items` / `permissions` / `menus` / `punishments` / `http` / `storage` / `console` — and wires behaviour with `events.on('PlayerJoin', e => …)`,
  the handler receiving the real event to read and cancel. Every one of the events above is scriptable by
  name (40 of them, listed by `events.names()`), at a **priority** of its choosing — which is what lets a
  script overrule the core's own rules, since regions and item behaviours are enforced at `HIGH` and a
  listener has to run later than that to have the last word. `on` / `once` hand back a handle, so a
  listener can stop without the plugin reloading. Scripts can also `events.emit` their own custom events
  (priority applies there too), register real `/slash` commands, schedule
  work (`setTimeout` / `runTimer`), and tap raw packets on every protocol. Permission state is reachable too
  — `player.isOp()`, `player.hasPermission('node')`, `player.getPrefix()`. What a script may touch is a
  **written contract**: `player` and `server` arrive as `ScriptPlayer` / `ScriptServer` wherever they cross
  into JavaScript, so the core's internals (a player's connection, the op list, the network server) are not
  reachable from a plugin — Rhino reflects an object's runtime class, so the api interfaces alone could
  never have enforced that. A saved edit reloads within a
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
  and once more at shutdown. Try `/visits` and `/forget` in `plugins/example.js`. It also closed a real
  scripting-layer bug on the way: a `String` returned *from Java* used to reach scripts wrapped, and a
  wrapper is never `===` a JS literal, so `player.getName() === 'Alice'` was silently false. Script scopes
  now hand Java strings, numbers and booleans over as JS primitives — which the command-args path had
  already done by hand for exactly this reason.

- ✅ **World-interaction API.** The shared world is editable from code exactly like a player edits it:
  `CoreWorld` publishes every committed block write to a change listener the server wires to all online
  connections, so a `setBlockId` from a script or command renders live on every client, cross-edition, and
  persists through autosave. Scripts get the `world` global — `getBlock` / `getMeta` /
  `setBlock(x, y, z, id[, meta])` / `fill(corner, corner, id[, meta])` (skips unchanged cells) /
  `getHighestY` / `getBiome` / `getSpawn` / `setSpawn` / `isInside` / **`getChest(x, y, z)`** (a real,
  persisted chest a player placed — read it, fill it, empty it; anyone with it open sees the change) — and the Java API gained
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

- ✅ **Time of day, cross-edition.** `/time set day|noon|night|midnight|<ticks>`, `add`, `query`, and
  `freeze` / `resume` — or `world.setTime(6000)` from a script. Scenery in exactly the sense the weather
  below is: the server holds a number and tells clients what it is, and the **client** animates the sun
  between updates, so a day passes here with nothing on this side ticking to make it. Reading the time
  answers what the clients are showing rather than what was last sent. Each world keeps its own hour, and
  arriving in one means arriving at its hour. Freezing is the client's own mechanism where it has one —
  Java reads a negative time as "stop counting", 0.14 has a flag — and 1.1.5, which has neither, is simply
  told again. Not persisted, like the weather: a restart starts the morning over.
- ✅ **Build a scene where you can see it — `/pose`.** Props go where a real block cannot: fractional
  positions, unsupported, overlapping. Authoring that in a script means typing three coordinates, saving,
  watching the reload and finding the lantern half inside the wall. `/pose new <name>`, then `block`,
  `item`, `text` and `mob` drop props where you are standing, `nudge` and `rotate` adjust them, `undo`
  takes one back, and `save` hands the arrangement to the same scene store a script's `group.save(name)`
  writes — so the server stands it back up at every boot with no plugin involved. A scene authored by hand
  and one authored in code are the same object.
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
See [Known limits](#known-limits).

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
├── jedrock-api          # Pure contracts: Server, Player, World, events, ServerProperties +
│                        #   PipelineSettings. No implementation deps and no file IO.
├── jedrock-utils        # Lazy<T>, LazyPacket, ByteBufUtils (VarInt/VarLong/zigzag), ticks,
│                        #   logging (JLogger + FileLog) and a small YAML reader (yaml/)
├── jedrock-network      # Transport + protocol handling for both editions
│   ├── handler/je/      # JavaProtocol per JE version; JavaHandshakeHandler picks 1.8 / 1.12.2
│   ├── je/packet/       # Java Edition packets (Serverbound* / Clientbound*)
│   ├── pipeline/        # Netty codecs: VarInt framing, lazy packet decoding
│   └── pe/              # Bedrock 1.1.5: PeRakNetServer (RakNet transport) + PeSession (MCPE game
│                        #   layer) delegating to McpeProtocol, McpeCodec, McpeChunkSerializer,
│                        #   McpeLoginIdentity, McpeSkin, PeBlockEditDecoder, McpeCompression
│       │                #   (McpePackets holds every clientbound body, as Mcpe014Packets does for 0.14;
│       │                #    McpeItemNbt writes item names for BOTH eras — see its note on dialects)
│       └── v014/         # Bedrock 0.14 (protocol 45): Pe014RakNetServer + PeSession014 + the
│                         #   pre-VarInt codec (Mcpe014Codec/Login/Packets/ChunkSerializer/Batch)
├── jedrock-gameloop     # Dedicated 20 TPS drift-correcting loop + Scheduler (Tickable)
└── jedrock-core         # The server: JedrockServer + ConnectionBridge (network → core),
    │                    #   PlayerRegistry, CoreWorld/BlockStorage
    ├── config/          #   ServerLayout (where everything lives) + the two config loaders
    ├── plugin/          #   the Rhino script host (the only non-api dep besides network) + its globals
    ├── entity/          #   CorePuppet / PuppetRegistry: the entity behind mobs, holograms and props
    ├── command/         #   CommandManager + the built-ins, on one CommandSender surface
    ├── permission/      #   OpList + PermissionManager (groups, per-player nodes, wildcards, prefixes)
    ├── region/          #   RegionManager: named boxes with rules, enforced through the event bus
    ├── item/            #   ItemRegistry: custom items — a name, lore and behaviour on a vanilla state
    ├── inventory/       #   Container + ContainerService: everything that moves an item between slots
    └── world/           #   WorldManager (every world, made from templates) + the bake per dimension
                         #     (OverworldGenerator / NetherGenerator), storage and level persistence
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

What a cell *contains* is the one thing an overworld and a nether disagree about, and it is the only
thing a `WorldGenerator` decides — storage, bounds, edits, chests, weather and persistence are identical
for both. Its shape is worth a line, because a naive signature would have made the bake unusable: a
column is evaluated **once** (`column(x, z)`) and packed into a `long` the bake carries down the y-axis,
so an overworld packs its surface height and the nether packs a floor and a ceiling, and the per-cell
`blockAt` is then pure arithmetic. Two height fields per *block* instead of per *column* would have
turned a few seconds of bake into minutes.

Worlds are plural. `WorldManager` owns them, makes them from a `WorldTemplate`, and finds the ones
already on disk by scanning for folders with a level file — the file records its own dimension, so
nothing has to be listed anywhere. A player stands in exactly one world; their connection streams chunks
from that one, and `WorldTravel` is what moves them (and their avatar, and the props they can see) to
another.

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
| `WorldGenerator` | core | What an overworld and a nether disagree about, and nothing else; a column is evaluated once and packed into a `long` the bake carries down the y-axis |
| `TerrainGenerator` / `BiomeGenerator` / `WorldDecorator` | core | The overworld's one-time bake: heightmap, biomes, trees / lakes / caves — then frozen |
| `NetherGenerator` / `NetherDecorator` | core | The nether's: two height fields with a lava sea between them, glowstone / soul sand / ore — 128 tall |
| `WorldManager` / `WorldTemplate` | core / api | Every world the server has, and the recipe one is made from; worlds are discovered from their folders, not from a list |
| `WorldTravel` | core | The four things a world switch must do together — membership, avatars, props, terrain |
| `LevelIO` / `BiomeStorage` | core | Persist the baked world + biome map to a compact `world/level.jdw` |
| `PlayerRegistry` | core | Thread-safe roster indexed by uuid / name / connection |
| `EventBus` / `EventPriority` | api | Cancellable, priority-ordered events the core routes decisions through; reflection-free, with a `hasListeners` hot-path gate |
| `PluginManager` / `ScriptPlugin` | core/plugin | The Rhino host: loads `plugins/*.js`, injects the globals, and owns each script's listeners, tasks, commands, taps and entities so a hot-reload tears them all down |
| `PuppetEntity` / `CorePuppet` | api / core | The server-driven entity: a mob, an NPC, a hologram line or a decoration prop — moved and dressed, never simulated |
| `Region` / `RegionManager` | api / core | Named boxes with rules **in one world**; flags enforced by cancelling the events the core already routes decisions through, and registered only while a region exists |
| `CustomItem` / `ItemRegistry` | api / core | A name, lore and behaviour on a vanilla item state; a stack carries the **key**, the registry gives that key meaning |
| `ItemDisplay` | api | The only part of a custom item the client learns — name + lore, legacy-rendered, `null` for an ordinary stack |
| `ItemCooldowns` | core/item | The half of a cooldown that isn't the item's: when each player last used each key. On the registry, so a hot reload doesn't reset it |
| `SlotEchoGuard` | core/inventory | Tells a Bedrock client's own inventory move from its echo of one the server made — by timing, since the two are identical in content |
| `CustomStackTrail` | core/inventory | Carries a stack's identity across a drag that client made and only reported afterwards — the same wire, the same reason, one stack deep |
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

Requires **JDK 21** to build; **JDK 21** to run.

```bash
mvn clean install      # build + run tests
mvn -o clean package   # offline (deps are cached after the first resolve)
```

That produces **`jedrock.jar`** in the project root — one file with every module and dependency in it.
(The root, not `target/`: that folder is excluded in the IDE, and a build output you can't see is a build
output you assume didn't happen.) Put it in an empty folder and start it:

```bash
java -jar jedrock.jar
```

The first run lays the folder out and tells you it did:

```
jedrock.jar
jedrock.properties   the settings
pipeline.yml         the network's own settings
worlds/              one folder per world, each with its level.jdw
plugins/             *.js, hot-reloaded
logs/                latest.log, and the runs before it
data/                ops.txt, permissions.txt, player-worlds.txt, plugin-storage.jdb
```

The four folder names are configurable (`paths.*`); the two config files are not, since they are how you
say where everything else is. A **flat install from an older build** — worlds and `ops.txt` sitting beside
the jar — is migrated into the layout on the next boot, moving nothing it would have to overwrite. One jar
can run several servers: `java -jar jedrock.jar ./survival` lays out and runs that folder instead.

It binds (defaults, configurable):

- **Java Edition** on TCP `0.0.0.0:25565` — 1.8 and 1.12.2 share it
- **Bedrock 1.1.5** on UDP `0.0.0.0:19132`
- **Bedrock 0.14** on UDP `0.0.0.0:19133` (`server.port.pe014`; disable with `pe014.enabled=false`)

The Bedrock listeners bind best-effort — a busy UDP port (the Minecraft Bedrock client itself holds 19132
for LAN discovery) disables just that edition, never the whole server.

### Releases and packages

Publishing a release runs [one workflow](.github/workflows/publish.yml) that builds, tests and then ships
two things from that same build: **`jedrock.jar` attached to the release**, for anyone who just wants to
run a server, and **the modules published to GitHub Packages**, for anything that wants to build against
`jedrock-api`. One build, so the two can never disagree about what they are.

> **Run the latest release, not an older one and not `main`.** This is a pre-1.0 project and the honest
> reason is not that new versions have more features: it is that they have fewer *defects*. What gets
> fixed between releases is disproportionately the invisible kind — a race that only shows up under a hot
> reload, a section of world that could be read as air, a leak that costs you memory for every player who
> has ever logged in, a duplication bug in an inventory. Those cost you nothing to pick up and are
> genuinely awkward to live with, so an old release is not a "stable" one here; it is one with more of
> them still in it. Read the [changelog](CHANGELOG.md) if you want to know which.
>
> A branch build is the other end of that. `test` is where things are tried and may be broken on purpose;
> `main` is clean but is whatever landed most recently, without the release's build and test run behind it.

To depend on the api, add the repository and the module (GitHub Packages requires authentication even to
read a public package — that is their policy, not this project's, so you will need a token with
`read:packages` in your `~/.m2/settings.xml`):

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Hiro-zzz/Jedrock</url>
</repository>

<dependency>
    <groupId>com.jedrock</groupId>
    <artifactId>jedrock-api</artifactId>
    <version>0.2.1</version>
</dependency>
```

Note that a **script plugin needs none of this** — `plugins/*.js` is the extension surface, and it needs
no build at all. The published modules are for someone writing Java against the api.

### Configuration

Two files, split by who edits them and what it costs to get one wrong.

**`jedrock.properties`** is the file you edit to run a server: name and MOTD, bind host and ports, the four
folder names, the world (`world.default-name`, `world.default-template`, `world.seed`, `world.load-all`,
`world.autosave-seconds`), the game (`game.tick-rate`, `game.view-distance`, `game.default-gamemode`),
whether a player rejoins the world they left (`player.remember-world`), the remote console (`rcon.*`,
see [Console & diagnostics](#console--diagnostics)), the script layer
(`plugins.enabled`, `plugins.hot-reload`, `plugins.reload-millis`), logging (`logging.to-file`,
`logging.keep-files`, `logging.debug`, `logging.status-seconds`), the blind-judge limits (`judge.*`) and the
Bedrock sidebar placement (`pe.sidebar.*`). Every key has a default, so a key you delete keeps its built-in
value and a bad value falls back with a warning instead of failing to start. Any key can be overridden at
launch with `-Dkey=value`.

**`pipeline.yml`** is the file you edit when you already know why: the transport's own numbers, each of
which used to be a constant compiled into the network module. Netty threads and socket options
(`netty.boss-threads`, `worker-threads`, `tcp-nodelay`, `so-keepalive`, `reuse-address`, `backlog`), the
Java keep-alive interval, per-era Bedrock knobs (`bedrock.v1_1_5` / `bedrock.v0_14`: `max-view-radius`,
`sidebar-repaint-ticks`, `max-particle-burst`, plus 1.1.5's `resync-delay-millis` and
`announce-dimension`), and the wire-level guards (`guard.max-inflated-batch-bytes`,
`max-packets-per-batch`, `max-list-entries`) that stop a hostile client deciding how much memory this
process uses. It is YAML because it is nested — four subsystems, one of them with two eras — and it is read
by a [deliberately small reader](jedrock-utils/src/main/java/com/jedrock/utils/yaml/Yaml.java) rather than
a dependency: mappings, sequences, scalars, comments, and a warning for anything else. A value outside its
sane range is refused and the default used, because an obeyed nonsense value here is a security limit
someone else chose.

### Storage

The server's small persistent facts — which world each player was last in, the ban / ip-ban / mute lists,
the whitelist, and when each player was last seen — go through a
`DataStore` with two backends. **`flatfile`** is the default and writes the same `key=value` files in
`data/` it always did: a few kilobytes, editable in any text editor, nothing to run. **`jdbc`** is there
for whoever wants them in a database instead — a network of servers sharing one account of who is where,
or a host that already has one. Turning it on changes where the rows are, not what they mean.

**No driver is bundled**, which is what keeps this jar 6.9 MB for everyone who doesn't want a database.
Drop the driver jar in `libs/` beside the server, then:

```properties
storage.backend=jdbc
storage.url=jdbc:sqlite:data/jedrock.db
storage.driver=org.sqlite.JDBC
```

(For MySQL: `mysql-connector-j`, `storage.driver=com.mysql.cj.jdbc.Driver`, plus `storage.user` /
`storage.password`.) A backend that can't be opened — driver missing, database down, typo in the url —
logs what to do about it and **falls back to files**; it never stops the server from starting. Note that
`ops.txt` and `permissions.txt` deliberately stay text files: those are the two an administrator edits by
hand, and that is a feature rather than an omission. World terrain has never been in scope here — a baked
level is a 400 KB DEFLATE blob, and a table has nothing to offer it.

A few knobs stay `-D`-only, since they exist to be turned down rather than tuned:
`-Djedrock.pe.raknetProtocolVersion=N` (default `8` = MCPE 1.1.5, for other client builds),
`-Djedrock.pe.slotEchoGuardMs=<ms>` (default `750`, `0` = off — how long after the server pushes an
inventory slot a Bedrock client's report of it is read as a stale echo),
`-Djedrock.pe.stackTrailMs=<ms>` (default `750`, `0` = off — how long a custom stack a Bedrock client
picked up stays claimable by the report that puts it down, which is what carries a named item across a
drag), and the 1.1.5 block-edit debounce
windows (`-Djedrock.pe.placeBurstMs`, `placeSameCellMs`, `breakSameCellMs`).

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

The same surface is available over **RCON** (`rcon.enabled`), the protocol every Minecraft management tool
already speaks — the table above plus every in-game command, run as an operator. It adds no commands of its
own: a remote client goes through the same `execute` the terminal does, so anything added to the console
works there the same day. `stop` replies *before* it shuts down, since a shutdown that ran inline would take
the answer with it.

> ⚠️ **RCON is plaintext.** The password and everything either side says cross the network in the clear.
> It is off by default, binds `127.0.0.1` by default (reach it through an SSH tunnel), and **refuses to
> start with a blank password** no matter what `rcon.enabled` says — an open RCON port is a remote console
> for whoever finds it. A wrong password closes the connection, so guessing costs a reconnect each time.

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

Tests are plain JUnit 5 (`mvn test`) — ~560 of them, no client required. Beyond the block matrix,
player registry, chunk encoding and MCPE compression, they pin the things that are expensive to get
wrong: the **byte layout** of packets that were ground-truthed against PocketMine or minecraft-data
(titles, sounds, particles, equipment, inventories, item NBT, props), the per-edition **id tables**, the
scripting layer end-to-end (a real script loads, cancels events, registers commands, hot-reloads and
tears down), and world persistence round-trips.

A byte-level test is only worth what its assertions are, which the item-NBT work made concrete: the
first encoder passed its own tests and still failed on a real client, because the tests agreed with it
about the wrong NBT dialect. The replacements assert the exact bytes rather than "it round-trips through
my own reader" — the rule these tests try to follow wherever a real client is the only other judge.

---

## Known limits

What [What works today](#what-works-today) doesn't say. Most of these aren't bugs waiting on a fix —
they're a legacy client's answer, a protocol that predates the feature, or a line this project drew on
purpose. Each one shaped a decision above, so they're recorded rather than hidden.

- **Bedrock 1.1.5 will not raise a chest window.** Confirmed on the retail client on **both PC and
  mobile** — every route was tried and the client either crashes or shows nothing. So chests and storage
  menus trade through click-transfer and a `/pick` list instead. The same client also double-fires
  place / break, mitigated server-side by a per-cell debounce but not eliminated. This is specific to
  the protocol-113 client across platforms — not the input method, and not the core.
- **The ghost correction costs a whole chunk column, and cannot be made cheaper.** That client ignores a
  standalone `UpdateBlock` contradicting a cell it edited itself (even with `FLAG_PRIORITY`), so a ghost is
  erased by re-sending the column. The obvious saving — send the targeted `UpdateBlock`s on the same
  *trailing edge* that made the chunk re-send work, rather than immediately as the original test did — was
  built and tried on a real client, and changed nothing. So the claim that client stakes on a cell it edited
  does not expire when the burst does, and the column is not a workaround for bad timing but the only
  correction it honours. Recorded so the idea isn't rebuilt a third time.
- **The `/pick` storage list still addresses stacks by slot number.** The number is the only thing a
  client here can point at, so that part stays; what it prints is now a name (a custom item's own, or the
  canonical one for its state) rather than a raw number.
- **Forms are out on both PE eras** — the legacy clients predate them, so a menu is a window (Java) or a
  list (Bedrock) and nothing richer.
- **Entities can't be posed finely.** No armor stands in either PE era, no per-entity scale and no limb
  posing. Head yaw *is* now separate from body yaw, but how far a neck may turn is the client's opinion
  and none of them tells us where the limit is — ask for 180° behind and you get whatever that client
  thinks a neck does. A `PLAYER` puppet can't glance at all, because it borrows a real player's rendering
  and a real player reports one yaw. On both PE eras a glance is a whole `MoveEntity` (there is no
  head-only packet on that wire), so a puppet that follows somebody every tick costs a move every tick
  there and one small packet on Java. 0.14 renders only the mobs it is old enough to know; anything
  younger silently doesn't appear.
- **Per-stack state lives exactly as long as the stack does.** A wand's remaining charges persist in a
  chest, because chests are in the level file; the same wand in a player's backpack loses them at logout,
  because a player's inventory has never been persisted here at all. Nothing is lost that wasn't already
  — the wand goes with it — but it is worth knowing before designing around it. For anything that must
  outlive a session, `storage.forPlayer` is still the right place.
- **A Bedrock client's drag is rescued one stack at a time.** That client owns its window and reports
  only an id, a meta and a count, so a custom item's identity is carried across a move by pairing the
  report that empties a slot with the one that fills another. A straight drag works. A *swap* displaces
  two stacks and there is only one trail, so one of the two arrives as the ordinary item it is drawn as
  — as does anything moved faster than the pairing window (`-Djedrock.pe.stackTrailMs`, default 750).
  Java's window is server-authoritative and has none of this problem.
- **Scenes cost packets, not ticks.** A static prop runs no logic, but every joining player pays one
  spawn packet for it. A 0.14 client will find that ceiling first — it's the number to watch as a scene
  grows.
- **Cross-edition skins are approximate.** A signed-texture limit, not a plumbing one: a Bedrock skin
  can't be handed to a Java client verbatim, so avatars are close, not identical.
- **A nether looks like an overworld on 0.14.** That era has no dimension packet this project has
  ground-truthed, and it is the client that crashes on a guessed id — so a world switch there is a chunk
  resend and nothing more. The blocks, the biome tint and the spawn are the destination's; the sky, the
  fog and the compass are not. On **1.1.5** the ChangeDimension packet *is* sent and does work on a real
  client; `-Djedrock.pe.changeDimension=false` stays as an escape hatch back to 0.14's behaviour, since a
  packet that can hang a client on a loading screen is worth a switch even once it is known good.
- **Travel is API and command only.** `/world tp`, `worlds.send`, or a teleport to a `Location` in another
  world. There is no portal block, and there isn't going to be one: noticing a player standing in a frame
  means checking positions every tick, which is the shape of simulation this server doesn't do.
- **Coming back is to a world, not to a spot.** The world someone logged out in is remembered; where they
  were standing in it is not, so they arrive at its spawn. A remembered position would have to answer what
  happens when the ground under it was dug away or the world shrank around it, and this server models no
  falling. On **0.14** the rejoin has the destination's blocks, spawn and biome tint under an overworld
  sky, for the same reason travel there does.
- **On the PE wire, a byte test is not the last word.** The surface itself has been walked through with
  real clients on both eras and works — join, movement, chat, edits, inventories, the illusion toolkit —
  apart from the 1.1.5 client bugs listed above, which are the client's and not this server's. Nothing is
  waiting on a login right now: the head/body yaw split, a custom item's identity carried through a drag
  inside a Bedrock client's own window, and the clock have each been in front of a real client since they
  were written. What stays is the reasoning, because it applies to whatever is added next: everything
  ground-truthed against PocketMine is byte-tested, and a byte test only proves the encoder agrees with
  itself. The item-NBT dialect passed its own tests and still showed the vanilla name on a real client.
  So anything newly added to that wire is unverified until somebody logs in, and this list being empty
  today means only that somebody did.
- **Non-goals (by design).** No mob AI / pathfinding, no redstone, no crafting / smelting mechanics, no
  runtime world simulation or physics, no 1.13+ flattening. Knockback is excluded for the same reason —
  the server simulates no physics. Custom logic that wants any of these lives in a script as an
  *illusion*, not in the core.

---

## Might be in the future

The big arcs are done: the world bakes and persists, the platform API is a real extension surface, the
puppet primitive carries mobs and scenery alike, and four clients share one world. What's left is
smaller by nature — a missing convenience on a surface that already exists, a polish pass, a real-client
verification run, and packaging the whole thing so it can be handed to someone. Nothing here is
promised; it's the list of what would be worth doing next, roughly in the order it would pay off.

- **Small additions to the script API.** Regions want a **greeting / farewell** message and a
  **priority** escape hatch for the case deny-wins can't express: an allow island inside a deny.
- **What worlds still want.** **Deleting** one, which is deliberately absent — unloading leaves the folder,
  and removing it is a decision that belongs to whoever can see the filesystem, not to a script. A
  **portal** is not on this list: noticing a player standing in a frame is the shape of simulation this
  server doesn't do, and travel already has a command, an api and a script call.
- **Polish on what's already there.** A **sharper judge** — per-axis movement limits and a real
  interaction ray-cast, still cheap, still approximate. And the illusion toolkit (sidebar, boss bar,
  menus) wants a **real-client pass on Bedrock 1.1.5**, which is the only way anything on that wire
  becomes true.
- **A scripting reference generated from the contract** rather than kept in step by hand, since
  `plugins/example.js` is currently both the reference and the test.

---

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it is mostly about which opinions this codebase holds and
what is permanently out of scope, which is the fastest way to know whether a change will land.

## License

[MIT](LICENSE). Minecraft is a trademark of Mojang Studios; this project is not affiliated with or
endorsed by Mojang or Microsoft, and ships no Mojang code or assets.

---

*Jedrock — do as little as possible, as late as possible.*
