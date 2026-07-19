// Example Jedrock plugin. Drop a .js file in this folder and it loads on start; save an edit and it
// hot-reloads within a second — no restart. Five globals are in scope: `server`, `events`, `scheduler`,
// `commands` and `console`.
//
// events.on(name, handler) subscribes to an event. The handler gets the real Java event object, so you
// call its getters/setters directly. Cancellable events have setCancelled(true).
//
// scheduler runs code later, in ticks (20/sec): scheduler.runLater(fn, delayTicks),
// scheduler.runTimer(fn, periodTicks) — each returns a handle with .cancel(). The familiar
// setTimeout(fn, ms) / setInterval(fn, ms) / clearTimeout(h) / clearInterval(h) work too, in milliseconds.
// Every task a script schedules is cancelled automatically when it unloads or hot-reloads.
//
// commands.register(name, handler) adds a /slash command; the handler gets (player, args). Or pass an
// options object { name, aliases, description, usage, execute } to give it help text. Commands show up in
// /help and are removed automatically on unload/reload.

console.log('example plugin loading');

// Greet a player as they join. (getPlayer() is the api Player; sendMessage takes the unified {color} markup.)
events.on('PlayerJoin', function (e) {
    e.getPlayer().sendMessage('{gold}Welcome, {white}' + e.getPlayer().getName() + '{gold}!');
});

// Shout on every chat line — and SHOUT it.
events.on('PlayerChat', function (e) {
    e.setMessage(e.getMessage().toUpperCase());
});

// Protect the world's spawn column from being broken: cancel a break near 0,0.
events.on('BlockBreak', function (e) {
    if (Math.abs(e.getX()) < 3 && Math.abs(e.getZ()) < 3) {
        e.setCancelled(true);
        e.getPlayer().sendMessage('{red}Spawn is protected.');
    }
});

// A once-a-minute heartbeat — the scheduler way, instead of counting ticks in ServerTick by hand.
scheduler.runTimer(function () {
    console.log('still alive at tick', server.getCurrentTick());
}, 20 * 60);                                   // every 60 seconds (20 ticks/sec)

// Greet again 3 seconds after a player joins, using a one-shot. (Cancels itself if they leave first
// would need a saved handle; here we keep it simple.)
events.on('PlayerJoin', function (e) {
    var player = e.getPlayer();
    scheduler.runLater(function () {
        if (player.isOnline()) {
            player.sendMessage('{gray}Tip: type {white}/help{gray} to see commands.');
        }
    }, 20 * 3);                                // 3 seconds later
});

// A /heal command: refills the sender's health. Shows the (player, args) handler form.
commands.register('heal', function (player, args) {
    player.setHealth(player.getMaxHealth());
    player.sendMessage('{green}You have been healed.');
});

// A /broadcast command with help text and an alias, using the options-object form.
commands.register({
    name: 'broadcast',
    aliases: ['bc'],
    description: 'Announce a message to everyone',
    usage: '/broadcast <message>',
    execute: function (player, args) {
        if (args.length === 0) {
            player.sendMessage('{red}Usage: /broadcast <message>');
            return;
        }
        server.broadcast('{gold}[Announce] {yellow}' + args.join(' '));
    }
});

// Optional: called when the plugin is unloaded or reloaded.
function onDisable() {
    console.log('example plugin unloading');
}
