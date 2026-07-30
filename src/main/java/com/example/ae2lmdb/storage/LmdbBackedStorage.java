package com.example.ae2lmdb.storage;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

/**
 * Implementação de {@link MEStorage} que opera inteiramente sobre um {@link CellCache}
 * (Fase 3 do TODO.md; ampliada na Fase 4 para expor métricas usadas pelo
 * {@link DatabaseStorageCell}).
 *
 * <p>Antes da Fase 3, essa lógica vivia direto dentro de {@link DatabaseStorageCell}, sobre um
 * {@code Map<AEKey, Long>} criado vazio a cada montagem da célula. Extrair para uma classe própria
 * separa "sou um {@code MEStorage} que só sabe mexer no cache" (aqui) de "sou uma
 * {@code StorageCell} da AE2 — tenho descrição, status, drenagem de energia e um hook de
 * persistência" ({@link DatabaseStorageCell}, que passa a delegar {@code insert}/{@code extract}/
 * {@code getAvailableStacks} para uma instância desta classe).</p>
 *
 * <p>Qualquer mutação chama {@link CellCache#markDirty()} para que o próximo flush (periódico, de
 * save do mundo, ou ao desmontar a célula — ver {@link CellCacheRegistry}) grave a mudança no
 * LMDB. Nenhum I/O de disco acontece aqui, só no cache em memória.</p>
 *
 * <p><b>Fase 4 — métodos adicionados:</b> {@link #size()}, {@link #contains(AEKey)} e
 * {@link #bytesUsed(int)} expõem métricas que o {@link DatabaseStorageCell} usa para impor os
 * caps de tipos/bytes e devolver o {@link appeng.api.storage.cells.CellState} correto.</p>
 */
public final class LmdbBackedStorage implements MEStorage {

    private final CellCache cache;

    public LmdbBackedStorage(CellCache cache) {
        this.cache = cache;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(what, amount, mode, source);
        if (amount <= 0) {
            return 0;
        }
        if (mode == Actionable.MODULATE) {
            cache.contents().merge(what, amount, Long::sum);
            cache.markDirty();
        }
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(what, amount, mode, source);
        Long stored = cache.contents().get(what);
        if (stored == null || stored <= 0 || amount <= 0) {
            return 0;
        }

        long extracted = Math.min(stored, amount);
        if (mode == Actionable.MODULATE && extracted > 0) {
            long remaining = stored - extracted;
            if (remaining <= 0) {
                cache.contents().remove(what);
            } else {
                cache.contents().put(what, remaining);
            }
            cache.markDirty();
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        cache.contents().forEach(out::add);
    }

    @Override
    public Component getDescription() {
        return Component.translatable("item.ae2lmdb.database_storage_cell");
    }

    public boolean isEmpty() {
        return cache.contents().isEmpty();
    }

    /** Número de tipos (chaves) distintos atualmente armazenados. */
    public int size() {
        return cache.contents().size();
    }

    /** True se a chave já está presente no cache (mesmo que com quantidade 0). */
    public boolean contains(AEKey what) {
        return cache.contents().containsKey(what);
    }

    /**
     * Total de bytes consumidos pelo conteúdo atual, dado um overhead {@code bytesPerType} por
     * tipo distinto. Igual ao modelo de custo nativo da AE2: cada tipo consome
     * {@code bytesPerType} + a quantidade armazenada conta como bytes.
     */
    public long bytesUsed(int bytesPerType) {
        long bytes = 0;
        for (long amount : cache.contents().values()) {
            bytes += bytesPerType + Math.max(0, amount);
        }
        return bytes;
    }
}
