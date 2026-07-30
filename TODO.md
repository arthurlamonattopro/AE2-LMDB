# TODO

Roadmap do addon AE2 LMDB Cells. Ordem sugerida — cada fase depende da anterior.

## Fase 0 — Setup do projeto
- [x] Criar projeto Forge MDK 1.20.1
- [x] Adicionar AE2 como dependência (via CurseMaven + `fg.deobf`, já que a 1.20.1/15.x não está no Maven Central) no `build.gradle`, disponível em compile e nos runs de dev
- [x] Adicionar `lmdbjava` como dependência shaded (Shadow plugin)
- [x] Confirmar que o mod carrega junto com AE2 sem crash (mod vazio) — confirmado pelo usuário

## Fase 1 — Integração básica com LMDB
- [x] `LmdbManager`: abrir/fechar `Env` do LMDB por save de mundo (`data/ae2lmdb/`) — implementado o `open(Path)`/`close()`; a integração com `LevelResource` + eventos de load/unload do mundo fica pro `WorldSaveHandler` na Fase 3
- [x] Definir sub-databases: decidido — uma DB única (`cells`) com prefixo de UUID na chave, em vez de uma sub-DB por célula (ver AGENTS.md, item 6)
- [x] `AEKeyCodec`: serializar/desserializar `AEKey` (via `toTagGeneric`/`fromTagGeneric` + `NbtIo`) para bytes
- [x] Teste isolado: escrever e ler um par `AEKey -> long` no LMDB sem envolver Minecraft — `LmdbManagerTest` (`./gradlew test`); usa bytes arbitrários no lugar do AEKey de verdade, já que testar o `AEKeyCodec` em si exigiria inicializar registries do Minecraft (ver nota abaixo)

> **Nota:** não escrevi um teste de round-trip para `AEKeyCodec` em si (que envolveria criar um
> `AEItemKey` de verdade) porque isso exige inicializar as registries do Minecraft (`Bootstrap.bootStrap()`
> e afins) dentro do teste, o que é frágil de fazer corretamente sem rodar de fato — prefiro validar isso
> manualmente/via `runServer` quando a Fase 2 (item da célula) já existir, a inventar um teste que talvez
> nem compile certo no seu ambiente.

## Fase 2 — Item da célula
- [x] `DatabaseStorageCellItem`: item que guarda só um UUID na NBT (tag `CellId`) — implementa
      `ICellWorkbenchItem` (não `IBasicCellItem`, que faria a AE2 gerenciar o conteúdo via NBT
      internamente, o oposto do que este addon quer)
- [x] Gerar UUID novo ao craftar/criar a célula — `onCraftedBy` chama `getOrCreateCellId`, que
      também serve de salvaguarda para stacks que cheguem sem UUID por outros caminhos (datagen,
      `/give`, testes); não confundir com a duplicação de UUID tratada na Fase 5
- [x] Implementar capability `StorageCell` do AE2 apontando pro storage customizado —
      `DatabaseCellHandler` (`ICellHandler`) registrado via `StorageCells.addCellHandler(...)` em
      `FMLCommonSetupEvent`, retornando um `DatabaseStorageCell` (`StorageCell`/`MEStorage`) por
      ItemStack

> **Nota:** `DatabaseStorageCell` ainda guarda o conteúdo só em memória (`ConcurrentHashMap`),
> criado vazio a cada vez que a célula é montada na rede — **não persiste em lugar nenhum ainda**.
> Isso é intencional: a Fase 3 é que troca esse mapa por um `CellCache` carregado/persistido via
> `LmdbManager`, com flush assíncrono. A classe foi desenhada para que essa troca não exija mudar
> a lógica de `insert`/`extract`/`getAvailableStacks`, só como `contents` é inicializado e salvo.

## Fase 3 — MEStorage + cache
- [x] `CellCache`: `ConcurrentHashMap<AEKey, Long>` carregado do LMDB ao montar a célula na rede —
      `CellCache.load(cellId, manager)`, chamado por `CellCacheRegistry#getOrLoad` a partir do
      construtor de `DatabaseStorageCell`
- [x] `LmdbBackedStorage implements MEStorage`: `insert`, `extract`, `getAvailableStacks` operando só no cache —
      `DatabaseStorageCell` agora delega essas três operações para uma instância de
      `LmdbBackedStorage` construída sobre o `CellCache` da célula
- [x] Flush assíncrono: periódico (timer) + no evento de save do mundo + ao desmontar a célula da rede —
      `CellCacheRegistry` sobe uma thread dedicada (`ae2lmdb-flush`) via `ScheduledExecutorService`;
      `WorldSaveHandler` liga `ServerStartingEvent`/`LevelEvent.Save`/`ServerStoppingEvent` a ela, e
      `DatabaseStorageCell#persist()` (chamado pela AE2 ao desmontar a célula da rede) despacha o
      flush daquela célula específica
- [x] Garantir que flush nunca bloqueia a thread principal do servidor — todo flush (periódico, de
      save do mundo, ou de `persist()`) é apenas *enfileirado* (`Executor#execute`/
      `scheduleWithFixedDelay`) na thread dedicada do `CellCacheRegistry`; a única gravação síncrona
      é o flush final em `ServerStoppingEvent`, quando o servidor já não está mais tickando

> **Nota:** `LmdbManager.replaceAll` (usado pelo flush) substitui *todas* as entradas de uma célula
> numa única transação (apaga o intervalo do prefixo de UUID, depois regrava o snapshot atual do
> cache), em vez de calcular um diff incremental — mais simples e correto por construção, ao custo
> de reescrever entradas que não mudaram; revisar se profiling na Fase 6 apontar isso como
> problema com datasets muito grandes.

## Fase 4 — Integração com AE2 (UI/progressão)
- [x] Registrar o item no `ICellProvider`/`IStorageProvider` corretamente — feito via
      `IBasicCellItem` no `DatabaseStorageCellItem` (que estende `AEBaseItem` e implementa
      `IBasicCellItem`, expondo `getKeyType`/`getBytes`/`getBytesPerType`/`getTotalTypes`/
      `getIdleDrain` lidos da `ModConfig`) + `DatabaseCellHandler` registrado em
      `StorageCells.addCellHandler(...)` (já desde a Fase 2)
- [x] Suporte à Cell Workbench (particionamento, filtros) — `getConfigInventory` retorna um
      `ConfigInventory.configTypes` via `CellConfig.create(...)` (63 slots, mesma capacidade das
      cells nativas); `getUpgrades` retorna um `ItemUpgradeInventory` (4 slots); upgrades
      aceitos (Fuzzy/Inverter/Equal Distribution/Void Card) registrados via `Upgrades.add(...)`
      em `AE2LmdbMod#commonSetup`; `DatabaseStorageCell` lê o partition list e o fuzzy mode no
      momento de montar a célula e rejeita inserts que não batem (igual ao `BasicCellInventory`)
- [x] Definir modelo de custo/capacidade (bytes, tipos ilimitados vs cap configurável) —
      decisão de balanceamento: defaults generosos (64 MiB, 0=ilimitado tipos, 8 bytes/tipo,
      1.0 AE/tick) já que LMDB escala melhor que NBT; tudo configurável via
      `config/ae2lmdb-common.toml` (ver `ModConfig`)
- [x] Textura, modelo, lang file — `assets/ae2lmdb/models/item/database_storage_cell.json`
      (parent minecraft:item/generated, layer0=item icon, layer1=LED overlay reusado do AE2),
      `assets/ae2lmdb/models/block/drive/cells/database_storage_cell.json` (parent
      ae2:block/drive/drive_cell), texturas PNG placeholder (16x16, azul/cinza para distinguir
      das cells nativas), `lang/en_us.json` + `lang/pt_br.json`, recipe shaped (64k cell
      component + housing-style pattern + calculation/engineering processors), e registro do
      drive cell model via `StorageCellModels.registerModel(...)` em `ClientSetup`

## Fase 5 — Mitigar duplicação de UUID
- [x] Detectar cenário de duplicação — `CellCacheRegistry.acquireMount`/`releaseMount` rastreiam
      quais UUIDs estão montados em alguma rede nesta sessão do servidor; `DatabaseStorageCell`
      chama `acquireMount` no construtor (chamado a cada `DatabaseCellHandler#getCellInventory`).
      Se o UUID já estiver reivindicado por outra célula ainda montada, é sinal de duas
      ItemStacks distintas com o mesmo UUID (pick block, `/give` com NBT copiada, bug de
      duplicação de outro mod)
- [x] Definir estratégia: **gerar novo UUID + copiar dados no LMDB** (não bloquear/avisar) —
      `CellCacheRegistry.cloneCellData` copia o conteúdo da célula original (preferindo o cache
      em memória, se ativo, sobre o disco, já que o flush é assíncrono) para o UUID novo; a
      célula original (ainda montada em outro lugar) não é tocada, só passa a ter um "irmão"
      com estoque independente em vez de compartilhado
- [ ] Testar casos: creative pick block, comando `/give`, hopper duplication bugs de outros mods
      — a lógica está implementada e coberta por teste unitário isolado
      (`CellCacheRegistryMountTest`, sem depender do Minecraft/AE2), mas os cenários reais (que
      exigem `runServer` com o AE2 presente) ainda não foram exercitados manualmente

> **Nota — limitação conhecida:** a marca de "montada" usada para detectar duplicação é liberada
> em `DatabaseStorageCell#persist()`, que a AE2 chama não só ao desmontar a célula da rede, mas
> também periodicamente enquanto ela continua montada (ver Fase 3). Isso significa que a marca
> pode ser liberada bem antes da célula sair de fato da rede, e uma segunda cópia com o mesmo
> UUID que apareça depois disso não seria mais pega por esta checagem (falso negativo). É uma
> escolha deliberada — a API de `StorageCell`/`ICellHandler` disponível para este addon não expõe
> um callback confiável de "esta célula específica saiu da rede agora", só `persist()`, que é
> ambíguo — preferindo esse "buraco" a arriscar falsos positivos (gerar UUID novo para uma célula
> legítima que nunca foi duplicada). Revisar se os testes manuais desta fase mostrarem que a
> janela é curta demais na prática; ver javadoc de `CellCacheRegistry#acquireMount`.

## Fase 6 — Performance e robustez
- [ ] Testar com dataset grande (dezenas de milhares de tipos distintos)
- [ ] Medir impacto no tick do servidor com muitas células ativas simultaneamente
- [ ] Tratar corrupção/arquivo LMDB ausente (recuperação gracioso, não crash)
- [ ] Fechar `Env` corretamente no shutdown do servidor (evitar corrupção)

## Fase 7 — Polimento
- [ ] Config (cap de tipos, intervalo de flush, tamanho máximo do LMDB map)
- [ ] Documentação de usuário final (não só dev)
- [ ] Testes em ambiente multiplayer real (não só singleplayer)
- [ ] Publicar no CurseForge/Modrinth (opcional)

## Backlog / ideias futuras
- [ ] Suporte a fluid cells (não só item)
- [ ] Ferramenta de migração: converter célula NBT padrão do AE2 → célula LMDB
- [ ] Métricas/debug command (`/ae2lmdb stats <uuid>`)
