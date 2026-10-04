# Online Feature Store

在线特征服务：离线侧持续写入新算出的特征，线上侧按推理请求给出的时间点读取特征。
实现基于 Spring Boot，存储为内存实现（接口可替换为持久化实现）。

## 架构

```
写入方 ──> FeatureLog(append-only, offset 单调递增)
                │
                ├─> Materializer ──> FeatureStore(版本化时间线) ──> 读取方
                │        └─ CheckpointStore(提交位点)
                └─> LogConsumer × N(各自独立位点)
```

- `FeatureLog`：只追加日志，写入的最小提交单位是 offset。
- `Materializer`：按 offset 顺序把日志应用到 `FeatureStore`，**先应用、后提交位点**。
- `FeatureStore`：每个 (entity, feature, version) 一条按 eventTime 排序的时间线；
  批量写在写锁内原子生效，读在读锁内只能看到完整的已提交批次。
- `FeatureRegistry`：特征定义的版本化注册与下线。

## 时间语义（Point-in-Time）

- 每个特征值携带 `eventTime`（特征产生的事件时间）。
- 读取 `getFeatures(entityId, requests, asOf)` 对每个特征返回
  `eventTime <= asOf` 的最新值；**任何情况下都不会返回 eventTime 晚于 asOf 的值**
  （通过时间线的 `floorEntry(asOf)` 保证，无特征穿越）。
- 之后到达的写入不会改变更早 asOf 的读取结果。

## 乱序、重复与迟到写入

- 同一 (entity, feature, version, eventTime) 的多次写入按 `(seq, sourceId)`
  确定性裁决（seq 大者胜，相等时 sourceId 字典序大者胜）。
- 因此同一批写入**无论以什么顺序应用，最终状态一致**；重复应用是幂等空操作。
- **迟到写入策略**：eventTime 早于当前最新值的写入一律接受，按 eventTime 插入
  时间线归位；它对 asOf 覆盖它的读取可见，且不会篡改更早 asOf 的历史结果。

## 一致性（并发读写）

- 一个写入批次在单个写锁内整体生效：读者只能看到批次应用前或应用后的完整状态，
  不存在半更新。
- 特征值对象不可变（`Map.copyOf`），不存在字段级混合。

## 新鲜度与过期

每个特征版本可配置 `ttl` 与 `stalenessPolicy`：

- 判定边界：`eventTime + ttl >= asOf` 为新鲜（**恰好等于 ttl 仍新鲜**）；
  `eventTime + ttl < asOf` 为过期。`ttl = null` 表示永不过期。
- 过期处理策略 `StalenessPolicy`：
  - `MARK_STALE`：照常返回值，但 `stale = true`（默认）。
  - `FAIL`：视为缺失（`missing = true`）。
  - `FALLBACK_TO_PREVIOUS`：沿版本号向下找最近一个在 asOf 未过期、且仍在
    保留期内的特征版本并返回其值；全部不可用则视为缺失。

## 恢复语义

- 位点（checkpoint）只在对应批次**完整应用后**才提交，且单调递增（提交更小的
  位点会被忽略）。
- 物化中断后重启：`Materializer` 从最近已提交位点继续。若崩溃发生在
  "已应用、未提交"之间，该批次会被重新应用——由于写入幂等，**效果上不重复、
  不跳过**。
- 重启后已提交状态（CheckpointStore 中的位点）与未完成状态（日志中位点之后的
  条目）始终可区分。

## 多消费方

- `LogConsumer` 各自持有独立位点，直接从共享日志拉取，互不阻塞；
  慢消费方不会阻塞快消费方，也不会拖住写入链路（写入只依赖日志追加）。
- 消费方重启后用自己的 id 从各自位点恢复。

## 版本兼容

- 同一特征可注册多个版本，版本号必须单调递增；允许多个版本同时处于活跃状态
  （便于迁移），未指定版本的读取解析到最高活跃版本。
- 版本下线（deprecate）后在 `retentionPeriod` 保留期内仍可被显式指定版本的
  请求读取；保留期过后按缺失处理。**任何情况下读取都不因版本上下线而报错**，
  未知特征/版本一律返回 `missing`。

## 配置项（`feature-store.*`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `feature-store.default-ttl` | `1d` | 特征值默认 TTL（null 表示永不过期） |
| `feature-store.default-staleness-policy` | `MARK_STALE` | 默认过期策略 |
| `feature-store.default-retention-period` | `7d` | 已下线版本的默认保留期 |
| `feature-store.materializer-batch-size` | `500` | 物化器每批应用的日志条数 |

## REST API

- `POST /api/features/register` — 注册特征版本（body 为 FeatureDefinition）
- `POST /api/features/deprecate?name=&version=&when=` — 下线版本
- `POST /api/features/write` — 写入特征值批次
- `POST /api/features/read` — 按时间点读取 `{entityId, features:[{featureName, featureVersion?}], asOf}`
- `GET  /api/features/read?entityId=&features=&asOf=` — 按当前活跃版本读取

## 测试

`src/test/java/.../featurestore/` 下覆盖：特征穿越（PointInTimeReadTest）、
乱序/重复/迟到（WriteOrderTest）、并发读写（ConcurrencyTest）、过期策略
（StalenessTest）、中断恢复与重启（RecoveryTest）、多消费方（MultiConsumerTest）、
版本兼容（VersionCompatibilityTest）。

运行：`mvn test`
