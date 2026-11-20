# R10 成片编码进程隔离

2026-11-20。单张导出、批量导出和失败任务重试通过共享成片处理合同，把冻结编辑、来源选项、尺寸、颜色空间和元数据交给 `render` 进程。像素输入和已编码结果都通过匿名文件描述符传送；Binder 仅传请求、尺寸和描述符。裁切、成片调色、尺寸变换、ICC 转换及 PNG/JPEG/TIFF/HEIF 编码在宿主中完成。MediaStore 临时记录、提交、源复核和最终导出任务状态仍由原有发布事务管理。DI 和灯台入口保持各自已验收的路径。

2026-11-20 在 API 37 x86_64 模拟器运行 48 个实际应用导出组合，覆盖 PNG、JPEG、TIFF、四种来源及四个文件色域。每次应用发布前都检查本次任务已收到与编辑器 PID 不同的宿主 PID；48 项均成功返回并由原发布流程提交。输入照片经过方向、裁切、旋转和 317px 自定长边组合，现有冻结配置行为保持。Debug 与 AndroidTest 编译、Lint、包边界和 15 项工具测试通过。Lint 为 0 errors、82 warnings、21 hints。

当前范围没有重新验收本次编码宿主上的 HEIF、全尺寸 NEF、Deflate 重试和编码中取消；这些场景仍列入 R10 后续回归。R10 总体验收未完成。执行记录保存在 `.tmp/r10-encode-host-app-manifest.json`、`.tmp/r10-encode-professional-app.log` 和 `.tmp/r10-encode-host-build.log`。
