// ============================================================================
//  Jedrock test plugin — a live reference that exercises EVERY scripting hook.
// ============================================================================
//
// Drop a .js file in this folder and it loads on start; save an edit and it hot-reloads within a second,
// no restart. Nine globals are in scope:
//
//   server     — the server: players, worlds, broadcast, puppets, holograms, status.
//   world      — the shared world: getBlock / setBlock / fill / getHighestY / getBiome / spawn / weather /
//                playSound / spawnParticle. Edits render live on every client, cross-edition.
//   entities   — spawn and drive puppets, props and labels; group() builds a scene. See /guard and /decor.
//   menus      — menus.create(title, rows): a virtual chest (Java + 0.14 windows; 1.1.5 gets a /pick list).
//                onClick + button(slot,item,label) makes a button menu; setItem alone is a storage chest.
//   storage    — the only thing that survives a restart: get / set / has / remove / keys / size / clear,
//                plus forPlayer(p) for per-player state. Strings, numbers, booleans, objects and arrays.
//   events     — events.on(name, fn): subscribe to a built-in event (28 below) OR a custom, script-defined
//                one (any other name). Built-in handlers get the real Java event (getters/setters, cancel);
//                custom handlers get {getName, getData, cancel, isCancelled}. events.emit(name, data) fires a
//                custom event to every listener and returns it (read data / isCancelled back).
//   scheduler  — run code later, in ticks (20/sec): run / runLater / runTimer, each returning a handle with
//                .cancel(). setTimeout / setInterval / clearTimeout / clearInterval work too (milliseconds).
//   commands   — commands.register(name, fn)  OR  register({name, aliases, description, usage, execute, complete}).
//                The handler gets (player, args); args is a JS array. Shows up in /help. An optional
//                `complete(player, args)` returns tab-completion candidates (Java clients) — see /kit.
//   packets    — the raw cross-edition wire tap: onReceive(fn) / onSend(fn) see every packet
//                ({getId, getBytes, getLength, getProtocol, getPlayer, cancel}); send(player, id, bytes) injects.
//   console    — console.log / .warn / .error, prefixed with this file's name.
//
// Java enums are reached through `Packages`; shortcuts up front:
var EntityType = Packages.com.jedrock.api.entity.EntityType;   // PLAYER, ZOMBIE, PIG, CHICKEN, COW, SKELETON, CREEPER
var PuppetFlag = Packages.com.jedrock.api.entity.PuppetFlag;   // ON_FIRE, INVISIBLE, SNEAKING
var GameMode   = Packages.com.jedrock.api.player.GameMode;     // SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR
var Blocks     = Packages.com.jedrock.api.world.Blocks;        // AIR, STONE, GRASS, DIRT, COBBLESTONE, GLASS, CHEST…

console.log('test plugin loading — wiring every hook');

// Live counters, reported by /teststats.
var stats = { events: {}, packetsIn: 0, packetsOut: 0 };

// ============================================================================
//  STORAGE — the only state that outlives the process (and a hot-reload).
// ============================================================================

// Counting boots is the smallest thing that proves it: this survives a restart, the counters above don't.
storage.set('boots', storage.get('boots', 0) + 1);
console.log('this is boot #' + storage.get('boots'));

// /seen — per-player state, keyed by uuid so it follows a rename. Objects and arrays are stored as JSON
// and handed back as real values, so `.when` below is a number, not a string that looks like one.
commands.register('seen', function (player, args) {
    var mine = storage.forPlayer(player);
    var last = mine.get('lastSeen');                     // undefined on a first visit
    if (last) {
        var ago = Math.round((Date.now() - last.when) / 1000);
        player.sendMessage('{gray}Last seen {white}' + ago + 's{gray} ago, at '
            + last.x + ', ' + last.y + ', ' + last.z);
    } else {
        player.sendMessage('{gray}First time here — noted.');
    }
    var at = player.getLocation();
    mine.set('lastSeen', { when: Date.now(), x: Math.round(at.x()), y: Math.round(at.y()), z: Math.round(at.z()) });
    mine.set('visits', (mine.get('visits', 0)) + 1);
    player.sendMessage('{gray}Visits: {white}' + mine.get('visits'));
});

// /forget — clears just this player's slice; the plugin's own keys (like 'boots') are untouched.
commands.register('forget', function (player, args) {
    storage.forPlayer(player).clear();
    player.sendMessage('{gray}Forgotten.');
});
function bump(name) { stats.events[name] = (stats.events[name] || 0) + 1; }

// ============================================================================
//  EVENTS — all 23. Each bumps a counter; most also log or act.
// ============================================================================

// --- Connection lifecycle ---
events.on('PlayerLogin', function (e) {                 // before the player object exists; can gate the connection
    bump('PlayerLogin');
    console.log('login attempt:', e.getUsername(), 'from', e.getAddress(), 'uuid', e.getUniqueId());
    // e.setKickReason('nope'); e.setCancelled(true);   // refuse the connection
});

events.on('PlayerJoin', function (e) {                  // joinMessage is the broadcast announcement (null/'' hides it)
    bump('PlayerJoin');
    e.getPlayer().sendMessage('{gold}Welcome, {white}' + e.getPlayer().getName() + '{gold}! Try {white}/test');
    e.setJoinMessage('{green}+ {white}' + e.getPlayer().getName());   // restyle the announcement
});

events.on('PlayerQuit', function (e) {                  // quitMessage is the broadcast announcement
    bump('PlayerQuit');
    e.setQuitMessage('{gray}- ' + e.getPlayer().getName());
});

// --- Chat & commands ---
events.on('PlayerChat', function (e) {                  // mutable message + format
    bump('PlayerChat');
    if (e.getMessage() === 'ping') e.setMessage('pong');
});

events.on('PlayerCommand', function (e) {               // the '/…' line without the slash; rewritable
    bump('PlayerCommand');
    console.log(e.getPlayer().getName(), 'ran /' + e.getCommand());
});

// --- Movement (high-frequency: counted, not logged) ---
events.on('PlayerMove', function (e) {                  // from / to Locations; cancel snaps back
    bump('PlayerMove');
});

events.on('PlayerTeleport', function (e) {              // from / to; setTo redirects; cancellable
    bump('PlayerTeleport');
    console.log('teleport', e.getPlayer().getName(), '->',
        e.getTo().getBlockX() + ',' + e.getTo().getBlockY() + ',' + e.getTo().getBlockZ());
});

events.on('PlayerRespawn', function (e) {               // mutable respawn location
    bump('PlayerRespawn');
});

// --- Combat & survival ---
events.on('PlayerDamage', function (e) {                // cause (FALL/VOID/ATTACK/KILL) + mutable amount
    bump('PlayerDamage');
    console.log(e.getPlayer().getName(), 'took', e.getAmount(), 'from', e.getCause().name());
    // e.setAmount(0); or e.setCancelled(true) to negate the hit
});

events.on('PlayerDeath', function (e) {                 // mutable/suppressible death message
    bump('PlayerDeath');
    e.setDeathMessage('{red}' + e.getPlayer().getName() + ' died ({gray}' + e.getCause().name() + '{red})');
});

events.on('PlayerInteractEntity', function (e) {        // target entity id; cancellable
    bump('PlayerInteractEntity');
});

events.on('PlayerPickupItem', function (e) {            // canonical item state; cancel to refuse the pickup
    bump('PlayerPickupItem');
});

events.on('InventoryClick', function (e) {              // survival inventory click; slot/button/shift; cancellable
    bump('InventoryClick');
    // e.setCancelled(true) to lock a slot; the server re-syncs so the client reverts.
});

events.on('PlayerKick', function (e) {                  // before a kick; cancellable, reason rewritable
    bump('PlayerKick');
    console.log('kicking', e.getPlayer().getName(), '—', e.getReason());
    // e.setCancelled(true) to veto, or e.setReason('...') to rewrite.
});

// --- Poses ---
events.on('PlayerToggleSneak', function (e) { bump('PlayerToggleSneak'); });   // e.isSneaking()
events.on('PlayerToggleSprint', function (e) { bump('PlayerToggleSprint'); }); // e.isSprinting()
events.on('PlayerUseItem', function (e) { bump('PlayerUseItem'); });           // e.isUsing()

events.on('GameModeChange', function (e) {              // from / newGameMode; setNewGameMode; cancellable
    bump('GameModeChange');
    console.log(e.getPlayer().getName(), e.getFrom().name(), '->', e.getNewGameMode().name());
});

// --- Equipment: what a player wears and what they hold ---
events.on('PlayerArmorChange', function (e) {           // slot + previous/next state; cancellable
    bump('PlayerArmorChange');
    // Fires wherever the piece came from: a creative drag, a survival window click, or setArmor from
    // code. Cancelling puts the slot back and corrects the client.
    if (e.getNext() !== 0 && e.getSlot().name() === 'HELMET') {
        e.getPlayer().sendMessage('{gray}Nice hat.');
    }
});

events.on('PlayerHeldItemChange', function (e) {        // previous/new slot + previous/new item; cancellable
    bump('PlayerHeldItemChange');
    // Only a real hotbar switch fires this — not the stack changing inside the slot you already hold.
    // Cancelling makes the server refuse to believe the switch (the client's own hotbar still moves).
    // e.g. lock everyone to slot 0 while a minigame runs:
    // if (locked) e.setCancelled(true);
});

// --- Blocks ---
events.on('BlockBreak', function (e) {                  // x/y/z + canonical state; cancellable
    bump('BlockBreak');
    if (Math.abs(e.getX()) < 3 && Math.abs(e.getZ()) < 3) {     // protect spawn column
        e.setCancelled(true);
        e.getPlayer().sendMessage('{red}Spawn is protected.');
    }
});

events.on('BlockPlace', function (e) {                  // x/y/z, state, replacedState; cancellable
    bump('BlockPlace');
});

events.on('PlayerInteractBlock', function (e) {         // right-click a block (x/y/z/state); cancellable
    bump('PlayerInteractBlock');
});

// --- Server & world lifecycle ---
events.on('ServerStart', function (e) { bump('ServerStart'); console.log('server started'); });
events.on('ServerStop',  function (e) { bump('ServerStop');  console.log('server stopping'); });
events.on('ServerTick',  function (e) { bump('ServerTick'); });   // e.getTick(); fires 20×/sec — counted only
events.on('WorldSave',   function (e) { bump('WorldSave'); console.log('world saved:', e.getWorld().getName()); });

var Weather = Packages.com.jedrock.api.world.Weather;   // the enum itself, for reading and redirecting

events.on('WeatherChange', function (e) {               // from / to; setTo redirects; cancellable
    bump('WeatherChange');
    // Every way in lands here: /weather, world.setWeather, or the Java api. Nothing has been sent to a
    // client yet, so cancelling leaves no trace — and setTo redirects instead of refusing.
    if (e.getTo().name() === 'THUNDER') {               // strings from Java compare with === as you'd expect
        e.setTo(Weather.RAIN);                          // no storms on this server
    }
    console.log('sky:', e.getFrom().name(), '->', e.getTo().name());
});

// ============================================================================
//  SCHEDULER — ticks (20/sec). Handles have .cancel().
// ============================================================================

scheduler.runTimer(function () {                        // every 60s: a heartbeat
    console.log('alive at tick', server.getCurrentTick(), '—', server.getPlayerCount(), 'online');
}, 20 * 60);

events.on('PlayerJoin', function (e) {                  // a one-shot 3s after join (if still online)
    var player = e.getPlayer();
    scheduler.runLater(function () {
        if (player.isOnline()) player.sendMessage('{gray}Tip: {white}/test {gray}exercises the API.');
    }, 20 * 3);
});

// ============================================================================
//  COMMANDS — both registration forms. Run them in-game to drive the API.
// ============================================================================

// /test — a battery over Player + Location + Server, reported back to the caller.
commands.register('test', function (player, args) {
    var loc = player.getLocation();
    var world = player.getWorld();
    var spawn = world.getSpawnLocation();

    player.sendMessage('{gold}== Player ==');
    player.sendMessage(' name={white}' + player.getName() + '{gray} id={white}' + player.getEntityId());
    player.sendMessage(' address={white}' + player.getAddress()
        + '{gray} ping={white}' + player.getPing() + 'ms');
    player.sendMessage(' displayName={white}' + player.getDisplayName());
    player.sendMessage(' held: slot={white}' + player.getHeldItemSlot()
        + '{gray} item={white}' + player.getHeldItem());
    player.sendMessage(' health={white}' + player.getHealth() + '/' + player.getMaxHealth()
        + '{gray} gamemode={white}' + player.getGameMode().name());
    player.sendMessage(' pose: sneak={white}' + player.isSneaking()
        + '{gray} sprint={white}' + player.isSprinting() + '{gray} useItem={white}' + player.isUsingItem());
    player.sendMessage(' op={white}' + player.isOp()
        + '{gray} prefix={white}"' + player.getPrefix() + '"'
        + '{gray} canTp={white}' + player.hasPermission('jedrock.command.tp'));

    player.sendMessage('{gold}== Location ==');
    player.sendMessage(' at {white}' + loc.getBlockX() + ',' + loc.getBlockY() + ',' + loc.getBlockZ()
        + '{gray} in {white}' + world.getName());
    player.sendMessage(' distance to spawn: {white}' + loc.distance(spawn).toFixed(1)
        + '{gray} (squared ' + loc.distanceSquared(spawn).toFixed(0) + ')');

    player.sendMessage('{gold}== Server ==');
    var st = server.getStatus();
    player.sendMessage(' ' + server.getName() + ' v' + server.getVersion()
        + '{gray} players={white}' + server.getPlayerCount());
    player.sendMessage(' tps={white}' + st.tps().toFixed(1) + '{gray} mspt={white}' + st.mspt().toFixed(2)
        + '{gray} tick={white}' + st.tick());

    // Safe side effects: heal to full and hand over a stone block.
    player.setHealth(player.getMaxHealth());
    var fit = player.giveItem(Blocks.state(Blocks.STONE, 0));
    player.sendMessage('{green}Healed; gave a stone block (' + (fit ? 'fit' : 'inventory full') + ').');
});

// /broadcast <msg> — the options-object form, with an alias and help text; uses server.broadcast.
commands.register({
    name: 'broadcast',
    aliases: ['bc'],
    description: 'Announce a message to everyone',
    usage: '/broadcast <message>',
    execute: function (player, args) {
        if (args.length === 0) { player.sendMessage('{red}Usage: /broadcast <message>'); return; }
        server.broadcast('{gold}[Announce] {yellow}' + args.join(' '));
    }
});

// /kit <name> — shows off tab-completion (Java clients): the `complete` function returns the candidates
// for the token being typed, and the core narrows them to what's been typed so far. Try "/kit " + TAB.
var KITS = ['starter', 'pvp', 'builder'];
commands.register({
    name: 'kit',
    description: 'Grab a starter kit',
    usage: '/kit <name>',
    execute: function (player, args) {
        if (args.length === 0 || KITS.indexOf(args[0]) < 0) {
            player.sendMessage('{red}Usage: /kit <' + KITS.join('|') + '>');
            return;
        }
        player.sendMessage('{green}Here is the {white}' + args[0] + '{green} kit (pretend).');
    },
    complete: function (player, args) {
        // Only suggest for the first argument; return the whole list — the core filters by the partial.
        return args.length === 1 ? KITS : [];
    }
});

// /testspawn — a puppet (interactable) plus a floating hologram beside the player.
commands.register('testspawn', function (player, args) {
    var here = player.getLocation();
    var puppet = server.spawnPuppet(EntityType.PIG, here.add(2, 0, 0));
    puppet.setNameTag('{light_purple}Test Pig');
    puppet.setFlag(PuppetFlag.ON_FIRE, true);
    puppet.onInteract(function (who) { who.sendMessage('{yellow}Oink, ' + who.getName() + '!'); });

    var holo = server.spawnHologram(here.add(0, 3, 0),
        '{aqua}Hologram line 1', '{white}spawned by ' + player.getName(), '{gray}right-click the pig');

    player.sendMessage('{green}Spawned a puppet + hologram. Removing both in 20s.');
    scheduler.runLater(function () { puppet.remove(); holo.remove(); }, 20 * 20);
});

// /run <command…> — run a command AS the caller, via server.dispatchCommand.
commands.register('run', function (player, args) {
    if (args.length === 0) { player.sendMessage('{red}Usage: /run <command>'); return; }
    server.dispatchCommand(player, args.join(' '));   // e.g. /run test
});

// /whoami — report permission state, and self-gate a "secret" on a custom node. Scripts can't declare a
// permission on the command itself yet, but a handler can check any node with player.hasPermission(...);
// grant it with:  /perm group <g> add example.secret   (or op the player, since an op holds every node).
commands.register('whoami', function (player, args) {
    player.sendMessage('{gold}' + player.getPrefix() + '{white}' + player.getName()
        + '{gray} — op={white}' + player.isOp());
    player.sendMessage(player.hasPermission('example.secret')
        ? '{green}You have {white}example.secret{green} — here is the secret: {white}42'
        : '{red}You lack {white}example.secret{red} (try {white}/perm group default add example.secret{red}).');
});

// /title [text…] — show a title + subtitle and an action-bar line (cross-edition: JE Title packet,
// PE 1.1.5 native SetTitle, PE 0.14 chat fallback). Verifies the player-facing UI additions.
commands.register('title', function (player, args) {
    var text = args.length ? args.join(' ') : 'Hello!';
    player.sendTitle('{gold}' + text, '{gray}subtitle here', 5, 40, 10); // fadeIn/stay/fadeOut in ticks
    player.sendActionBar('{aqua}action bar: {white}' + player.getName());
});

// /sb on|off — a live sidebar scoreboard (Java clients; Bedrock ignores it). setSidebar takes a title
// and an array of lines (markup rendered per line); calling it again only sends what changed, so it's
// cheap to refresh on a timer with no flicker. A per-player timer is kept in storage-free local state.
var sbTasks = {};   // player uuid -> timer handle
commands.register({
    name: 'sb', description: 'Toggle a live sidebar', usage: '/sb <on|off>',
    complete: function (player, args) { return args.length === 1 ? ['on', 'off'] : []; },
    execute: function (player, args) {
        var id = String(player.getUniqueId());
        if (args[0] === 'off') {
            if (sbTasks[id]) { sbTasks[id].cancel(); delete sbTasks[id]; }
            player.clearSidebar();
            player.sendMessage('{gray}Sidebar off.');
            return;
        }
        var t = 0;
        function draw() {
            var loc = player.getLocation();
            player.setSidebar('{gold}{bold}Jedrock', [
                '{gray}Player: {white}' + player.getName(),
                '{gray}Ping: {white}' + player.getPing() + 'ms',
                '{gray}At: {white}' + Math.round(loc.x()) + ', ' + Math.round(loc.z()),
                '{gray}Uptime: {white}' + (t++) + 's'
            ]);
        }
        draw();
        if (sbTasks[id]) sbTasks[id].cancel();
        sbTasks[id] = scheduler.runTimer(draw, 20);   // redraw every second (20 ticks)
        player.sendMessage('{green}Sidebar on. {gray}/sb off to hide.');
    }
});

// /boss <0-100> [color] — a boss bar across the top (Java 1.12.2; 1.8 and Bedrock ignore it). setBossBar
// takes a title, a 0..1 fill, and an optional colour name; calling it again updates the same bar.
commands.register({
    name: 'boss', description: 'Show a boss bar', usage: '/boss <0-100> [color]',
    complete: function (player, args) {
        return args.length === 2 ? ['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white'] : [];
    },
    execute: function (player, args) {
        if (args[0] === 'off') { player.clearBossBar(); return; }
        var pct = args.length ? parseInt(args[0]) : 100;
        if (isNaN(pct)) { player.sendMessage('{red}Usage: /boss <0-100> [color]  (or /boss off)'); return; }
        player.setBossBar('{red}The Boss {gray}(' + pct + '%)', Math.max(0, Math.min(100, pct)) / 100,
            args.length > 1 ? args[1] : 'purple');
    }
});

// /menu — a virtual chest opened as a BUTTON menu (Java and Bedrock 0.14; a 1.1.5 player is told it can't
// show, since that client crashes on a chest window).
// The slots are read-only: clicking one fires onClick instead of moving the item, so each slot is a button.
// (Drop the onClick and it's a plain storage chest the player can move items in and out of, backed by no
// world block — nothing persists.)
commands.register('menu', function (player, args) {
    var m = menus.create('{dark_purple}Pick a class', 1);   // 1 row = 9 slots
    // button(slot, item, label): the label is what the 1.1.5 list fallback shows and /pick matches; the
    // window clients (Java, 0.14) just show the item. Give every choice a label so 1.1.5 can list it.
    m.button(2, Blocks.state(276, 0), 'Warrior');   // diamond sword
    m.button(4, Blocks.state(261, 0), 'Archer');    // bow
    m.button(6, Blocks.state(345, 0), 'Scout');     // compass
    m.onClick(function (p, slot, state) {
        var pick = slot === 2 ? 'Warrior' : slot === 4 ? 'Archer' : slot === 6 ? 'Scout' : null;
        if (pick) {
            p.sendMessage('{green}You chose {white}' + pick + '{green}!');
            storage.forPlayer(p).set('class', pick);   // remembered across restarts
        }
    });
    // Java / 0.14 open a chest window; 1.1.5 gets a text list it picks from with /pick <label>. open()
    // returns true in both cases, so there's nothing to fall back on here.
    m.open(player);
});

// /inv — exercise the scripting inventory API (survival: give / set / count / remove / clear).
commands.register('inv', function (player, args) {
    var stone = Blocks.state(Blocks.STONE, 0);
    var glass = Blocks.state(Blocks.GLASS, 0);
    var gaveStone = player.giveItem(stone, 32);          // give 32 stone (returns how many fit)
    player.setItem(8, glass, 5);                         // put 5 glass in the last hotbar slot
    player.sendMessage('{gold}Inventory API:');
    player.sendMessage(' gave {white}' + gaveStone + '{gray} stone; slot 8 = {white}'
        + player.getItem(8) + '{gray}×{white}' + player.getItemCount(8));
    player.sendMessage(' stone count: {white}' + player.countItem(stone)
        + '{gray}; hasGlass={white}' + player.hasItem(glass));
    if (args[0] === 'clear') {
        player.clearInventory();
        player.sendMessage('{green}Inventory cleared.');
    } else {
        player.sendMessage('{gray}(run {white}/inv clear{gray} to empty it)');
    }
});

// /testtimer — one-shot + repeating scheduler + a setTimeout (ms) demo.
commands.register('testtimer', function (player, args) {
    player.sendMessage('{gray}now; +1s (setTimeout); then 3 ticks of a timer…');
    setTimeout(function () { player.sendMessage('{white}setTimeout fired (~1s)'); }, 1000);
    var n = 0;
    var handle = scheduler.runTimer(function () {
        player.sendMessage('{white}timer tick ' + (++n));
        if (n >= 3) handle.cancel();
    }, 20);
});

// ============================================================================
//  CUSTOM EVENTS — plugin-to-plugin messaging via events.emit / events.on.
// ============================================================================
// Any name that isn't a built-in event is a custom channel. Listeners share the data object and can
// cancel; the emitter reads both back. Another plugin could listen for 'example:greet' and react.

events.on('example:greet', function (e) {               // a custom listener
    var d = e.getData();
    d.reply = 'Hello from the test plugin, ' + d.who + '!';
    // e.cancel();   // a listener may veto — the emitter sees isCancelled()
});

// /greet — emit the custom event and show what a listener wrote back.
commands.register('greet', function (player, args) {
    var result = events.emit('example:greet', { who: player.getName(), reply: null });
    player.sendMessage(result.isCancelled()
        ? '{red}greet was cancelled'
        : '{green}' + result.getData().reply);
});

// ============================================================================
//  WORLD — block-level access to the shared world. Every edit below lands in
//  storage (so it persists and autosaves) and is broadcast to every online
//  client in its own protocol — both editions watch it appear live.
// ============================================================================

// /deck — build a 5×5 glass platform under your feet (setBlock/fill demo).
commands.register('deck', function (player, args) {
    var loc = player.getLocation();
    var x = loc.getBlockX(), y = loc.getBlockY() - 1, z = loc.getBlockZ();
    if (!world.isInside(x - 2, z - 2) || !world.isInside(x + 2, z + 2)) {
        player.sendMessage('{red}Too close to the world edge.');
        return;
    }
    var changed = world.fill(x - 2, y, z - 2, x + 2, y, z + 2, Blocks.GLASS);
    player.sendMessage('{green}Deck built{gray} (' + changed + ' block(s) changed; '
        + 'ground here is y={white}' + world.getHighestY(x, z) + '{gray}, biome '
        + world.getBiome(x, z) + ').');
});

// /pillar [meta] — a coloured wool pillar in front of spawn (per-block setBlock demo).
commands.register('pillar', function (player, args) {
    var s = world.getSpawn();
    var x = s.getBlockX() + 3, z = s.getBlockZ() + 3;
    var base = world.getHighestY(x, z) + 1;
    var meta = args.length > 0 ? parseInt(args[0], 10) || 0 : 14; // default red wool
    for (var i = 0; i < 4; i++) world.setBlock(x, base + i, z, Blocks.WOOL, meta);
    player.sendMessage('{green}Wool pillar at {white}' + x + ',' + base + ',' + z);
});

// /armor [off] — dress yourself in diamond; everyone sees it on your avatar, cross-edition.
var ArmorSlot = Packages.com.jedrock.api.player.ArmorSlot;
commands.register('armor', function (player, args) {
    if (args.length > 0 && args[0] === 'off') {
        player.clearArmor();
        player.sendMessage('{gray}Armor removed.');
        return;
    }
    player.setArmor(ArmorSlot.HELMET, Blocks.state(310, 0));
    player.setArmor(ArmorSlot.CHESTPLATE, Blocks.state(311, 0));
    player.setArmor(ArmorSlot.LEGGINGS, Blocks.state(312, 0));
    player.setArmor(ArmorSlot.BOOTS, Blocks.state(313, 0));
    player.sendMessage('{aqua}Full diamond! {gray}(others see it; /armor off to remove)');
});

// /nick [name…] — a coloured chat nickname (displayName demo); no args resets it.
commands.register('nick', function (player, args) {
    if (args.length === 0) {
        player.setDisplayName(null);
        player.sendMessage('{gray}Nickname reset to {white}' + player.getName());
    } else {
        var nick = args.join(' ');
        player.setDisplayName('{gold}' + nick + '{reset}');
        player.sendMessage('{green}You now chat as {gold}' + nick);
    }
});

// /fx [boom] — sounds + particles at your feet, rendered per edition (effects API demo).
commands.register('fx', function (player, args) {
    var loc = player.getLocation();
    var x = loc.x(), y = loc.y(), z = loc.z();
    if (args.length > 0 && args[0] === 'boom') {
        world.playSound('explode', x, y, z);                     // everyone hears it
        world.spawnParticle('huge_explosion', x, y + 1, z);
    } else {
        world.playSound('levelup', x, y, z);
        world.spawnParticle('villager_happy', x, y + 1, z, 12, 0.8); // a 12-sparkle burst, ±0.8
    }
    player.sendMessage('{green}fx! {gray}(try /fx boom)');
});

// ============================================================================
//  ENTITIES — programmable bodies. The server renders and relays them but
//  simulates nothing: the onTick handler below IS the mob's brain. Everything
//  spawned here is owned by this plugin and despawns on reload.
// ============================================================================

// /guard — a zombie that watches you, follows while you're close, and goes home when you leave.
commands.register('guard', function (player, args) {
    var here = player.getLocation();
    var guard = entities.spawn('zombie', here.add(3, 0, 0));
    guard.setNameTag('{red}Guard');
    guard.set('home', guard.getLocation());

    guard.onTick(function (e) {
        var target = e.nearestPlayer(12);
        if (target) {
            e.lookAt(target);
            if (e.distanceTo(target) > 2.5) e.moveToward(target.getLocation(), 0.12);
        } else {
            e.moveToward(e.get('home'), 0.08);   // drift back to its post
        }
    });

    guard.onInteract(function (e, who) {
        e.swing();
        e.hurt();
        who.sendMessage('{red}The guard glares at you, ' + who.getName() + '.');
    });

    player.sendMessage('{green}Guard posted. {gray}(' + entities.count() + ' entity(ies) from this plugin)');
});

// /decor — a scene built as one object: props posed where real blocks can't go, a label naming it,
// and the whole arrangement kept in a group so it can be turned or cleared in a single call.
var scene = null;   // remembered so /decor spin and /decor off can act on it

commands.register('decor', function (player, args) {
    if (args.length > 0 && args[0] === 'spin' && scene) {
        scene.rotate(45);                       // the whole arrangement turns together
        player.sendMessage('{gray}Scene turned 45°.');
        return;
    }
    if (args.length > 0 && args[0] === 'off') {
        if (scene) scene.remove();
        scene = null;
        player.sendMessage('{gray}Scene cleared.');
        return;
    }

    var loc = player.getLocation();
    var x = Math.floor(loc.x()) + 0.5, y = loc.y(), z = Math.floor(loc.z()) + 0.5;

    // A ring of gems at fractional radius: eight props inside one block's footprint. The shape
    // helper walks the circle; the callback decides what stands at each point.
    scene = entities.circle(8, x, y + 1.1, z, 1.2, function (px, py, pz) {
        return entities.spawnItem(Blocks.state(264, 0), px, py, pz);
    });
    scene.setPivot(x, y, z);

    // A lantern hovering at head height — no block below it, no block it could ever occupy —
    // and its label, which is now an entity like everything else.
    scene.add(entities.spawnItem(Blocks.state(89, 0), x + 2, y + 2.4, z));
    scene.add(entities.spawnText('{yellow}Lantern', x + 2, y + 3.1, z));

    // A full-size block floating at half height — an arch a real block can't make.
    scene.add(entities.spawnBlock(Blocks.state(20, 0), x - 2, y + 2.5, z));

    // The other way to pose a block: wear it. An invisible body holding the block on its head,
    // which puts it at any height, any angle, with nothing underneath.
    var pedestal = scene.add(entities.spawn('zombie', x - 2, y, z + 2));
    pedestal.setFlag('invisible', true);
    pedestal.setArmor('helmet', Blocks.state(35, 14));

    // A slowly rising centrepiece — decoration doesn't have to hold still. Left out of the group
    // on purpose: its tick drives its own position, so a group move would fight it.
    var core = entities.spawnItem(Blocks.state(133, 0), x, y + 1.0, z);
    core.set('t', 0);
    core.onTick(function (e) {
        var t = e.get('t') + 1;
        e.set('t', t);
        e.moveTo(x, y + 1.0 + Math.sin(t / 20) * 0.35, z);
    });

    player.sendMessage('{green}Scene built {gray}(' + scene.size() + ' in the group, '
        + entities.count() + ' total). {white}/decor spin{gray}, {white}/decor off');
});

// /despawn — remove every entity this plugin spawned.
commands.register('despawn', function (player, args) {
    var n = entities.count();
    entities.removeAll();
    player.sendMessage('{gray}Removed {white}' + n + '{gray} entity(ies).');
});

// ============================================================================
//  PACKETS — raw cross-edition wire tap. Counted here; reported by /teststats.
// ============================================================================

packets.onReceive(function (p) {                        // client -> server, before the core handles it
    stats.packetsIn++;
    // if (p.getId() === 0x00) p.cancel();              // e.g. drop a specific inbound packet
});

packets.onSend(function (p) {                           // server -> client, before it hits the socket
    stats.packetsOut++;
});

// /teststats — dump the event + packet counters gathered so far.
commands.register('teststats', function (player, args) {
    player.sendMessage('{gold}== Event counters ==');
    var names = Object.keys(stats.events).sort();
    if (names.length === 0) player.sendMessage('{gray} (none yet)');
    for (var i = 0; i < names.length; i++) {
        player.sendMessage(' {white}' + names[i] + '{gray}: ' + stats.events[names[i]]);
    }
    player.sendMessage('{gold}== Packets =={gray} in={white}' + stats.packetsIn
        + '{gray} out={white}' + stats.packetsOut + '{gray} (' + player.getConnection().getProtocolVersion() + ')');
});

// Optional: called when the plugin is unloaded or reloaded. Everything a script registered — listeners,
// scheduled tasks, commands, packet taps — is torn down automatically; this is just for your own cleanup.
function onDisable() {
    console.log('test plugin unloading');
}
