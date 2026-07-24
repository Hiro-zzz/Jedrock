// ============================================================================
//  Jedrock test plugin — a live reference that exercises EVERY scripting hook.
// ============================================================================
//
// Drop a .js file in this folder and it loads on start; save an edit and it hot-reloads within a second,
// no restart. Six globals are in scope:
//
//   server     — the server: players, worlds, broadcast, puppets, holograms, status.
//   events     — events.on(name, fn): subscribe to a built-in event (25 below) OR a custom, script-defined
//                one (any other name). Built-in handlers get the real Java event (getters/setters, cancel);
//                custom handlers get {getName, getData, cancel, isCancelled}. events.emit(name, data) fires a
//                custom event to every listener and returns it (read data / isCancelled back).
//   scheduler  — run code later, in ticks (20/sec): run / runLater / runTimer, each returning a handle with
//                .cancel(). setTimeout / setInterval / clearTimeout / clearInterval work too (milliseconds).
//   commands   — commands.register(name, fn)  OR  register({name, aliases, description, usage, execute}).
//                The handler gets (player, args); args is a JS array. Shows up in /help.
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
    player.sendMessage(' address={white}' + player.getAddress());
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
