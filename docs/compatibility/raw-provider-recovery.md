# RAW 内容提供器访问恢复

日期：2026-11-18。对应 [2.4.2 开发清单 R01](../upstream-2.4.2-android-roadmap.md)。本批只改 RAW 输入访问与其测试，没有修改 LibRaw、胶片数学、原生渲染、材料或输出质量。

## 原因与修复范围

旧 `RawDecoder.withRawInput` 对 regular/seekable 内容描述符直接调用 `/proc/self/fd/N`。描述符可读并不能保证按这个路径再次打开：另一应用 UID 授予的 fd 可通过 Binder 传递，但 SELinux 仍可能拒绝路径重开。旧路径没有一次本地暂存恢复，因此 LibRaw `open_file` 会以 `RAW open: Input/output error` 结束。带 offset/length 的内容资产也不能当整个底层文件交给 LibRaw。

新路径使用 `openAssetFileDescriptor`：

- 完整、regular、seekable 资产继续直读，不给普通本地 RAW 增加拷贝。
- 只有固定 LibRaw 版本的 `RAW open: Input/output error` / `RAW metadata: Input/output error` 允许一次暂存恢复。恢复重置并读取已经授权的同一 fd，不重复获取 provider/云端内容。
- 子文件按 `startOffset` 和声明长度读取，截断在解码前拒绝；管道也复用已开的 fd。
- 不重试 unpack/process/unsupported/cancelled，不因可选 embedded preview 返回 null 重试。无嵌入 JPEG 的显示预览语义保持；本批不为可选预览的 null 新增重试。
- 读前、复制期间、解码前检查取消；暂存 success/fail/cancel 均清理。恢复仍失败时保留原 IO 错误为 suppressed 便于诊断。

JNI 当前固定英文错误文本是窄范围重试门槛；以后更新 LibRaw/JNI 错误合同须同步本测试，不应扩大成任意 RAW 错误都重解码。

## 实测与红绿记录

环境：原有 `Medium_Phone_API_37.0`，Android17/API37 x86_64 模拟器，测试 APK 中 Java provider 运行于独立应用 UID。测试宿主为 Debug DEX 加本批正式 Release 六份原生库；不是与 Release DEX 相同的二进制。设备未连接小米/vivo。

证据保留于 Android 仓库 `.tmp/libraw-upgrade/`：

| 记录 | 结果 | 含义 |
| --- | --- | --- |
| `raw-input-red/raw-input-access.json` | FAIL，RAW open IO | 旧输入实现不能通过模拟重开拒绝；仅可见性改为 internal 以便作用域内注入读取 lambda |
| `raw-input-green/raw-input-access.json` | FAIL，Java `/proc` EACCES | 测试本身错误地按路径重开跨 UID fd，暴露真实平台限制；保留现场。修正测试直读为 dup(fd)，不放宽产品判断 |
| `raw-input-real-red/raw-input-access.json` | FAIL，RAW open IO | 调整测试顺序后，第一项调用真实 native 解码独立 UID 的 regular fd；无异常注入，自然复现旧实现失败 |
| `raw-input-green2/raw-input-access.json` | PASS，418ms | 相同真实提供器通过；另含21项恢复/边界合同，原有 file/FileProvider/pipe 对照也通过 |
| `raw-input-green2/raw-cancellation.json` | PASS，924ms | 真实全尺寸 NEF 预览和导出在解码过程中取消 |
| `raw-input-green2/nef-import.json` | PASS，13388ms | 六种 Nikon Z6II 12/14bit NEF 预览/导出，lossless/lossy导入渲染，截断拒绝及暂存清理 |

21项合同：真实跨UID常规fd的预览/128px导出/矩阵（3）；强制open/metadata恢复预览（2）；恢复导出/矩阵（2）；保留直读/可选null不重试（2）；unpack/unsupported/cancelled/process不重试（4）；取消优先/恢复失败保留原异常（2）；分段精确字节/预览/导出（3）；声明长度截断拒绝/复制中取消/进入前取消（3）。

DNG对照检查完整线性RGB浮点数组及相机矩阵逐值相同；128px native export 检查全部输出浮点值。分段夹具137字节前缀、509字节尾缀被正确排除，provider计数确认每次只开一次，进入前取消不开provider。所有路径结束无 `raw_input_` / `raw_export_` 暂存泄漏。测试入口仍为 `raw-input-access`，由 `RawInputAccessAudit.kt` / `PipeRawFixtureProvider.java` 承载。

Debug、AndroidTest、Release离线构建通过；14项Python工具单测、源码分包边界、开发进度一致性和 `git diff --check` 通过。APK资源/原生库对照摘要写在 `.tmp/libraw-upgrade/raw-input-green2/verification.json`。

## 限制与后续

这证明并修复了输入访问合同缺口。小米15Ultra原始失败DNG未提供，文件损坏/压缩不支持等原因仍须排查；不能把本结果写成已确认该手机唯一根因。小米17Pro片边/Adreno导出是另一条已记录的兼容工作，见 [片边导出回归](raw-overscan-export-regression.md)。

418ms等是本测试夹具整组耗时，不是24MP照片导出性能。本批未重新测量或承诺手机导出加速；需要恢复的来源会多一次落盘拷贝。上一批实际尺寸/格式矩阵和耗时记录继续有效，但不冒充本批新跑了整套大矩阵。
