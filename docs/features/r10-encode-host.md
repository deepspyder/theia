# R10 成片编码进程隔离

2026-11-20。单张导出、批量导出和失败任务重试通过共享成片处理合同，把冻结编辑、来源选项、尺寸、颜色空间和元数据交给 `render` 进程。像素输入和已编码结果都通过匿名文件描述符传送；Binder 仅传请求、尺寸和描述符。裁切、成片调色、尺寸变换、ICC 转换及 PNG/JPEG/TIFF/HEIF 编码在宿主中完成。MediaStore 临时记录、提交、源复核和最终导出任务状态仍由原有发布事务管理。DI 和灯台入口保持各自已验收的路径。

2026-11-20 在 API 37 x86_64 模拟器运行 48 个实际应用导出组合，覆盖 PNG、JPEG、TIFF、PNG/JPEG/WebP/DNG 来源及四个文件色域。每项均确认编码宿主 PID 与编辑器进程不同，并通过原发布事务保存。电脑端独立解码全部 48 个文件，对照各自的 TIFF RGB16 参考共 9,905,616 个输出 RGB 通道样本：PNG 最大通道差 1/255，TIFF 最大通道差 5/65535，JPEG 平均差最大 11.69/255，均通过既有阈值。输入覆盖照片方向、裁切、旋转和 317px 自定长边。

真实 Z6II NEF 的 6064×4040 TIFF、PNG、JPEG、Adobe RGB TIFF/PNG/JPEG 及 Deflate TIFF 七项全尺寸输出均通过宿主导出。独立核对每份 TIFF 数据共 73,495,680 个 RGB16 样本：sRGB TIFF、PNG 和 Deflate TIFF 最大差为 0，Adobe RGB TIFF 最大差 19，Adobe RGB PNG 最大差 1。全尺寸 HEIF 也通过 ICC/像素对照，平均通道误差 4.37/255，低于 12 的编码阈值。Deflate TIFF 在缓存命中时耗时 4.949 秒，HEIF 端到端耗时 15.089 秒；均为模拟器单次耗时，不代表真机速度。

ProPhoto RGB、317px Deflate 冻结失败任务重试通过完整 TIFF16 对照，重复提交和源变更拒绝通过。新增宿主专项在 8192×4096 PNG 行编码期间取消；强制终止正在编码的 worker 后，新 worker 成功恢复并编码 600×300 输出；发布拷贝中途取消不留下 pending 文件，保留 EXIF Make。RenderHost、批量导出专项通过。Debug/AndroidTest/Release、Lint、包边界和 15 项工具测试通过；Lint 为 0 errors、82 warnings、21 hints。Release APK 原证书 v1/v2/v3 签名和 16 KiB 对齐通过，SHA-256 为 `e575a890d05f25b345c00d9a570ab788158723097c7bd3a17222721320c925ca`。首轮取消探针误将客户端检查轮询当作远端行进度，调整为编码阶段进入后计时取消并以 8192×4096 随机源复测，最终通过。执行证据位于 `.tmp/r10-encode-evidence/`、`.tmp/r10-encode-host-interruption-final.log`、`.tmp/r10-encode-export-tasks-editor.log`、`.tmp/r10-encode-render-host.log` 和 `.tmp/r10-encode-professional-output-batch.log`、`.tmp/r10-encode-release-build.log` 和 `.tmp/r10-encode-release-verification.json`。R10 总体验收未完成，后续继续拼版、多源恢复、缓存预算和完整故障矩阵。
