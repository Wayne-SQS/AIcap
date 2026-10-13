# 会议 Agent 语音质量评测

本工具离线比较人工真值、`transcription-draft.json` 和 `diarization-preview.json`，输出转写错误率、说话人分离错误率和词级时间戳覆盖率。它不调用模型、不上传音频，也不把没有人工真值的演示录音计入准确率。

## 准备真值

复制 `ai-service/evals/speech_reference.example.json`，逐字核对录音并标注真实说话人时间段：

- `sample_id`：稳定且不含个人信息的样本编号；
- `duration_ms`：必须与两份模型产物完全相同；
- `text`：人工确认的完整发言文字；
- `turns`：`start_ms/end_ms/speaker_id`，允许不同说话人的时间段重叠，同一说话人的时间段不能重叠。

说话人名称只用于同一个评测文件，可以写 `PERSON_A`。工具不会假定模型的 `SPK1` 对应某个固定人物，而是按重叠时间求一对一最优映射。

## 执行

在 `ai-service/` 目录运行：

```powershell
.venv/Scripts/python.exe -B evaluate_speech.py `
  --reference evals/my-sample.reference.json `
  --transcription ../qa/.daily-live/<run>/transcription-draft.json `
  --diarization ../qa/.daily-live/<run>/diarization-preview.json `
  --output evals/results/my-sample.report.json
```

输入读取失败、真值为空、时间范围非法或三份产物时长不同都会失败，不生成部分成绩。输出标记 `data_classification=explicit_ground_truth`，便于和合成流程测试区分。

## 指标口径

- `wer.error_rate = (替换 + 删除 + 插入) / 真值 token 数`。中文每个汉字是一个 token，英文和数字按连续单词处理，执行 NFKC、大小写和标点归一化。因此中文部分更接近常用 CER，字段保留 WER 便于形成统一验收报告。
- `der.error_rate = (漏检 + 误检 + 说话人混淆) / 真值说话人毫秒数`。匿名标签先按时间重叠做最优一对一映射；边界容差为 0 ms，重叠语音参与计分。
- `word_alignment.word_timestamp_coverage` 表示具有合法时间戳的词数比例；`audio_time_coverage` 是这些词时间段的并集占录音时长比例；`speaker_overlap_coverage` 表示有效词时间段与任一分离时间段有交集的词数占全部词数比例。

仓库现已保存一份不含原始音频和逐字稿的 AISHELL-4 基线摘要：`ai-service/evals/aishell4_speech_baseline.json`。原始数据由运行者按固定 URL 与 SHA-256 下载到忽略目录；正式发布门槛仍应在更多会议、设备和业务词汇样本上确定。

## AISHELL-4 中文多人会议基线

使用 OpenSLR 111 发布的 AISHELL-4 测试录音 `L_R003S01C02`。数据集许可为 CC BY-SA 4.0；TextGrid 提供说话人、时间和转写，RTTM 提供说话人活动。仓库不提交约286 MB原音频、标注或含逐字内容的单次产物，只提交下载校验值、适配器、执行脚本和不含文本的汇总报告。

在 `ai-service/` 目录首次运行：

```powershell
.venv/Scripts/python.exe -B ../qa/run_aishell4_baseline.py --download `
  --start-seconds 0 --duration-seconds 300
```

其后分别评测 `600–900s` 与 `1200–1500s`，再用 `evaluate_speech_suite.py` 汇总三份报告。汇总按原始错误计数计算，不对每窗百分比取算术平均；重复样本、口径不一致、伪造百分比或来源不一致会失败。

| 窗口 | 参考说话人数 | WER | DER |
| --- | ---: | ---: | ---: |
| 0–300s | 6 | 41.60% | 7.73% |
| 600–900s | 5 | 64.93% | 30.77% |
| 1200–1500s | 4 | 66.77% | 7.86% |
| **加权汇总 900s** | — | **57.96%** | **16.16%** |

汇总含3199个参考token、1952个模型词；词时间戳覆盖率100%，词与任一说话人时间段重合率99.80%。当前Whisper base中文转写必须人工复核；DER也存在明显窗口波动。AISHELL-4包含重叠发言，而产品当前输出单流文字，所以评测会把按起始时间合并的重叠参考文字计入WER；这个限制会抬高错误率，但不能解释全部识别错误。
