# 内置 Office 模块

运行时：Collabora Office 26.04.3.1，ARM64，minSdk26。原官方 APK 的 SHA256、下载地址及5,330个保留文件的路径/大小/SHA256见 `runtime-manifest.json`。

## 来源与源码

- 对应官方源码：[CollaboraOnline/online.mirror，20a46c332c380925803a1fe538a545c6f9b8fce7](https://github.com/CollaboraOnline/online.mirror/tree/20a46c332c380925803a1fe538a545c6f9b8fce7)。APK `assets/program/versionrc` 的 buildid=20a46c332c38。
- `src/main/java` 和原 Android XML 资源来自该版本 `android/lib/src/main/`，保留 MPL 文件头和 `COPYING`。`upstream-source-manifest.json` 记录未修改上游文件的哈希。
- Java和Android资源从源码编译；完整native库、网页编辑资源、注册表、字体和配置采用原官方运行时。没有使用上游的dex、resources.arsc、Manifest、应用壳、签名或密钥，也不安装/调用第二个APK。
- 源码原始归档可从 [固定版本 codeload](https://codeload.github.com/CollaboraOnline/online.mirror/tar.gz/20a46c332c380925803a1fe538a545c6f9b8fce7) 获取。本机已校验回收81,098个文件；导出归档不含57个export-ignored文件，完整Git树和构建模板可通过检出同一commit获得。

## 本地修改（MPL 文件继续采用 MPL）

`LOActivity.java`：宿主工作副本URI改走上游的原子文件回写；去掉工作副本不需要的广泛存储权限申请；关闭编辑器后返回宿主并回写最终保存内容；广播限定到本包；退出后只清理`:office`进程，保留扫描应用状态。保持JNI类名与函数签名。

新增Manifest只声明两个非导出Activity，均属于`com.localdoc.scanner`、在`:office`运行。使用AppCompat主题，保持本App原签名、数据库与包名。`fonts.conf`的字体缓存目录改为本包目录。关闭上游Google Play评分提示。Java层能够使用现有SDK35/AGP8.7.3构建；native预编译产物仍来自上游对应工具链，而不是把较新源码与较旧库拼接。

## 许可清单与排除项

上游完整 `license.html` 和 `notice.txt` 随APK保留，设置内可离线查看，并直接提供原生对应源码和本项目MPL修改文件的链接。原生NSS/NSPR、C++运行时、网页组件及字体各自许可仍适用，主工程MIT不覆盖它们。

原始拼写词典/extensions的独立许可没有逐一确认，因此全部排除；不把外层MPL作为复制这些资源的依据。`runtime-manifest.json`明确列出排除文件。字典缺失会使某些拼写检查不提供词典，但不替代或禁用文档排版编辑。

## 恢复与验证

```text
python tools/restore_office_runtime.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python tools/check_embedded_office.py app/build/outputs/apk/debug/app-debug.apk
```

恢复脚本核验原官方APK SHA256，只导入清单允许的库/数据；不覆盖已存在但内容不同的文件。可用`--apk`指定已下载原包，`--verify`只校验。Gradle构建也会核验每个保留文件，缺失时直接失败。

最终APK检查覆盖内容哈希、ABI、ELF依赖、JNI入口和16KB LOAD对齐；它不执行Android代码。中文DOCX分页、XLSX公式和PPTX对象修改→保存→重开→分享仍须在手机上验收。
