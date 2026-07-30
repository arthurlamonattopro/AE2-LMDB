/**
 * Handlers de eventos do Forge relacionados ao ciclo de vida do mundo (Fases 1 e 3 do TODO.md).
 *
 * <p>{@code WorldSaveHandler} dispara o flush do cache em memória para o LMDB quando o
 * mundo é salvo ou descarregado, garantindo que nada fique só na memória.</p>
 */
package com.example.ae2lmdb.event;
