# SPEC-P10 · 全文检索:自研倒排索引 + 关键词/向量/结构化三路融合

| 项 | 值 |
|---|---|
| 期次 | P10(**Approved**,2026-10-30 所有者“都按你推荐的来”确认 D1~D8) |
| 版本 | 0.1a |
| 起草日期 | 2026-10-30 |
| 上游 | `docs/tang_db-设计方案.md` §19-P10、§2.3-AI-11、决策 D15 |
| 前置 | SPEC-P8(生产化:监控/慢查询/备份/控制台)、SPEC-P7.1(向量检索) |
| 执行顺序 | 设计方案写死 **P8 → P10 → P9**(P9 生态接入优先级仍最低) |
| 本期定位 | 让"关键词"与"语义"两条召回走**同一条查询路径**:`MATCH(...) + 结构化过滤 + 向量 ORDER BY` 一次跑完并融合排序 |

---

## 1. 为什么现在做

1. **向量有了,关键词没有**。P7 做完了 `VECTOR(n)` + HNSW,但"戒烟"这种**精确词**查询只能靠 `LIKE '%戒烟%'` —— 全表扫、无排序、无打分。语义检索擅长"意思相近",关键词检索擅长"就是这个字",两者缺一不可。
2. **倒排索引是一套独立存储结构**,不是 P7 的增量:词典、postings、跳表、压缩、doc_len、位置信息,和 HNSW 图段是并列的两套格式。塞进 P7 会把那期拖垮 —— 设计方案把它单列一期的理由就是这个。
3. **P8 刚把可观测性和备份做出来**,倒排索引正好能复用它们:建索引进度要能看、慢查询要能记、备份要能带上 `.ft` 段文件、崩溃要能不重建。
4. **生态位**:商业库里"全文 + 向量 + 过滤"三合一是卖点(tang_db 创新点 D15)。这一期做完,"文档检索"这类场景才算真的能接。

## 2. 范围

### 2.1 本期做(五组)

**A 组 · 文本分析与词典**
- 分析器(切词 / 归一化 / 停用词)在 **BE 实现**,FE 只下发"分析器名 + 原始查询串"
- ASCII 词按字母数字下划线切分并统一小写;**CJK 连续块按双字 bigram 切分**(不引任何中文词库)
- 位置信息(delta 编码)保留,供短语与邻近查询
- 停用词内置小表,可外部文件覆盖;分析器版本写进段头,**变更即需重建,不静默混用**

**B 组 · 倒排段存储**
- 每个 segment 每个全文索引列一个 `<seg>.<colid>.ft`,与 `<seg>.seg` / `<seg>.hnsw` 同级
- 段内分区:HEADER / DICT(词典) / POSTINGS(倒排表) / SKIP(跳表) / CRC
- postings 用 varint + delta(docid 与位置均 delta 编码),`tf` 与 `doc_len` 参与打分
- **原子落盘**:`.ft.tmp` → fsync → rename → MANIFEST 登记(与 P7 图段同一套模式)
- 建索引是**后台任务**,不阻塞写入;进度可查
- compaction 后**新段重建**倒排(与图段策略一致),旧 `.ft` 随段删除
- **崩溃重启直接加载已有 `.ft`,不重建全量**;只有缺失的段才补建

**C 组 · 查询与打分**
- `WHERE MATCH(col, '查询串')` 作为布尔谓词;`score` 作为伪列可投影、可排序
- 自带 **BM25**(k1=1.2 / b=0.75)自研实现,不引库
- 支持引号短语(`"戒烟 方法"`,用位置校验)、前缀(`戒*`)、布尔 AND/OR/NOT
- `EXPLAIN` 标注 `fulltext-scan`、候选数、命中段数、是走索引还是降级
- **未建全文索引时明确报错并提示建索引语句**(不静默退化成全扫)

**D 组 · 混合检索与融合排序**
- 同一条 SQL 里 `MATCH(...)` 与向量 `ORDER BY distance(...)` 可同时出现
- 融合默认 **RRF**(Reciprocal Rank Fusion,k=60);可 `SET fusion_mode='weighted'` 用加权归一
- 两路各取 topN 候选(默认 `max(limit*10, 200)`),融合后取 limit
- 融合前应用结构化谓词过滤;融合结果同分按主键稳定排序
- `EXPLAIN` 展示两路候选数、融合方式与权重

**E 组 · 运维接入与脚本**
- 指标接 P8:`fts_index_bytes` / `fts_terms` / `fts_docs` / `fts_build_seconds` / `fts_query_count`
- 慢查询(P8 系统表 10)同样记录 `MATCH` 语句;控制台库表树显示全文索引、状态区显示 fts 体积
- `scripts/tests/p10-test.sh` 覆盖 SC-466~485;评测集生成器(10 万中文文档 + 200 条带标准答案的查询)
- 三路对比脚本:纯关键词 / 纯向量 / 混合,输出同一批查询的 top-10 与命中率对照表

### 2.2 本期明确不做(逐条写死归属)

| 不做 | 归属 / 理由 |
|---|---|
| 中文分词词库、词性标注、同义词、拼写纠错 | 不做。用双字 bigram 覆盖中文,**不引任何词典**;需要词库的语义交给向量那条路 |
| Lucene / Elasticsearch / Bleve / Tantivy 等任何现成倒排库 | **红线**:依赖清单只有 grpc + protobuf,倒排全部自写 |
| 分布式分片、副本、跨 BE 的词项合并 | 本期单 BE;多 BE 的词项汇总归后续 |
| 全文索引的增量更新(段内原地更新) | 不做。段不变式(INV-01 等价)照旧:**只有新段或重建**,更新走写新段 |
| 高亮片段抽取(snippet) | 不做。前端要展示可自己切 |
| 拼写纠错 / 语义扩展 / 查询改写 | 不做。这类"猜意图"的活交给向量与后续大模型期 |
| 全文索引参与 JOIN 的连接条件(两侧都是 MATCH) | 只允许驱动侧 `MATCH`;被驱动侧 MATCH 走过滤 |
| `MATCH` 用于 `UPDATE` / `DELETE` 的 WHERE | **允许**(本期做):谓词层支持,但需走同一选路与门限 |
| 拼音、繁简转换 | 不做 |

## 3. BDD 场景

### 场景 1:关键词检索能用了
```
建表 docs(id INT PRIMARY KEY, title VARCHAR(120), content TEXT, vec VECTOR(768), category VARCHAR(32));
CREATE FULLTEXT INDEX ft_content ON docs(content);
-- 导入 10 万条中文文档,后台建索引,SHOW FTS STATUS 可见进度 → 就绪
SELECT id, title, score FROM docs
 WHERE MATCH(content, '戒烟') AND category = '健康'
 ORDER BY score DESC LIMIT 10;
→ 命中文档含"戒烟"字样,按相关性降序,EXPLAIN 显示 fulltext-scan + 候选数
```

### 场景 2:关键词与 LIKE 对得上(**正确性锚点**)
```
同一批数据下:SELECT COUNT(*) FROM docs WHERE content LIKE '%戒烟%';
             SELECT COUNT(*) FROM docs WHERE MATCH(content, '戒烟');
→ 后者必须 ≥ 前者(凡 LIKE 命中的,MATCH 必须命中)
```

### 场景 3:三路对比
```
纯关键词:WHERE MATCH(content,'戒烟') ORDER BY score DESC
纯向量  :ORDER BY l2_distance(vec, ?) LIMIT 10
混合    :WHERE MATCH(content,'戒烟') ORDER BY l2_distance(vec, ?) LIMIT 10
→ 关键词敏感集上混合 ≥ 纯向量;语义敏感集上混合 ≥ 纯关键词
```

### 场景 4(★崩溃):建索引中途强杀
```
CREATE FULLTEXT INDEX 后立刻 taskkill /F be.exe(索引仍在后台构建)
→ 重启后:BE 正常启动,SHOW FTS STATUS 显示该索引"未就绪/部分段缺索引",
  不出现"重建全量"的长时间阻塞;已完成的段直接可用;
  补建完成后查询结果与未强杀时**逐行一致**
```

### 场景 5(★并发):建索引期间持续写入
```
后台建索引的同时 8 线程持续 INSERT/UPDATE
→ 写入 P99 ≤ 不建索引时的 1.5 倍;写入零丢失;建完后新写入的数据也在索引里
```

### 场景 6:结构化过滤 + MATCH + 分页
```
SELECT id FROM docs WHERE MATCH(content,'戒烟') AND category='健康' ORDER BY score DESC LIMIT 10 OFFSET 20;
→ 与"先取出所有命中的 id 再手工过滤排序"的结果逐行一致
```

## 4. 功能需求(FR,续排:上期最大 FR-870,本期从 FR-871 起)

### A 组 · 文本分析(FR-871 ~ FR-880)

| 编号 | 需求 |
|---|---|
| FR-871 | `CREATE FULLTEXT INDEX <名> ON <表>(<列>) [WITH (analyzer='default', min_gram=2)]`;一表可建多个,一列至多一个 |
| FR-872 | 只允许 `VARCHAR(n)` / `TEXT` 列;其他类型(含 `VECTOR`)明确报错并指出列类型 |
| FR-873 | 分析器(Go,BE 侧):ASCII 词 `[A-Za-z0-9_]+` 切分 + 小写折叠;CJK 连续块按**双字 bigram**;单字块保留单字 |
| FR-874 | 归一化:全角→半角、大小写折叠、去零宽/控制字符、去 BOM |
| FR-875 | 停用词:内置小表(的/了/是/在/和/有/就/不/一个/我们/这个/那个…),可由 `fts_stopwords_file` 覆盖;停用词不计分但**保留位置占位** |
| FR-876 | postings 记录 `tf` 与 delta 位置;`doc_len` 按"有效 term 数"计,供 BM25 |
| FR-877 | 分析器名 + 版本 + `min_gram` 写入段头;与分析器参数不符的段**不参与召回**并记 warn |
| FR-878 | `SHOW CREATE INDEX <名>` / `DESC <表>` 展示全文索引及其列、分析器、状态 |
| FR-879 | **未建全文索引时 `MATCH` 报错**(错误码 1235 族)并在 message 里给出该建什么索引,不静默全扫 |
| FR-880 | 单文档文本上限 `fts_max_doc_bytes`(默认 1MB):超限截断并在慢查询/告警里记一条 |

### B 组 · 倒排段存储(FR-881 ~ FR-895)

| 编号 | 需求 |
|---|---|
| FR-881 | 段文件命名 `<seg>.<colid>.ft`,与 `<seg>.seg`、`<seg>.hnsw` 同目录同级 |
| FR-882 | 段内布局:HEADER(魔数/版本/seg_id/行数/分析器/CRC)→ DICT → POSTINGS → SKIP |
| FR-883 | 编码:varint;docid 与位置均 **delta**;`tf` 单字节(>255 用扩展);`doc_len` 定长数组 |
| FR-884 | 词典按 term 字节序排序,二分定位;支持前缀范围扫描(供 `戒*`) |
| FR-885 | SKIP 跳表:每 128 个 doc 一个跳点,支持 `advanceTo(docid)` 做交集/差集 |
| FR-886 | 原子落盘:`.ft.tmp` → `fsync` → `rename` → MANIFEST 记 `has_fts`;任何一步失败都当"该段无索引" |
| FR-887 | 缺失/损坏的 `.ft`:该段跳过关键词召回并计 `fts_missing_segments`,**其余段照常**;`EXPLAIN` 标注 `partial=true` |
| FR-888 | 建索引为后台任务:BE 落段后按段排队构建,不阻塞前台读写;`SHOW FTS STATUS` 给进度(已建段/总段/字节) |
| FR-889 | compaction 产生的新段**重建**倒排(与 P7 图段一致);被合并的旧段 `.ft` 随段删除 |
| FR-890 | 崩溃重启:**已有 `.ft` 直接 mmap/读入,不重建**;启动时间是"段数 × 头部校验",10 段内 ≤ 5 秒 |
| FR-891 | 备份(P8):`.ft` 进 `BACKUP_META` 清单并逐个校验和;恢复后**无需重建**即可查询 |
| FR-892 | 体积保护:`fts_max_index_bytes`(默认 4GB/表),超限停止建新段索引并告警,不 OOM |
| FR-893 | `DROP INDEX` / `DROP TABLE` / `DROP DATABASE`:`.ft` 文件随段生命周期回收,不留孤儿 |
| FR-894 | **BE 只认文本槽与操作码**:FE 下发(分析器参数, 原始查询串或 term 列表, topN, docid 允许集),BE 返回 `(docid, score)` 列表;BE 不知道列名/表名/SQL(BE/FE 边界不变) |
| FR-895 | 单段 term 数上限 `fts_max_terms_per_seg`(默认 500 万),超限报错并提示调大 `memtable_size` 或列选择 |

### C 组 · 查询与打分(FR-896 ~ FR-906)

| 编号 | 需求 |
|---|---|
| FR-896 | 谓词形式:`WHERE MATCH(<列>, '<查询串>')`;查询串支持空格(隐式 AND)、`OR`、`NOT`、`"短语"`、`前缀*` |
| FR-897 | `score` 伪列:可 `SELECT`,可 `ORDER BY score DESC`;未写 `ORDER BY` 时**默认按 score 降序**(与 MySQL 一致) |
| FR-898 | 打分:BM25(k1=1.2, b=0.75),用段内 `doc_len` 与全局 `avgdl`;多 term 求和 |
| FR-899 | `MATCH` 与结构化谓词共存:谓词**先**过滤出允许集再打分(结果集等价于"先过滤后匹配"),并在 `EXPLAIN` 说明 |
| FR-900 | `MATCH` 可用于 `SELECT`/`UPDATE`/`DELETE` 的 WHERE,以及子查询一层、JOIN 驱动表 |
| FR-901 | `EXPLAIN` 增列:`access=fulltext-scan`、`candidates_examined`、`segments_hit`、`partial`、`fusion` |
| FR-902 | `SHOW FTS STATUS` 列:表/列/索引名/段数/已就绪段/term 数/文档数/字节/构建进度/分析器版本 |
| FR-903 | **一致性锚点**:单词查询下,凡 `LIKE '%<词>%'` 命中的行,`MATCH` 必须命中(SC-466 逐行校验) |
| FR-904 | 空查询串 / 全停用词 / 语法错误:明确报错(1235),不静默返回空集 |
| FR-905 | `LIMIT/OFFSET` 分页与同分排序稳定(同分按主键升序) |
| FR-906 | 前缀查询 `戒*`:词典范围扫描;必须带前缀长度 ≥ `min_gram`,否则报错 |

### D 组 · 混合检索与融合(FR-907 ~ FR-916)

| 编号 | 需求 |
|---|---|
| FR-907 | 同一条 SQL 同时出现 `MATCH(...)` 与向量排序(`ORDER BY l2_distance/cosine_distance/inner_product`)→ 走混合路径 |
| FR-908 | 融合默认 **RRF**(k=60):`score = Σ 1/(k + rank_i)`;`SET fusion_mode='rrf'|'weighted'` 会话开关 |
| FR-909 | 候选数:`topN = max(limit*10, fts_candidate_floor=200)`,两路各取 topN 再融合 |
| FR-910 | `weighted` 模式:两路分数归一化到 [0,1] 后线性加权,默认 `w_fts=0.7 / w_vec=0.3`,可 `SET fusion_weight` 调 |
| FR-911 | 融合结果保留来源标记,`EXPLAIN` 与(可选)`src` 伪列输出 `fts`/`vec`/`both` |
| FR-912 | 只有一路可用(如该表未建向量索引)→ 明确降级为单路并在 `EXPLAIN` 标 `fusion=single`;两路都不可用 → 报错 |
| FR-913 | 融合前应用结构化谓词过滤;被过滤掉的行不进入任一路候选 |
| FR-914 | 结果稳定:融合同分按主键升序,分页可复现 |
| FR-915 | `EXPLAIN` 展示两路候选数、融合方式、权重、是否降级 |
| FR-916 | 混合检索必须能在评测集上量化收益(SC-477 / SC-478),不允许"跑通即交付" |

### E 组 · 运维接入与脚本(FR-917 ~ FR-926)

| 编号 | 需求 |
|---|---|
| FR-917 | 指标接 P8 双通道:`/metrics` 与 `SHOW METRICS` 增加 `fts_*` 五族(见 §5.3) |
| FR-918 | 慢查询(P8 系统表 10):`MATCH` 语句照常落 `sys_slow_query`,含 `candidates_examined` |
| FR-919 | 控制台:库表树节点显示全文索引;状态区加 `fts 体积 / 未就绪段数` |
| FR-920 | 配置项(§5.3)写入 `config/fe.conf` 与 `config/be.conf`,默认值与示例齐全 |
| FR-921 | `scripts/tests/p10-test.sh`:覆盖 SC-466~485,输出 PASS/FAIL/NOTE,支持 `SKIP_REGRESSION=1` 与 `P10_REGRESSION=1` |
| FR-922 | 评测集生成器 `scripts/tests/fts-corpus.sh`:10 万条中文文档(标题+正文,平均 80 字)+ 200 条标注查询(关键词敏感 100 / 语义敏感 100) |
| FR-923 | 三路对比脚本 `scripts/tests/fts-compare.sh`:同一批查询输出纯关键词/纯向量/混合的 top-10 与命中率表 |
| FR-924 | `fts_enabled=false` 时:全文相关命令报错(1235),其余功能完全不受影响 |
| FR-925 | README + `docs/读代码地图/读代码地图-P10.md`(本期交付物,见 §9) |
| FR-926 | 施工发现规约缺口时记入「待补充」,不静默改门限、不夹带无关重构 |

## 5. 数据与格式

### 5.1 `<seg>.<colid>.ft` 段布局

```
HEADER
  magic       8B   "TDBFTS01"
  fmt_ver     2B   1(本期)
  seg_id      8B   与 <seg>.seg 同 id
  rows        8B   段内行数(N)
  analyzer_id 2B   分析器编号(与段头分析器版本一致)
  min_gram    1B
  doc_len_off 8B   doc_len 区偏移
  dict_off    8B
  post_off    8B
  skip_off    8B
  n_terms     4B
  crc32       4B   对 header 之外全部内容
DICT        (按 term 字节序排序)
  term_len varint | term bytes | post_off varint | post_len varint | df varint
POSTINGS    (每个 term 一段)
  df varint | docid deltas(varint) | tf(varint,1 字节起) | 位置 deltas(varint)
SKIP        (每 128 个 doc 一个跳点)docid varint | post_off varint
DOC_LEN     (N 个 varint)N 个文档的有效 term 数
```

### 5.2 与 MANIFEST / format_version

- MANIFEST 的 segment 记录增字段 `has_fts: bool` 与 `fts_cols: [colid]`
- **`format_version` 由 3 升到 4**(P7.1 曾明确"不动磁盘格式",本期必须动):老数据目录启动时提示"需要重建全文索引",不做隐式迁移
- 备份(P8)清单里 `.ft` 与 `.seg` 同一级,逐个校验和;`RESTORE` 校验失败即整体拒绝

### 5.3 新增配置

| 键 | 默认 | 说明 |
|---|---|---|
| `fts_enabled` | `true` | 总开关;关掉后 `MATCH` 报错,其余不受影响 |
| `fts_analyzer` | `default` | 分析器名(本期只有 `default`:ASCII 词 + CJK bigram) |
| `fts_min_gram` | `2` | CJK 最小切分长度 |
| `fts_stopwords_file` | 空 | 停用词文件路径;空则用内置表 |
| `fts_max_doc_bytes` | `1MB` | 单文档建索引上限,超出截断 + 告警 |
| `fts_max_index_bytes` | `4GB` | 单表倒排体积上限,超出停建 + 告警 |
| `fts_max_terms_per_seg` | `5000000` | 单段 term 上限 |
| `fts_build_parallel` | `2` | 后台建索引并发段数(不占满 CPU) |
| `fts_candidate_floor` | `200` | 混合检索候选下限 |
| `fusion_mode` | `rrf` | `rrf` / `weighted`(会话可 `SET` 覆盖) |
| `fusion_weight` | `0.7` | weighted 模式下关键词权重,向量取 `1-w` |

## 6. 关键技术决策(K,续排:上期最大 K200,本期从 K201 起)

| 编号 | 决策 | 理由 |
|---|---|---|
| K201 | **分析器与打分都在 BE**,FE 只下发分析器参数 + 查询串 + 允许集 | 与 P7 的距离计算分工(图内距离 BE 算)一致;BE 仍不认列/表/SQL 语义,边界不变 |
| K202 | 中文用**双字 bigram**,不引词库 | 零依赖 + 召回保守(不会漏),误召回由打分压下去;与"凡 LIKE 命中必被 MATCH 命中"这条锚点天然契合 |
| K203 | 打分用 **BM25**(k1=1.2 / b=0.75) | 有长度归一,短文档不会被长文档淹没;参数业界默认,可调 |
| K204 | 融合默认 **RRF**,加权模式可选 | RRF 不需要分数归一化,两路分数量纲不同(距离 vs BM25)也不会打架 |
| K205 | compaction 后**整段重建**倒排,不做增量合并 | 与 P7 图段策略一致,实现简单、正确性容易保证;代价是 CPU,用 `fts_build_parallel` 与低峰期控制 |
| K206 | `format_version` 升 **4** | 新增段文件类型必须让老目录明确拒绝/提示重建,不能默认兼容 |
| K207 | 段文件名 `<seg>.<colid>.ft`(每列一个) | 多列全文索引互不干扰;列 id 稳定,重命名列不受影响 |
| K208 | postings 用 **varint + delta**(docid 与位置都 delta)+ 128 跳表 | 体积可控(SC-471 的 1.5× 上限靠它),交集可 advance,不用解全表 |
| K209 | 建索引**后台队列 + 进度可见** | 不阻塞写(SC-472);进度进 `SHOW FTS STATUS` 与指标 |
| K210 | 缺失/损坏 `.ft` → **跳过该段并标 `partial`**,不阻断查询 | 可用性优先;异常必须可见,不能静默少召回 |
| K211 | 前缀查询用**词典范围扫描**,不用 trie | 词典已排序,范围扫描零额外结构;trie 的内存与落盘成本本期不值 |
| K212 | 停用词**保留位置占位** | 短语查询里 `"这个 戒烟 方法"` 去掉停用词后位置不塌陷,短语仍能对上 |
| K213 | `MATCH` 未建索引时**报错**,不退化全扫 | 静默全扫会让 10 万行表出现秒级查询还查不出原因(P8 慢查询会记成"很正常的一句") |
| K214 | 混合检索走**同一候选管道**:两路各自取 topN → 融合 → 回表 | 避免"融合后再回表"造成的大结果集;与 P7.1 的批量回表复用 |
| K215 | `.ft` 进备份清单并校验和 | P8 的备份承诺是"恢复即可用";不带上就得重建,违背 SC-474/FR-891 |

## 7. 验收标准(SC,续排:上期最大 SC-465,本期从 SC-466 起)

| 编号 | 标准 | 门限 |
|---|---|---|
| SC-466 | **关键词正确性锚点**:单词查询下凡 `LIKE '%词%'` 命中的行,`MATCH` 必中 | 逐行一致,0 漏 |
| SC-467 | 排序质量:100 条构造语料,期望文档进 top-1 | ≥ 90/100 |
| SC-468 | 召回:200 条标注查询的 top-10 召回率 | ≥ 95% |
| SC-469 | 关键词检索时延:10 万文档(平均 80 字)MATCH top-10 | **P99 ≤ 50ms**(空载) |
| SC-470 | 并发退化:8 线程 20s 混合读,P99 相对空载 | **≤ 2×** |
| SC-471 | 倒排体积:索引字节 / 原文 UTF-8 字节 | **≤ 1.5×** |
| SC-472 | 建索引不阻塞写:构建期间 8 线程写 P99 | **≤ 1.5×**(无构建时) |
| SC-473 | ★崩溃:建索引中途强杀 → 重启 | 启动 ≤ 10s,不重建已完成段,补建后结果与强杀前**逐行一致** |
| SC-474 | ★崩溃:已就绪索引的段强杀重启 | 加载 ≤ 5s,查询结果与重启前逐行一致 |
| SC-475 | compaction 后新段重建 | 查询结果逐行一致,`SHOW FTS STATUS` 段数与 MANIFEST 一致 |
| SC-476 | `EXPLAIN` 可解释 | 含 `access=fulltext-scan`、`candidates_examined`、`segments_hit`、`partial`、`fusion` |
| SC-477 | 混合收益(关键词敏感集 100 条) | top-10 命中率比纯向量**高 ≥ 10 个百分点** |
| SC-478 | 混合收益(语义敏感集 100 条) | top-10 命中率比纯关键词**高 ≥ 10 个百分点** |
| SC-479 | 结构化过滤 + MATCH 正确性 | 与"手工先过滤再匹配"逐行一致 |
| SC-480 | 短语查询(引号) | 位置校验正确,构造 30 条用例全对 |
| SC-481 | 停用词 | 不参与打分,短语位置不塌陷 |
| SC-482 | `DROP INDEX` 后再 `MATCH` | 明确报错 + 提示重建,不 500 |
| SC-483 | `fts_enabled=false` | 全文命令全报错,其余功能零影响(回归不红) |
| SC-484 | 可观测 | `/metrics` 与 `SHOW METRICS` 都有 `fts_*`;控制台可见体积与未就绪段数 |
| SC-485 | 一键验收 | `p10-test.sh` PASS=*** / FAIL=0;文档与读代码地图齐全 |

**不达标一律在验收报告里写原因,不许改门限、不许把"能跑通"当"达标"。**

## 8. 边界与异常(续排:上期最大 190,本期从 191 起)

| 编号 | 边界 | 期望行为 |
|---|---|---|
| 191 | 列值为 `NULL` | 不建索引项,不影响其他行 |
| 192 | 空串 `''` | 无 term,`doc_len=0` |
| 193 | 全是停用词 | 无有效 term,`MATCH` 该词返回空集(不报错) |
| 194 | 超长文本(> `fts_max_doc_bytes`) | 截断建索引 + 告警 + 慢查询记一条 |
| 195 | 单 term 超长(> 255 字节) | 丢弃该 term 并计 `fts_oversize_terms` |
| 196 | 纯 emoji / 符号 | 按 ASCII 规则丢弃,不产生 term |
| 197 | 中英混排 `戒烟quit smoking` | 中文走 bigram,英文按词,位置连续 |
| 198 | 数字串 `2026-10-30` | 切成 `2026`/`10`/`30`,连字号视为分隔 |
| 199 | 全角 `ＡＢＣ` | 归一化为 `abc` 后建索引,查询侧同样归一化 |
| 200 | 零宽字符 / BOM | 建索引前剔除,查询侧同样剔除 |
| 201 | 大小写混用 | 统一小写,查询不区分大小写 |
| 202 | 同一段内多列都建全文索引 | 各自独立 `.ft`,互不影响 |
| 203 | 同一列重复建索引 | 报错(一列至多一个全文索引) |
| 204 | 对 `VECTOR` 列建全文索引 | 报错并指出列类型 |
| 205 | 表无主键 | 允许(行号定位沿用引擎既有机制) |
| 206 | 事务内写入后同事务查询 `MATCH` | 读己之写可见(走 memtable 未落段时的降级路径) |
| 207 | memtable 未落段时查询 | 未落段部分**不参与召回**并在 `EXPLAIN` 标 `uncommitted_skipped`(本期限制,写清楚) |
| 208 | 索引构建中查询 | 已完成段照常召回,未完成段跳过并标 `partial` |
| 209 | `UPDATE` 命中 `MATCH` | 先召回再更新;更新后旧 term 仍在旧段(段不变式),新段重建后一致 |
| 210 | `DELETE` 命中 `MATCH` | 同上,删除标记生效后不再召回 |
| 211 | 备份中途建索引 | 备份快照里 `.ft` 可能缺失 → `RESTORE` 后标"需重建",不报错 |
| 212 | 恢复旧目录(`format_version=3`) | 明确提示"需重建全文索引",不隐式迁移 |
| 213 | 磁盘满 | 建索引失败不损坏已有段;查询照常 |
| 214 | `LIMIT` > 命中数 | 返回全部命中,不补空 |
| 215 | 融合时一路 0 命中 | 退化为另一路排序,`fusion=single`,不报错 |

## 9. 交付物

1. 代码:BE `store/fts/`(分析器 / 段读写 / 跳表 / 打分)+ FE 解析(`MATCH`)、选路(`fulltext-scan`、混合)、`EXPLAIN` 扩展
2. 脚本:`scripts/tests/p10-test.sh`、`fts-corpus.sh`、`fts-compare.sh`
3. 文档:`docs/验收报告/P10-验收报告.md`、`docs/读代码地图/读代码地图-P10.md`
4. 数据:`specs/invariants.md` 新增 INV-22(倒排段不变式)、INV-23(融合结果稳定性)

## 10. 已拍板事项(2026-10-30 所有者“都按你推荐的来”,全部取推荐项)

| 编号 | 决策点 | 结论 |
|---|---|---|
| D1 | 中文切分 | **A 双字 bigram**:零依赖、召回保守、天然满足“LIKE 命中必被 MATCH 命中”(SC-466) |
| D2 | 打分 | **A BM25**(k1=1.2 / b=0.75):有长度归一,短文档不被淹没 |
| D3 | 融合 | **A RRF 默认 + weighted 可选**:两路量纲不同(距离 vs BM25)不需要归一化 |
| D4 | compaction | **A 整段重建**(与 P7 图段一致),CPU 代价用 `fts_build_parallel` 与低峰控制 |
| D5 | `format_version` | **A 升 4 + 老目录提示重建**,不做隐式迁移 |
| D6 | 默认行为 | **A 必须显式 `CREATE FULLTEXT INDEX`**,不自动给字符串列建索引 |
| D7 | 未落段数据 | **A 本期不参与召回 + `EXPLAIN` 标 `uncommitted_skipped`**(memtable 临时倒排留待后续) |
| D8 | 门限 | **A 按 §7 的 20 条原样执行**,不调 |

## 11. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1 | 2026-10-30 | 初稿(Draft):五组范围、FR-871~926、K201~215、SC-466~485、边界 191~215、待拍板 D1~D8 |
| 0.1a | 2026-10-30 | 所有者确认 D1~D8 全部取推荐项,状态 Draft → **Approved**(未开工) |
