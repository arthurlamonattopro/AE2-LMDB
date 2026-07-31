package com.example.ae2lmdb.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import appeng.api.client.StorageCellModels;

import com.example.ae2lmdb.AE2LmdbMod;
import com.example.ae2lmdb.item.ModItems;

/**
 * Setup de cliente (Fase 4 do TODO.md).
 *
 * <p>Registrado no <em>mod event bus</em> com {@link Dist#CLIENT} para garantir que só carregue
 * no cliente (o servidor dedicado não precisa — para ele, modelos de célula são irrelevantes).</p>
 *
 * <p>Por enquanto só faz uma coisa: registra o modelo de drive cell (a textura que aparece na
 * bay da drive quando a célula está inserida) para a nossa célula. Sem isso, a drive mostra o
 * modelo default do AE2 (uma textura cinza genérica) — funciona, mas fica visualmente
 * indistinguível das cells nativas.</p>
 *
 * <p>O id do modelo aponta para {@code ae2lmdb:block/drive/cells/database_storage_cell}, que por
 * sua vez estende {@code ae2:block/drive/drive_cell} (o modelo pai que define a geometria do
 * slot da drive) e só sobrescreve a textura {@code cell}.</p>
 */
@EventBusSubscriber(modid = AE2LmdbMod.MODID, value = Dist.CLIENT, bus = Bus.MOD)
@SuppressWarnings("removal") // new ResourceLocation(String,String) — works in 1.20.1, deprecated for 1.21+ forward-compat
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // O id do modelo aponta para assets/ae2lmdb/models/block/drive/cells/<name>.json,
            // que estende ae2:block/drive/drive_cell e só sobrescreve a textura "cell".
            StorageCellModels.registerModel(
                    ModItems.DATABASE_STORAGE_CELL.get(),
                    new ResourceLocation(AE2LmdbMod.MODID, "block/drive/cells/database_storage_cell"));
            StorageCellModels.registerModel(
                    ModItems.DATABASE_FLUID_STORAGE_CELL.get(),
                    new ResourceLocation(AE2LmdbMod.MODID, "block/drive/cells/database_fluid_storage_cell"));
            // Nota: células portáteis não têm modelo de drive — elas são itens portáteis.
        });
    }
}
