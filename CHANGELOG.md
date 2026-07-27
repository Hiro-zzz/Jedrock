# Changelog

All notable changes to Jedrock are recorded here. This is an internal project log; the format
loosely follows [Keep a Changelog](https://keepachangelog.com/). The project is pre-1.0 and
unstable — anything may change between entries.

## [Unreleased]

### Added

- **Regions — named boxes with rules, the platform's next primitive.** The thing every game mode ends up
  needing and nothing here could express: a lobby, an arena, a shop floor, a spawn nobody can dig up. Until
  now a script wanting any of that had to keep its own coordinates and re-check them by hand in
  `PlayerMove`, reinventing the same box maths — the core's own comment on that path already named "a
  region border" as the hypothetical reason to cancel a move.
  A region is six numbers and a set of allowances — **`build`, `interact`, `pvp`, `damage`, `entry`** —
  every one **on** until denied, so a fresh region changes nothing until it's told to. Bounds are inclusive
  and normalized, so two corners in any order select what they look like they select. Where regions
  overlap, **deny wins** — the rule this server already uses for permissions — which needs no priority
  number and makes a small no-build box inside a big free-build one behave the obvious way.
  Nothing about it is simulated. There is no trigger volume, nothing ticks, and there is no second
  rulebook: **each flag is enforced by cancelling the event the core already routes that decision
  through**, so a region's refusal is the same cancellation a script could have written, and a script
  listening at a higher priority can overrule one. Crossings fire **`PlayerRegionEnter` /
  `PlayerRegionLeave`**, once per region actually crossed rather than per movement packet, and both are
  cancellable — refusing an enter is what the `entry` flag does, and refusing a *leave* is how an arena
  holds somebody until a round is over. A refused crossing is undone whole: everything is decided before
  anything is committed, so a player never half-enters.
  **A server with no regions pays nothing**, which is the whole reason this could go on the movement path
  at all. Every query starts with one array-length read, and the enforcement listeners are **registered
  only while at least one region exists** — registering them unconditionally would make every block edit on
  every server build an event for rules nobody wrote. Movement deliberately isn't one of those listeners,
  since a permanent `PlayerMoveEvent` listener would defeat the `hasListeners` fast path that keeps
  movement allocation-free; the core asks the manager directly instead, behind the same emptiness check.
  Membership lives on the player and reuses its buffers, so walking around inside a region allocates
  nothing — only an actual crossing does.
  Regions are **server-owned**, like saved scenes: `world/regions.jdb` (the same compact DEFLATE + atomic
  move + dirty flag as the level and the scene store), loaded **before the first login**, so a protected
  spawn protects itself with no script running. `create` refuses a name already taken rather than replacing
  it, so a script that creates its regions on every load can't wipe flags an operator set by hand.
  Authored either way: **`/region`** (`pos1` / `pos2` / `create <name>`, or `here <name> <radius>`; then
  `list`, `info`, `flag <name> <flag> allow|deny`, `remove`, with tab-completion for names and flags) —
  corner selection lives on the player, so two operators can select at once and a disconnect throws a
  half-made selection away — or the **`regions` script global** (`create` / `get` / `all` / `at` /
  `of(player)` / `remove` / `allows`, flags addressed by name). Try `/zone` in `plugins/example.js`.
  Tested (21 new): corner normalization and inclusive, floored containment; a name taken only once;
  deny-wins across overlaps; each flag cancelling exactly its own event and only inside the box; damage
  judged where the *victim* stands; enforcement appearing with the first region and going away with the
  last; crossings firing once per crossing and not while walking around inside; a denied `entry` and a
  cancelled enter both refusing the step; a cancelled leave keeping membership; a refused crossing leaving
  membership untouched; the file round-tripping with its flags; an untouched set not being rewritten; and a
  boot-loaded region enforcing itself immediately.

- **Storage menus reach Bedrock — the transfer moves into the list.** The last gap in the illusion toolkit,
  and the one the `menus` global had been carrying since it landed: a button menu degrades to a `/pick`
  list on Bedrock, but a list can only ever *signal*, and storage is the one menu shape that has to move
  items. So it was refused on 1.1.5 and, on 0.14, sent a window that a real client never brings up. Both
  dead ends are client-verified and neither is going to change: 1.1.5 crashes on a block-bound chest window
  (it builds a chest block-entity only from chunk data, which a blockless menu has none of) and raises no
  GUI for an entity-bound one, and 0.14 simply ignores the menu window.
  Rather than keep waiting for a window, the **transfer moved into the list**: a storage menu now lists its
  *contents*, one option per occupied slot, plus two verbs. `/pick <n>` takes the stack in slot `n`
  (1-based), **`/pick put`** puts the held one in, **`/pick close`** is done — and the list **redraws after
  every transfer**, so it stays up and pickable the way an open window does instead of being consumed by
  one choice. That is the same trade world chests already make on 1.1.5 (click-transfer instead of a
  window), with commands standing in for the right-click a virtual menu has no block to receive. Net
  effect: **both menu shapes now work on all four editions.** 0.14 gives up its unreliable window for the
  list as well — one behaviour on both eras beats a path that only sometimes appears.
  The two transfer primitives (`takeStack` / `putStack`) are now **shared with the world-chest
  click-transfer**, deliberately: the rule that keeps them honest is subtle enough to be worth having in
  exactly one place — a creative player's inventory is infinite and client-managed, so creative *takes* by
  destroying the stack and *puts* without consuming, or a put→take cycle mints items (a duplication this
  cost us once already). Survival moves real items both ways. Each caller words its own message and decides
  whether the world is marked dirty, since a menu's contents are transient and a chest's are not.
  Known rough edge, called out rather than hidden: a stack is addressed by **slot number** and shown as its
  raw state, because the core has no item-name table (a block is an id by design). Honest, not friendly —
  names are the natural follow-up. Tested: taking a slot moves the stack and leaves the list up showing
  what is actually left, `put` deposits the hand, `close` ends it for good, a full inventory loses nothing,
  creative neither gains items on take nor loses them on put, a button list is still one-and-done, and the
  world-chest click-transfer still persists through the shared primitives.

### Fixed

- **A survival player couldn't rearrange their own inventory on Bedrock.** Moving the held stack into
  storage (or back out of it) held only until the inventory was closed, at which point the item jumped
  back to where it started. Creative was unaffected. This was the known trade-off of the chest-deposit
  dupe fix coming due: Bedrock owns window 0 — the client moves the item in its own GUI and the inbound
  `ContainerSetSlot` is the **only** notice the server ever gets — and that report was being dropped
  outright in survival, so the server's copy never changed and the resync that closing the window
  triggers put the item back. Nothing was lost; the server simply never agreed the move had happened.
  Applying the report again on its own would re-open the dupe, because the same client also **echoes** a
  slot the *server* just changed, carrying the value it held **before** — right after a chest deposit that
  echo re-added the stack the deposit had consumed, so the item ended up in the chest and in the hand at
  once. The two reports are identical in content: an echo is a stale move, not a malformed one. What
  separates them is **time** — an echo answers a push that has only just gone out. So every
  server-authored push (`CorePlayer.syncSlot` / `syncInventory`) now arms a short per-slot guard
  (`SlotEchoGuard`, 750 ms, `-Djedrock.pe.slotEchoGuardMs=<ms>`, `0` = off), and a report landing inside
  it is answered with a correction rather than trusted; past the window the client is believed and its
  move sticks. Same shape, and the same reason, as the `PeEditDebounce` on the block path: this client
  reports one action more than once. Creative is untouched — it owns its inventory outright, so its
  report stays a plain mirror and is never second-guessed. The fix is core-side, so **0.14 gets it too**.
  Tested: a move out of the hand and back into it both survive the close, a stale echo is refused and
  corrected, a survival chest deposit is not undone by the echo that follows it, creative is still a
  mirror, armor (its own PE window) is not reachable through window 0, and the timing rule itself —
  expiry, per-slot independence, a full resync, and the off switch — against an explicit clock.

### Added

- **Scenes survive the restart — decoration stops being a demo.** Everything a script spawned died with
  it: a hot reload, or a restart, took the lanterns away, so an arrangement only existed for as long as the
  code describing it kept running. That is right for a guard with an `onTick` brain and wrong for a lamp
  post. **`group.save(name)`** freezes an arrangement as it stands — type, position, facing, name tag, held
  item, armor and flags — and the **server** owns it from then on: it is stood back up at boot, before
  anyone can log in, with no script involved at all. `entities.loadScene(name)` hands a script the props
  (spawning them if they aren't up, returning what is standing if they are — asking on every reload can't
  breed copies), `entities.scenes()` lists them, `entities.removeScene(name)` takes one out of the world
  and forgets it.
  What is deliberately not saved is *behaviour*: a saved prop has no brain, because a saved scene has no
  plugin. Stored like the world and the script store already are — one compact DEFLATE file
  (`world/scenes.jdb`, next to the level file since scenes decorate that world), written atomically, with a
  dirty flag so an untouched set is never rewritten, flushed by the same autosave and once more at
  shutdown. Try `/scene save`, then restart the server and look. Tested: a scene round-trips through the
  file with its look intact, standing one up twice yields the same props rather than two copies, removing
  one takes it out of the world as well as the store, an unknown name is empty rather than an error, and an
  untouched store is not rewritten.

- **Scripts can reach the chests players actually placed.** The `menus` global has been able to conjure a
  chest out of nothing for a while; the one kind of storage a script could <em>not</em> touch was the kind
  that matters — a real chest block, with contents that persist in the level file and that anyone standing
  at it can open. `world.getChest(x, y, z)` returns one now (`null` where there is no chest block, so a
  script can't conjure storage in mid-air), with `hasChest` for the cheap check: `getItem` / `getCount` /
  `setItem`, `add` / `remove` (returning how many actually fit or were found, so a full chest is
  distinguishable from a successful drop), `count` / `contains`, `clear` and `size`.
  It is the same container everything else uses, not a copy: an edit marks the world dirty for autosave
  and is pushed to anyone who has that chest open at that moment — otherwise they would keep looking at a
  stale window and their next click would be judged against contents that no longer exist. Try `/stash`.
  Fixed a stale comment on the way: `CoreWorld` still claimed chest contents were in-memory only and did
  not survive a restart, which stopped being true when the level file learned to carry them (format v3).

- **The sidebar reaches Bedrock — on the item-name line.** Neither legacy Bedrock era has a scoreboard, so
  `player.setSidebar(title, [lines])` borrows the one persistent text field those clients do have: the
  **popup**, which MCPE draws in the HUD slot where a held item's name appears, displaced up the screen.
  The title goes in the popup's own string and the rows follow beneath it, newline-joined — the two-string
  shape PMMP's `sendPopup(message, subtitle)` uses. `TextPacket TYPE_POPUP = 3` and its layout are verbatim
  from PocketMine at **both** protocols (1.7dev-27 for 113, e11b76318 for 45): a `type` byte, then `source`
  and `message`. The eras are structurally identical here and differ only in their strings — varint-length
  at 1.1.5, big-endian-short at 0.14 — so each session writes its own, as everywhere else.
  The one thing a popup isn't is *stateful*: unlike a Java scoreboard the client holds nothing, and the
  line fades after a couple of seconds. So `PlayerConnection` gained **`sidebarRepaintTicks()`** — how
  often this client needs its sidebar re-sent, `0` (the default, and Java's answer) meaning never — and
  `PlayerBroadcast.repaintSidebars` honours it on the game loop from the rendered copy `CorePlayer` now
  keeps. The core still never learns which edition it is talking to; it just obeys a number the connection
  declares. A script sets the sidebar once and it stays up on every edition; a server with no sidebars in
  use pays one field read per player per tick.
  **Confirmed on a real client**: the panel renders, and `\n` really does give multiple lines rather than
  one run-on string — the open question this landed with. What it also showed is that the client, not the
  server, decides *where* that line goes: it sits centred, right on top of the hotbar. Since the only lever
  is the text itself, placement is two config knobs — **`pe.sidebar.raise`** (blank rows padded under the
  panel, each lifting it a line clear of the hotbar; negative pads above instead, for a client that anchors
  the other way) and **`pe.sidebar.shift`** (spaces padded on every row, positive right / negative left;
  the client centres the popup, so the panel moves about half as far as you pad). Defaults 4 and 16 — off
  the hotbar and aside, roughly where a Java sidebar sits; `0`/`0` restores the raw centred position. A pad
  row is a single space, not an empty line, since a renderer is free to drop trailing blanks.
  Tested: the popup bytes at both protocols, the newline join and its 16-line cap, the padding in both
  directions on both eras, and the repaint cadence (fires on its tick, not off it; stops on
  `clearSidebar`; never fires for a Java connection).

- **Virtual chests fall back to `/pick` on 0.14 too.** The `menus` window doesn't come up on a real 0.14
  client, so that era now takes the same route 1.1.5 does: a **button** menu is shown as a labelled text
  list and chosen with `/pick <label>`, firing the same handler the window would. 0.14 predates the
  Bedrock command manifest entirely and sends `/` lines as ordinary chat, so `/pick` needed nothing new
  there. The two eras aren't treated identically, because their failure modes aren't: on 1.1.5 a window
  *crashes* the client, so a menu with no labels is refused outright; on 0.14 a window is merely
  unreliable, so a **storage** menu — which moves items and therefore can't be a list at all — still
  attempts it rather than losing the only mechanism that era has. Net effect: `menus` is now a **button
  menu on all four editions**, and Bedrock storage menus are the remaining soft spot.

- **Decoration grows up: labels, scenes and a wider cast.** Three gaps between "props exist" and "props
  are an authoring surface", closed together.
  **Labels are entities now.** A floating line of text was the one member of the family outside the
  entity API — reachable only as `server.spawnHologram(...)`, with no tick, no state and no plugin
  ownership. New `EntityType.TEXT` / `entities.spawnText('{yellow}Lantern', x, y, z)` returns an
  ordinary `ScriptEntity`: movable, re-textable through `setNameTag`, despawned with its plugin. It
  reuses the very wire the hologram lines ride (invisible marker armor stand on Java, item entity with
  no item on Bedrock), so nothing new had to be learned about the protocols. `spawnHologram` stays for
  managed multi-line stacks.
  **Scenes.** `entities.group()` returns a `ScriptGroup` — a set handled as one: `add`, `move`,
  `moveTo`, `rotate(degrees)` around a pivot (positions *and* facings), `setNameTag`, `remove`, with
  the pivot defaulting to the members' centre. Plus shape helpers that place a callback's output along
  a `circle` / `line` / `grid` and hand back the group, keeping the arrangement and its contents as
  separate concerns. A group is a view, not an owner: entities still belong to their plugin, so a
  hot-reload clears them whether grouped or not.
  **17 more mobs** — sheep, wolf, villager, mooshroom, squid, bat, ocelot, snow golem, spider, cave
  spider, silverfish, enderman, slime, zombie pigman, ghast, magma cube, blaze — taking the cast from 6
  to 23. Java ids checked against minecraft-data, Bedrock ids against the legacy table PocketMine's own
  classes corroborate (Wolf 14, Villager 15, Squid 17 read straight from its source). Since 0.14 is a
  2016 client that predates some of them, a new `Pe014Entities` gate lists what that era is known to
  have and simply doesn't render the rest for those players — the same graceful degradation its blocks
  and sounds already use, and the same "grow it only against a real client" rule.
  `/decor` is rebuilt on all of it: the ring comes from the `circle` helper, the label is a text entity,
  and `/decor spin` turns the whole scene at once. Tested in `ScriptEntitiesTest` — which caught a real
  bug on the way, that an entity returned from a script callback arrives wrapped as a `NativeJavaObject`
  and has to be unwrapped before the group would accept it.

- **Two more ways to pose a block: worn, and full-size.** Building on item props, the decoration
  vocabulary gains the two techniques that put a *block* wherever you want it.
  **Equipment on entities:** puppets gained `setHeldItem(state)` and `setArmor(slot, state)` (scripts:
  `entity.setHeldItem(...)`, `entity.setArmor('helmet', ...)`), relayed to every viewer and caught up
  when a newcomer spawns. Any canonical state works, blocks included — so an entity wearing a block,
  with `setFlag('invisible', true)` hiding the body, is a block posed at any height with nothing under
  it. It also just makes better mobs: a script guard can now carry a sword and wear armour.
  **Falling-block props:** a new `EntityType.FALLING_BLOCK` / `entities.spawnBlock(state, x, y, z)`
  renders a block at *full size* where an item prop renders a small model. Per edition: **JE** Spawn
  Object type **70** with the block packed into the object-data int as `id | (meta << 12)` — the exact
  packing ViaVersion's 1.13 converter unpacks the other way — plus the no-gravity field on 1.12.2;
  **PE** an AddEntity of PMMP's `FallingSand` (id 66) carrying the block as `id | (meta << 8)`, in
  `DATA_VARIANT` (index **2**) at 1.1.5 but `DATA_BLOCK_INFO` (index **20**) at 0.14 — the eras moved
  it — and pinned immobile. 0.14 routes the block through the same crash gate the chunks use.
  **JE 1.8 renders every prop as a worn block instead**, and that is not a shortcut — it is the only
  encoding that holds still there. Confirmed on a real 1.8 client: item entities *and* falling blocks
  both drop to the ground, because a 1.8 client locally simulates the "simple" entity kinds (items,
  falling blocks, projectiles) rather than interpolating toward the positions the server sends, and 1.8
  has no no-gravity metadata to switch that off. A living entity is not simulated that way — which is
  why the hologram armor stands have always held their place — so on 1.8 both prop kinds ride an
  invisible marker armor stand wearing the item on its head, with the spawn and every later move
  offset to keep the block where the caller asked. The size distinction survives the substitution: an
  item prop rides a *small* stand (whose head renders what it wears at about half scale, the nearest
  1.8 has to the item entity other versions use) and a block prop a full-size one. Later versions use
  the real entity types with no-gravity set. `/decor` in `plugins/example.js` shows all three techniques. Tested in
  `EntityTypeIdsTest` (prop types have object types, not mob ids) and `ScriptEntitiesTest` (worn block
  + full-size block).

- **Item props — blocks and items as free-standing decoration, cross-edition.** A new canonical
  `EntityType.ITEM`: an entity whose *body is an item or a block*, rendered as a small floating model.
  It is the decoration primitive, and it does what a real block cannot — sit at a fractional position,
  hang unsupported in mid-air, overlap its neighbours, carry a floating label, and move. `Server
  .spawnItem(location, state)` and, for scripts, `entities.spawnItem(Blocks.state(89, 0), x, y, z)`
  returning the same `ScriptEntity` as any other entity, so props tick, pose and despawn alike. **No
  resource pack is involved**: this is a vanilla entity type doing its normal job, so it renders on
  unmodified clients — which is the whole point, since a server pack would break the project's
  join-with-any-client promise (and 0.14 barely supports one).
  Per edition, because an item stack is not a mob: **JE** spawns it with Spawn Object (1.8 `0x0E`,
  fixed-point; 1.12.2 `0x00`, doubles; object type 2 both) and then names the item in **Entity
  Metadata** — index **10** at 1.8 and **6** at 1.12.2, the shift ViaVersion's own index table records
  (item was 5 in 1.9; 1.10 inserted no-gravity at 5 and pushed every later field along); 1.12.2 also
  pins no-gravity. **PE** uses `AddItemEntity` (1.1.5 `0x0f`, 0.14 `0x9a` big-endian), spawned
  immobile — inline in the metadata at 1.1.5, and at 0.14 with a following `SetEntityData`, since that
  era's packet has no metadata field at all. Zero velocity everywhere, and the server never sends a
  pickup, so a prop is inert. `/decor` demo in `plugins/example.js` (a hovering lantern, a ring of gems
  at fractional radius inside one block's footprint, and a bobbing centrepiece). Tested in
  `PeHeldItemEncodingTest` (both PE wire shapes), `EntityTypeIdsTest` (non-mob types have no mob id)
  and `ScriptEntitiesTest` (a prop is an entity like any other).

### Added

- **Virtual chests on Bedrock 1.1.5 — as a `/pick` list.** The 1.1.5 client crashes on a chest window, so a
  button menu there degrades to a text **list**: a script gives each button a label with the new
  `menu.button(slot, item, label)`, and on 1.1.5 those labels are printed and chosen with a built-in
  **`/pick <label>`**, which fires the same click handler the window would. `/pick` is a built-in (not a
  per-menu command) on purpose — the 1.1.5 client rejects any command not in the manifest it got at spawn,
  and re-sending that manifest per menu is exactly the risky, unverifiable wire this avoids. On Java the
  labels tab-complete (from the player's pending list); on 1.1.5 the client only knows `/pick` takes free
  text, so the player reads the options from the chat list — true client-side label completion there would
  need a dynamic per-menu command manifest, a follow-up against a real client. A menu with no labels has
  nothing to list, so it's still refused on 1.1.5; storage menus (which move items) can't be a list and
  stay window-only. Tested: the list build, the case-insensitive pick, the label completion, and the
  no-labels refusal.

- **Virtual chests reach Bedrock 0.14.** The `menus` virtual chest (Java last round) now opens on 0.14
  too, which — unlike 1.1.5 — shows a real chest window without crashing (it already had the full
  container flow wired for world chests: ContainerOpen `0xb5`, ContainerSetContent `0xb9`, inbound
  ContainerSetSlot `0xb7`). `openMenu` now refuses only 1.1.5, not all of Bedrock. Because the PE window is
  client-authoritative, a **storage** menu works cleanly — the client moves items and reports each slot,
  which is applied to the menu (and, being transient, never marks the world dirty) — while a **button**
  menu's read-only revert is best-effort: `onContainerSetSlot` fires the click for the tapped slot and
  re-sends the window to undo the client's optimistic move, which can't perfectly reverse a cross-window
  drag the way the server-authoritative Java path does. Tested for both PE shapes plus the 1.1.5 refusal.

- **Boss bar reaches Java 1.8 and Bedrock 1.1.5.** The boss bar (added on 1.12.2 last round) now shows on
  two more editions, so `player.setBossBar(...)` is cross-edition wherever a client can draw one.
  **Java 1.8** has no boss-bar packet, so it uses the classic **wither illusion**: an invisible wither is
  spawned a few blocks below the player (clear of their collision box), named the title, its health driving
  the fill (max 300), and teleported to follow the player so it stays loaded as they travel — the approach
  battle-tested 1.8 bar plugins use. (A first cut rode the wither on the player via Attach Entity; that
  didn't render the bar on a real client, so it was replaced with the spawn-and-follow above.) The wither's
  name is set without the always-visible flag, so no floating text leaks under the player. **Bedrock 1.1.5** uses the native **BossEvent**
  packet (`0x4c`) bound to the player's own entity id, so no extra entity is spawned: TYPE_SHOW to add
  (title + fill + colour + overlay, the fields in PMMP's fall-through order), TYPE_HEALTH_PERCENT and
  TYPE_TITLE to update, TYPE_HIDE to clear. **0.14 predates boss bars** and keeps the no-op. The 1.1.5
  layout is byte-checked against PocketMine at protocol 113 (`1.7dev-27`); neither the wither trick nor the
  BossEvent is verified on a real client here, so both are best-effort — the 1.8 one fails cosmetically (no
  bar), and every 1.1.5 field mirrors PMMP exactly to avoid a malformed-packet disconnect.

- **Virtual chests for scripts — the `menus` global.** A script-owned chest window with no world block
  behind it: `menus.create(title, rows)` (1–6 rows) builds one, `setItem` / `getItem` / `clear` lay it
  out, and `open(player)` shows it. Two shapes decided by one call: give it an `onClick(player, slot,
  state)` and it becomes a **button menu** — the slots go read-only, and clicking one fires the handler
  instead of moving the item, so each slot is a button (a class picker, a shop, a confirm dialog); leave
  `onClick` off and it's a plain **storage chest** the player moves items in and out of, transient (nothing
  persists — it isn't a world block). It reuses the server-authoritative Java chest-window flow, which was
  generalized from a hard-coded 27 slots to the container's actual size along the way, so the player-
  inventory half of the window lines up for any menu size. Java only: `open` returns `false` for a Bedrock
  player, because the retail 1.1.5 client crashes on a chest window (the same reason world chests trade
  through click-transfer there) and 0.14 is unwired. The core routing is unit-tested (button vs storage,
  the non-27 slot math, the Bedrock refusal, transient-not-persisted), and the script surface through
  Rhino. Try `/menu` in `plugins/example.js`.

- **Boss bar, Java 1.12.2.** `player.setBossBar(title, progress[, color])` / `clearBossBar()` — a titled
  bar across the top of the screen with a 0..1 fill and one of seven colours (`pink` … `white`, default
  purple). Purely presentational: no entity, no combat, just a bar showing whatever you set. Driven by the
  dedicated Boss Bar packet (`0x0C`): an add the first time, then only the deltas — a health update, a
  title update, and a style update when the colour actually changed — so a per-tick refresh never re-adds
  the bar. The sequencing lives in `JeBossBar` (unit-tested with a recording wire). 1.8 has no boss-bar
  packet (it predates 1.9's, and the wither-entity trick that fakes one is a separate, riskier technique
  left for later); Bedrock's legacy clients aren't wired here. Both let the default no-op stand. Try
  `/boss 50 red` in `plugins/example.js`.

- **Sidebar scoreboard, Java (1.8 + 1.12.2).** `player.setSidebar(title, [lines])` / `clearSidebar()` — a
  titled panel of text lines down the right of the screen, authored in the unified markup. Purely
  presentational, true to the illusion: the server tracks no real scores; the lines are whatever you set,
  and setting them again replaces them. It updates by **diffing** — the objective is created once, the
  title retitled only when it changes, and only the score entries that actually changed are re-sent — so
  refreshing on a timer costs a couple of packets and never flickers (the version-neutral diff lives in
  `JeScoreboard`, unit-tested; the two versions differ only in the objective packet, a JSON component +
  VarInt type at 1.12.2 vs a plain string + type string at 1.8). Duplicate lines are kept distinct by an
  invisible trailing colour code per row, and up to 16 lines show. The pre-1.13 client draws a small red
  number beside each line — the vanilla scoreboard look, unavoidable without the 1.13 number-format.
  Bedrock ignores it: 0.14 predates scoreboards and 1.1.5 isn't wired here yet (a follow-up, against a real
  client). Packet ids checked against minecraft-data (objective 0x42/0x3B, score 0x45/0x3C, display
  0x3B/0x3D); on-client behaviour isn't verified here. Try `/sb on` in `plugins/example.js`.

- **Typed command arguments and tab-completion.** Two things that fall out of one declaration, and the end
  of every command parsing its own `String[]` by hand.
  A command may describe its arguments as a list of typed `CommandArg` — a name, an `ArgType`, and
  required/optional. `ArgType` is the single thing that both parses a token into a value and suggests
  completions for a partial one: `WORD`, `GREEDY` (the trailing message), `INTEGER`, `NUMBER`, `BOOLEAN`,
  `PLAYER` (resolves against and completes the online roster), `GAME_MODE` (the lenient parse `/gamemode`
  always had, plus name suggestions), and `choice(…)` for a fixed literal set — a new one is parse-or-throw
  plus an optional suggestion list, a few lines. `ArgCommand` builds on it: a subclass gives `arguments()`
  and `run()`, and the tokens are validated once — missing required, a type's own rejection message, extra
  tokens past a non-greedy tail, a greedy final arg swallowing the rest — before the body runs, with a
  usage line generated from the signature. `/gamemode` is migrated to it as the showcase.
  **Tab-completion** has one entry point, `CommandManager.complete(sender, line)`: still on the name, it
  offers matching labels (each with its slash) the sender may actually run, permission- and
  player-only-gated like `/help`; past the name, it asks the command, whose default derives suggestions
  from `arguments()`. `tp` keeps its hand-written parse (it takes `<player>` **or** `<x> <y> <z>`, which one
  signature can't say) but gets a completion override; weather, msg, heal, kill, tphere, op and deop
  declare their arguments for completion only.
  **On the wire (Java only):** a client's serverbound Tab-Complete (`0x01` at 1.12.2, `0x14` at 1.8) is
  decoded to `ConnectionListener.onTabComplete`, the core completes it — only a real command line, only for
  a registered player, so an unauthenticated socket can't enumerate commands — and answers with clientbound
  Tab-Complete (`0x0E` / `0x3A`, a shared `JeTabComplete` body since the format is identical across the
  legacy versions). The parse/completion layer takes the api `Server`, not the concrete `JedrockServer` —
  it needs only the roster, which keeps it testable without a live server. Bedrock is untouched: it
  completes client-side from the AvailableCommands manifest it already gets, and the retail 1.1.5 client's
  known bugs are reason enough not to enrich that manifest.
  **Scripts** get completion too: a command's optional `complete(player, args)` returns candidates the core
  narrows to the partial, so a script returns its whole list (`/kit` in `plugins/example.js`); a completer
  that throws yields no suggestions rather than breaking the client's typing. The byte layout is tested,
  and the core logic end-to-end; the packet ids and serverbound layout come from the historical protocol
  tables, and behaviour on a live client isn't verified here.

- **Persistent storage for scripts — the `storage` global.** The last thing the platform-API roadmap was
  waiting on: until now a plugin's state died with the process, which ruled out scores, homes, statistics
  and saved scenes — everything a server actually remembers.
  Every plugin gets a private store: `get(key[, fallback])` / `set` / `has` / `remove` / `keys` / `size` /
  `clear`, plus **`forPlayer(player)`**, a view of the same store narrowed to one player and keyed by uuid
  so it follows a rename. Keys are bucketed per plugin *name*, which gives two properties worth stating:
  two scripts can both keep a `count` without meeting, and data belongs to the name rather than to the
  loaded instance, so a hot-reload — which tears down listeners, tasks, commands and entities — leaves the
  memory alone.
  Values are deliberately few. A string, a number and a boolean are stored as themselves; a JS object or
  array is rendered through the script's own `JSON.stringify` and handed back through `JSON.parse`, so a
  saved arrangement returns as a real value and not text that resembles one. Anything else — a function, a
  Java object — is refused loudly rather than persisted as nonsense, and `set(key, null)` removes the key,
  since there is no useful difference on disk between "absent" and "nothing". Nothing is executed to read
  the file back.
  Written the way the world is written: one DEFLATE stream in `plugin-storage.jdb`, a dirty flag so an
  untouched store is never rewritten, and an atomic temp-and-move so a crash mid-write cannot destroy what
  was already saved. Strings are length-prefixed UTF-8 rather than `writeUTF`, whose two-byte length caps
  a value at 64 KB — small for a serialized scene, which is one of the things this exists to hold. Loaded
  before any script can ask for it, flushed by the same autosave that persists the world, and once more at
  shutdown *after* `onDisable`, so a script's parting write is included. `plugins` in the console now
  reports the store's size. Try `/seen` and `/forget` in `plugins/example.js`.

- **Scripts now see Java strings, numbers and booleans as JS primitives.** Found while building the store,
  by the test for its most ordinary case — `storage.get('mode') === 'hard'` was false. Rhino wraps values
  returned from Java by default, and a wrapper is never `===` a JS literal, so `player.getName() ===
  'Alice'` and `e.getTo().name() === 'THUNDER'` were silently false too: a bug that reads as a logic error
  and hides in whichever branch never runs. The script scope now disables primitive wrapping, which is
  what the command-args path had already arranged by hand for exactly this reason — the comment there
  spelled out the right answer, it just wasn't applied to the rest of the surface. The test added last
  round to *document* the trap now asserts it is gone.

- **Events for weather and equipment — the event model catches up with the features.** Three additions
  close the gap the roadmap named: the sky and a player's gear could both be changed, but nothing could
  subscribe to either.
  **`WeatherChangeEvent`** carries `from` / `to`, and is both cancellable and redirectable (`setTo`) — a
  server that wants rain but never thunder is four lines. It is posted by `CoreWorld.setWeather` itself
  rather than by its callers, which is a deliberate exception to how block events work (those fire at the
  handler that decided on the edit): a block edit arrives from one player through one handler, while a
  weather change has three front doors — `/weather`, a script's `world.setWeather`, and the api — and only
  the world is common to all of them. Nothing has been sent to a client when the event fires, so a refusal
  leaves nothing to undo.
  **`PlayerArmorChangeEvent`** fires per slot with the previous and next state, wherever the piece came
  from: `Player.setArmor` from code, a creative client's drag into slots 36-39, or a survival window click.
  The window paths compare the four worn slots before and after the click instead of predicting what the
  click will do — what a click does depends on the cursor — and a refused change is written back and
  corrected on the client by the resync that path already performs. Only snapshotted when something is
  listening.
  **`PlayerHeldItemChangeEvent`** fires on a real hotbar switch (not when the stack inside the held slot
  changes — the player didn't choose that), carrying both slots and both items. Cancelling has the same
  honest limit as the sneak toggle: the server refuses to *reflect* the switch — nothing that reads the
  held item sees it and no other client redraws the hand — but the switcher's own hotbar stays where they
  put it, because no edition here has a clientbound packet that moves it back. `onHeldSlotChange` moved
  from `JedrockServer` into `ContainerService` on the way, where the rest of the equipment logic lives.
  All three are scriptable by name (`events.on('WeatherChange', …)`), demonstrated in `plugins/example.js`.
  **A Rhino trap is now pinned by a test**, because it cost this project a bug once already (script command
  args) and every enum-carrying event walks into it: a `String` returned *from Java* is not `===` a JS
  string literal, so `e.getTo().name() === 'THUNDER'` is silently false and the `if` around a listener's
  real work never runs. Loose `==`, `String(…)`, and comparing the enum constants themselves all behave —
  the test asserts all four outcomes at once so the guidance can't rot.

### Fixed

- **The 1.8 boss bar still didn't show — the wither has to be in front of you.** Second attempt at this
  (the first swapped riding the player for spawn-and-follow, which was also invisible). The missing fact:
  the 1.8 client draws its boss health bar from the wither it is **rendering**, not merely from one it has
  been told about — so an invisible wither parked five blocks under the player's feet, permanently out of
  frame, never produces a bar no matter how correct its packets are. Ground truth this time is
  **ViaRewind**, which has to solve the identical problem (give a 1.8 client the boss bar of a modern
  server) and whose recipe explains itself: it holds the wither **48 blocks straight down the player's line
  of sight** and re-places it on every **look** packet as well as every move. That trigonometry only makes
  sense if the entity has to stay in view — which is the whole answer.
  Now copied field for field: the placement (`x - cos(pitch)·sin(yaw)·48`, `y - sin(pitch)·48`,
  `z + cos(pitch)·cos(yaw)·48`), the follow on look as well as position, the wither's
  invulnerable-time metadata (index 20 = 880, which keeps the client's copy out of its spawn sequence),
  and an empty bar as *almost* zero health rather than zero (a wither at 0 is dead and draws nothing).
  The spawn seed now carries the facing too, so a bar shown before the client's first position report is
  already placed correctly. Tested: the placement is pinned in four directions plus the invariant that the
  wither always sits exactly 48 blocks away — the kind of mistake that is invisible otherwise, since the
  packets send happily either way. One deliberate difference from ViaRewind remains: it also sets the
  always-show-nametag flag, which would hang the title in the world as floating text, so that stays off.

- **A sidebar line built by concatenation crashed the script.** `player.setSidebar(title, [lines])` threw
  `ClassCastException: ConsString cannot be cast to String` for any line a script assembled with `+` — which
  is every interesting line, since a static sidebar has no reason to exist. The cause is one Rhino detail:
  `NativeArray` *implements* `java.util.List`, so a JS array handed to a `List<String>` parameter is passed
  **by identity, with its elements unconverted** — and a JS concatenation is a lazy `ConsString`, not a
  `String`, so reading an element into a `String` local checkcast and threw. (A `String` or `String[]`
  parameter is safe: Rhino converts those element by element. `List<String>` is the one shape that isn't,
  and `setSidebar` is the only place in the api that takes one.) `CorePlayer.setSidebar` now reads the
  elements as `Object` and stringifies, so any `CharSequence` renders. The existing test missed it by using
  string *literals* — which really are `java.lang.String` — so the regression test now concatenates.

### Changed

- **The script API is a contract now, not an accident.** Rhino reflects an object's <em>runtime</em> class.
  Declaring a field as an api interface changes nothing, and neither does Rhino's own `staticType`, which
  only narrows the surface when reflection outright fails — confirmed by experiment before any of this was
  written. So for as long as the core handed scripts its own objects, a plugin could call every public
  method those objects happened to have: `player.getConnection()` was a door into the network layer (raw
  packet writes to anyone), `server.getOpList()` / `getNetworkServer()` / `getPlugins()` were doors into
  the permission store, the socket and the plugin host. Meanwhile the `api` module — whose entire job is to
  be the contract — described none of it, so a plugin's real surface was whatever the implementation
  happened to expose, and renaming an internal broke scripts with nothing failing to compile.
  New **`ScriptPlayer`** and **`ScriptServer`** are that surface, written down: every method a plugin may
  call, delegating to the api. **`ScriptWrapFactory`** substitutes them wherever a core object crosses into
  JavaScript — and because Rhino routes *every* path through a wrap factory, that is all of them at once:
  the globals, `e.getPlayer()`, a command argument, `server.getPlayers()`, an entity's nearest-player
  query. There is no way left to obtain a core object in a script.
  Kept working on purpose: **`==` between two players**. Rhino compares Java objects by unwrapping and
  comparing references, so a fresh view per crossing would have quietly made `e.getPlayer() == watched`
  false — a script that works today silently ignoring the player it watches. Each player's view is kept on
  the player itself, so it is the same object every time and dies with them rather than in a map of
  everyone who ever logged in. Names match the api exactly, so existing plugins are unaffected, with one
  deliberate exception: `player.getConnection()` is gone, replaced by **`player.getVersion()`** for the
  one thing scripts used it for. `isOp()` and `hasPermission()` moved onto the api `Player` as part of
  this — they were documented, used by the example plugin, and existed only on the implementation.
  **`ScriptPuppet`** and **`ScriptHologram`** close the same hole for what `server.spawnPuppet(...)` and
  `server.spawnHologram(...)` hand back (the `entities` global was already wrapped). The puppet is the
  interesting one: its interaction callback is a function living in a plugin's scope, and a raw
  `onInteract` stored it as a bare lambda on the puppet — so after a hot reload it kept firing into a
  scope that had been thrown away, off the script lock, for as long as the server ran. It now dispatches
  through the plugin that registered it, under the same lock, context and swallow-and-log as every other
  script callback. Which plugin that is comes from the scope Rhino hands the wrap factory.
  Nothing in the api is left unwrapped now: player, server, world, puppet and hologram all arrive as
  contract objects, and `entities.spawn(...)` keeps returning the plugin-owned `ScriptEntity` it always did.

- **`PeSession` split: the 1.1.5 encoders move out (1749 → 1218 lines).** The 0.14 layer has always been
  two classes — `PeSession014` for the session, `Mcpe014Packets` for the bytes — and 1.1.5 never got the
  same treatment, so its session had accumulated every packet body it sends inline, most of them as
  lambdas inside the method that sent them. New **`McpePackets`** is the 1.1.5 counterpart: ~35 clientbound
  encoders (chat and popups, titles, boss-bar events, level events, the PlayerList/AddPlayer pair, entities
  and props, metadata, animations, blocks, equipment, inventories, and the whole join sequence from
  PlayStatus through StartGame to the command manifest), each a pure function of its arguments.
  The point isn't the line count, it's what the split buys: an encoder that touches no session state can be
  read against PocketMine field by field and pinned by a unit test that needs no client, no socket and no
  login — which is exactly how these layouts were verified in the first place. The eight existing PE
  encoding tests now call `McpePackets` directly, and they are what proves the move was byte-for-byte: the
  wire is unchanged. Where an encoder needed the player's own entity id it takes it as an argument rather
  than reaching for a constant, so nothing in the new class knows what a session is. Behaviour is identical
  — even the boss bar still sends its fill and title as two separate batches, which is what it did before.

- **`JedrockServer` split again: the network bridge moves out (1099 → 622 lines).** The last split pulled
  out what the server *does* (broadcasting, combat, containers, entities, the level). What stayed was two
  things that never share a line of code: the server's own life — config, the collaborators it owns,
  bootstrap, the tick, the api surface — and the ~470 lines of *inbound decisions*, one per thing a client
  can report. New **`ConnectionBridge`** takes the second half, and the split is the one
  `ConnectionListener` was always shaped for: the network layer holds a listener, not a server, so it now
  holds exactly that and `JedrockServer` stops implementing the interface entirely. Where a decision is
  genuinely the server's — which mode a returning player joins in, what commands there are to advertise —
  the bridge forwards rather than keeping a second copy of the state. Everything else was already
  delegated and stays so. No behaviour change: the bodies moved verbatim, and 18 imports went dead in the
  process, which is its own measure of how much did not belong there.

- **`JedrockServer` split into five collaborators (1822 → 1043 lines).** The class had accumulated every
  responsibility that ever needed the roster, and its next feature would have made that worse. What came
  out, each owning one thing and testable on its own: **`PlayerBroadcast`** — the single place that walks
  the online roster and pushes (chat, avatar moves, pose, held item, armor, the hurt flash, the
  server-authoritative reposition), so the "loop the players, skip the subject" pattern is written once
  instead of nine times; **`EntityDirector`** — puppets and holograms, their spawn / relay / despawn and
  the join-time catch-up; **`ContainerService`** — windows, chests, click-transfer and the creative
  mirror, the one owner of the survival inventory; **`CombatService`** — fall, void and melee funnelled
  into one damage path with the death and respawn that follow; **`LevelManager`** — the world's life on
  disk (load, one-time bake, autosave, shutdown write). The server keeps the surface everything else
  talks to (`Server`, `ConnectionListener`, its public accessors), so commands, scripts and the console
  are untouched. `CorePuppet` / `CoreHologram` now hold the `EntityDirector` rather than the whole
  server: an entity knows only its relay path. Seven `relayPuppet*` methods and fourteen imports left
  with them, none of which had a caller outside the puppet itself.

- **Hot-path allocations, four places.** A `LOGGER.debug(() -> …)` never *invokes* its lambda with debug
  off, but a lambda that captures anything is still allocated on every call — and three of those sat on
  per-packet paths (inbound JE, inbound PE-in-batch, and **every** outbound packet). A new
  `JLogger.isDebugEnabled()` gates them, so a walking player no longer mints one object per packet in
  each direction. `ChunkView.recenter` walked each ring by scanning the whole square and discarding the
  interior — 969 visits for the 289 chunks of a radius-8 view; it now walks the perimeter directly, same
  nearest-first order (pinned by a new test). `PlayerRegistry.all()` built a fresh unmodifiable wrapper
  per call and now keeps one live view, and the world's change listener — which fires per written cell,
  so a script `world.fill` pays for it thousands of times — iterates the typed roster instead.

- The Java Edition **Slot** wire format now lives in one place (`JeSlots`) instead of being spelled out
  again in every packet that carries an item.

- **Programmable entities for scripts — the `entities` global.** Puppets stop being one-off props and
  become the scripting API's mob primitive: an eighth global lets a script spawn bodies, drive them and
  find them. `entities.spawn('zombie', x, y, z)` (or a `Location`, optionally named) returns a
  `ScriptEntity` with movement (`moveTo`, `teleport`, `moveToward(target, speed)` — a straight-line step
  that walks through walls as happily as across a field, because there is still no pathfinding),
  aim (`lookAt` accepting a point, a player or another entity), looks (`setNameTag`, `setFlag('on_fire')`),
  animations (`swing`, `hurt`), a per-entity **state bag** (`set` / `get` / `has` — the entity's memory
  between ticks), spatial queries (`nearestPlayer(radius)`, `distanceTo(anything)`), and the two hooks
  that make it programmable: **`onTick(fn)`** — the mob's brain, run every tick with the entity as its
  argument — and `onInteract(fn)`. Plus `entities.all()` / `near(x, y, z, r)` / `count()` / `removeAll()`.
  The server still simulates nothing: behaviour is whatever the script writes, which is the illusionist
  model taken to its conclusion.
  **Entities are owned by the plugin that spawned them** and despawned on unload or hot-reload, following
  the same lifecycle as its commands, tasks and taps — this also fixes a leak, since a script that
  spawned puppets previously left them standing after a reload with callbacks bound to a torn-down scope.
  Ticking costs **one scheduled task per plugin**, not per entity, started lazily on the first `onTick`;
  every callback runs on the game-loop thread under the usual script lock, and a throwing handler is
  logged without stalling the others. `/guard` and `/despawn` demos in `plugins/example.js`. Tested in
  `ScriptEntitiesTest` (spawn + dress + drive from a tick handler, reload despawns the old bodies, a
  handler stops with the entity it drives).

- **Armor on avatars, cross-edition.** A worn helmet / chestplate / leggings / boots now render on the
  wearer's avatar for every other player, on all four protocols. Visual only — the illusionist rule
  holds, the server simulates no protection. New `ArmorSlot` enum (head-to-feet, each carrying its
  backing inventory slot 36-39 — past the 36 storage slots, which is why `setArmor` exists rather than
  the storage-slot `setItem`), plus `Player.getArmor(slot)` / `setArmor(slot, state)` / `clearArmor()`.
  The version split that makes this per-protocol: **JE 1.8** numbers Entity Equipment `0` held then
  `1-4` feet-to-head, while **1.9+ (1.12.2)** inserted the off-hand at `1` and pushed armor to `2-5` —
  ground truth ViaVersion's own 1.9→1.8 slot transform. Both **PE eras** take all four pieces in one
  `MobArmorEquipment` (1.1.5 `0x20`, 0.14 `0xa8` with the era's big-endian eid), head-to-feet, so the
  core's slot order maps straight through. The **wearer's own copy is a different packet**: JE reads its
  own armor from the inventory window's armor slots, but a Bedrock client shows the wearer nothing
  unless the four pieces are pushed to its dedicated armor window (`ContainerSetContent` to window
  `0x78`, per PMMP's own `sendArmorContents`) — caught on a live PE client, which saw everyone's armor
  but its own, and now covered by `sendOwnArmor`. Relays fire from the same equipment hook as the held item
  (now a two-method `EquipmentListener`, so a hotbar switch doesn't re-send armor and vice versa), a
  freshly spawned avatar arrives already dressed, and a **creative player dragging armor into their
  own slots 5-8 dresses their avatar for everyone** (that path already reached the core inventory — it
  just wasn't relayed). 0.14 routes each piece through its crash gate. `/armor` demo in
  `plugins/example.js`. Tested in `PeHeldItemEncodingTest` (both PE wire shapes) and
  `CorePlayerIdentityTest` (wear / read back / clear, hooks stay independent).

- **Held-item tracking — the item in your hand is visible to everyone, cross-edition.** Every edition
  already reported hotbar switches; the reports were being dropped (JE 1.8 swallowed them outright,
  1.12.2 and PE 1.1.5 kept a local view for placement only). They now funnel through one
  `onHeldSlotChange` callback into `CorePlayer`, and the held stack is drawn on every *other* client's
  copy of the avatar: **JE** Entity Equipment (1.8 `0x04` — slot is an `i16`; 1.12.2 `0x3f` — a varint;
  slot 0 = main hand), **PE 1.1.5** MobEquipment `0x1f` and **PE 0.14** MobEquipment `0xa7` (big-endian
  eid, and only two trailing slot bytes — the era predates 113's windowId). A player draws their own
  hand from their inventory, so the holder is skipped. The visual also refreshes when the *stack*
  changes rather than the slot (mining a block, placing one, a script `setItem`) — `CorePlayer` fires a
  `HeldItemListener` from its sync path when the change touches the held slot, following the
  `CoreWorld.BlockChangeListener` precedent — and a freshly spawned avatar is shown holding whatever it
  already holds. API: `Player.getHeldItemSlot()` and `getHeldItem()`, both reachable from scripts (in
  `/test`). 0.14 sends the item through its usual crash gate, so an id it can't render becomes an empty
  hand instead of a crash. Tested in `PeHeldItemEncodingTest` (both PE wire shapes) and
  `CorePlayerIdentityTest` (slot tracking, hook fires only for the hand).

- **Weather, cross-edition.** One `Weather` enum (`CLEAR` / `RAIN` / `THUNDER`) on the world — pure
  client-side scenery in the illusionist model: no timer, no simulation, it stays until set again.
  `World.getWeather()` / `setWeather(...)` broadcast a change to every player (deduped — setting the
  same sky twice sends nothing) and a **late joiner walks into the current sky** (pushed from
  `CoreWorld.addPlayer`; clear needs no push, it's the client default). Per edition: **JE** (1.8 +
  1.12.2, same reason semantics) uses Change Game State — begin/end rain plus explicit strengths, since
  on these clients reason 7 is `setRainStrength` and 8 is `setThunderStrength`: full 1.0 makes the
  change visible instantly instead of the slow vanilla ramp (the first cut sent 7 = 0 after begin-rain,
  which silently killed the rain it had just started — caught on a live client); **PE both eras** use the
  LevelEvent 3001-series (start/stop rain/thunder — weather landed in PE 0.12, and the ids are present
  in both era's packet tables), no coordinates, intensity 10000 on starts. New `/weather
  <clear|rain|thunder>` command (`jedrock.command.weather`, console-friendly, no argument reports the
  state); scripts get `world.getWeather()` / `world.setWeather('rain')` (case-insensitive, invalid
  names list the valid set). Rain renders as snow in cold biomes — the client decides by biome, which
  is exactly the illusion working. Tested in `WorldWeatherTest` (broadcast, dedupe, late-joiner push).

- **`Player.getPing()` + chat display names.** Two small QoL API surfaces. *Ping:* JE measures the
  keep-alive round trip (the ~15 s cadence doubles as a ping probe, recorded when the response lands
  — the 1.8 handler previously swallowed the response silently); both Bedrock eras read RakNet's own
  per-session estimate for free; `-1` until known. *Display name:* `getDisplayName()` /
  `setDisplayName(name)` — a chat-facing nickname that may carry the unified `{color}` / Markdown
  markup. The chat format's `%name%` now renders the display name; a script-set nickname is authored
  text and renders **raw** (like the group prefix — coloured nicks are the point), while the default
  (the real, client-controlled name) stays escaped, so the injection guard holds exactly where the
  input is untrusted. Identity is untouched everywhere else — commands, tab list, joins, permissions
  all use the real name. Scripts reach both for free; `/nick` demo + ping in `/test` in
  `plugins/example.js`. Tested in `CorePlayerIdentityTest`.

- **PE 0.14 creative inventory grew ~100 → ~209 states.** The palette now mirrors the block half of
  PocketMine-MP's own 0.14 creative list (`resources/creativeitems.json` in the 0.14 tree) — the exact
  207 id/meta entries PMMP served to real protocol-45 clients, so every one is battle-tested against
  the client generation that crashes on an unknown id — plus the two extras (farmland, note block)
  validated against a real client earlier. New in the menu: stone variants (granite / diorite /
  andesite + polished), stone bricks, podzol, redstone ore, all twelve stairs, stone + wooden slabs,
  fences with wood-type metas + all six fence gates, both trapdoors, cobblestone walls, lily pad,
  vines, ladder, torch, flowers, saplings, tall grass / fern / dead bush, mushrooms, hay bale, all
  sixteen carpets, monster spawner, enchanting table, stonecutter, end portal frame and the anvil.
  Since `supports()` derives from the palette, the 0.14 **chunk serializer now renders these blocks
  too** when a Java / 1.1.5 player places them, instead of filtering them to air — cross-play parity
  widened. (The wood-slab legacy hole at `44:2` is skipped exactly as PMMP skips it.) Pinned in
  `Pe014BlocksTest` (≥320 states, carpet/fence metas, all stairs, the 44:2 hole, exotics still rejected).

- **PE 0.14 weapons, tools & items + a working inventory API.** Two finds fixed together:
  `PeSession014` never implemented `setInventory` / `setInventorySlot` (silent no-op defaults), so the
  whole inventory API — scripts' `giveItem` / `setItem` / `clearInventory`, survival mining pickup, the
  `/inv` demo — did nothing on a 0.14 client. Both now speak the PMMP wire shape: window-0
  `ContainerSetContent` with the 36 storage slots **and the 9-entry `i + 9` hotbar-link table** (without
  which the client leaves its hotbar HUD empty), and single-slot `ContainerSetSlot` for live HUD
  refreshes (`Pe014InventoryEncodingTest`). On top of that the 0.14 creative menu gains the **item half
  of PMMP's own 0.14 list** (`Pe014Items`, ~114 entries): all five tool/weapon tiers, the four armor
  sets, bow/rod/shears/clock/compass, food, and materials — minus exactly one PMMP entry, the spawn egg
  (`383:17`), whose meta overflows the canonical 4-bit state. (1.1.5 already had its richer item menu.)
  Items are **inert** (held / stacked / chest-stored; no durability, crafting or eating), and a
  "placeable" item (door, bed) doesn't place — the 0.14 use-item path now also **corrects the client's
  optimistic ghost** with the true block instead of silently dropping the attempt. Every item-shaped
  slot sent to 0.14 passes one crash gate (`safeState`): a block outside the renderable set or an item
  outside the classic set (a 1.1.5 ender pearl in a shared chest, a JE elytra) renders as an empty slot
  instead of crashing the old client.

- **Sounds and particles, cross-edition.** Canonical `Sound` (12: click, door, fizz, bow, teleport,
  anvils, explode, levelup, pop, orb, note) and `Particle` (20: poof, huge explosion, bubble, splash,
  crit, smokes, drips, villager moods, note, portal, enchantment, flame, lava, redstone, snowball,
  slime, heart) enums in the API, each mapped per protocol from ground truth: **JE** Named Sound Effect
  (1.8 `0x29` era names like `random.levelup`; 1.12.2 `0x19` + category with `entity.player.levelup`
  names) and World Particles (1.8 `0x2a` / 1.12.2 `0x22`, one shared pre-flattening id table —
  minecraft-data), **PE 1.1.5** LevelEvent `0x1a` (1000-series sound ids, data = pitch×1000 per PMMP
  `GenericSound`; particles as `0x4000|type`) plus LevelSoundEvent `0x19` for the three sounds that
  live only there (explode 45 / levelup 55 / note 72), and **PE 0.14** LevelEvent `0xa2` (big-endian
  short/floats/int) with its own shorter tables — sounds 0.14 predates fall back to the closest
  available id, documented per case. API: `World.playSound(sound, x, y, z[, volume, pitch])` /
  `spawnParticle(particle, x, y, z[, count, spread])` (broadcast to the world) and
  `Player.playSound(sound[, volume, pitch])` (a private ding at the player). JE draws a burst from one
  packet; the PE eras get one packet per particle, capped at 32 per burst. Scripts:
  `world.playSound('levelup', x, y, z)` / `world.spawnParticle('heart', x, y, z, 12, 0.8)` with
  case-insensitive names and an error listing the valid set; `/fx [boom]` demo in `plugins/example.js`.
  Byte-verified in `PeEffectsEncodingTest` (113 LevelEvent / LevelSoundEvent, 0.14 big-endian body,
  mapping completeness both eras) and `JeEffectsTest` (names both eras, ids below the data-carrying
  range, particle body layout).

- **World-interaction API — programmatic edits that render live, cross-edition.** The world can now be
  edited from code exactly like a player edits it: `CoreWorld` gained a **block-change listener** that the
  server registers after bake/load, so **every** `setBlockId` write — a player's edit, a command, a script —
  is pushed to each online client in its own protocol (JE Block Change / PE UpdateBlock). Player edits ride
  the same path now (the manual broadcast loop in `onBlockChange` is gone — one broadcast path). The API
  grew `Server.getDefaultWorld()`, `World.setBlock(x, y, z, id, meta)` (sugar over the packed state),
  `World.fill(corner, corner, state)` (inclusive box, skips unchanged cells, returns the changed count) and
  `World.isInsideBounds(x, z)` (default true — an unbounded world; `CoreWorld` overrides with the finite
  edge). Scripts get a **`world` global**: `getBlock` / `getMeta` / `setBlock(x, y, z, id[, meta])` /
  `fill(...)` / `getHighestY` / `getBiome` / `getSpawn` / `setSpawn` / `isInside`, with id-range validation.
  Defensively, `CoreWorld.setBlockId` now **drops writes outside the finite bounds or the 0–255 Y range** at
  the storage boundary (no listener fire, no dirty flag, no section allocation), so no API path can grow the
  world past its edge. `plugins/example.js` gained `/deck` (glass platform via `fill`) and `/pillar` (wool
  via `setBlock`). Tested in `WorldInteractionTest` (write+notify, air-not-sentinel on break, dropped
  out-of-bounds writes, any-corner-order fill, refill-changes-nothing).

- **Scripting inventory API.** A script (or any `Player` caller) can now read and write the player's
  inventory, not just `giveItem` one block: `getInventorySize()`, `getItem(slot)` / `getItemCount(slot)`,
  `setItem(slot, state, count)`, `giveItem(state, count)` (returns how many fit), `removeItem(state, count)`
  (returns how many went), `countItem(state)`, `hasItem(state)` and `clearInventory()`. Items are the
  canonical `(id << 4) | meta` state used everywhere, over the 36 storage slots (0-8 hotbar, 9-35 main);
  every change syncs to the client. Server-authoritative, so it's meaningful in survival (a creative client
  manages its own inventory). `plugins/example.js` gained an `/inv` demo. Tested in `CorePlayerInventoryTest`.

- **Player-facing UI — titles, subtitles and the action bar, cross-edition.** `player.sendTitle(title,
  subtitle[, fadeIn, stay, fadeOut])`, `sendActionBar(text)` and `clearTitle()` — a big centred title with a
  subtitle, or a line just above the hotbar, authored in the unified `{color}` markup and rendered per
  edition. **JE** uses the Title packet (**1.8 id 0x45**, **1.12.2 id 0x48** — the first ship guessed 0x4B, a
  different packet, which disconnected the client; the id is now ground-truthed against
  PrismarineJS/minecraft-data) with the action bar via a chat message at position 2. **PE 1.1.5** uses the
  native **SetTitle (0x59)**, byte-verified against PocketMine-MP at protocol 113 (type / text / fadeIn /
  stay / fadeOut). **PE 0.14** predates the packet, so it falls back to chat lines. Fits the illusionist
  model — the server just asks the client to draw text. Tested (`PeSetTitleEncodingTest`, and CorePlayer
  render + timing delegation).

- **QoL API surface.** `Server.getPlayer(UUID)`; `Player.teleport(x, y, z)` and
  `teleport(x, y, z, yaw, pitch)` (keep or set facing, current world); `World.getHighestBlockY(x, z)` (the
  topmost non-air block, handy for a safe drop-in); and `World.setSpawnLocation(...)` (a movable spawn,
  re-bound to the world). Small, no-new-packet wins for scripts and commands.

- **Native group permission system.** On top of operators sits a real permission system (`permissions.txt`):
  named **groups** with inheritance, a **default group** new players fall into, and permission **nodes** with
  `*` / `a.b.*` wildcards and `-node` **explicit deny** (deny wins over any grant). Each group carries an
  optional chat **prefix** (`{red}[Admin] `), shown through the new `%prefix%` slot in the chat format. A
  `/perm` command manages it all live — create/delete groups, grant or deny nodes, set inheritance, prefix
  and the default group, assign players — and every change persists. `Player` gained `getPrefix()`; scripts
  reach `player.isOp()` / `hasPermission(node)` / `getPrefix()` for free (Rhino reflection). Resolution is a
  cycle-safe union of a player's groups (or the default) with wildcard + deny matching, op as a super-user
  override. Unit-tested (`PermissionManagerTest`: wildcards, deny-beats-grant, inheritance + cycles, default
  fallback, prefix, full persist/reload — which caught a trailing-space-in-prefix loss on load).

- **A single command surface, unified console and operators.** Commands are now written against a
  `CommandSender` (a player *or* the console), so the same command runs from chat and from stdin: the console
  runs any `/`-command as an **operator** (`op alice`, `gamemode creative bob`), and a player-only command
  (`/spawn`, `/tp`) is refused there with a clear message. **Operators** persist to `ops.txt` — an op holds
  every permission, and the console is always an op, so the first `/op` is granted from the console. Commands
  can declare a `permission()` node and `playerOnly()`; the manager gates on both, and `/help` hides what the
  sender can't run. New `/op`, `/deop` commands (work on offline names). All 15 built-ins moved onto the new
  sender; script commands run from the console too. Tested in `OpListTest` and `CommandTest`.

- **Scripting API grew four capabilities** (each hot-reload-safe, tracked per plugin): a **scheduler**
  (`scheduler.runLater` / `runTimer`, plus `setTimeout` / `setInterval` in ms) so a script defers work
  without counting ticks; **script commands** (`commands.register`) that show up in `/help` with a handler
  getting `(sender, args)`; a **raw packet API** (`packets.onReceive` / `onSend` / `send`) that taps and
  injects packets on all four protocols with cancel + inject (no byte rewrite); and **custom events**
  (`events.emit(name, data)` + `events.on(anyName, …)`) alongside the built-ins, now 25. Two more built-in
  events landed too — `InventoryClickEvent` and `PlayerKickEvent` — plus `Server.dispatchCommand` and
  `Player.getAddress`. The script globals are now six: `server` / `events` / `scheduler` / `commands` /
  `packets` / `console`.

- **Script plugins — the scripting layer lands (Rhino).** The platform's whole point: custom gameplay now
  lives in hot-reloadable JavaScript, not the compiled core. Drop a `.js` in `plugins/` and it loads on
  start; save an edit and it reloads within a second, no restart. A script gets three globals — `server`,
  `events`, `console` — and wires behaviour with `events.on('PlayerJoin', e => …)`, the handler receiving
  the real Java event to read and mutate (`e.setCancelled(true)`, `e.setMessage(...)`). All 23 events are
  scriptable by friendly name (`EventTypes`). Built on **`rhino-runtime` 1.7.13** — pure Java, ~1.5 MB, zero
  transitive deps: the lightweight pick over GraalJS (tens of MB incl. ICU4J), in keeping with the project's
  few-deps ethos, and it lives only in `core` so the `api` stays runtime-neutral. Rhino runs interpreted
  (no per-script class generation, clean hot reload) with ES6 syntax (arrow functions, `let`/`const`); a
  `ClassShutter` sandbox keeps scripts to Jedrock's classes and a safe JDK slice (best-effort guard rails,
  not a security boundary — plugins are operator-installed). All script execution is serialized under one
  lock (Rhino isn't thread-safe; events post from several threads). A `plugins` console command lists them
  and `plugins reload` forces a reload. Sample `plugins/example.js` included. Unit-tested end-to-end
  (`PluginManagerTest`: a real script cancels/edits a posted event, hot-reloads, tears down, survives a
  throwing handler) and smoke-verified in a live server (the sample loads its 4 listeners on start).

- **Gate, inventory and lifecycle events — breadth across new categories.** Five more, deliberately spread
  across shapes the model didn't cover: `PlayerLoginEvent` — the connection *gate*, fired before any state
  is created (carries identity, not a `Player`, since there isn't one yet; cancel with a kick reason for a
  whitelist / ban, distinct from the post-setup `PlayerJoinEvent`); `PlayerPickupItemEvent` — cancel a
  survival mining pickup (the block still breaks, the item isn't collected — there are no item entities to
  drop); `ServerStartEvent` / `ServerStopEvent` (`event.server`) — one-time plugin setup / teardown, fired
  while the world and players are still alive; and `WorldSaveEvent` (new `event.world`) — flush world-tied
  state before each autosave and the shutdown save. Tested in `PlayerEventsTest`. 215 tests green.

- **Lifecycle + teleport events — and a scriptable heartbeat.** Four more: `ServerTickEvent` — a whole new
  category, fired once per loop tick (gated so an idle server pays nothing), the thing a script hangs "every
  N ticks" work on without polling; `PlayerRespawnEvent` (redirect where a dead player reappears — a bed, a
  lobby); `PlayerUseItemEvent` (completes the pose trio with sneak / sprint); and `PlayerTeleportEvent` (veto
  or redirect a `/tp` / `/spawn`). `teleport()` now returns whether it applied and shares a private
  `reposition()` with the respawn path — so a cancelled teleport can never strand a dead player (respawn
  routes through `PlayerRespawnEvent`, not the vetoable teleport). Tested in `PlayerEventsTest`.

- **More events — the model widened to cover damage, commands, poses and mode.** Building on the engine,
  seven more cancellable events, each routed through the core so cancelling actually changes behaviour:
  `PlayerDamageEvent` (a `DamageCause` — FALL / VOID / ATTACK / KILL — and a mutable amount; cancel or zero
  it for invulnerability) and `PlayerDeathEvent` (restyle or suppress the death broadcast), both threaded
  through the one `hurt()` funnel every source uses; `PlayerCommandEvent` (rewrite or veto a slash command
  before dispatch); `PlayerToggleSneakEvent` / `PlayerToggleSprintEvent` (cancel makes the server ignore the
  pose toggle); `GameModeChangeEvent` (veto or redirect a `/gamemode` switch — `setGameMode` now returns
  whether it applied, and the command reports honestly); and `PlayerInteractBlockEvent` (gate a right-click
  on any block — cancel suppresses both the chest open and the placement). Hot paths stay free via the
  `hasListeners` gate. Unit-tested (`PlayerEventsTest`) for the mutable-field contracts the wiring depends on.

- **The event engine — the platform API's foundation.** The `EventBus` went from a two-event stub to a real
  extension seam, and — the part that matters — the core now actually *routes its decisions through it*, so a
  listener can veto or reshape what the server is about to do. New: `EventPriority` (LOWEST…MONITOR, earliest
  proposes, latest decides), priority-ordered dispatch, `ignoreCancelled` listeners, precise removal via a
  returned `Subscription` handle, and a cached `hasListeners(type)` fast-path so a hot caller can skip
  *building* an event nobody wants. Cancellable events wired end-to-end: `PlayerChatEvent` (cancel suppresses;
  message + format are mutable), `BlockBreakEvent` / `BlockPlaceEvent` (cancel leaves the world untouched and
  reverts the editor's client), `PlayerMoveEvent` (cancel snaps the player back — posted only when something
  listens, so the hottest path stays free), `PlayerInteractEntityEvent` (cancel drops the hit before any
  damage or puppet callback). `PlayerJoinEvent` / `PlayerQuitEvent` moved onto shared `PlayerEvent` /
  `CancellablePlayerEvent` bases. Reflection-free and dependency-free, so it maps cleanly onto the planned
  GraalJS binding. Unit-tested (`EventBusTest`: priority order, cancellation skipping, cache invalidation,
  subscription removal, listener-fault isolation). Scripts / plugins are the next layer; this is the gate they
  wait on.

- **Puppets came alive — name tags, gaze, poses, animations — and holograms.** The puppet foundation grew
  from a mute mannequin into something that can act, without the server simulating a thing. New api:
  `PuppetEntity.setNameTag` (floating text, in the unified chat markup), `lookAt` / `setRotation` (the whole
  "it noticed me" illusion — trigonometry, not pathfinding), `setFlag` with a canonical `PuppetFlag` set
  (`ON_FIRE` / `INVISIBLE` / `SNEAKING`), and `swing()` / `hurt()`. `PuppetFlag` holds only flags that map to
  one bit on *every* edition — baby and glowing are deliberately out (Bedrock has a universal bit, Java
  doesn't, so they'd render on a phone and silently do nothing on a PC). The mapping lives in a new
  `EntityFlagIds`, `EntityTypeIds`' sibling and the rest of the entity tax.
  **Holograms** (`Hologram`, `Server.spawnHologram`, `CoreHologram`) are the purest illusion here: a name tag
  with the body taken away. Each line is its own invisible entity, and every edition plays the trick with what
  it has — Java hangs the text on an invisible marker armor stand, Bedrock (no armor stand in either legacy
  era) on an item entity with no item, which is PocketMine's own floating-text hack. Temporary `/hologram`
  (spawn / setline / remove / list) and new `/puppet` verbs (name / look / flag / swing / hurt) drive both
  until the API can. Unit-tested byte-for-byte (`EntityFlagIdsTest`, `PeTextLineEncodingTest`,
  `Mcpe014AddEntityTest`, `ClientboundSpawnMobTest`); the four metadata dialects were ground-truthed against
  ViaVersion (pinned per version) and PocketMine-MP at both PE eras.

### Fixed

- **Script command args now compare with `===`.** A command handler's `args` elements were JS String
  *objects* (`new String("clear")`), so a natural `args[0] === 'clear'` silently returned false (object vs
  primitive) and the branch was skipped — e.g. `/inv clear` did nothing. Args are now JS *primitive* strings
  (a `java.lang.String` is Rhino's primitive-string representation, copied into an `Object[]`), so
  `args[0] === 'x'`, `parseInt(args[0])`, `.join(' ')` and every string method all work.

- **PE 1.1.5 block-interaction "hallucinations" (ghost blocks and holes).** The retail protocol-113 client
  draws its own edits optimistically and, unlike 0.14, double-fires and desyncs, so a single click could
  leave a phantom second block or hole. Three parts, all client-verified: (1) a **debounce** collapses the
  real double-fire — placement's distinct-cell "staircase" and a creative break's `START` + `CONTINUE`
  stream on the same cell (`PeEditDebounce`, with tests). (2) Ground-truthed from a live debug log that the
  server actually applies just one edit — the extra block is a **client-only ghost**, and the client
  **ignores a standalone `UpdateBlock`** that contradicts one of its own cells (even with the PRIORITY flag),
  the same "trusts chunk data over standalone packets" trait that makes chests need a chunk tile. So the
  correction re-sends the affected **chunk**, and **trailing-edge** (one flush ~180 ms after edits settle,
  on the session event loop) — a mid-burst resend carried a stale state that resurrected a just-broken block
  and made breaking jerky. Covers server-rejected edits too, so ghost holes from spawn-protect / reach
  cancels also clear. Not perfect (1.1.5 stays experimental), but tolerable. A debug-gated
  `[PE] place/break gap=..ms` log — which pinned the diagnosis — stays for future tuning.

- **PE 0.14 `AddEntity` wrote its fields in the wrong order**, so every 0.14 puppet was malformed on the wire:
  the entity-links short was written *before* the metadata block, where protocol 45 puts metadata first and
  links last. Found by reading PocketMine-MP's `AddEntityPacket::encode` at `CURRENT_PROTOCOL = 45` rather
  than waiting on a client — this was the "pending live-client verification" note the previous entry left.
  Pinned by `Mcpe014AddEntityTest`. Two related traps are now encoded in the same place: a string *inside*
  0.14 metadata carries a **little-endian** length (every other 0.14 string is big-endian), and 0.14 keeps the
  name tag at index **2** with a separate show-name-tag byte at 3 — where 1.1.5 uses index 4 and folds
  visibility into flag bits, so 1.1.5's layout would have silently done nothing.

- **Puppet entities — the foundation before the platform API.** A puppet is a server-puppeteered visual
  entity (the base for mobs / NPCs / holograms), never simulated: the server spawns a visual, moves it and
  relays it cross-edition, and that's all. New api contracts — `EntityType` (a canonical, protocol-agnostic
  set), `PuppetEntity` (a handle to move / remove / hook interaction), and `Server.spawnPuppet` — plus the
  entity counterpart of the block palette (`EntityTypeIds`: canonical → per-edition numeric id; classic JE
  mob ids for 1.8 / 1.12.2, legacy MCPE ids for 1.1.5 / 0.14). `PlayerConnection` gained
  `spawnEntity` / `moveEntity` / `removeEntity`, implemented on all four editions (JE Spawn Mob 0x03 / 0x0F,
  PE AddEntity 0x0D / 0x98; despawn reuses the existing DestroyEntities / RemoveEntity wire). Core: a shared
  `EntityIds` allocator (players + puppets share one id space), `CorePuppet`, `PuppetRegistry`, and lifecycle
  relay in `JedrockServer` (spawn to all, show existing puppets to a joiner, move/despawn relay). An
  **interaction hook** reuses the existing cross-edition attack decode — hitting a puppet fires its
  `onInteract` callback (the seam the API will drive) and, as a demo, flashes it red on every client. A
  **temporary `/puppet` command** (spawn / move / remove / list) exercises it before the API lands.
  A **player-avatar puppet** (`EntityType.PLAYER`, a named NPC) is included: it renders through the same
  cross-edition avatar machinery real players use (a tab / player-list entry + spawn-player, and move /
  despawn via the avatar path) rather than spawn-mob — `/puppet spawn player <name>`.
  Unit-tested (`EntityTypeIdsTest`, `ClientboundSpawnMobTest`); the PE 1.1.5 / 0.14 AddEntity byte layouts
  are pending live-client verification.

- **More in-game and console commands.** The in-game set grew from four to fourteen, all authored once and
  advertised to Bedrock via the `AvailableCommands` manifest: `/help [cmd]` (now details one command),
  `/list` (online players + edition), `/tps` (server health), `/say`, `/me`, `/msg` (private message),
  `/tphere`, `/tpall`, `/heal`, `/kill`, `/clear` — alongside the existing `/gamemode`, `/tp`, `/spawn`.
  The stdin **console** gained `say`, `kick <player> [reason]`, and `kill` / `heal <player>`. New public
  `JedrockServer` helpers back them (`broadcast`, `kill`, `heal`). Unit-tested (`CommandTest` guards).

- **Chests on Bedrock 1.1.5 (click-transfer).** The retail 1.1.5 client crashes on a real chest window, so
  chests there now use a click-transfer: a right-click withdraws the first stack, a sneaking right-click
  deposits the held hotbar slot (tracked from `MobEquipment`). Works in survival and creative (creative
  deposits the held item without consuming it). New `ConnectionListener.onChestInteract` hook.

- **Interactive inventories and chests (Java Edition).** The player inventory became a real,
  server-authoritative container instead of a display: a `Container` abstraction (shared by players and
  blocks) plus `InventoryClick` — left / right / shift click semantics (pick up, place, merge, split,
  swap, quick-move) with a server-tracked `Cursor`. The player model grew to 41 slots (added armor +
  off-hand), mapped onto each edition's window layout. **Chests** are a new placeable block (id 54) backed
  by a 27-slot container keyed by position: right-click opens the window (`onUseBlock` suppresses the
  placement the same packet would be), items move between the chest and the player inventory (including
  shift quick-transfer), and closing returns the cursor. Works in **survival and creative** — the creative
  inventory is now mirrored server-side (via Creative Inventory Action → `onCreativeSetSlot`) so a chest's
  player-inventory half is tracked. Chest contents **persist**: the level file is bumped to **v3** with a
  container section (only non-empty chests; positions + slots), and load stays back-compatible with v2
  worlds (they upgrade in place on the next save). New Java packets: Click Window (0x07 / 0x0E), Confirm
  Transaction, Open Window, generalized Set Slot (window id, for the cursor). Wired for **JE 1.12.2 and
  1.8**; the Bedrock port is next. The server only stores and moves items — no crafting/smelting
  simulation, no item entities (overflow simply vanishes). Unit-tested (`InventoryClickTest`,
  `JeInventoryCodecTest`, `LevelIOTest` chest round-trip).

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

- **World block storage is palette-compressed (large RAM cut).** `BlockStorage` stored every allocated 16³
  section as a `short[4096]` (8 KB); most baked sections hold only a handful of distinct states. An unedited
  section is now a `PalettedSection` — a small palette of distinct states plus the 4096 cell indices
  bit-packed at the smallest width that fits (2 bits ≈ 1 KB, 4 bits ≈ 2 KB), a single-state section being
  the degenerate size-1 palette. The first differing write promotes the section back to a mutable
  `short[4096]`, so the rare edited sections still pay full price but the thousands of static ones don't.
  Reads go through `cellOf` / `readSection`, so the hot path is one extra dispatch per section and MSPT is
  unchanged (measured: idle MSPT flat, well under the 20 TPS budget). **Measured on a real 48×48 world:
  retained heap ~13 MB after load (11 547/11 547 sections compressed), down from ~66 MB (uniform-only) and
  ~95 MB (uncompressed) for the block matrix.** On-disk level format untouched (v3): sections expand on save
  via one reused scratch buffer, so old worlds load unchanged. Pinned by `BlockStoragePaletteTest`.
- **Dead-code cleanup.** Removed the unused `PeBlockEditDecoder.decodeInventoryTransaction` /
  `decodeUseItem` / `skipInventoryAction` (protocol 113 has no InventoryTransaction packet — edits arrive
  via UseItem / PlayerAction), the constants they used in `McpeProtocol`, and the now-orphaned
  `McpeCodec.readItemId`. `PacketGuard.saneCount` coverage is retained.
- **Roadmap trimmed.** Reduced to the platform API (next) and puppet entities, with the remaining polish
  folded into one "final touch-ups" line, to keep the project's scope legible.

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

- **PE 1.1.5 survival hotbar stayed empty (mined items showed only with the inventory open).** The player
  window (`ContainerSetContent` to window 0) was sent as 36 slots with no hotbar links. PMMP sends
  `getSize() + getHotbarSize()` = **45 slots** (36 storage + 9 trailing air) followed by a **9-entry
  hotbar-link array** (each value `index + 9`); without the links the client fills storage but never wires
  up the on-screen hotbar. Now serialized PMMP-exact (`PeSession.writePlayerInventory`, pinned by
  `PePlayerInventoryEncodingTest`); `ContainerSetSlot` also matches PMMP (`hotbarSlot`/`selectSlot` = 0).

- **Chest item duplication on Bedrock 1.1.5 (both game modes).** In **creative**, deposit never consumed
  the (infinite) held item but withdrawal handed real items back — so a deposit→withdraw cycle minted
  items; creative withdrawal now just clears the chest stack (no player give), and deposit uses the honest
  held count instead of a forced 64. In **survival**, the client is inventory-authoritative and echoes a
  `ContainerSetSlot` after a deposit, which re-added the just-moved stack (item in the chest *and* back in
  the inventory); the server now ignores the client's window-0 echo in survival (it owns the inventory
  there — mining, placing and chest transfers all flow through it), closing the dupe.

- **A wrong Click Window id dropped 1.12.2 connections on creative interaction.** `ServerboundClickWindow`
  was mapped to 0x08 (which is Close Window at 1.12.2 — a 1-byte body); a real Close Window then over-read
  past the buffer (`readerIndex(1) + length(2) exceeds writerIndex(1)`) and killed the connection. Click
  Window is **0x07**; 0x08 is now handled as Close Window. Also gated window clicks to survival so a
  creative click never resyncs an empty server inventory over the client's creative hotbar.
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
