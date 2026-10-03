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

当前工具建立了可重复的测量方法，但仓库未包含许可明确且人工标注完成的多人会议真值集。正式准确率门槛应在收集代表性中文会议样本后确定；现有四人真实链路只证明系统可运行，不能证明 WER 或 DER 达标。
