# 内容寻址本地对象存储（Java / Spring Boot）

在本地磁盘上实现一套**内容寻址（content-addressed）**的对象存储，提供：

- **去重写入与校验读取**：相同字节只存一份；读取时整流重算 SHA-256，损坏被明确拒绝。
- **版本管理与条件写入（CAS）**：版本只能在期望值满足时原子追加；历史版本永不被覆盖。
- **快照保留与空间回收（GC）**：对象历史、保留中的快照、未完成写入引用的内容一律受保护；其余 blob 被物理删除且可重复安全执行。

所有关键操作都通过 SLF4J 打印**操作与判定依据**（接受/拒绝原因、版本期望值与实际值、GC 标记与删除依据）。

---

## 1. 磁盘存储组织

```
<root>/
├── blobs/
│   └── <xx>/<64位 sha256 十六进制>     # 不可变内容块（按哈希前2字符分片）
├── tmp/
│   └── upload-<uuid>.tmp              # 写入暂存区（崩溃残留启动时清理）
├── objects/
│   └── <urlencode(objectKey)>.json    # 每个对象的完整版本历史
└── snapshots/
    └── <snapshotId>.json              # 不可变快照清单
```

- **内容身份**：`ContentId = SHA-256(bytes)` 的小写十六进制。blob 文件名即其内容哈希，天然去重、天然可校验。
- **写入协议**（`BlobStore.put`）：先写入 `tmp/` 并流式计算摘要 → 在 per-id 发布锁内以 `ATOMIC_MOVE` 发布到 `blobs/` → 发布前先登记 pending 保护 → 由上层提交元数据 → 提交后解除 pending。
- **元数据落盘**：对象/快照 JSON 均通过“临时文件 + 原子 rename”写入，读者不可能读到半份文档；先持久化成功再更新内存状态，因此**不存在可见的中间状态**。
- 重启后从 `objects/`、`snapshots/` 重建内存索引（见 `PersistenceTest`）。

## 2. 版本与条件写入约束

- 版本号从 **1** 开始、单调递增、只追加（append-only）。
- API：
  - `putIfAbsent(key, content)`：仅当 key 不存在时创建版本 1，否则抛 `VersionConflictException`。
  - `putIfVersion(key, content, expectedVersion)`：仅当**当前版本 == expectedVersion** 时追加 `expectedVersion+1`。
    `expectedVersion=0` 等价于“必须不存在”。
- 所有条件判断与版本追加都在同一把进程内写锁内完成，语义等价于原子 compare-and-append：
  - 条件不满足 / 竞争落败 ⇒ 明确抛 `VersionConflictException`（日志含 expected/actual），无任何写入生效；
  - 成功提交的版本号连续、无重复、无空洞；历史版本与当前版本均可独立寻址读取；
  - 写 blob 与提交元数据是两个阶段：CAS 落败只会留下一个**孤儿 blob**（无任何引用），由后续 GC 回收，绝不会出现元数据指向缺失 blob 的悬空引用（pending 保护，见下）。

## 3. 读取与失败原因区分

读取返回 `VerifyingInputStream`：边读边计算 SHA-256，读到 EOF / 关闭时比对内容 id 与长度。

| 现象 | 异常类型 | 含义 |
| --- | --- | --- |
| 对象 key 不存在 / 指定版本不存在 | `ObjectNotFoundException` | 寻址失败 |
| 条件不满足 / 并发竞争 | `VersionConflictException` | CAS 拒绝（消息含 expected/actual） |
| blob 文件缺失 / 不可读（物理损坏） | `ContentCorruptedException` | 物理层失败 |
| 字节哈希不等于内容 id（位翻转、截断、篡改） | `ChecksumMismatchException`（读流上为 `DigestMismatchIOException`，携带 expected/actual 两个哈希） | 逻辑校验失败 |
| 元数据无法原子持久化 | `MetadataPersistenceException` | 提交失败，状态不变 |

因此“文件没了”和“文件在但内容坏了”是**可区分的两种失败原因**。

## 4. 快照与回收约束

### 4.1 快照

- `snapshots().create(name)` 在对象写锁内捕获每个对象的 **(版本号, contentId, size)**，清单自包含、不可变；
  即使对象之后被删除或覆盖，快照固定的 blob 仍可直接验证读取。
- 删除快照后，仅被该快照引用的内容才变为可回收。

### 4.2 垃圾回收（mark-and-sweep）

一个 blob 被视为**存活**，当且仅当它被以下任一来源引用：

1. **任意对象的任意历史版本**（全量历史保留）；
2. **任意保留中的快照**（快照清单自带 contentId，直接固定）；
3. **未完成的写入**：`BlobStore` 的 pending 集合（blob 已发布但元数据尚未提交）。

安全保证（关键设计）：

- 整轮 GC 在对象写锁内执行，与快照创建、条件写入互斥；
- 删除某个 blob 前，在与 `put` **相同的 per-id 发布锁**内重新判定
  “是否被引用 / 是否仍在 pending”。这关闭了如下竞态窗口：
  写入者在 GC 标记快照之后才发布 blob 并登记 pending；
- 因此 GC 与写入/读取可以**完全并发**运行：不会删到任何已提交或在途的内容；
- 回收后物理文件被删除，磁盘占用真实下降；
- **重复回收幂等**：无垃圾时 `deletedBlobs=0, reclaimedBytes=0`，不会破坏现有对象或使引用错乱。

> 注：GC 只负责 `blobs/`；`tmp/` 中的暂存文件在每次打开存储时清理（崩溃恢复）。

## 5. 代码结构

主代码包 `com.github.xingjianluo.contentaddressedobjectstorejava.storage`：

| 类型 | 职责 |
| --- | --- |
| `ContentAddressedObjectStore` | 门面：写入/读取/快照/GC 的单一入口 |
| `BlobStore` | 暂存、哈希、原子发布、去重、pending 保护、物理删除 |
| `ObjectStore` | 版本历史、条件写入（CAS）、读锁/写锁边界、JSON 持久化 |
| `SnapshotStore` | 快照创建/列举/删除与快照固定集 |
| `GarbageCollector` | 标记-清除，引用集合 = 对象历史 ∪ 快照 ∪ 在途写入 |
| `MetadataStore` | 对象/快照 JSON 的原子落盘与启动加载 |
| `VerifyingInputStream` | 流式 SHA-256 校验（含长度校验） |
| `StorageException` 及子类 | 可区分的失败原因体系 |

## 6. 验证方法

```bash
# 全部测试（18 个）
mvn test

# 单个场景
mvn test -Dtest=DeduplicationTest          # 去重：相同内容一份 blob，多对象/多版本独立寻址
mvn test -Dtest=CorruptionDetectionTest    # 篡改/截断/缺失：校验失败 vs 物理损坏可区分
mvn test -Dtest=ConditionalWriteTest       # 过期版本拒绝；16 线程竞争仅 1 个胜出；160 次 CAS 链
mvn test -Dtest=SnapshotGcTest             # 历史/快照固定、孤儿回收、磁盘占用下降、重复 GC 幂等
mvn test -Dtest=GcConcurrencyTest          # GC 与读写并发：事后逐版本哈希/大小一致
mvn test -Dtest=InFlightProtectionTest     # 在途写入保护；CAS 落败孤儿在下一轮 GC 回收
mvn test -Dtest=PersistenceTest            # 关闭重开后历史/快照恢复，GC 不误删
```

测试均使用 JUnit 5 `@TempDir`，无需外部依赖。运行日志中可以看到例如：

- `conditional put REJECTED ... reason=version-mismatch expected=1 actual=2`
- `writer 3 LOST ...` / `writer 7 WON -> committed version 2`
- `verify FAILED checksum ... expected=... actual=...`
- `GC mark phase: liveReferences=...`、`GC preserves in-flight content=...`
- `GC deleted unreferenced content=... bytes=...`、`GC pass complete ... reclaimedBytes=...`

## 7. 使用示例

```java
try (var store = new ContentAddressedObjectStore(Path.of("/data/store"))) {
    ObjectVersion v1 = store.putIfAbsent("report.pdf", inputStream);   // 创建 v1
    ObjectVersion v2 = store.putIfVersion("report.pdf", in2, v1.version()); // CAS 到 v2

    Snapshot snap = store.snapshot("before-migration");               // 固定此刻状态
    byte[] current = store.readCurrent("report.pdf").readAllBytes();  // 读流即校验
    byte[] old     = store.readVersion("report.pdf", 1).readAllBytes();

    GcResult r = store.collectGarbage(); // 删除无引用 blob；历史/快照/在途写入受保护
}
```
