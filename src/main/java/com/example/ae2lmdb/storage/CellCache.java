package com.example.ae2lmdb.storage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import appeng.api.stacks.AEKey;

import com.example.ae2lmdb.serialization.AEKeyCodec;

/**
 * Cache em memória do conteúdo de uma célula (Fase 3 do TODO.md).
 *
 * <p>Substitui o {@code ConcurrentHashMap<AEKey, Long>} que vivia direto dentro de
 * {@link DatabaseStorageCell} na Fase 2. A diferença é que agora esse mapa é <b>carregado do
 * LMDB</b> quando a célula é montada na rede ({@link #load}) e pode ser <b>persistido de volta</b>
 * ({@link #flush}) — mas continua sendo, em si, só um mapa em memória: toda leitura/escrita de
 * gameplay ({@code insert}/{@code extract}/{@code getAvailableStacks}, ver {@link LmdbBackedStorage})
 * opera exclusivamente sobre {@link #contents}, nunca direto no LMDB (decisão arquitetural nº2 do
 * AGENTS.md).</p>
 *
 * <p>{@link #flush()} é seguro de chamar de uma thread de background: {@link #contents} é um
 * {@link ConcurrentHashMap}, e a flag {@link #dirty} só controla se vale a pena gravar — na pior
 * hipótese (uma escrita acontece exatamente entre o snapshot e o commit), a próxima chamada de
 * {@link #flush()} simplesmente grava de novo, já que {@link #markDirty()} marca a flag outra vez.</p>
 */
public final class CellCache {

    private final UUID cellId;
    private final LmdbManager manager;
    private final Map<AEKey, Long> contents = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private CellCache(UUID cellId, LmdbManager manager) {
        this.cellId = cellId;
        this.manager = manager;
    }

    /** Carrega (via {@link LmdbManager#loadAll}) o conteúdo já persistido de uma célula. */
    public static CellCache load(UUID cellId, LmdbManager manager) {
        CellCache cache = new CellCache(cellId, manager);
        for (LmdbManager.Entry entry : manager.loadAll(cellId)) {
            AEKey key = AEKeyCodec.decode(entry.keyBytes());
            cache.contents.put(key, entry.amount());
        }
        return cache;
    }

    public UUID getCellId() {
        return cellId;
    }

    /** O mapa em memória em si — usado por {@link LmdbBackedStorage} para insert/extract/listagem. */
    public Map<AEKey, Long> contents() {
        return contents;
    }

    /** Marca o cache como tendo mudanças ainda não persistidas no LMDB. */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * Grava o conteúdo atual no LMDB, substituindo tudo que já estava lá para esta célula — mas
     * só se houver alguma mudança pendente desde o último flush ({@link #markDirty()}).
     *
     * <p>Faz I/O de disco (via {@link LmdbManager#replaceAll}); o chamador é responsável por não
     * rodar isso na thread principal do servidor (ver {@link CellCacheRegistry}, que agenda os
     * flushes num executor dedicado).</p>
     */
    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }

        List<LmdbManager.Entry> snapshot = contents.entrySet().stream()
                .map(e -> new LmdbManager.Entry(AEKeyCodec.encode(e.getKey()), e.getValue()))
                .toList();
        manager.replaceAll(cellId, snapshot);
    }
}
