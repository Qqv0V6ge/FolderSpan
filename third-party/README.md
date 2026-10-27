# 第三方许可维护输入

本目录保存普通版声明生成所需的组件索引、上游许可原文、版权和本地修改说明。项目Apache-2.0仅覆盖FolderSpan有权授权的普通版自有代码，第三方条款独立适用。

- [manifest.json](manifest.json)：每份原文的来源URL、适用范围和SHA-256；相同字节在输出中合并展示，但各组件关联保留。
- [Maven组件清单](maven/components.csv)：普通版Android release和macOS ARM64桌面的555个已解析组件，包括Kotlin元数据变体；不是全部目标的最终SBOM。
- [根第三方声明](../THIRD_PARTY_NOTICES.txt)：由上述输入离线生成。每个发行目标还需核对实际嵌入内容和平台范围。
- [OkHttp公共后缀数据源码](maven/okhttp-publicsuffix-source/README.md)：保留4.12.0对应MPL数据、可编辑规则/例外及精确来源。
- [未来iOS重链接条件](native/IOS-RELINK-REQUIREMENTS.md)：静态LGPL等条件须按实际发行另行完成。

原生包装层的Apache声明不替代WebRTC、Skia/Adobe DNG SDK或其他嵌入代码的条件。实际随包JRE须保留完整runtime/legal，并按实际发行商/build提供完整对应源码或有效提供方式；仅有src.zip或总声明不等于源码交付完成。声明集合不代表桌面WebRTC实际core/GN依赖图、未来iOS重链接或所有目标的二进制对应已验证。旧公开v1.0.0资产未在本次重建。

## v1.0.1 已核对运行时的源码

[同版本发布页的源码与摘要索引](https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/JRE_SOURCES.json)提供两份完整上游源码归档：Linux x64 包中识别的 Temurin 17.0.20.1+1，以及 Windows ARM64 包中识别的 Microsoft OpenJDK 17.0.19+10。每份归档保留上游许可；其他目标和第三方组件继续按实际嵌入版本核对。

## 离线维护与核验

在仓库根目录运行：

```bash
python3 -B -m unittest discover -s scripts/tests -p 'test_generate_third_party_notices.py'
python3 scripts/generate_third_party_notices.py --check
node third-party/resources/tests/verify_web_streams.cjs
python3 -B third-party/resources/tools/verify_gbk_mapping.py
```

更新依赖时，核对对应版本的上游原文、版权和来源，更新manifest及组件关联，再运行生成器（不带--check）刷新根声明。不能只删除未识别组件或用项目许可证替代上游条款。完整上游许可文本保持原字节；FolderSpan本地说明可随事实和索引更新。原生运行时与最终发行包验证单独完成，不因生成器通过即视为全部义务已履行。

上架工作稿、历史构建记录、下载探测、对照快照等审计资料不属于此最小维护输入，不随本次主要代码提交；它们不参与声明生成。大型JDK对应源码归档应按实际发行与二进制一同提供，不提交进源码仓库。
