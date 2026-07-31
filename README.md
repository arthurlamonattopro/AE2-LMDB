# AE2 LMDB Cells

<p align="center">
  <strong>High-performance storage backend for Applied Energistics 2.</strong><br>
  Replace huge Storage Cell NBT with an LMDB database.
</p>

<p align="center">

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green)
![Forge](https://img.shields.io/badge/Forge-47.x-orange)
![Java](https://img.shields.io/badge/Java-17-blue)
![Status](https://img.shields.io/badge/Status-Realease-green)

</p>

---

## Overview

AE2 LMDB Cells replaces the traditional NBT storage used by Applied Energistics 2 Storage Cells with a high-performance LMDB database.

Instead of storing every item directly inside the ItemStack, each Storage Cell stores only a UUID. The real contents are stored externally inside a world-specific database.

This dramatically reduces NBT size while keeping gameplay completely transparent.

---

# ✨ Features

- 🚀 Extremely small Storage Cell NBT
- ⚡ Fast in-memory cache
- 💾 Asynchronous disk writes
- 📦 One LMDB database per world
- 🔄 Automatic synchronization
- 🔍 UUID-based storage
- 🔧 Transparent to players

---

# ❓ Why?

The default AE2 implementation stores every item directly inside the Storage Cell's NBT.

Large modpacks often generate huge NBT payloads due to:

- Enchantments
- Durability
- Custom NBT
- Modded items
- Thousands of unique item variants

This increases:

- Save time
- Network traffic
- Memory usage
- NBT corruption risk

---

# ⚙ How it works

```
          Storage Cell

     ┌──────────────────┐
     │ UUID only in NBT │
     └────────┬─────────┘
              │
              ▼
     ┌──────────────────┐
     │ Memory Cache     │
     │ ConcurrentHashMap│
     └────────┬─────────┘
              │
     Async Flush
              │
              ▼
     ┌──────────────────┐
     │ LMDB Database    │
     │ UUID → Items     │
     └──────────────────┘
```

Gameplay never talks directly to the disk.

All insert/extract operations happen in memory and are periodically synchronized with LMDB.

---

# 📦 Requirements

- Minecraft 1.20.1
- Forge
- Java 17
- Applied Energistics 2
- lmdbjava (shaded)

---

# 🔨 Building

```bash
./gradlew build
```

Output:

```
build/libs/
```

---

# ⚠ Known Limitations

### Cell duplication

Duplicating a Storage Cell outside normal gameplay (Creative cloning, `/give`, etc.) may initially duplicate its UUID.

The addon detects duplicated mounted cells and automatically generates a new UUID while copying the contents.

Some edge cases are still documented in `TODO.md`.

---

### World databases

Each world has its own LMDB database.

Storage Cells are **not portable between worlds.**

---

# 📈 Performance

Compared to vanilla Storage Cells:

| Feature | Vanilla AE2 | AE2 LMDB |
|----------|------------|-----------|
| Item NBT Size | Large | Tiny |
| Save Performance | Medium | High |
| Network Payload | Large | Small |
| Disk Storage | NBT | LMDB |
| Memory Cache | ❌ | ✅ |

---

# 🚧 Project Status

> **Beta**

Core functionality is working.

Current focus:

- More testing
- Edge case fixes
- Performance tuning
- Better migration support

---

# 📚 Documentation

- `TODO.md` — Roadmap
- `AGENTS.md` — Coding conventions

---

# License

License to be defined.
