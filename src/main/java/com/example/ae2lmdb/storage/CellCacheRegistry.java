package com.example.ae2lmdb.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.example.ae2lmdb.serialization.AEKeyCodec;

/**
 * Dono do {@link LmdbManager} do save atual e dos {@link CellCache} atualmente montados em alguma
 * rede AE2 (Fase 3 do TODO.md).
 *
 * <p>Ciclo de vida gerenciado por {@code com.example.ae2lmdb.event.WorldSaveHandler}:</p>
 * <ul>
 *     <li>{@link #open(Path)} — no início do servidor (um {@link LmdbManager} por save, decisão
 *     arquitetural nº3 do AGENTS.md).</li>
 *     <li>{@link #flushAll()} — no evento de save do mundo, e periodicamente (ver
 *     {@link #startPeriodicFlush(long)}).</li>
 *     <li>{@link #close()} — ao desligar o servidor: faz um último flush síncrono (o servidor já
 *     está parando, não há mais tick de jogo pra bloquear) e fecha o ambiente LMDB.</li>
 * </ul>
 *
 * <p><b>Flush assíncrono</b> (decisão arquitetural nº4 do AGENTS.md): {@link #flushAll()} nunca
 * grava no disco na própria thread chamadora — só enfileira o trabalho em {@link #flushExecutor},
 * uma thread dedicada só disso. Isso cobre os três gatilhos do TODO.md: o timer periódico
 * ({@link #startPeriodicFlush}), o evento de save do mundo, e — indiretamente — o desmonte da
 * célula da rede, já que a AE2 chama {@code StorageCell.persist()} nesse momento (ver javadoc de
 * {@link DatabaseStorageCell#persist()}), e {@code persist()} despacha um flush aqui.</p>
 *
 * <p><b>Fase 5 — mitigação de duplicação de UUID:</b> {@link #acquireMount}/{@link #releaseMount}
 * rastreiam quais UUIDs estão atualmente montados em alguma rede nesta sessão do servidor, e
 * {@link #cloneCellData} copia o conteúdo de uma célula para um UUID novo quando
 * {@code DatabaseStorageCell} detecta que dois ItemStacks distintos reivindicam o mesmo UUID ao
 * mesmo tempo. Ver o javadoc de {@link #acquireMount} para a limitação conhecida dessa checagem.</p>
 */
public final class CellCacheRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final CellCacheRegistry INSTANCE = new CellCacheRegistry();

    private final Map<UUID, CellCache> activeCaches = new ConcurrentHashMap<>();

    /** UUIDs atualmente reivindicados por uma {@code DatabaseStorageCell} montada (Fase 5). */
    private final Set<UUID> mountedCells = ConcurrentHashMap.newKeySet();

    @Nullable
    private volatile LmdbManager manager;

    @Nullable
    private ScheduledExecutorService flushExecutor;

    private CellCacheRegistry() {
    }

    public static CellCacheRegistry getInstance() {
        return INSTANCE;
    }

    /** Abre o ambiente LMDB do save atual e sobe a thread dedicada de flush assíncrono. */
    public synchronized void open(Path worldSaveDir) {
        if (manager != null) {
            LOGGER.warn("AE2 LMDB Cells: open() chamado com um LmdbManager já aberto; ignorando");
            return;
        }
        manager = LmdbManager.open(worldSaveDir);
        flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ae2lmdb-flush");
            t.setDaemon(true);
            return t;
        });
        LOGGER.info("AE2 LMDB Cells: ambiente LMDB aberto em {}", worldSaveDir);
    }

    /**
     * Agenda o flush periódico de todos os caches ativos, a cada {@code periodSeconds} segundos,
     * rodando inteiramente na thread dedicada de flush — nunca na thread principal do servidor.
     */
    public synchronized void startPeriodicFlush(long periodSeconds) {
        if (flushExecutor == null) {
            throw new IllegalStateException("startPeriodicFlush chamado antes de open()");
        }
        flushExecutor.scheduleWithFixedDelay(
                this::flushAllSynchronously, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /**
     * Obtém (carregando do LMDB se necessário) o {@link CellCache} de uma célula, e o registra
     * como ativo para fins de flush periódico/de save.
     */
    public CellCache getOrLoad(UUID cellId) {
        LmdbManager current = requireManager();
        return activeCaches.computeIfAbsent(cellId, id -> CellCache.load(id, current));
    }

    /**
     * Tenta reivindicar este UUID como "montado" nesta sessão do servidor (Fase 5 do TODO.md —
     * mitigação de duplicação de UUID). Retorna {@code false} se outra {@code DatabaseStorageCell}
     * já reivindicou o mesmo UUID e ainda não foi liberada via {@link #releaseMount} — sinal de
     * que duas ItemStacks distintas (pick block, {@code /give} com NBT copiada, bug de duplicação
     * de outro mod) estão apontando para a mesma célula ao mesmo tempo.
     *
     * <p><b>Limitação conhecida (deliberada):</b> {@link #releaseMount} é chamado a partir de
     * {@code DatabaseStorageCell#persist()}, que a AE2 invoca não só ao desmontar a célula da
     * rede, mas também periodicamente enquanto ela continua montada (ver o javadoc desse método).
     * Isso significa que a marca de "montada" pode ser liberada bem antes da célula realmente
     * sair da rede, e uma segunda cópia com o mesmo UUID que apareça depois desse ponto não seria
     * mais pega por esta checagem (falso negativo). É uma troca deliberada: a API de
     * {@code StorageCell}/{@code ICellHandler} disponível para este addon não expõe um callback
     * confiável de "esta célula específica saiu da rede agora" — só {@code persist()}, que é
     * ambíguo — e um falso negativo ocasional é preferível a um falso positivo (gerar um UUID
     * novo, e portanto "esquecer" o vínculo, para uma célula legítima que nunca foi duplicada).
     * Revisar caso o "Testar casos" da Fase 5 mostre que essa janela é curta demais na prática.</p>
     */
    public boolean acquireMount(UUID cellId) {
        return mountedCells.add(cellId);
    }

    /** Libera a marca de "montada" de um UUID — ver limitação documentada em {@link #acquireMount}. */
    public void releaseMount(UUID cellId) {
        mountedCells.remove(cellId);
    }

    /**
     * Copia todo o conteúdo de {@code sourceCellId} para {@code targetCellId} — usado por
     * {@code DatabaseStorageCell} quando {@link #acquireMount} detecta uma duplicação e a
     * estratégia escolhida (Fase 5 do TODO.md) é gerar um UUID novo para a célula duplicada em
     * vez de bloquear/avisar. A célula original (ainda montada em outro lugar) não é tocada —
     * só ganha uma cópia independente do seu conteúdo sob o novo UUID.
     *
     * <p>Prefere ler do {@link CellCache} em memória de {@code sourceCellId}, se ele estiver
     * ativo, em vez de ler direto do LMDB: como o flush é assíncrono/periódico (Fase 3), o que
     * está no disco pode estar desatualizado em relação ao cache.</p>
     */
    public void cloneCellData(UUID sourceCellId, UUID targetCellId) {
        LmdbManager current = requireManager();
        CellCache active = activeCaches.get(sourceCellId);
        List<LmdbManager.Entry> entries = active != null
                ? active.contents().entrySet().stream()
                        .map(e -> new LmdbManager.Entry(AEKeyCodec.encode(e.getKey()), e.getValue()))
                        .toList()
                : current.loadAll(sourceCellId);
        current.replaceAll(targetCellId, entries);
    }

    /** Despacha (de forma assíncrona) o flush de uma única célula — usado por {@code persist()}. */
    public void flushAsync(UUID cellId) {
        CellCache cache = activeCaches.get(cellId);
        if (cache == null || flushExecutor == null) {
            return;
        }
        flushExecutor.execute(() -> safeFlush(cache));
    }

    /** Despacha (de forma assíncrona) o flush de todos os caches ativos — usado no save do mundo. */
    public void flushAll() {
        if (flushExecutor == null) {
            return;
        }
        flushExecutor.execute(this::flushAllSynchronously);
    }

    private void flushAllSynchronously() {
        activeCaches.values().forEach(this::safeFlush);
    }

    private void safeFlush(CellCache cache) {
        try {
            cache.flush();
        } catch (RuntimeException e) {
            LOGGER.error("AE2 LMDB Cells: falha ao fazer flush da célula {}", cache.getCellId(), e);
        }
    }

    /**
     * Flush final (síncrono — o servidor já parou de tickar) de todos os caches, seguido do
     * fechamento do ambiente LMDB e da thread de flush. Chamado uma única vez, no desligamento do
     * servidor.
     */
    public synchronized void close() {
        if (flushExecutor != null) {
            flushAllSynchronously();
            flushExecutor.shutdown();
            flushExecutor = null;
        }
        activeCaches.clear();
        mountedCells.clear();
        if (manager != null) {
            manager.close();
            manager = null;
        }
    }

    private LmdbManager requireManager() {
        LmdbManager current = manager;
        if (current == null) {
            throw new IllegalStateException(
                    "CellCacheRegistry usado antes de open() — o LmdbManager do save ainda não foi aberto");
        }
        return current;
    }
}
