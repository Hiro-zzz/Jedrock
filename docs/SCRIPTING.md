# Scripting Jedrock

Custom logic lives in JavaScript, not in the compiled core. Drop a `.js` file into `plugins/`, and it
loads on start; save an edit and it reloads within a second, no restart. This is the whole extension
surface — there is no plugin jar, no build step, no API to compile against.

```js
// plugins/hello.js
events.on('PlayerJoin', function (event) {
    event.getPlayer().sendMessage('{gold}Welcome, {yellow}' + event.getPlayer().getName());
});
```

That is a complete plugin.

> The reference implementation of everything below is [`plugins/example.js`](../plugins/example.js) — a
> single file that exercises every hook, kept working because it is also how the surface gets tested.
> When this document and that file disagree, the file is right.

---

## Contents

- [The basics](#the-basics) · [The JavaScript dialect](#the-javascript-dialect) · [Blocks and items are numbers](#blocks-and-items-are-numbers) · [Text markup](#text-markup)
- Globals: [`events`](#events) · [`commands`](#commands) · [`server`](#server) · [`world`](#world) · [`worlds`](#worlds) · [`entities`](#entities) · [`scheduler`](#scheduler) · [`storage`](#storage) · [`regions`](#regions) · [`permissions`](#permissions) · [`items`](#items) · [`menus`](#menus) · [`packets`](#packets) · [`console`](#console)
- [Every event](#every-event) · [Custom events](#custom-events) · [Limits worth knowing](#limits-worth-knowing)

---

## The basics

**A file is a plugin.** Its name is its identity: `plugins/shop.js` is the plugin `shop.js`. Each one gets
its own scope, so two plugins can both declare `var count` without meeting.

**Hot reload.** A background watcher polls the folder (`plugins.reload-millis`, default one second). A
changed file is torn down and re-run: its listeners, commands and scheduled tasks are removed first, so a
reload never leaves two copies of a handler behind. Turn it off with `plugins.hot-reload=false`.

**`onDisable`** is called when your plugin is unloaded — by a reload, or by the server stopping. Define it
to clean up anything the server doesn't know you own:

```js
function onDisable() {
    console.log('going away');
}
```

Everything a script registered through a global — listeners, commands, timers, packet taps — is unregistered
for you. What isn't: things you handed to the *server*, because those outlive the script deliberately. A
world you created, a region, a saved scene, a value in storage — those are server state, and a reload does
not undo them.

**Errors don't take the server down.** A throw inside a handler is logged with your file name and line, the
handler carries on being registered, and the next event still fires. A file that fails to *load* is skipped
with the error; the rest keep working.

---

## The JavaScript dialect

The engine is [Rhino](https://github.com/mozilla/rhino) 1.7.13 — chosen over GraalJS because it is 1.5 MB
with no transitive dependencies, against tens of megabytes. It is not a browser and not Node: there is no
`require`, no `fetch`, no DOM, no file access.

What it does and doesn't take, probed against this exact build rather than guessed:

| Works | Doesn't |
|-------|---------|
| `let` / `const` | default parameters — `function f(a = 1)` |
| arrow functions — `x => x + 1` | spread / rest — `f(...args)` |
| template literals — `` `hi ${name}` `` | `class` |
| `for…of`, destructuring | |
| `JSON`, `Math`, `Date`, `RegExp` | |
| `Array.forEach/map/filter`, `Object.keys`, `String.trim` | |

Java is reachable through `Packages` when you need an enum the API takes:

```js
const CREATIVE = Packages.com.jedrock.api.player.GameMode.CREATIVE;
```

Most APIs take a string instead (`player.setGameMode('creative')`), and that is the intended way.

**A Java collection is not a JS array.** `server.getPlayers()` hands back a Java `Collection`, and the
difference bites exactly where you'd expect it not to — again, probed rather than assumed:

```js
server.getPlayers().size();                 // ✅ and .isEmpty(), .toArray(), .iterator()
Array.from(server.getPlayers());            // ✅ the usual way across — now it's a JS array
for each (var p in server.getPlayers()) {}  // ✅ Rhino's own loop
for (var p of server.getPlayers()) {}       // ❌ TypeError: not iterable
server.getPlayers().map(fn);                // ❌ Cannot find function map
```

Anything that returns a Java *array* — `worlds.all()`, `entities.all()`, `storage.keys()`, `regions.all()`
— arrives as a JS array with `.length`, `.map` and the rest. It is only collections that need crossing.

**Your code runs on the server's threads.** A handler that blocks — a long loop, a sleep — blocks the game
loop, and every player feels it. Do the work in a [`scheduler`](#scheduler) task if it is big.

---

## Blocks and items are numbers

There is one model for both, and it is an integer: `(id << 4) | meta`. Block 35 with meta 14 is red wool,
which is `35 * 16 + 14 = 574`. The same number describes the item in a hand and the block in the world,
which is why `world.setBlock` and `player.giveItem` speak the same language.

```js
world.setBlock(10, 64, 10, 35, 14);   // id and meta separately…
player.giveItem(35 * 16 + 14, 8);      // …or packed, when the API takes a state
```

Ids are the pre-1.13 numeric ones (the "legacy" set every target version of this server shares). `Blocks`
constants exist on the Java side; from a script, use the numbers.

---

## Text markup

Every string a player sees is written once in an edition-agnostic markup and rendered per client, so one
line looks the same on Java and Bedrock:

```js
player.sendMessage('{gold}Gold, {red}red, {reset}plain, **bold**, *italic*, __underline__, ~~strike~~');
```

Colours are `{black}` `{dark_blue}` `{dark_green}` `{dark_aqua}` `{dark_red}` `{dark_purple}` `{gold}`
`{gray}` `{dark_gray}` `{blue}` `{green}` `{aqua}` `{red}` `{light_purple}` `{yellow}` `{white}`, plus
`{reset}`. Hex colours are deliberately unsupported — no target version can render them.

---

## `events`

```js
events.on(name, handler)          // subscribe; the handler takes one event object
events.emit(name)                 // fire a custom event
events.emit(name, data)           // …with a payload
```

Every event object has `getName()`. Most carry a player — reached with `getPlayer()`, as every accessor
here is a method rather than a property — and many can be cancelled:

```js
events.on('BlockBreak', function (event) {
    if (event.getY() > 100) {
        event.cancel();           // the block stays, and the client is corrected
    }
});
```

A cancelled event means "this did not happen": the block is not broken, the message is not sent, the
teleport does not occur. See [Every event](#every-event) for what each one carries.

---

## `commands`

```js
commands.register('heal', function (sender, args) { … });
commands.register({ name, execute, description, usage, aliases, complete });
```

A registered command appears in `/help`, is advertised to Bedrock clients (which refuse to send a command
they were not told about), and is unregistered when the plugin reloads.

```js
commands.register({
    name: 'kit',
    description: 'Hand out a starter kit',
    usage: '/kit [player]',
    aliases: ['starter'],
    execute: function (sender, args) {
        if (!sender.hasPermission('myserver.kit')) {      // gating is yours to do
            sender.sendMessage('{red}Not for you.');
            return;
        }
        const target = args.length > 0 ? server.getPlayer(args[0]) : sender;
        if (!target) {
            sender.sendMessage('{red}No such player.');
            return;
        }
        target.giveItem(276);        // a diamond sword
        sender.sendMessage('{green}Done.');
    },
    complete: function (sender, args) {                   // tab-completion, Java clients
        return Array.from(server.getPlayers()).map(function (p) { return p.getName(); });
    }
});
```

The function key is **`execute`**, not `handler` — registering without one throws at load with that
message. There is no `permission` key: a script gates its own command with `sender.hasPermission(node)`,
which keeps the check where the reason for it is.

`sender` is a player or the console — both have `getName()`, `sendMessage()`, `isOp()` and
`hasPermission()`. A command that needs a body (a location, an inventory) should check.

---

## `server`

```js
server.getName()                  server.getVersion()          server.isRunning()
server.getCurrentTick()           server.getStatus()
server.getPlayers()               server.getPlayerCount()
server.getPlayer(nameOrUuid)      server.broadcast(message)
server.getDefaultWorld()          server.getWorld(name)
server.dispatchCommand(player, '/gamemode creative')
server.spawnPuppet(type, at)      server.spawnHologram(at, lines…)
```

`server.getPlayer` returns `null` when nobody is online by that name — check before using it.

---

## `world`

The world the server starts in. Every other world is the same object from [`worlds`](#worlds).

```js
world.getBlock(x, y, z)           world.getMeta(x, y, z)
world.setBlock(x, y, z, id)       world.setBlock(x, y, z, id, meta)
world.fill(x1,y1,z1, x2,y2,z2, id[, meta])     // returns how many cells changed
world.getHighestY(x, z)           world.getBiome(x, z)
world.getSpawn()                  world.setSpawn(x, y, z)
world.isInside(x, z)              // the world is finite — this is its edge
world.getChest(x, y, z)           world.hasChest(x, y, z)
world.playSound(name, x, y, z[, volume, pitch])
world.spawnParticle(name, x, y, z[, count, spread])
world.getWeather()                world.setWeather('rain' | 'thunder' | 'clear')
```

An edit lands in the shared world and reaches every client in it, cross-edition, as a single block update
— not a chunk resend. `fill` on a large box is one loop and a lot of packets; prefer it to your own loop,
and keep the box sane.

A chest is a real one — the block a player placed. `world.getChest(x, y, z)` gives its contents, which
persist in the level file.

---

## `worlds`

A view onto the server's set of worlds. Worlds are **server-owned**: one your script creates outlives the
script, a reload, and the process.

```js
worlds.all()                      worlds.names()          worlds.getDefault()
worlds.get(name)                  worlds.exists(name)     worlds.kindOf(name)
worlds.create(name, template[, seed])
worlds.getOrCreate(name, template)
worlds.unload(name)
worlds.defineTemplate(name, kind, size, decorate[, seed])
worlds.templates()
worlds.send(player, worldName)                     // travel, to that world's spawn
worlds.sendTo(player, worldName, x, y, z)          // travel, to a spot
worlds.of(player)                                  // the world they are standing in
```

Built-in templates: `overworld`, `nether`, `overworld_small`, `nether_small`, `bare`. A template is a
*recipe* (kind, size, decoration, optionally a fixed seed), not a saved world — two worlds from one
template share its rules and nothing else.

```js
events.on('ServerStart', function () {
    // Creating a world BAKES it, which blocks the calling thread for a moment — so do it here, once,
    // rather than while the file is being parsed.
    worlds.getOrCreate('arena', 'overworld_small');
});

commands.register('arena', function (sender) {
    worlds.send(sender, 'arena');
});
```

---

## `entities`

Puppets: everything visible that is neither a player nor a block. Mobs, props, floating labels — the same
primitive, driven by your code, because the server simulates no AI.

```js
entities.spawn(type, x, y, z)          entities.spawn(type, location[, name])
entities.spawnItem(state, x, y, z)     // a floating item
entities.spawnBlock(state, x, y, z)    // a floating block
entities.spawnText(text, x, y, z)      // a label
entities.all()   entities.count()   entities.near(x, y, z, radius)   entities.removeAll()
entities.group()                       // a scene: many props moved and saved together
entities.circle(count, cx, cy, cz, radius, placeFn)
entities.loadScene(name)   entities.scenes()   entities.removeScene(name)
entities.in('hell')                    // the same object, pointed at another world
```

One entity:

```js
const guard = entities.spawn('zombie', 10, 64, 10, 'Guard');
guard.setNameTag('{red}Guard');
guard.setHeldItem(276);
guard.setArmor('helmet', 306);
guard.setFlag('invisible', false);   // also 'on_fire', 'sneaking'
guard.lookAt(player);                  // or a location
guard.moveToward(player.getLocation(), 0.2);
guard.set('mood', 'angry');            // per-entity state, yours
guard.onTick(function () { … });       // called every tick while it lives
guard.onInteract(function (player) { … });
guard.nearestPlayer(16);               // null if nobody is close
guard.swing();  guard.hurt();          // animations
```

A scene is a group with a pivot — build it, then `save('name')` and it comes back with the world at boot,
without your script:

```js
const scene = entities.group();
scene.add(entities.spawnBlock(17, 0, 65, 0));
scene.setPivot(0, 65, 0);
scene.rotate(45);
scene.save('gate');
```

A saved scene has no brain: `onTick` belongs to the script that spawned it.

---

## `scheduler`

```js
scheduler.run(fn)                          // next tick
scheduler.runLater(fn, delayTicks)
scheduler.runTimer(fn, periodTicks[, initialDelay])
```

Twenty ticks are a second. The familiar names work too and take milliseconds, rounded to ticks:

```js
setTimeout(fn, 1000);   setInterval(fn, 5000);   clearTimeout(h);   clearInterval(h);
```

Every task is cancelled when the plugin reloads, so a timer can't outlive the code that made it.

---

## `storage`

What your plugin remembers between restarts. Values may be a string, number, boolean, object or array —
anything JSON-shaped. Not a function.

```js
storage.get(key[, fallback])   storage.set(key, value)   storage.has(key)
storage.remove(key)            storage.keys()            storage.size()   storage.clear()
storage.forPlayer(player)      // a namespace of your bucket, keyed by their uuid
```

```js
const homes = storage.forPlayer(player);
homes.set('home', { x: 10, y: 64, z: 10 });
```

Each plugin has its own bucket, keyed by file name; two plugins cannot see each other's keys. Writes are
kept in memory and saved with the world's autosave and at shutdown.

---

## `regions`

Named boxes with rules, enforced by the core on the paths that already run — no per-tick scanning. Regions
are **server-owned**: they outlive the script, a reload and a restart.

```js
regions.create(name, x1,y1,z1, x2,y2,z2)         regions.createIn(world, name, …)
regions.get(name)     regions.remove(name)     regions.all()     regions.count()
regions.at(x, y, z)   regions.atIn(world, x, y, z)   regions.of(player)
regions.allows(x, y, z, flag)                    regions.allowsFor(player, x, y, z, flag)
```

A region denies nothing until told to:

```js
const spawn = regions.create('spawn', -32, 0, -32, 32, 128, 32);
spawn.deny('build').deny('damage').deny('interact');
```

Flags are **`build`** (place and break), **`interact`**, **`pvp`**, **`damage`** and **`entry`**. `deny`
wins over `allow` where two regions overlap; the escape hatch is a per-flag bypass permission —
`spawn.getBypassPermission('build')` gives you the node to grant (`jedrock.region.spawn.build`), which is
what lets a builder work inside spawn protection. `jedrock.region.spawn.*` covers every flag of one region,
`jedrock.region.*` every region, and an operator is exempt everywhere.

Crossings are events, fired once per region actually entered or left:

```js
events.on('PlayerRegionEnter', function (event) {
    event.getPlayer().sendActionBar('{yellow}' + event.getRegion().getName());
});
```

---

## `permissions`

```js
permissions.has(player, node)        permissions.isOp(player)
permissions.forPlayer(player)        // add / remove nodes for one player
permissions.group(name)              permissions.createGroup(name)   permissions.deleteGroup(name)
permissions.getGroups()              permissions.getOps()
permissions.defaultGroup()           permissions.setDefaultGroup(name)
permissions.reload()
```

Nodes support wildcards (`myserver.*`), explicit denial, group inheritance and a chat prefix. An operator
holds every node.

---

## `items`

A custom item is a **name, lore and behaviour hung on an ordinary block or item state**. There is no
resource pack — that would break the promise that any unmodified client can join — so the model and
texture are always a vanilla one.

```js
const blade = items.define('frostblade', 276);   // a diamond sword
blade.setName('{aqua}Frostblade')
     .setLore(['{gray}Chills what it touches', '{dark_gray}— of the North'])
     .onUse(function (player, ctx) { … })
     .onHit(function (player, victim) { … })
     .onBreak(function (player, x, y, z) { … })
     .onHold(function (player) { … });

items.give(player, 'frostblade');
items.heldKey(player);               // the key of what they're holding, or null
```

**Identity is the key.** A stack carries `'frostblade'`, not a copy of the definition — so editing this
code and saving changes every existing one. State per *stack* (this particular sword's charges) has
nowhere to live yet; keep it in [`storage`](#storage) against the player.

---

## `menus`

A chest-shaped window whose slots are buttons.

```js
const menu = menus.create('{dark_gray}Shop', 3);   // 3 rows
menu.button(0, 264, '{aqua}Buy a diamond')
    .setItem(8, 166)
    .onClick(function (player, slot, state) {
        if (slot === 0) { player.giveItem(264); }
    });
menu.open(player);
```

On Java this is a real window. **On Bedrock it is not** — that client generation will not raise a chest
window at all, so the menu is delivered as a `/pick` list instead. Write the menu once; the core decides
how it arrives.

---

## `packets`

The escape hatch: raw bytes, on all four protocols, in and out.

```js
packets.onReceive(function (player, id, payload) {
    // return true to CANCEL — the core never sees the packet
});
packets.onSend(function (player, id, payload) {
    // return true to CANCEL — nothing reaches the socket
});
packets.send(player, id, payload);      // payload: an array of byte values
```

This runs on the network hot path for every packet, and the whole tap system is skipped entirely when no
script registers one. Register a tap and you are paying for it on every movement packet of every player —
so keep the handler small, and don't register one you don't need.

---

## `console`

```js
console.log(…)   console.warn(…)   console.error(…)
```

Prefixed with your file name, and written to `logs/latest.log` like everything else.

---

## Every event

Subscribe by name. **Cancellable** means `event.cancel()` prevents it.

| Event | Cancellable | Carries |
|-------|:-----------:|---------|
| `PlayerLogin` | ✅ | the gate — before any state exists. Cancel to refuse the connection |
| `PlayerJoin` | ✅ | `player`; `setJoinMessage(…)` to restyle or suppress the announcement |
| `PlayerQuit` | — | `player`; `setQuitMessage(…)` |
| `PlayerKick` | ✅ | `player`, reason |
| `PlayerChat` | ✅ | `player`, message |
| `PlayerCommand` | ✅ | `player`, the command line |
| `PlayerMove` | ✅ | `player`, from / to |
| `PlayerTeleport` | ✅ | `player`, from / to — `getTo()` can be redirected |
| `PlayerWorldChange` | ✅ | `player`, from world, destination |
| `PlayerRespawn` | — | `player` |
| `PlayerDeath` | — | `player`; `setDeathMessage(…)` |
| `PlayerDamage` | ✅ | `player`, amount, cause |
| `PlayerPickupItem` | ✅ | `player`, state |
| `PlayerUseItem` | ✅ | `player`, state |
| `PlayerHeldItemChange` | ✅ | `player`, slot |
| `PlayerArmorChange` | ✅ | `player`, slot, state |
| `PlayerInteractBlock` | ✅ | `player`, position |
| `PlayerInteractEntity` | ✅ | `player`, target |
| `PlayerToggleSneak` / `PlayerToggleSprint` | ✅ | `player`, the new state |
| `PlayerRegionEnter` / `PlayerRegionLeave` | ✅ | `player`, region |
| `InventoryClick` | ✅ | `player`, slot, button |
| `GameModeChange` | ✅ | `player`, old / new mode |
| `BlockBreak` | ✅ | `player`, position, state |
| `BlockPlace` | ✅ | `player`, position, state |
| `WeatherChange` | ✅ | world, new weather |
| `WorldSave` | — | world |
| `ServerStart` | — | everything is up; do one-time setup here |
| `ServerStop` | — | the world and players are still alive |
| `ServerTick` | — | every tick. Built only when something listens — an idle server pays nothing |

---

## Custom events

Any name that isn't built in is a channel of your own. Listeners share the data object, so one can change
what the next sees, and any of them can cancel it.

```js
// in one plugin
events.on('shop:purchase', function (event) {
    if (event.getData().price > 100) { event.cancel(); }
});

// in another
const event = events.emit('shop:purchase', { player: player, price: 250 });
if (!event.isCancelled()) { … }
```

---

## Limits worth knowing

- **Bedrock 1.1.5 will not open a chest window.** Menus and chests reach that client as a `/pick` list.
  0.14 and Java are the clean targets.
- **Forms are out on both Bedrock eras** — those clients predate them. A menu is a window or a list.
- **No mob AI, pathfinding, redstone or crafting** — by design. A puppet does what your `onTick` says and
  nothing else; that is the point of the primitive.
- **Entities can't be posed finely**: no armour stands on Bedrock, no per-entity scale, no limb posing,
  and head yaw isn't split from body yaw. A 0.14 client renders only the mobs it is old enough to know.
- **Scenes cost packets, not ticks.** A static prop runs no logic, but every joining player pays one spawn
  packet for it.
- **Per-stack item state has nowhere to live yet** — a custom item definition is shared by every stack that
  names it.
- **Your handler is on the server's thread.** Blocking blocks everyone.
