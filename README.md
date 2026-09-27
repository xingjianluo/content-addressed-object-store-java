# 本地内容寻址对象存储（Content-Addressed Object Store）

一套基于本地文件系统的对象存储，提供**内容寻址去重、多版本与条件写入、快照保留、空间回收（GC）**，
并保证在去重写入、并发更新、快照保留下数据状态**可预期、可验证**。

核心入口：`com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService`

---

## 1. 存储组织方式

所有数据位于初始化时给定的根目录 `root/`：

```
root/
├── blobs/                         # 不可变内容（内容寻址）
│   └── ab/cd/<64位SHA-256>        # 按哈希前 4 位两级分片，文件名即内容哈希
├── staging/                       # 写入中途的临时内容（不属于任何 GC 根）
├── objects/
│   └── <urlencoded-key>/
│       ├── meta.json              # 对象“当前版本”指针（原子写）
│       ├── v0.json                # 不可变版本记录：版本号/哈希/长度/时间
│       ├── v1.json
│       └── ...
├── snapshots/
│   └── <snapshotId>.json          # 快照：创建时刻 “对象 -> (版本号, 哈希)” 的不可变映射
└── journal/
    └── <entryId>.json             # 未完成写入登记：写入中引用的内容哈希
```

要点：

- **内容寻址 + 物理去重**：blob 以内容 SHA-256 命名，相同内容在 `blobs/` 下只有一份物理副本。
  多个对象、同一对象的多个版本可引用同一 blob，彼此独立寻址、互不影响。
- **两阶段写入**：写入先落 `staging/` 并计算哈希；提交时同分区**原子 rename** 发布到 `blobs/`。
  目标哈希已存在则直接丢弃 staging 副本（去重命中）。
- **元数据与内容分离**：版本记录/指针是小 JSON，通过“同目录临时文件 + 原子 move”更新。

## 2. 版本管理与条件写入约束

- 版本号从 **0** 开始单调递增；`vN.json` 一旦写入**永不被覆盖**（历史不可丢失）。
- `meta.json` 是当前版本指针，更新是原子的：读线程要么看到旧指针、要么看到新指针，
  **读不到中间状态**。
- 条件写入 `putIfVersion(key, expectedVersion, content)`：
  - 新对象 `expectedVersion = -1`；
  - 仅当对象**当前版本恰好等于** `expectedVersion` 时才提交，否则抛出
    `VersionConflictException`（携带 expected/actual，可区分、可重试）；
  - “条件检查 → 写不可变版本记录 → 原子推进指针”在提交互斥区内完成，**原子生效**。
- 并发竞争：同一基准版本下只有一个写者成功，其余全部得到冲突；冲突写入
  **不产生新版本、不覆盖历史、不留可读中间状态**。

## 3. 快照保留与空间回收约束

GC 采用 **mark-and-sweep**，根集合（保留判定依据）为：

1. **所有对象的全部历史版本**引用的哈希；
2. **所有快照**引用的哈希；
3. **所有未完成写入（journal）**引用的哈希。

约束：

- 快照是创建时刻的不可变拷贝；对象后续更新不影响快照，`readSnapshot` 读回的是快照时刻版本。
- 写入流程在发布 blob 前先 `journal.begin(...)` 登记引用，版本提交后再 `complete`。
  因此“写入中”的内容一定在 GC 根中，**绝不会被回收**；失败/中止则 `abort` 解除登记。
- GC 运行期间持有全局写锁，与一切读/写互斥：mark 看到的引用关系在 sweep 期间不变，
  **不会误删正在被引用的内容**。
- 竞争失败的条件写可能已发布 blob 但未产生版本指针——这是**合法垃圾**，由后续 GC 回收。
- GC 后 `blobs/` 物理占用（`physicalContentBytes()`）实际下降；
  **重复 GC 幂等**：第二次起无可删除内容，现存对象与引用关系不受影响。

## 4. 读取校验与可区分的失败原因

读取时执行两级校验：

1. **物理层**：blob 文件缺失/不可读，或实算 SHA-256 与文件名不一致
   → `ContentCorruptedException`（物理损坏 / 位腐烂）；
2. **元数据层**：版本记录登记的哈希/长度与实际内容对不上
   → `ChecksumMismatchException`（校验和/元数据不匹配）。

另有：

- 对象/版本/快照不存在 → `ObjectNotFoundException` / `SnapshotNotFoundException`；
- 条件写版本不匹配 → `VersionConflictException`。

所有异常都继承自 `StorageException`，且携带判定所需字段（expected/actual hash、版本号等）。

## 5. 并发模型

`StoreConcurrency` 使用一把**公平读写锁** + 一把提交互斥锁：

- 读（`read*`/查询/快照读取）与多阶段写入持**读锁**，彼此可并发；
- 一次 `put` 从 stage 到 journal 登记、版本提交、journal 完成的整个窗口都持读锁，
  从而阻止 GC 在写入中途插入；
- 版本“检查 + 推进”在提交互斥区串行，保证条件写原子判定；
- GC 持**写锁**，与所有读/写互斥。

## 6. 验证方法

运行全部测试：

```bash
mvn test
```

测试位于 `src/test/java/.../tests/`，关键操作与判定依据通过 SLF4J 打印到控制台
（日志前缀如 `[blob] [version] [snapshot] [journal] [gc] [store] [TEST] [CONCUR]`）：

| 测试类 | 覆盖点 |
| --- | --- |
| `DeduplicationTest` | 相同内容跨对象/跨版本物理去重、独立寻址、不同内容新增占用 |
| `CorruptionTest` | 物理位腐烂 vs 元数据校验和不匹配，失败原因可区分；坏对象不影响好对象 |
| `ConditionalWriteTest` | 版本递增、历史不可变、过期条件写被拒绝且不留中间状态 |
| `ConcurrentConditionalWriteTest` | 16 线程 × 8 轮竞争：每轮恰一个赢家，其余冲突，版本连续、历史可读 |
| `SnapshotAndGcTest` | 孤儿回收后磁盘下降、重复回收幂等、快照/历史/未完成写入内容被保留 |
| `GcConcurrencyTest` | GC 与上万次读、上千次条件写并发：零损坏、零不一致，收敛后 GC 稳定 |

程序化使用示例：

```java
ObjectStoreService store = new ObjectStoreService(Path.of("/data/store"));
store.init();

VersionInfo v = store.putIfVersion("k", -1, "hello".getBytes(UTF_8)); // 创建 v0
store.putIfVersion("k", 0, "world".getBytes(UTF_8));                  // 条件更新
byte[] cur = store.read("k");                                         // 读当前版本
byte[] old = store.readVersion("k", 0);                               // 读历史版本

String snap = store.createSnapshot();
byte[] asof = store.readSnapshot(snap, "k");                          // 读快照时刻内容

GcResult r = store.garbageCollect();                                  // 回收
long bytes = store.physicalContentBytes();                            // 物理占用
```

## 7. 主要组件

| 组件 | 职责 |
| --- | --- |
| `Hashing` | SHA-256 摘要与十六进制编码 |
| `StoreLayout` | 目录布局、分片路径、文件名编码、原子写 |
| `FileBlobStore` | 内容寻址 blob：staging / 去重发布 / 读取校验 / 删除 |
| `FileVersionStore` | 对象版本记录、当前指针、条件提交 |
| `FileSnapshotStore` | 不可变快照映射 |
| `FilePendingWriteJournal` | 未完成写入登记（GC 根） |
| `GarbageCollector` | 标记-清除回收 |
| `StoreConcurrency` | 读写锁 + 提交互斥 |
| `ObjectStoreService` | 对外门面，编排完整流程与校验 |

> 说明：本实现为**单进程、单根目录**设计；崩溃恢复语义依赖本地文件系统的原子 rename
> （同分区）。默认保留全部历史版本；删除历史内容需先显式删除引用它的快照/版本（本版未暴露
> 删除对象版本的接口，历史始终作为 GC 根保留）。
