/**
 * Serialização de {@code AEKey} para bytes armazenáveis no LMDB (Fase 1 do TODO.md).
 *
 * <p>{@code AEKeyCodec} usa os helpers do próprio AE2 ({@code AEKey.toTagGeneric()} /
 * {@code fromTagGeneric()}) como base, convertendo o {@code CompoundTag} resultante
 * para bytes via {@code NbtIo} — nada de formato binário customizado antes de validar
 * que o overhead da NBT é de fato um problema (ver AGENTS.md).</p>
 */
package com.example.ae2lmdb.serialization;
