# 右下角小窗 V0.4

上游固定为 [HyperSideBar e45661e](https://github.com/mikudayoooooooo/HyperSideBar/tree/e45661e0f2f51bac84d00f954ad2da0edf1c24e3)，Apache-2.0。

V0.1–V0.3 的自写触发器、事件会话和悬浮窗已经删除，不再用于构建。直接使用上游 CornerTrigger → SystemUiInputSpy/sysPilferNow → RelayGestureSink → TurboLayout → FanMenuController → ComposeFanHost → 原生小窗启动器。桌面 Java 手势和 Native Hook 同样恢复为上游实现。

仅调整：右角限定、横屏支持、灵敏度参数、六位置单层菜单数据、原位收藏切换、精简设置、只读版本诊断、手动重启 SystemUI、包名对应的 C 配置路径、配置文件增量合并。删除常驻 InsetProbe 探针窗和窗口预热，保留上游实际菜单窗口机制。不会修改 LSPosed 的遮挡触摸保护。

```sh
python3 RightCornerMiniWindow_SourcePatch/apply_patch.py /path/to/pinned/HyperSideBar
```

必须在干净的固定上游提交上执行。脚本对文本锚点计数检查，未知版本停止。构建产物附带 miniwindow-changes.json，列出实际变更文件。

CI 只编译 unsigned APK，不再运行模拟手势单元测试，不使用每次变化的 debug 签名。分发包在本机由长期保存的固定私钥签名，证书 SHA256 见 signing/certificate-sha256.txt。私钥不进入仓库、缓存或构建产物。旧版临时签名的私钥已不可恢复，首次迁移可能需要卸载旧版；以后不得重新生成密钥冒充更新。

编译成功只表示源码可打包。没有小米 Pad 7 Ultra / HyperOS 4 的真机验证，不宣称触发成功率已解决。上游本身也不能视为该设备的兼容性证明。
