# Online Feature Store

这是一个基于 Spring Boot 的在线特征服务。离线物化任务以“提交”为单位写入特征，在线请求以事件时间 `asOfEpochMillis` 读取特征。服务把完整提交持久化到本地文件日志，重启后重放已提交文件，未完成的临时文件不会进入状态。

## 1. 时间语义与防穿越

读取使用：

```text
floor(eventTimeEpochMillis <= asOfEpochMillis)
```

因此：

- 永不返回 `eventTimeEpochMillis > asOfEpochMillis` 的特征；
- `eventTimeEpochMillis == asOfEpochMillis` 可取；
- 提交接收时间 `commitTimeEpochMillis` 只用于审计，不参与时间点读取；
- 晚到写入不会影响过去时间点的隔离边界：查询过去某个 `asOf` 时，仍只看该时间点之前的 `eventTime`；
- 未指定版本时，自动选择在 `asOf` 已生效、且当前可读的最高生效时间版本。

注意：服务不会用当前墙上时钟拒绝历史事件时间。历史回填和乱序物化是合法输入；防穿越完全由读取端的事件时间边界保证。

## 2. 版本注册

`POST /features/versions`

```json
{
  "featureName": "price",
  "version": "v2",
  "effectiveFromEpochMillis": 1500,
  "ttlMillis": 100,
  "freshnessStrategy": "FALLBACK_PREVIOUS",
  "lateWriteStrategy": "REJECT",
  "allowedLatenessMillis": 1000,
  "retentionMillis": 86400000
}
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `effectiveFromEpochMillis` | 版本开始生效的事件时间。 |
| `ttlMillis` | 新鲜度时长；`asOf - eventTime <= ttlMillis` 判定为新鲜，边界等于 TTL 仍新鲜。 |
| `freshnessStrategy` | `MARK_STALE` 或 `FALLBACK_PREVIOUS`。 |
| `lateWriteStrategy` | `ACCEPT` 或 `REJECT`。 |
| `allowedLatenessMillis` | 相对当前特征/版本水位允许的晚到范围，边界等于允许时长时不拒绝。 |
| `retentionMillis` | 版本退役后仍允许显式读取的保留时长，边界等于退役时间加保留期时仍可读。 |

相同配置重复注册返回 `ALREADY_REGISTERED`，不会改变状态。配置不同返回 `REJECTED`。

## 3. 乱序、重复与同时间点冲突

`POST /materialization/commits`

```json
{
  "commitId": "offline-job-20261004-0001",
  "commitTimeEpochMillis": 5000,
  "writes": [
    {
      "featureName": "price",
      "version": "v2",
      "eventTimeEpochMillis": 2000,
      "valueJson": "{\"amount\":110}",
      "revision": 1
    }
  ]
}
```

提交是原子的：所有写入先校验，再落盘，再一次性发布到内存状态。读者不会看到半批写入。

确定性规则：

1. 完全相同的 `(featureName, version, eventTimeEpochMillis, revision, valueJson)` 是重复写入，会被忽略且不改变结果。
2. 同一特征、版本和事件时间支持多个 `revision`；读取该时间点时返回最高 `revision`。
3. 已存在更高 `revision` 后到达的较低 `revision` 是过期重放，会被统计为 `ignoredLowerRevisionWrites`，不会覆盖高版本。
4. 相同特征、版本、事件时间和 `revision` 但值不同是冲突，整个提交拒绝。
5. 相同 `commitId` 重放：`writes` 完全一致时返回 `DUPLICATE`，即使重试时的 `commitTimeEpochMillis` 不同也不重复应用；`writes` 不同返回 `REJECTED`。

晚到策略：

- 水位是该 `(featureName, version)` 已见到的最大事件时间。
- `REJECT`：当 `watermark - eventTime > allowedLatenessMillis` 时，整个提交拒绝，原因中包含水位、事件时间和允许范围。
- `ACCEPT`：即使超出允许窗口也接受，但仍遵守上述 revision、重复和冲突规则。
- 边界 `watermark - eventTime == allowedLatenessMillis` 不算晚到。

## 4. 新鲜度与过期策略

`GET /features/{featureName}/value?asOfEpochMillis=2000&version=v2&consumerId=online-a`

不指定 `version` 时由服务自动选择版本。

### `MARK_STALE`

- 新鲜：`FOUND`；
- 超过 TTL：返回 `STALE`，同时保留旧值，由调用方决定是否使用；
- 不存在时间点之前的值：`NO_DATA`。

### `FALLBACK_PREVIOUS`

- 当前选择版本存在新鲜值：返回该版本的 `FOUND`；
- 当前版本过旧或缺值：查找生效时间严格更早、在 `asOf` 可读且有新鲜值的最近版本；
- 找到回退值：仍返回 `FOUND`，但 `fallback=true`，响应中的 `version` 是实际返回的版本；
- 找不到任何新鲜回退：有旧值返回 `EXPIRED`，没有值返回 `NO_DATA`。

其他状态：

- `UNKNOWN_FEATURE`：特征名从未注册；
- `UNKNOWN_VERSION`：显式请求的版本未注册；
- `VERSION_RETENTION_EXPIRED`：版本退役且超过保留期；
- `INVALID_REQUEST`：参数缺失或时间不是正数。

## 5. 版本兼容性与退役

新版本注册后，旧版本读取请求不会报错。旧版本只有在调用 `POST /features/{featureName}/versions/{version}/retire?retireTimeEpochMillis=...` 后才进入退役状态。

- 退役后 `retentionMillis` 内，显式读取旧版本仍可成功；
- 超过保留期返回 `VERSION_RETENTION_EXPIRED`；
- 自动选版本不会选择在 `asOf` 已超出保留期的退役版本；
- 退役操作幂等；重复退役时间一致返回 `ALREADY_RETIRED`。

## 6. 恢复语义

持久化目录由配置项 `feature-store.dir` 指定，默认 `data/feature-store`：

```text
data/feature-store/
  versions/    # 每个版本一个原子 JSON 文件
  commits/     # commit-00000000000000000000.json，按序列号命名
  consumers/   # 每个消费方一个确认偏移文件
```

提交写入采用临时文件加原子 rename。启动时：

1. 删除所有 `.tmp` 文件，它们表示未完成状态；
2. 按序列号读取并校验完整 commit 文件；
3. 重放提交，重建索引、水位和事件流；
4. `lastCommittedSequence` 从最近完整提交继续；
5. 使用相同 `commitId` 重试已提交内容返回 `DUPLICATE`，不会重复应用。

查询恢复信息：

`GET /admin/recovery`

```json
{
  "lastCommittedSequence": 12,
  "lastCommitId": "offline-job-0012",
  "recoveredCommitCount": 13,
  "discardedTempFileCount": 0
}
```

## 7. 多消费方事件流

所有已提交写入形成一条只追加的全局事件流，每条记录都有全局 `offset` 和所属提交的 `sequence`。

轮询：

`GET /streams/{consumerId}/poll?offset=0&maxEvents=100`

确认：

`POST /streams/{consumerId}/acknowledge?offset=99`

进度：

`GET /streams/{consumerId}/progress`

不同 `consumerId` 的确认位置分别持久化。慢消费方只保存自己的 offset，不会阻塞快消费方，也不会阻塞写入链路。当前实现保留完整事件日志用于独立重放，因此慢消费方需要的历史事件不会被删除。

## 8. 常用接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/features/versions` | 注册版本配置。 |
| `GET` | `/features/versions` | 查看版本配置。 |
| `POST` | `/features/{f}/versions/{v}/retire` | 退役版本。 |
| `POST` | `/materialization/commits` | 原子提交离线写入。 |
| `GET` | `/features/{f}/value` | 按事件时间读取特征。 |
| `GET` | `/streams/{c}/poll` | 消费提交事件流。 |
| `POST` | `/streams/{c}/acknowledge` | 持久化消费进度。 |
| `GET` | `/streams/{c}/progress` | 查询消费进度与高水位。 |
| `GET` | `/admin/recovery` | 查询重启恢复信息。 |

## 9. 测试

运行：

```bash
mvn test
```

测试覆盖：

- 时间穿越防护；
- 同事件时间乱序 revision；
- 提交内和跨提交重复写入；
- 重复 `commitId` 幂等；
- 晚到接受与拒绝；
- TTL 边界、过期标记和上一版本回退；
- 并发提交与读取；
- 崩溃后临时文件丢弃、完整提交重放和序列号续接；
- 多消费方独立 offset；
- 新版本注册、旧版本退役与保留期。

## 10. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `0` | 骨架默认随机端口；生产环境可显式指定。 |
| `feature-store.dir` | `data/feature-store` | 持久化根目录，也可用环境变量 `FEATURE_STORE_DIR` 覆盖。 |

