# Evaluation fixtures — SYNTHETIC

此目录根部的 `rag-agent-eval-cases.jsonl`、`rag-retrieval-golden-cases.jsonl`、`research-cases.jsonl`、`research-workflow-golden-cases.jsonl`、`training-plan-cases.jsonl` 和 `baselines/research-workflow-v1.json` 全部按 **SYNTHETIC / 手工构造回归夹具** 使用，不是已审核真实用户样本或模型成绩。旧 Java JSONL schema 没有 sourceType 字段，此说明明确其来源边界；不要将其改标 REAL_USAGE。

- `rag-retrieval-golden-cases.jsonl`：示意问题与占位文档 ID，验证加载器；并不对应部署中自动生成的真实向量 ID。
- `research-cases.jsonl`：预置 Outcome，仅验证指标计算/序列化，不执行研究流程。
- `research-workflow-golden-cases.jsonl` 与 `baselines/research-workflow-v1.json`：固定假搜索/抓取供应商，执行真实研究工作流的确定性回归。baseline 是 mock 行为预期，不是线上成绩。
- `training-plan-cases.jsonl`：输入/草稿变体，固定生成器验证澄清、安全、约束和引用。
- `rag-agent-eval-cases.jsonl`：旧 LLM judge runner 的问题示意，运行可能调用付费模型；不纳入本次确定性门禁。
- 旧 ResearchMetrics/TrainingPlanMetrics 的部分空分母 ratio=1 是历史 helper 约定，不应被解释为通过率或用于日常 Eval。新 Python 流程保留 0/0 和 UNKNOWN。

`private/` 只存私有、人工定义标准的晋升案例，Git/Docker 忽略；`public/` 只允许逐字段人工隐私审核过的真实样例。当前未创建 public REAL_USAGE 样例。合成 runner 示例必须显式带 `sourceType: SYNTHETIC`。

真实 raw、reviews、salts 与 runner 报告不进入此公开夹具目录，默认保存在被忽略的 `output/`。公开门禁见 [eval-cli](../../../../docs/eval-cli.md)，测试筛选见 [test-audit](../../../../docs/test-audit.md)。
