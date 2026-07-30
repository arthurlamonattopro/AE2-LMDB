# AE2 LMDB Cells

Addon para [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) (Minecraft 1.20.1, Forge) que substitui o armazenamento baseado em NBT das Storage Cells por um backend em **LMDB** (Lightning Memory-Mapped Database).

## Problema que resolve

No AE2 padrão, cada Storage Cell física serializa toda a sua lista de tipos/quantidades como NBT dentro do próprio `ItemStack`. Em modpacks com muitos tipos de item (variantes NBT, encantamentos, durabilidade, itens de outros mods), essa NBT cresce muito e causa:

- Lag no salvamento de chunk (NBT gigante sendo serializada a cada save)
- Payload grande de sincronização cliente/servidor ao segurar a célula
- Risco de corrupção em NBTs muito grandes
- Limite prático de ~63 tipos por célula (modelo de custo do próprio AE2)

## Como resolve

- A célula guarda apenas um **UUID** na NBT do item (quase nada).
- O conteúdo real (`AEKey -> long`) fica em um banco **LMDB**, um arquivo por mundo salvo, indexado por esse UUID.
- Uma camada de cache em memória (`ConcurrentHashMap`) fica na frente do LMDB: todas as operações do jogo (insert/extract) acontecem no cache; o disco só recebe flush assíncrono periódico, em save do mundo, ou quando a célula sai da rede.

```
ItemStack (célula)          LMDB (por save)
┌───────────────┐           ┌─────────────────────┐
│ NBT: { uuid }  │  ──────▶ │ subdb "uuid" →       │
└───────────────┘           │   AEKey → count (KV) │
                             └─────────────────────┘
        ▲
        │ cache em memória (ConcurrentHashMap)
        │ flush assíncrono
```

## Requisitos

- Minecraft 1.20.1
- Forge (MDK correspondente)
- Java 17
- Applied Energistics 2 (dependência de compile/runtime)
- [lmdbjava](https://github.com/lmdbjava/lmdbjava) (shaded no jar final)

## Build

```bash
./gradlew build
```

O jar final fica em `build/libs/`. Requer o AE2 instalado no mesmo modpack.

## Limitações conhecidas

- **Duplicação de item = duplicação de referência (mitigada, não eliminada).** Copiar a célula fora do fluxo normal do jogo (pick block no criativo, `/give` com NBT copiada, clonagem por outros mods) pode fazer duas células apontarem pro mesmo UUID. A partir da Fase 5, o addon detecta quando duas células com o mesmo UUID são montadas em alguma rede ao mesmo tempo e dá um UUID novo (com cópia do conteúdo) pra segunda — mas essa detecção tem uma janela de falso negativo documentada em `CellCacheRegistry#acquireMount`. Veja `TODO.md` para os detalhes e o que falta testar manualmente.
- Cada save de mundo tem seu próprio banco LMDB; células não são portáveis entre mundos.

## Status

Em desenvolvimento inicial. Veja `TODO.md` para o roadmap e `AGENTS.md` para convenções de código.

## Licença

_A definir._
