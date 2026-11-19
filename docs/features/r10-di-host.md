# R10-B DI 导出迁移到独立宿主（限定范围已验证）

日期：2026-11-19。在R10-A持久安全重试基础上继续接DI入口。本批只迁移DI渲染和任务合同，编码/灯台完整宿主化及阶段缓存仍在R10后续。

冻结请求schema6增加ExportArtifact，旧schema1—5恢复为FINISHED；DI必须声明DIGITAL_INTERMEDIATE且使用TIFF/sRGB工作空间、无成片customSize或Deflate。这里sRGB是旧DI输入链的工作参数，DI文件本身仍为Cineon设备密度，不嵌ICC。未知artifact拒绝；普通成片保持原分支和缓存身份。

原DI快捷操作改经同一源快照、任务日志、RenderHostLease与独立:render进程，宿主按artifact调用原有renderDigitalIntermediate；匿名RGBA16描述符读回，应用继续条带写TIFF16，保留EXIF/GPS白名单。跳过成片裁切/后期调色/尺寸限制/文件色彩转换和成片缓存，避免把调色后的普通TIFF冒充DI。原导出长边约束、解码/镜头/WB及胶片前构图仍沿用原DI行为。输出名和日志保留Theia_DI_与DI导出语义。

DI任务加入R10-A安全重试，同一源/原参数/原时间/身份保持，明确记录DI而不是普通sRGB成片。关闭胶片模拟时继续禁止DI。取消和独立宿主的设备释放/恢复合同沿用普通单张导出，不能在宿主未释放设备前恢复UI Vulkan会话。

新增专项将PNG与DNG的独立旧native DI完整U16、旋转/翻转、任意行读取、取消和序列化与宿主结果逐样本对照；应用测试故意设置ProPhoto/强后期/成片裁切/31px custom/Deflate，要求DI仍与独立DI密度和完整尺寸一致、无ICC/未压缩、取消不发布。实际构建和专项结果如下。

生成的内置测试图没有可持久恢复的源描述符，保留原有本地DI路径，不伪造源文件身份或悄悄降精度编码再导入；实际来源照片全部走宿主。测试图兼容分支单列验收，不宣传DI所有入口已经无条件宿主化。

## 像素、应用和重试验证

在API37 x86_64模拟器、相同Release原生库测试宿主上执行：PNG/DNG共142,650个输出像素、完整U16与任意行读取，与独立原DI native调用逐样本一致；新resolved入口在无随机效应基线上保持旧样本。schema1—5按普通成片恢复，未知artifact拒绝，实际宿主PID与主进程不同，rendering阶段取消通过。

实际应用导出验证完整尺寸、Cineon16、无ICC、无压缩；成片后期/裁切/31px自定义尺寸和ProPhoto/HEIF选择不混入DI。失败DI任务按原请求重试时，故意改动当前曝光及filmOff，重复点击仍只发布一份、attempts只增加一次，完整TIFF字节与首次输出一致。真实取消不发布，filmOff不启动DI，内置生成测试图兼容分支通过。任务记录明确显示DI TIFF/16 bit Cineon。源元数据继续经共享ExportMetadata白名单传递；本批DI逐像素夹具未新增GPS/EXIF组合矩阵，不单独声称这些组合已重验。

原始`DSC_5099.NEF`，6064×4040，开启颗粒并冻结随机实现，独立resolved DI渲染与隔离宿主分别写出完整RGB16 TIFF。设备和电脑独立解析均通过：文件146,991,552字节、RGB载荷146,991,360字节、73,495,680样本，未缩小；两份整文件SHA256均为`5812b0fb12e7bef42941c6e8703745df267e6b379beaf44ca4e591c06cd36018`，RGB载荷SHA256为`73ab53f9fa5adc7c3358ab51dcca4738a4f66ade345ad1cc8bb5dfa43b1b3aa4`。这里全尺寸随机对照是相同冻结身份的两个执行路径，不把它描述为旧版本随机帧的字节复现。

导出快捷操作专项通过3600px JPEG来源→3400px高清预览、指定1280px DI及P3 TIFF、真实MediaStore提交、导出后sRGB交互会话恢复和继续编辑；没有为保存额外刷新预览。发布中断/源改变、原持久任务的ProPhoto/Deflate/317px和历史命名日期重试专项继续通过。

## 耗时与边界

保留设备日志`.tmp/r10-di-host/device-timing.log`。本轮原尺寸NEF隔离宿主单次样本：RAW解码8.884s、镜头校正3.835s、engine input3.556s、host render94.513s；host render包括DI渲染/读回路径，不含应用发布与全部来源准备。原生池frame high-water为1,243,027,572字节，不能据此声称首次大图峰值已经解决。整组NEF比对262.56s，含独立参考、宿主两次执行及两份TIFF写出。应用专项153.31s含参考、首次、重试、取消和demo多项操作；这些是一次非交错模拟器测试记录，无受控冷/热缓存和前后对照，不能作为手机导出速度或迁移性能收益。

本批仍不包含灯台/后期/编码全部宿主化、GPU中途续算、完整后台/撤权/云端/满盘矩阵或源/负片新缓存。仅API37 x86_64，未宣称小米/vivo/ARM或物理P3屏幕验收，R10总体保持进行中。

## 工程与界面验收

9组专项加4种布局，共13条设备执行记录通过；4张原生任务截图覆盖phone、1.3倍字体、landscape和tablet，独立fresh五节复核结论SHIP（仅DI任务摘要扩展范围），documenter保持现有设计系统并补行为合同。最终Debug/AndroidTest/Release及Lint通过，零Error、78既有Warning、21Hint，15工具测试、包依赖边界及差异空白检查通过。Release58,777,774字节，SHA256`f7455fcb05bdd8871d445b2f22bc8845dcc7ce99d5aad787e9208c6e8b7b85d9`，原证书V1/V2/V3及16KiB对齐通过。202个native/engine条目中仅两ABI主库因DI入口新增而变化，其他200项一致。证据`.tmp/r10-di-host/verification.json`、`raw/desktop-verification.json`、`responsive/`和`finish-review.md`；app/release和APK不提交。

R10-A总回归补验的失败与定点重跑完整记录见[持久导出任务](r10-durable-export-tasks.md)，不将194/207初跑记录改写为无失败总回归。后续继续R10-C灯台/后期/编码及预算工作，R10仍未整体完成。
