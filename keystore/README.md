# Release 签名（仅供个人使用）

签名文件不要提交到 Git。仓库里只保留模板 `signing.properties.example`。

1. 复制模板：把 `signing.properties.example` 拷成 `signing.properties`。
2. 生成密钥库（若你本地还没有）：

```bash
keytool -genkeypair -v -keystore keystore/release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias sleepcollect
```

3. 把 `signing.properties` 里的密码和别名改成你自己的。

`app/build.gradle.kts` 会在 `signing.properties` 存在时自动用于 `assembleRelease`。没有该文件时，debug 构建仍可正常进行。

不要把证书用于上架。更换电脑或重新克隆仓库后，请使用你自己保管的同一套 `release.jks` 和 `signing.properties`，否则更新安装会签不兼容。
