# Jedrock

**Lightweight Minecraft server core** with absolute abstraction and lazy parsing.

Target versions:
- **Minecraft: Java Edition 1.12.2**
- **Minecraft: Bedrock Edition (PE) 1.1.5**

## Philosophy

Jedrock is built around three core ideas:

1. **Lightweight** — minimal memory, minimal dependencies, minimal allocations on hot paths.
2. **Absolute Abstraction** — the API contains **zero** knowledge of concrete packets, block states, or network details. Everything is behind interfaces.
3. **Lazy Parsing** — packets and data structures are **not** fully decoded until something actually needs the value. Most packets in the wild are never fully inspected.

## Module Structure

```
jedrock
├── jedrock-api          # Public contracts only. No impl.
├── jedrock-utils        # Low-level helpers, Lazy<T>, ByteBufUtils, logging
├── jedrock-network      # Transport + packet abstraction (Netty based skeleton)
├── jedrock-gameloop     # Dedicated 20 TPS loop + lightweight scheduler
└── jedrock-core         # Glue. The actual server implementation.
```

## Design Highlights

### Protocol Handlers (scaling foundation)

Inbound protocol logic lives in `ProtocolHandler` implementations (see `network/handler/je` and `pe`).
`JedrockConnection` is deliberately thin and delegates to the handler selected by `ProtocolVersion`.

### Lazy Packet Example

```java
LazyPacket incoming = ...;
SomePacket p = incoming.materialize(buf -> SomePacket.read(buf));

// If you never call materialize(), the expensive NBT/chunk data is never parsed.
```

### Event System

Extremely simple registration:

```java
server.getEventBus().register(PlayerJoinEvent.class, event -> { ... });
```

No annotations, no reflection in the hot path.

### Game Loop

- Single high-priority thread
- Drift-correcting fixed tick rate
- `Tickable` + `Scheduler` (run later / repeating)

## Building

```bash
mvn clean install
```

## Running (current skeleton)

```bash
mvn -pl jedrock-core -am exec:java -Dexec.mainClass="com.jedrock.core.JedrockServer"
```

Or just run the main class from your IDE.

## Next Steps / Extension Points

- Flesh out `PacketRegistry` + per-state dispatch (foundation stub already present)
- Complete PE 1.1.5 login (Batch 0xFE + zlib + Login JSON)
- Wire real `BlockStorage` data into `ClientboundChunkData`
- World / chunk storage abstraction (lazy chunk loading)
- Config system (tiny)
- Command system (also behind abstraction)

Pull requests and ideas that respect the three principles (lightweight + abstraction + lazy) are welcome.

---

*Jedrock — do as little as possible, as late as possible.*
