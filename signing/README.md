# 固定签名

分发证书 SHA256：`8e153e57ae04cdc5e0205425ae409a8e277ebfa8534f8e760a0addda92504247`。

CI 产出未签名 APK，签名私钥只保存在维护机器私有目录，不上传公开仓库、Actions artifact 或缓存。使用 sign.py 和 Android SDK 自带 apksigner.jar 签名；工具会在签名前后核对证书，证书不符即删除输出并报错。

旧 V0.1–V0.3 由临时 debug 密钥签名，不能覆盖升级到此证书。后续分发继续使用同一把固定密钥。
