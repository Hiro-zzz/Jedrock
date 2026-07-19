// Example Jedrock plugin. Drop a .js file in this folder and it loads on start; save an edit and it
// hot-reloads within a second — no restart. Two globals are in scope: `server`, `events`, and `console`.
//
// events.on(name, handler) subscribes to an event. The handler gets the real Java event object, so you
// call its getters/setters directly. Cancellable events have setCancelled(true).

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

// A once-a-second heartbeat (ServerTick fires every tick; 20 ticks == 1 second).
events.on('ServerTick', function (e) {
    if (e.getTick() % 1200 === 0) {           // every 60 seconds
        console.log('still alive at tick', e.getTick());
    }
});

// Optional: called when the plugin is unloaded or reloaded.
function onDisable() {
    console.log('example plugin unloading');
}
