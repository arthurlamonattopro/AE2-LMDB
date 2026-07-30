package com.example.ae2lmdb.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste da mitigação de duplicação de UUID (Fase 5 do TODO.md):
 * {@link CellCacheRegistry#acquireMount}, {@link CellCacheRegistry#releaseMount} e
 * {@link CellCacheRegistry#cloneCellData}.
 *
 * <p>{@code cloneCellData} normalmente copia a partir do {@link CellCache} em memória de uma
 * célula ativa (ver seu javadoc), mas isso exigiria instanciar um {@code AEKey} de verdade — o
 * mesmo obstáculo documentado em {@code LmdbManagerTest} (precisa das registries do Minecraft
 * inicializadas). Por isso este teste exercita só o caminho de fallback (nenhum cache em memória
 * carregado para a célula de origem, ver {@link CellCacheRegistry#cloneCellData}), que passa
 * direto pelo {@link LmdbManager} e não toca em {@code AEKey}; a cobertura do caminho "cache em
 * memória com dados ainda não sincronizados no disco" fica para validação manual/{@code runServer},
 * igual à ressalva já feita para {@code AEKeyCodec} na Fase 1.</p>
 *
 * <p>Como {@link CellCacheRegistry} é um singleton, cada teste fecha o registry em
 * {@link #fecharRegistry()} para não vazar estado (LmdbManager aberto, UUIDs montados) entre
 * execuções.</p>
 */
class CellCacheRegistryMountTest {

    @AfterEach
    void fecharRegistry() {
        CellCacheRegistry.getInstance().close();
    }

    @Test
    void primeiraReivindicacaoDeUmUuidSempreConsegue() {
        UUID cellId = UUID.randomUUID();

        assertTrue(CellCacheRegistry.getInstance().acquireMount(cellId));
    }

    @Test
    void segundaReivindicacaoDoMesmoUuidFalhaEnquantoNaoLiberada() {
        UUID cellId = UUID.randomUUID();
        CellCacheRegistry registry = CellCacheRegistry.getInstance();

        assertTrue(registry.acquireMount(cellId));
        assertFalse(registry.acquireMount(cellId), "um segundo mount do mesmo UUID deveria ser rejeitado");
    }

    @Test
    void uuidsDiferentesNaoConflitamEntreSi() {
        CellCacheRegistry registry = CellCacheRegistry.getInstance();

        assertTrue(registry.acquireMount(UUID.randomUUID()));
        assertTrue(registry.acquireMount(UUID.randomUUID()));
    }

    @Test
    void reivindicacaoVoltaAFuncionarDepoisDeLiberada() {
        UUID cellId = UUID.randomUUID();
        CellCacheRegistry registry = CellCacheRegistry.getInstance();

        assertTrue(registry.acquireMount(cellId));
        registry.releaseMount(cellId);

        assertTrue(registry.acquireMount(cellId), "depois do release, o mesmo UUID deve poder ser remontado");
    }

    @Test
    void liberarUmUuidNuncaMontadoNaoLancaErro() {
        // Não deveria acontecer em uso normal, mas releaseMount não precisa ser simétrico com
        // acquireMount para ser seguro de chamar.
        CellCacheRegistry.getInstance().releaseMount(UUID.randomUUID());
    }

    @Test
    void cloneCellDataSemConteudoPrevioNaoLancaErroEDuplicadaContinuaMontavel(@TempDir Path tempDir) {
        CellCacheRegistry registry = CellCacheRegistry.getInstance();
        registry.open(tempDir);

        UUID original = UUID.randomUUID();
        UUID duplicado = UUID.randomUUID();

        // "original" nunca foi carregado via getOrLoad, então cloneCellData cai no fallback de
        // ler direto do LMDB (LmdbManager.loadAll), que retorna uma lista vazia para um UUID que
        // nunca teve nada gravado — o importante aqui é que a chamada não lance exceção.
        registry.cloneCellData(original, duplicado);

        // E que, depois do clone, a célula duplicada continua um UUID normal: monta e carrega
        // sem erro, com o cache vindo vazio (nada para copiar).
        assertTrue(registry.acquireMount(duplicado));
        assertTrue(registry.getOrLoad(duplicado).contents().isEmpty());
    }
}
