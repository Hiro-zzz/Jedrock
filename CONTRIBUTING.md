# Contributing to Jedrock

Thanks for looking. This is a from-scratch experiment with strong opinions, and the fastest way to have a
change accepted is to know which opinions those are — so this document is mostly about them, not about
process.

## Before anything: is it in scope?

Jedrock is **not** a re-implementation of Mojang's server. It is a packet switch that spends CPU only when
it must. Some things are permanently out, and a pull request adding them will be declined however good the
code is:

- **Mob AI and pathfinding**, redstone, crafting and smelting mechanics
- **Server-side physics** of any kind — including knockback, which is asked for often
- **Runtime world simulation**: the world is baked once and then served
- **1.13+ flattening**, which inverts the `(id << 4) | meta` block model everything here shares

None of that is laziness; each one is load-bearing. The world can be a flat array of ids *because* nothing
simulates it. Custom logic that wants any of the above belongs in a [script](docs/SCRIPTING.md), as an
illusion.

If you're unsure whether something fits, open an issue before writing it.

## The three rules the code holds to

1. **Lightweight.** Few dependencies, few allocations. The whole server is Netty, a RakNet library and
   Rhino. A pull request that adds a dependency needs to argue for it, and "it would be convenient" isn't
   the argument — see `Yaml.java`, which is 300 lines specifically so SnakeYAML isn't a dependency, and the
   JDBC driver, which is deliberately not bundled.
2. **Absolute abstraction.** The `api` module knows nothing about packets, wire formats or file IO. The
   `network` module never depends on `core`; it reaches it only through `ConnectionListener`. If a change
   needs a new arrow between modules, that is a design discussion, not a detail.
3. **Lazy parsing.** Inbound bytes stay as `ByteBuf` until something needs a value. On a per-packet path,
   don't allocate — check `isDebugEnabled()` before building a log lambda, don't build a string you might
   not send.

## Branches

- **`main`** is clean and stable. It is what a release is cut from.
- **`test`** is where experimental work lands and gets merged to `main` when it holds up.

Branch from `main` for a fix, from `test` for a feature.

## Building and testing

Requires **JDK 21**.

```bash
mvn clean install      # build + run every test
mvn -o clean package   # offline; produces jedrock.jar in the project root
```

`java -jar jedrock.jar` in an empty folder gives you a running server that lays itself out. That is the
fastest way to check a change by hand.

## What a test looks like here

There are ~620 of them and they are not decoration. Two kinds matter most:

**Byte tests for anything on the wire.** A packet encoder is a pure function, so it gets pinned by its
bytes — see `PeStartGameEncodingTest`, `RconPacketTest`. If you add or change a packet, pin its layout. The
client reads most of these positionally, so a field written in the wrong place shifts everything after it
and the failure is silent.

**A test should fail for the reason it claims.** A test that passes because of timing is worse than no
test: this repository has already shipped one (`RconServerTest`, which raced and only lost the race on CI).
If you're asserting an ordering, construct the test so the wrong order *can't* pass.

Be honest in test names and comments about what is actually verified. A byte test proves the encoder agrees
with itself, not that a client accepts it — that distinction is written down all over this codebase because
it has been wrong before.

## Protocol work

Four protocols are supported and each has an era-specific handler. If you're touching one:

- **Ground-truth it.** The Bedrock formats here come from reading PocketMine-MP at a specific tag, not from
  a wiki. Say in the commit where a format came from.
- **A guess can crash a client.** The older Bedrock clients crash on an unknown id rather than ignoring it,
  which is why 0.14's creative palette is a hard-coded list and why its dimension byte is left alone.
- **Say what hasn't met a real client.** The wire as it stands has been through a verification pass on
  both PE eras, so what is written down as working is working; the point of that is only worth anything
  if the next addition is held to it too. A byte test proves the encoder agrees with itself and nothing
  more — the item-NBT dialect passed its own and still failed on a client. So if yours is unverified,
  say so out loud rather than implying it, and where the risk is a hung client, put it behind a flag.

## Commits

Commit messages here are prose, and they explain **why**, not what — the diff already says what. The
subject is a short `Area: what changed` line:

```
Worlds: more than one, and a way to walk between them
Fix: a survival player couldn't rearrange their Bedrock inventory
Docs: bring the README back in line with the code
```

The body is sentences. If a decision has a trade-off, name it and name what was rejected. If you fixed
something that was subtly wrong for a long time, say how it went unnoticed.

Update `CHANGELOG.md` in the same commit when the change is user-visible.

## Pull requests

- One coherent change per PR. If it needs three paragraphs to explain because it does three things, it is
  three PRs.
- `mvn clean install` must pass. CI runs the same thing.
- Update `README.md` and `docs/SCRIPTING.md` when you change behaviour they describe. Documentation drifting
  out of step with the code is treated as a bug here.
- New scripting surface goes in `plugins/example.js` too — that file is both the reference and how the
  script API gets exercised.

## Reporting a bug

Say which **client version** (1.8, 1.12.2, 1.1.5, 0.14) and whether it reproduces on another one — the
answer is often "this client does that", and knowing it early saves everyone the search. Include the
relevant part of `logs/latest.log`.

Known-bad behaviour is documented rather than hidden: check [Known limits](README.md#known-limits) first.
Bedrock 1.1.5 not opening a chest window is not a bug report, it is a client.
