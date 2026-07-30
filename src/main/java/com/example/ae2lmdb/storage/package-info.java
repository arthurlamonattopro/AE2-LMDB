/**
 * Backend de armazenamento em LMDB e cache em memória (Fases 1 e 3 do TODO.md).
 *
 * <ul>
 *   <li>{@code LmdbManager}: abre/fecha um {@code Env} do LMDB por save de mundo,
 *       em {@code data/ae2lmdb/} (via {@code LevelResource}, nunca um path global).</li>
 *   <li>{@code CellCache}: {@code ConcurrentHashMap<AEKey, Long>} carregado do LMDB;
 *       toda leitura/escrita de gameplay passa por aqui, nunca direto no disco.</li>
 *   <li>{@code LmdbBackedStorage}: implementa {@code MEStorage} do AE2 sobre o cache,
 *       com flush assíncrono (nunca bloqueante na thread principal do servidor).</li>
 * </ul>
 */
package com.example.ae2lmdb.storage;
