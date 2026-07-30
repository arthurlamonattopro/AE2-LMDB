package com.example.ae2lmdb.event;

import java.nio.file.Path;

import net.minecraft.world.level.storage.LevelResource;

import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.example.ae2lmdb.config.ModConfig;
import com.example.ae2lmdb.storage.CellCacheRegistry;

/**
 * Liga o ciclo de vida do {@code CellCacheRegistry}/{@code LmdbManager} aos eventos de servidor
 * do Forge (Fase 3 do TODO.md; ampliado na Fase 4 para ler a config).
 *
 * <p>Registrado no {@code MinecraftForge.EVENT_BUS} (não no mod event bus) por
 * {@code AE2LmdbMod}, já que {@link ServerStartingEvent}/{@link ServerStoppingEvent} e
 * {@link LevelEvent.Save} são eventos do jogo, não de ciclo de vida do mod.</p>
 *
 * <p>Cobre dois dos três gatilhos de flush pedidos na Fase 3 (o terceiro — desmonte da célula da
 * rede — é tratado em {@code DatabaseStorageCell#persist()}):</p>
 * <ul>
 *   <li>{@link #onServerStarting}: abre o {@code LmdbManager} do save atual (um por save, decisão
 *   arquitetural nº3 do AGENTS.md, via {@link LevelResource}) e agenda o flush periódico.</li>
 *   <li>{@link #onLevelSave}: despacha um flush assíncrono de todos os caches ativos sempre que o
 *   jogo salva o mundo.</li>
 *   <li>{@link #onServerStopping}: flush final e fecha o ambiente LMDB.</li>
 * </ul>
 */
public final class WorldSaveHandler {

    /** Nome da subpasta dentro do save do mundo (`&lt;save&gt;/data/ae2lmdb/`). */
    private static final LevelResource DATA_FOLDER = new LevelResource("ae2lmdb");

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Path worldSaveDir = event.getServer().getWorldPath(DATA_FOLDER);
        CellCacheRegistry registry = CellCacheRegistry.getInstance();
        registry.open(worldSaveDir);
        // Intervalo do flush periódico lido da config (Fase 4). Revertia a um valor hardcoded
        // de 30s antes; agora o admin pode ajustar via config/ae2lmdb-common.toml.
        long periodSeconds = ModConfig.common().flushIntervalSeconds.get();
        registry.startPeriodicFlush(periodSeconds);
    }

    @SubscribeEvent
    public void onLevelSave(LevelEvent.Save event) {
        // Assíncrono: só enfileira o trabalho na thread dedicada do registry, nunca bloqueia a
        // thread principal do servidor que disparou este evento (decisão arquitetural nº4 do
        // AGENTS.md).
        CellCacheRegistry.getInstance().flushAll();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Aqui é síncrono de propósito: o servidor já está parando (não há mais tick de jogo pra
        // bloquear) e precisamos garantir que tudo esteja gravado e o Env fechado antes do
        // processo morrer, para não arriscar corrupção do LMDB (ver Fase 6 do TODO.md).
        CellCacheRegistry.getInstance().close();
    }
}
