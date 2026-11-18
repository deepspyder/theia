# Adreno 830：DIR 原始内核分批导出

日期：2026-11-17。应用版本维持 0.1.7、versionCode 8。对应客户固件 `PD2454C_A_17.2.12.0.W10.V00 OL`，反馈系统 Android 17；错误报告 GPU 为 Adreno (TM) 830、vendor 20803、device 1141178369、driver 2150760528。

## 已确认的边界

客户的兼容验证包仍在 `vkCreateComputePipelines` 创建 `spk_couplers_correction.windowed` 时返回 `-13`，关闭编译优化后仍为 `-13`。上一轮通用重试没有修复真机故障。详见 `vivo-vulkan-export-2026-11-16.md` 的真机复测段；旧模拟器的故障注入通过不代表客户驱动兼容。

本次针对已定位的内核改变调度方式，避免再要求驱动编译这条窗口化变体。它不是对厂商编译器内部具体缺陷的证明，也不保证不存在其他内核的设备兼容问题。

## 实现

`spk_couplers_correction` 是逐像素的染料耦合校正：读取一个 CMY 三元组，按正片标志与最大密度变换，再应用位移和 3×3 矩阵。它不读取邻居、照片坐标或随机数，空间扩散另行调度，因此可以严格使用局部像素索引。

- 仅一次性导出的此内核进入 `LocalCouplerDispatch.cpp.in`；其他窗口化内核、交互预览和共享 Windows 引擎保持。
- 显式加载未修改的 `spk_couplers_correction.spv`，不创建报错的 `.windowed` 管线，不使用推送的全图基址及逐绑定偏移。
- 将 RGB32 输入、输出描述符同时移到当前分段的准确起点，原始内核从局部像素 0 计算。矩阵、最大密度、正片标志和位移保持整块只读。
- 每段使用私有四字节像素数量。通过同一命令缓冲中的 `vkCmdUpdateBuffer` 更新，并在 transfer 与 compute 间建立屏障；不改写共享参数缓冲。
- 每个非末段像素数为 `alignment / gcd(alignment, 12)` 的整数倍，保证下段 RGB32 字节起点准确对齐，绝不通过向前取整让局部像素 0 指向上段。
- 同时限制设备 `maxStorageBufferRange`、现有工作窗口预算、最大工作组数量和每段 1,048,576 像素上限。末段由原始内核的数量保护屏蔽补齐线程。
- 同步提交结束后才更新描述符和计数；每段检查取消；关闭会话后不保留新增整图缓冲。新增的只是小型元数据资源，不搬运整张浮点照片，不切换 CPU 计算，不关闭 DIR。

`get_pipeline` 新增显式原始模块选择参数，原始和窗口化管线继续按不同键缓存。正常预览仍使用相同的原始模块，导出没有继承错误的推送常量布局。

## 画质与回归合同

原始 SPIR-V、胶片和相纸数据、浮点公式、RAW 解码、色彩空间、颗粒随机身份、照片尺寸、导出位深和质量参数均未修改。195 个 Release DEX 与引擎资源条目和原版 v0.1.7 逐字节一致；原生适配库重新编译。

本地证据目录：`.tmp/vivo-local-dir/`。测试宿主为同源码 Debug UI 配 Release 原生库，最终交付为原证书签名的 Release Universal；不把宿主 DEX 声称为 Release DEX。

1. 独立 C++ 规划测试覆盖 378 组对齐、绑定范围、工作组上限与像素数量组合，包含超大逻辑照片、无效请求、无法前进时失败、奇数总量、尾部补齐及每个 RGB 通道只写一次。
2. 两张客户 JPEG，四种胶片、日期开关；另含 CineStill 800T 配 Vision 2383 和背反射的全尺寸 NEF，共 17 组。原版、原始完整调度与新路径的全通道 RGBA16 摘要相同。
3. 8193×281 奇数宽场景，覆盖多段、颗粒、眩光、物理光晕；另含裁剪旋转翻转、片边和日期。与原始完整调度的全部 U16 像素相同。扩展 DIR 强度与正片边界回归在测试报告中单独记录。
4. 正式应用导出经过独立 `:render` 进程、缓存和相册保存，再独立解码整个文件比较 RGB8 PNG 与 RGB16 TIFF；JPEG 按正常有损误差检查。每个尺寸及编辑组合首张清除衍生成片缓存。
5. 日志 `local_dir kernel=spk_couplers_correction.spv` 记录真实运行路径、分段数和设备对齐；不以测试开关关闭 DIR 来取得通过。

正式文件矩阵已完成 66 张：两张客户 JPEG 各 33 张；Portra 400 日期开关各覆盖 2048、2560、3840、原尺寸，其余 CineStill 800T、Velvia 100、HP5 Plus 开启日期后背，各覆盖 2560。每个组合均导出 JPEG、PNG、TIFF sRGB。独立整文件逐通道比较中，22 张 PNG RGB8、22 张 TIFF RGB16 差异为 0；22 张 JPEG 的最大单图平均绝对通道差为 2.732819/255。其他胶片没有声称完成全部尺寸排列，本轮没有新增 P3 矩阵。

更严格的 8 MiB 工作窗口、128 MiB 存储绑定限制下，8193×281 的 7 组像素测试通过，包含 DIR 强度 0、2 和正片强度 2；奇数宽几何/片边/日期另有 2 组对照。全部材料和兼容相纸、随机颗粒、消费已缓存负片等 42 组对照通过，另验证取消后会话关闭并能恢复普通编辑。

实际导出日志有 77 条 `local_dir kernel=spk_couplers_correction.spv`，报错的 `spk_couplers_correction.windowed` 创建记录为 0。证据见 `matrix/pixel-verification.json`、`matrix/native.log` 和 `.tmp/libraw-upgrade/vivo-local-dir-strict/summary.json`。

## RAW 性能与内存

关闭诊断开关后，原版与新路径按 before/after/after/before/before/after 顺序交错各三轮。同一次测试中的原生 TIFF 摘要、正式 CineStill 原尺寸 TIFF 摘要均逐轮完全一致。GPU 池帧峰值全部为 1,243,027,572 字节，没有增加整图缓冲。

| 项目，中位数 | 原版 | 新路径 |
| --- | ---: | ---: |
| 原生解码、镜头校正、渲染及 TIFF 保存总计 | 5877.28 ms | 5854.29 ms |
| 其中原生渲染阶段 | 2065.90 ms | 2118.40 ms |
| 其中 TIFF 编码保存 | 580.09 ms | 647.16 ms |
| 正式应用 CineStill 原尺寸冷导出 | 8888 ms | 8887 ms |
| 正式应用 CineStill 原尺寸缓存导出 | 859 ms | 842 ms |

本轮应用端冷导出基本持平，原生总计 -0.39%，单项渲染 +2.54%、编码保存 +11.56%；编码保存代码没有修改，但不能把所有单项差异直接当作零成本。样本量为每组三轮，运行区间存在波动；不能据此保证所有设备和温控条件下完全没有变化。

原生性能样本 `perf-z6ii.NEF` 当前 SHA-256 为 `1b67d15817dbd4143a1f1089e2f09e319a2a2327c124e2643284509dd7621030`，正式 CineStill 和画质测试使用 `pc-parity-DSC_5099.NEF`，SHA-256 为 `cafd8050d216fd08923eb972221c2011c2a4d39cda33efb6b0ed39e2a77ede3b`，均输出 6064×4040。证据为 `raw-benchmark/runs.json` 和 `raw-benchmark/summary.json`。

模拟器为 API 37 x86_64、GFXStream；新实现仍需客户 Adreno 830 固件复测，不能把模拟器像素通过称为真机已解决。

## 验证包

`app/release/v0.1.7-vivo-dir/Theia-v0.1.7-vivo-dir-universal.apk`，58,428,747 字节，SHA-256 `63f71b28a32f609e430f363838041393e1878b6874b862ae7e871d09bdb6c6ce`。原证书 V1/V2/V3 验证通过；arm64-v8a、x86_64 共六个 SO 的 LOAD 对齐至少 16 KiB。

此文件名中的 `vivo-dir` 用于区别上次 `vivo-compat` 验证包，两者版本号相同。没有覆盖原发布包，也没有创建 GitHub Release。

## 剩余验收

在客户报告的同一固件上安装这份新包，保留原来胶片、相纸、调色和日期后背，以同一 JPEG 分别导出 2560 和原尺寸。若原始模块也被驱动拒绝，应保留新的完整阶段、内核和驱动信息继续定位，不能反复发布同一条无效重试。还需 ARM 真机上的耗时、内存、温控和其他大图格式回归。

后续客户又反馈 `spk_matmul3.windowed` 同样失败，说明此单 DIR 适配不足以覆盖公共兼容范围，不能按此报告宣称真机导出已解决。下一轮公共接口适配和新验证包见 `adreno-window-shader-abi-2026-11-17.md`。
