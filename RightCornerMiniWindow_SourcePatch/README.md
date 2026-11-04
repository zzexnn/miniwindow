# 右下角小窗 · V0.1 源码开发补丁

> **重要：这不是 APK，尚未做完整 Android 编译和 HyperOS 4 真机测试。**
> 交付内容是可复用的 Kotlin 菜单实现、基于 HyperSideBar 指定提交的自动补丁、核心逻辑冒烟测试与自动 APK 构建工作流。

## 当前实现

- **只显示单层四分之一圆弧扇形**（非方形）：固定 6 个位置；前 5 个为系统推荐，最后一个为文件夹入口。
- **滑到文件夹无须松手**：菜单原位切换为 5 个固定收藏和返回入口；向回移动到任一收藏图标，松手启动原生小窗。
- **文件夹防抖**：切换页面后，必须先离开第六位置，才能再次进入该位置返回，避免文件夹反复开合。
- 推荐：使用原项目缓存的 `DataLoader.rankedApps(...)`，每次呼出冻结列表；排除收藏，取最多五个。
- 收藏：复用上游设置页的 `扇形应用`，按 `CUSTOM_APPS_ORDER` 排序，显示最多五个。
- 启动：复用 `FreeformLauncher.launch()`，调用 HyperOS `MiuiMultiWindowUtils.getActivityOptions`，不做全屏回退。
- 事件：仅右下角角区启用，保留上游的 SystemUI InputMonitor / Native 手势争抢保护，绘制层 `FLAG_NOT_TOUCHABLE` 不夺取触摸。
- 设置入口**已替换成精简页面**：总开关、收藏应用选择/排序、振动。其他上游旧页面源码暂时保留但不从主设置页访问，首轮先减少系统 Hook 改动风险。

## 本地纯 Kotlin 状态机测试

在装有 Java + Kotlin 编译器的电脑运行：

```bash
kotlinc patches/CornerMenuEngine.kt tests/CornerMenuEngineSmoke.kt -include-runtime -d /tmp/corner-test.jar
java -jar /tmp/corner-test.jar
```

已通过这一组冒烟测试（推荐选中、进入收藏、文件夹防抖、收藏选中、返回、空白取消、失配松手取消）。这**不能替代 Android 构建或真机测试**。

## 生成可安装的调试 APK

### 推荐：GitHub Actions

1. 创建一个新的 GitHub 仓库用于放本补丁（不要提交到上游作者的原仓库），将这个文件夹里的全部文件和 `.github/workflows` 一并上传。
2. 在仓库的 **Actions** 里运行 `Build RightCornerMiniWindow test APK`（如果已开启 push 触发，推送后会自动运行）。
3. 构建成功后从该 workflow 运行结果的 **Artifacts** 下载 `RightCornerMiniWindow-HyperOS4-test-apk`。该 APK 使用 debug 签名、独立包名 `io.github.zzexnn.rightcornermini`，与原版不同。
4. 首次测试前在 LSPosed 里只启用这一个侧边角手势模块，禁用原 HyperSideBar 或其它同类 Hook 模块。作用域沿用原版：`com.miui.securitycenter`、`com.miui.home`、`com.android.systemui`，重启设备。
5. 在设置页的 `自定义收藏` 选择、排序收藏应用（最多显示前 5 个）。底角入口默认开启；从右下角斜滑并沿扇形拖动。

> 如果 CI 因 SDK/Gradle/ROM 隐藏 API 报错，以 Actions 日志为准，需要修订工程；不要把源码包直接改名成 APK。

### 在安装了 Android SDK 的 Mac 上构建

```bash
git clone https://github.com/mikudayoooooooo/HyperSideBar.git
cd HyperSideBar
git checkout e45661e0f2f51bac84d00f954ad2da0edf1c24e3
cd ..
python3 /你的路径/RightCornerMiniWindow_SourcePatch/apply_patch.py HyperSideBar
cd HyperSideBar
./gradlew assembleDebug
```

目标路径：`HyperSideBar/app/build/outputs/apk/debug/app-debug.apk`。

## 需真实设备验证与后续完善

- **核心待验证**：在 HyperOS 4 上，InputMonitor 抢流以后，`ACTION_MOVE`/`ACTION_UP` 是否完整到达 `:ui`，以及真正小窗是否被系统允许。
- 首次菜单可能有推荐缓存未就绪；下一次打开才有推荐。收藏可以预先设置。必要时后续增加直观的空状态说明。
- 如果推荐第六位文件夹过近右边缘，按手感调整 226 dp 半径/36 dp 命中带。可改 `CornerMenuEngine.kt` 中角度序列。
- 当前设置页已精简为总开关、收藏选择及排序、振动。**触发灵敏度仍固定 12dp**，尚未接入可用滑杆。
- **系统安全**：Hook 和 Native 代码运行在高权限宿主中。首次安装前需有恢复方式。遇到 SystemUI 重启/手势失灵时立即在 LSPosed 禁用模块并重启。勿和其他拦截相同手势的模块同时开启。
- 目标为竖屏右下角，非平板分屏/横屏适配。

## 来源与许可

- 基线：[`mikudayoooooooo/HyperSideBar`](https://github.com/mikudayoooooooo/HyperSideBar)，Apache-2.0，固定提交 `e45661e0f2f51bac84d00f954ad2da0edf1c24e3`。
- 新增文件为该工程的扩展，保留上游 LICENSE 和第三方许可。第三方库可能有额外许可证义务。
- [`Leaf-lsgtky/MeiWindow`](https://github.com/Leaf-lsgtky/MeiWindow) 仅作为产品交互调研，**没有复制其 GPL-3.0 源码**。
