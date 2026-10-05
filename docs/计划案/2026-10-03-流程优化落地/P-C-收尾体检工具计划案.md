# P-C 小计划案：收尾体检工具 check_session_close.py

## 目标

把「完成前三查」从规则条文变成一键脚本：一条命令回答"这个项目现在能不能声称完成"。放在 `C:\Agent\AImanager\tools\`，与 check_modules.py、git_push_github.py 同目录同生态。

## 交付物

`C:\Agent\AImanager\tools\check_session_close.py`（仅 Python 标准库 + git 命令，零第三方依赖），Windows 下 GBK 控制台不乱码（自身 stdout 显式 UTF-8；子进程输出也按 UTF-8 解码，env 注入 `PYTHONIOENCODING=utf-8`，防止中文 JSON 解码失败误入"工具故障"分支）。

## 功能规格

用法：`python check_session_close.py <项目路径> [--allow-wip "原因说明"]`；`--help` 打印用法与三项检查的一句话说明（argparse 实现）。

**查一 · 模块总览**：子进程调用同目录 `check_modules.py <项目路径> --json`，解析其 JSON 的 `rows[].status`（`ok` / `可能过期` / `无关联文件`），不解析人读表格。退出码映射（check_modules 实测语义：0=全绿 / 1=无总览目录 / 2=有过期）：子进程 0→查一绿；2→查一红，列出 status=`可能过期` 的模块；1→查一绿但提示"该项目未建总览目录"；其余返回码或 JSON 无法解析→本工具退出 2（工具故障），不把子进程的 2 误判为自己的故障。复用而非复制逻辑，不改 check_modules.py 一行。

**查二 · git 工作区**：先 `git rev-parse --is-inside-work-tree` 确认是 git 仓库（失败→查二黄档跳过，提示"非 git 仓库，跳过未提交检查"）。是 git 仓库时跑 `git status --porcelain`：空=绿；非空且未给 `--allow-wip`=红，列出未提交文件并提示"commit 或用 --allow-wip 声明原因"；给了 `--allow-wip "原因"`（非空串）=放行，输出注明"已声明原因：<原因>"与文件数；`--allow-wip ""` 空串视为未声明。

**查三 · 敏感信息扫描**：非 git 仓库同查二黄档跳过（`git diff HEAD` 前提不成立）。是 git 仓库时对两条路径分别扫描，任一命中即红：`git diff HEAD`（已跟踪改动；零提交仓库无 HEAD 时跳过此路径，仅扫未跟踪——此时全部文件本就未跟踪，覆盖完备）；`git ls-files --others --exclude-standard` 枚举的全部未跟踪文件（gitignore 的文件不入库、不扫）：
- 文件名后缀：`.jks` `.keystore` `.pem` `.p12` `.kdbx`（`.env.example` 豁免）；
- 内容模式（大小写不敏感）：`password/passwd/secret/api_key/apikey/token` 后跟 `=` 或 `:` 再跟 ≥6 位非空值；`-----BEGIN ... PRIVATE KEY-----`；
- 单文件 >5MB 记黄色提醒（不算红）。
- 报告时注明命中来源路径（"已跟踪改动"或"未跟踪文件：<文件名>"）。

**输出纪律**：命中的值一律掩码显示（前 2 后 0，中间 ***），绝不打印完整疑似密钥；每项检查给 ✓/✗ 和一句人话结论。

**退出码**：0=三项全绿（含已声明的放行与黄档跳过）；1=有红项；2=工具自身故障（git 不可用、路径不存在、子进程输出不可解析），不得以异常堆栈代替报告。

## 验收标准（自测矩阵，全部必须实测通过）

fixture 构造约定：每场景在临时目录 `git init` + `git config user.email/name`（不依赖本机全局配置）+ 写文件；场景 7 的"过期总览"用「写总览 → 写关联代码文件 → `os.utime` 把代码 mtime 拨到晚于总览」构造，不靠 sleep。

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 干净且总览新鲜的仓库 | 退出 0，三项 ✓ |
| 2 | fixture 有未提交文件、未声明 | 查二红，退出 1 |
| 3 | 同上但 `--allow-wip "测试"` | 查二放行，退出 0 |
| 4 | 同上但 `--allow-wip ""`（空串） | 等同未声明，查二红退出 1 |
| 5a | 已跟踪文件改为含 `db_password = "<值掩码>"`（未提交） | 查三红，值被掩码，来源标注"已跟踪改动" |
| 5b | 未跟踪新增文件含 `api_key = "<值掩码>"` | 查三红，值被掩码，来源标注"未跟踪文件：<名>" |
| 6 | fixture 含 fake.jks（未跟踪） | 查三红 |
| 7 | fixture 含未跟踪 `notes.env.example` 且内容带 `token = "<值掩码>"` | 豁免不红 |
| 8 | fixture 含 >5MB 无后缀大文件（未跟踪）且传 `--allow-wip "大文件"` | 查二放行；黄档提醒不算红，退出 0 |
| 9 | 传不存在的路径 | 退出 2，人话报错不崩 |
| 10 | fixture 总览存在但代码 mtime 更新 | 查一红列出过期模块，退出 1 |
| 11 | fixture 无 `docs/模块总览/` 且工作区干净 | 查一绿+提示"未建总览目录"，退出 0 |
| 12 | fixture 非 git 目录（普通文件夹，有总览） | 查二/查三黄档跳过，退出 0 |
| 13 | `--help` | 打印用法与三项说明，退出 0 |

## 评分权重

按 P0 §3「工具产出」表执行（正确性 40 / 健壮性 20 / 可用性 15 / 协同 15 / 风格 10），≥90 且无 P1 通过。

## 明确不做

不自动修复任何发现的问题、不代 commit、不改 git 配置、不碰 check_modules.py 与 git_push_github.py 的现有行为。
