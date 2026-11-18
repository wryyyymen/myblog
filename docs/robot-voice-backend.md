# 机器人语音后端接入

## 数据流

GEC210 开发板向 Java 后端的 TCP `54321` 端口逐轮发送 96,000 字节原始 PCM（16 kHz、单声道、16 位、3 秒）。Java 在独立 `robot` 包中接收音频，把每轮音频交给同机的 Vosk 离线识别进程 `127.0.0.1:54322`。识别进程按原项目 `x86/bin/cmd.bnf` 的九条短语映射命令 ID，返回与开发板兼容的 `nlp` XML。Java 将 XML 回送开发板，并将文字、命令 ID、置信度和时间发布给已登录的网页。

旧版讯飞 SDK 在正确系统时间下构建语法时返回 `11212`，因此本方案不依赖其授权文件、`.msc` 资源或动态库。Vosk 模型和 Python 依赖需在部署时单独安装；模型不提交到 Git。ARM 交叉编译容器只用于开发板程序；识别进程可以运行在 Windows 或 Linux x86-64 主机上。

## 安装和启动离线识别进程

在运行博客后端的 **Linux x86-64** 主机上，从仓库根目录执行：

```bash
python3 -m venv backend/native/robot_voice/.venv
backend/native/robot_voice/.venv/bin/pip install -r backend/native/robot_voice/requirements.txt
mkdir -p backend/models
curl -fL https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip -o backend/models/vosk-model-small-cn-0.22.zip
python3 -m zipfile -e backend/models/vosk-model-small-cn-0.22.zip backend/models
backend/native/robot_voice/.venv/bin/python backend/native/robot_voice/vosk_worker.py --model backend/models/vosk-model-small-cn-0.22
```

Windows 本地开发可用 PowerShell：

```powershell
py -3.14 -m venv backend/native/robot_voice/.venv
.\backend\native\robot_voice\.venv\Scripts\python.exe -m pip install -r backend/native/robot_voice/requirements.txt
New-Item -ItemType Directory -Force backend/models
curl.exe -fL https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip -o backend/models/vosk-model-small-cn-0.22.zip
py -3.14 -m zipfile -e backend/models/vosk-model-small-cn-0.22.zip backend/models
.\backend\native\robot_voice\.venv\Scripts\python.exe backend/native/robot_voice/vosk_worker.py --model backend/models/vosk-model-small-cn-0.22
```

若 Linux 系统缺少 `venv`，先安装发行版的 `python3-venv` 包。下载和安装阶段需要网络；模型安装好后识别过程离线运行。工作进程只绑定 `127.0.0.1`，模型加载完毕并输出 `Offline robot recognizer ready` 后才启动 Java 监听。`commands.json` 保留原 `cmd.bnf` 的短语与 ID；`grammar.json` 按所选中文小模型的词表切分短语，不能直接把未分词的 BNF 输入 Vosk。识别结果必须完整匹配短语，且 Vosk 词置信度不低于 70，才会输出命令 ID；有文字的未匹配、低置信度和无词置信度结果只显示文字，不触发开发板动作。这里的置信度来自 Vosk 词得分，与原讯飞 SDK 数值不可直接比较。

## Java 配置

默认不开启 TCP 监听；启用时至少设置：

```text
ROBOT_TCP_ENABLED=true
ROBOT_TCP_ALLOWED_REMOTE_ADDRESS=<开发板的 IPv4 地址>
```

`ROBOT_TCP_ALLOWED_REMOTE_ADDRESS` 为空时，Java 会拒绝启动监听。可按需设置 `ROBOT_TCP_BIND_ADDRESS`（默认 `0.0.0.0`）、`ROBOT_TCP_PORT`（默认 `54321`）、`ROBOT_WORKER_PORT`（默认 `54322`）。工作进程和 Java 必须共享同一网络命名空间的回环地址；现有 Linux `compose.yaml` 使用 host 网络，可以在宿主机运行工作进程。启动顺序：先启动工作进程，再启动后端并启用 TCP 监听，最后在开发板执行 `./voicectl <博客主机 IP>`。请勿提交本地 `.env`。

## 协议与浏览接口

| 通道 | 请求 | 响应 |
| --- | --- | --- |
| 开发板 → Java `:54321` | 每轮固定 96,000 字节原始 PCM；同一连接可发送多轮 | 一个完整 `nlp` XML；工作进程故障时为低置信度空结果 |
| Java → Vosk `127.0.0.1:54322` | 每连接固定 96,000 字节 PCM | 4 字节网络序 XML 长度，随后为完整 UTF-8 XML；每连接一轮 |
| `GET /api/robot/events` | 已登录博客 Session；浏览器用 `EventSource` 订阅 | SSE `recognition` 事件，支持 `Last-Event-ID` 重连补发内存中最近的结果 |
| `GET /api/robot/results/latest` | 已登录博客 Session | 最新识别结果；尚无结果时返回 204 |

结果 JSON 字段为 `sequence`、`text`、`commandId`、`confidence`、`occurredAt`。结果只在本进程内存中短暂保留，不入库，也不存储原始音频。前端页面尚未接入；SSE 已提供打印所需的数据源。Nginx 已为 SSE 关闭代理缓冲。

开发板当前 `wait4id()` 只读取一次、最多 1024 字节 XML；TCP 不保证一次读取收到完整报文。真实设备联调时还需完善开发板读取完整 XML 的逻辑。Java 端已按完整帧读取音频并按连接处理多轮。

## 验证

```powershell
py -3.14 -m unittest discover -s backend/native/robot_voice -p 'test_*.py' -v
cd backend
.\mvnw.cmd test "-Dspring.jpa.hibernate.ddl-auto=validate" "-Dspring.sql.init.mode=never" "-Ddebug=false"
```

Python 定向测试使用模拟识别器，不依赖模型。Java 协议测试使用模拟工作进程与开发板。已在 Windows 本地用 Vosk 0.3.45、中文小模型和原项目的 `x86/bin/pcm/cmd.pcm` 验证：TCP 返回的 XML 中 `rawtext=你好`、`cmd id=2`、`confidence=100`；静音和随机噪声没有触发命令。目标主机及真实开发板仍需进行设备联调；浏览器页面接入是后续前端工作。
