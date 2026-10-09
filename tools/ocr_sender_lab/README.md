# 群 Bot 发送人 OCR 样本实验室

用真实微信截图多轮回归：**能不能稳定认出「谁 @ 了 Bot」**。

## 目录

```
tools/ocr_sender_lab/
  samples/          ← 把截图丢这里（png/jpg）
  labels.json       ← 每张图的期望发送人 / Bot 昵称
  bench.py          ← 批跑脚本
  out/              ← 每次运行的明细（自动生成）
```

## 标注 labels.json

```json
{
  "botNickname": "豆姐",
  "cases": [
    {
      "id": "01_moky_lunch",
      "file": "01_moky_lunch.png",
      "expectedSender": "Moky",
      "triggerContains": "@豆姐",
      "notes": "Moky @豆姐问午饭"
    },
    {
      "id": "02_tang_status",
      "file": "02_tang_status.png",
      "expectedSender": "汤恒宇",
      "triggerContains": "@豆姐",
      "notes": "汤恒宇 @豆姐，中间有人插话也可以"
    }
  ]
}
```

- `expectedSender`：正确群昵称（和微信气泡上方一致）
- `triggerContains`：触发句里应出现的片段（用来确认扫到了那条 @）
- 认不出发送人但脚本产出「群友」→ 记为 **FAIL（无昵称）**
- 产出别人名字 → **FAIL（认错人）**

## 安装依赖（本机 Python 3.10+）

```bash
pip install rapidocr-onnxruntime pillow
```

若 `rapidocr` 装不上，可改试：

```bash
pip install easyocr
```

脚本会按 rapidocr → easyocr 自动选引擎。

## 跑一轮

在仓库 `apps/joy` 下：

```bash
python tools/ocr_sender_lab/bench.py
```

或指定目录：

```bash
python tools/ocr_sender_lab/bench.py --samples tools/ocr_sender_lab/samples --labels tools/ocr_sender_lab/labels.json
```

输出：

- 终端：每张图的期望 / 实际 / 是否通过，以及总准确率
- `out/last_run.json`：完整摘录，方便对照改规则后复跑

## 多轮调试流程

1. 你丢 5～15 张失败/成功截图进 `samples/`，填好 `labels.json`
2. 跑 `bench.py`，记下 FAIL 类型（无昵称 / 认错人 / 没扫到 @）
3. 改 Kotlin 几何规则（或先改本脚本里的镜像规则做实验）
4. 再跑同一批样本，看准确率是否上升
5. 规则稳了再同步回 App 的 `OcrChatFormatter` / `GroupBotMention`

**注意：** 脚本用 RapidOCR/EasyOCR，和手机上的 ML Kit 引擎不同，框会有偏差；  
几何规则对齐后，仍建议在真机用同一批图抽查。认人主瓶颈若是「引擎根本没读出小字昵称」，两边都会挂。
