package com.example.ae2lmdb;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import appeng.api.storage.StorageCells;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;

import com.example.ae2lmdb.config.ModConfig;
import com.example.ae2lmdb.event.WorldSaveHandler;
import com.example.ae2lmdb.item.ModItems;
import com.example.ae2lmdb.storage.DatabaseCellHandler;

/**
 * Ponto de entrada do addon AE2 LMDB Cells.
 *
 * <p><b>Fase 0:</b> mod vazio, só precisa carregar junto com o AE2 sem crash.</p>
 *
 * <p><b>Fase 2:</b> registra {@link ModItems#ITEMS} (a célula de armazenamento) no mod event bus, e
 * registra o {@link DatabaseCellHandler} na AE2 durante {@link FMLCommonSetupEvent} — momento
 * exigido pelo javadoc de {@code StorageCells.addCellHandler}, já que registrar antes disso lança
 * exceção.</p>
 *
 * <p><b>Fase 3:</b> registra o {@link WorldSaveHandler} no {@code MinecraftForge.EVENT_BUS} (o event bus
 * do <em>jogo</em>, não o mod event bus usado acima) — é ele que abre/fecha o
 * {@code LmdbManager} do save atual e dispara o flush assíncrono do
 * {@code CellCache}/{@code LmdbBackedStorage} em resposta a start/stop do servidor e save do
 * mundo.</p>
 *
 * <p><b>Fase 4:</b> registra a {@link ModConfig} (caps de capacidade/custo, intervalo de flush,
 * tamanho do map LMDB) e os upgrades aceitos pela nossa célula (Fuzzy Card, Inverter Card,
 * Equal Distribution Card, Void Card — os mesmos que as cells nativas de itens da AE2 aceitam).
 * O registro de upgrades <b>precisa</b> acontecer em {@code enqueueWork} dentro de
 * {@link FMLCommonSetupEvent}, igual ao {@code addCellHandler}, porque o registro é global e
 * não é thread-safe em paralelo com outros mods.</p>
 */
@Mod(AE2LmdbMod.MODID)
@SuppressWarnings("removal") // ModLoadingContext.get() — works in 1.20.1, deprecated for 1.21+ forward-compat
public class AE2LmdbMod {

    public static final String MODID = "ae2lmdb";

    private static final Logger LOGGER = LogUtils.getLogger();

    public AE2LmdbMod(FMLJavaModLoadingContext context) {
        LOGGER.info("AE2 LMDB Cells: inicializando (Fase 4 - UI/progressao)");

        IEventBus modBus = context.getModEventBus();
        ModItems.ITEMS.register(modBus);
        modBus.addListener(this::commonSetup);

        // Registra a config no ModLoadingContext. O arquivo gerado é config/ae2lmdb-common.toml.
        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(new WorldSaveHandler());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // enqueueWork garante que isso rode de forma sincronizada com o resto do setup da AE2,
        // em vez de correr em paralelo com outros mods durante FMLCommonSetupEvent.
        event.enqueueWork(() -> {
            StorageCells.addCellHandler(new DatabaseCellHandler());
            registerUpgrades();
        });
    }

    /**
     * Registra os upgrade cards aceitos pela nossa célula — mesmos 4 que as cells nativas de
     * itens da AE2. {@code Upgrades.add(upgradedItem, upgradeCard, maxCount, group)} associa
     * um upgrade card a um item "upgradeable", e o {@code ItemUpgradeInventory} retornado por
     * {@code DatabaseStorageCellItem#getUpgrades} respeita esses registros automaticamente.
     *
     * <p>O grupo (4º argumento) é só uma chave de tradução para o tooltip; reusar
     * {@code "item.ae2lmdb.database_storage_cell"} faz o tooltip do card mostrar esta célula
     * como uma das compatíveis.</p>
     */
    private static void registerUpgrades() {
        var cell = ModItems.DATABASE_STORAGE_CELL.get();
        // 1 de cada: mesmo padrão das cells nativas de itens da AE2 (ver InitUpgrades).
        Upgrades.add(cell, AEItems.FUZZY_CARD, 1, "item.ae2lmdb.database_storage_cell");
        Upgrades.add(cell, AEItems.INVERTER_CARD, 1, "item.ae2lmdb.database_storage_cell");
        Upgrades.add(cell, AEItems.EQUAL_DISTRIBUTION_CARD, 1, "item.ae2lmdb.database_storage_cell");
        Upgrades.add(cell, AEItems.VOID_CARD, 1, "item.ae2lmdb.database_storage_cell");
    }
}
