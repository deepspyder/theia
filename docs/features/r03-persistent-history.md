# R03 持久撤销与重做

日期：2026-11-18。基线 Android `88422aa`；对应 [2.4.2 清单 R03](../upstream-2.4.2-android-roadmap.md)。本批扩展现有撤销/重做，无新增页面、主题、图库虚拟副本类型或照片像素缓存。

## 行为与存储合同

- 真实照片的撤销步骤与当前游标跨照片切换、Activity重建和应用进程重启保留；重启时不再用最新编辑重置整条历史。
- 撤销后重新编辑会原子删除该版本的redo分支；普通自动保存和参数默认值展开不创建假步骤，也不清空redo。延续原350ms连续输入合并策略；已有曲线/HSL/混合/色轮的预览手势在完成时提交，取消不写历史。
- 历史以默认编辑`variant_id`或虚拟副本`version_id`为身份。原图重定位不移动或复制历史；副本独立建立自己的历史基线，不继承另一个版本的游标。副本重命名不改变身份，已删除版本仍受现有tombstone保护。
- 每个版本最多保留256个状态（一个基线加最多255次可撤销变化），同时不超过8MiB压缩参数。回收最早状态与写入新步骤在同一事务内；从游标到当前成片之间不会留下断档。单个未压缩参数上限2MiB；解压有界并校验摘要。
- 每步保存稳定step UUID、sequence、时间、origin、变更字段label和完整压缩JSON；头记录保存cursor、独立generation、已裁掉数量及merge key。保留未知编辑字段，不将历史表或图库版本当成图片副本。
- `saved-photos.db`从schema5增量迁移到6。旧编辑ID、JSON、revision和相册/工作台不改；首次需要历史时将现有记录作为基线。自动显影新图把默认编辑和建议之间的变化记录为可撤销一步。内置无URI测试图继续使用会话内历史。
- step、cursor、redo裁剪、旧历史回收和当前编辑在同一SQLite事务提交。generation防止过时编辑器覆盖独立写入；失败时编辑器恢复已提交参数、按钮与保留画面，并使用已有存储错误入口反馈。满盘原始错误不会被事务清理异常掩盖。
- 未来数据库schema在启用WAL/迁移前以只读方式拒绝；未来历史format或损坏压缩状态不被重置。未知/损坏记录保留，后续读取恢复由单独流程处理。

## 实现职责

| 位置 | 职责 |
| --- | --- |
| `core.model/EditHistoryPosition.kt` | 不依赖页面的已提交游标值，含canUndo/canRedo/generation |
| `data.library/EditHistoryRepository.kt` | 压缩状态、摘要、分支/合并/配额/读取校验；复用所属事务 |
| `data.library/SavedPhotoRepository.kt` | 稳定版本ID、schema6迁移；当前编辑与历史共事务；批量写入同样覆盖历史 |
| `data.local/EditStore.kt` | 串行持久入口、旧sidecar采用、错误传播、智能显影默认基线 |
| `feature.editor/EditorViewModel.kt` | 现有提交/手势/工具栏接线，保持小型游标缓存（最多8版本），不加载256份参数或像素进内存 |

原先“先改内存undo栈、再单独保存最新参数”的真实照片链路已替换。现有无URI测试图的64状态内存栈保持。没有新增Gradle依赖、worker、线程池、服务或JNI接口，不修改引擎、材料、输出尺寸/位深。

## 验证与红绿证据

API37 / Android17 / x86_64模拟器。宿主为同源码Debug DEX+正式Release六份原生库；不声称与Release DEX相同，也没有ARM手机结果。证据位于`.tmp/r03-history/`及`.tmp/libraw-upgrade/r03-*`。

1. **先红后绿的真实进程重启**：原R02应用在`history-process-seed`完成两步编辑和undo后，独立重启`verify`明确失败“Process restart lost undo/redo cursor”。R03同入口三阶段seed/verify/branch通过：EV1→EV2→undo1，重启保留redo，恢复2/撤回1、新分支3、再次重启保留undo且不复活旧分支。
2. **完整图像与真实工具栏**：重启前后完整RGBA16预览SHA相同；JPEG/PNG/TIFF三种原尺寸文件SHA相同；重启后通过真实可访问性工具栏点击redo/undo，核对参数和新渲染，不用直接注入UI回调替代该验证。截图`history-restored-toolbar.png`保留。
3. **实际进程终止**：`PersistentHistoryCrashAudit`在WAL事务中四处调用真实killProcess：新压缩步骤写入后、保存当前编辑后、游标移动后、undo当前编辑更新后。每次独立重启验证完整回滚；另验证已提交undo的游标和redo跨进程保留。host脚本`run_crashes.py`核对到达断点标记和预期进程退出，不能把崩溃后的旧results.json当作通过。
4. **真实SQLite存储专项**：默认/副本隔离、源重定位保持ID、旧v5迁移/未知字段、默认值展开不造历史、分组/无操作/redo分支/CAS、压缩损坏与未来schema/format保护、256状态和8MiB上限。四处异常回滚、批量两版本历史回滚、超限回收后失败回滚均核对原编辑、generation、count和cursor。
5. **满盘**：在隔离数据库上设置真实`max_page_count`限额，写入随机大状态触发`SQLiteFullException`。核对当前JSON、游标/generation和integrity_check保持；不填满用户磁盘。该fixture关闭WAL以确保page上限与写入使用同一连接，正式产品与进程终止测试仍使用WAL。
6. **编辑器失败与响应**：存储故障注入后参数/undo-redo/画面退回已提交状态；独立writer推进generation后，旧UI写入被拒绝而不覆盖新值。100次连续调色提交只保留一个可撤销变化。实测主线程提交中位6.874ms、P95 19.373ms、最大23.701ms；只是一轮模拟器513×385测试，含同步持久化，不是60fps保证或真机性能结论。P95超16.7ms，后续F01性能优化仍保留，不能靠取消持久提交来美化数据。
7. **相关回归**：真实源重定位/工作台、版本创建/删除/批量导出、曲线/HSL/通道/色轮拖动取消、分组复制/预设、智能显影，以及最终完整应用总入口。真实设备、云端和ARM驱动范围不扩大。

失败记录保留：第一轮页面限额夹具用了WAL连接池，未触发满盘；改为隔离单连接后确实触发，暴露旧finally掩盖原始SQLITE_FULL的错误，修复后通过。旧v3升级fixture从当前数据库删表模拟旧schema时忘删新增历史表；修正fixture后再跑，无需在产品迁移中忽略重复表或吞异常。

## 规范与范围限制

执行Debug/AndroidTest/Release构建、`:app:lintDebug`、14项工具测试、分包边界、进度一致性与`git diff --check`。不屏蔽检查；Lint零Error，原有warning/hint逐条与基线源码核对。既有撤销/重做布局不变，原图、材质与渲染数学不变。

本批提供持久历史底层与原有撤销/重做入口，未新增历史浏览/任意跳步或历史清理界面。预算是每编辑版本的逻辑压缩状态预算，不等于整个SQLite文件上限（页面/WAL/其他照片另占空间）。跨设备项目互通、相册级空间管理、真机持续负载和电源断电耐久性另列；现有WAL策略覆盖应用崩溃，不承诺掉电时每一步都已物理刷盘。

补充UI重跑记录：短暂导出窗口使脚本可选的“继续显影”步骤被跳过；现场仍在首页，canUndo/canRedo均true。已保存可访问性树/截图并改为必须等待和真实点击入口，再操作工具栏；verify/branch最终均通过。未放宽游标、像素或真实UI断言。

最终验收：17组、235条执行记录（含重复；完整应用总入口207/207），另四次真实进程终止加七个种子/恢复阶段全部通过。证据汇总`.tmp/r03-history/verification.json`。正式Release 0.1.7/code8，58,496,323字节，SHA-256 `da4c59eb0e2a3aae15708d208ad3477bc5920b4030c1678f952103edd34b9b67`；原证书V1/V2/V3和16KiB APK对齐通过，205项原生/运行资源（含6份SO）与R02相同。APK只保存在本地构建输出，`app/release`仍完全忽略。
