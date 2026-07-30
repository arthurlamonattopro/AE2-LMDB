package com.example.ae2lmdb.item;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.example.ae2lmdb.AE2LmdbMod;

/**
 * Registro dos itens do addon (Fase 2 do TODO.md).
 *
 * <p>{@link #ITEMS} precisa ser registrado no mod event bus a partir de
 * {@link com.example.ae2lmdb.AE2LmdbMod} — ver o construtor de {@code AE2LmdbMod}.</p>
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, AE2LmdbMod.MODID);

    /**
     * A célula de armazenamento em si. Modelo/textura/lang própria adicionados na Fase 4 —
     * ver {@code assets/ae2lmdb/models/item/database_storage_cell.json} e a textura em
     * {@code assets/ae2lmdb/textures/item/database_storage_cell.png}.
     */
    public static final RegistryObject<Item> DATABASE_STORAGE_CELL = ITEMS.register(
            "database_storage_cell",
            () -> new DatabaseStorageCellItem(new Item.Properties()));

    private ModItems() {
    }
}
