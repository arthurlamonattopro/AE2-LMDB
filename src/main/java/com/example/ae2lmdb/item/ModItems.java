package com.example.ae2lmdb.item;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import appeng.api.stacks.AEKeyType;

import com.example.ae2lmdb.AE2LmdbMod;

/**
 * Registro dos itens do addon (Fase 2 do TODO.md; ampliado na Fase 7+).
 *
 * <p>{@link #ITEMS} precisa ser registrado no mod event bus a partir de
 * {@link com.example.ae2lmdb.AE2LmdbMod} — ver o construtor de {@code AE2LmdbMod}.</p>
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, AE2LmdbMod.MODID);

    /**
     * Célula de armazenamento de itens. Modelo/textura/lang adicionados na Fase 4.
     */
    public static final RegistryObject<Item> DATABASE_STORAGE_CELL = ITEMS.register(
            "database_storage_cell",
            () -> new DatabaseStorageCellItem(AEKeyType.items(), new Item.Properties()));

    /**
     * Célula de armazenamento de fluidos (Fase 7+). Usa o mesmo backend LMDB, mas com filtro de
     * tipo {@link AEKeyType#fluids()} em vez de {@link AEKeyType#items()}.
     */
    public static final RegistryObject<Item> DATABASE_FLUID_STORAGE_CELL = ITEMS.register(
            "database_fluid_storage_cell",
            () -> new DatabaseStorageCellItem(AEKeyType.fluids(), new Item.Properties()));

    /**
     * Célula portátil de itens (Fase 7+). Combina armazenamento LMDB com bateria interna e GUI
     * portátil, seguindo o padrão {@code PortableItemCell} da AE2.
     */
    public static final RegistryObject<Item> PORTABLE_DATABASE_STORAGE_CELL = ITEMS.register(
            "portable_database_storage_cell",
            () -> new DatabasePortableCellItem(AEKeyType.items(), new Item.Properties()));

    /**
     * Célula portátil de fluidos (Fase 7+). Versão fluida da portátil, seguindo o padrão
     * {@code PortableFluidCell} da AE2.
     */
    public static final RegistryObject<Item> PORTABLE_DATABASE_FLUID_STORAGE_CELL = ITEMS.register(
            "portable_database_fluid_storage_cell",
            () -> new DatabasePortableCellItem(AEKeyType.fluids(), new Item.Properties()));

    private ModItems() {
    }
}
