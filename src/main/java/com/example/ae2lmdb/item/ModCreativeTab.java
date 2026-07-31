package com.example.ae2lmdb.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.example.ae2lmdb.AE2LmdbMod;

/**
 * Creative tab do addon (Fase 7+).
 *
 * <p>Registra uma tab própria no menu de criativo para agrupar todas as células LMDB,
 * facilitando a localização pelos jogadores.</p>
 */
public final class ModCreativeTab {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AE2LmdbMod.MODID);

    public static final RegistryObject<CreativeModeTab> AE2LMDB_TAB = CREATIVE_TABS.register(
            "ae2lmdb_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2lmdb"))
                    .icon(() -> new ItemStack(ModItems.DATABASE_STORAGE_CELL.get()))
                    .displayItems((parameters, output) -> {
                        // Adiciona todas as células LMDB à tab
                        output.accept(ModItems.DATABASE_STORAGE_CELL.get());
                        output.accept(ModItems.DATABASE_FLUID_STORAGE_CELL.get());
                        output.accept(ModItems.PORTABLE_DATABASE_STORAGE_CELL.get());
                        output.accept(ModItems.PORTABLE_DATABASE_FLUID_STORAGE_CELL.get());
                    })
                    .build());

    private ModCreativeTab() {
    }

    public static void register(IEventBus eventBus) {
        CREATIVE_TABS.register(eventBus);
    }
}
