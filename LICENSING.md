# FolderSpan 许可范围

普通版（`no-pro`）的 FolderSpan 自有代码采用 [Apache License 2.0](LICENSE)。发布者于 2026-10-21 确认采用；各源文件已有的第三方版权与许可声明继续有效。

`proMain/` 是独立子模块，不属于本次普通版 Apache-2.0 授权范围。普通版构建使用 `-PincludeProMain=false`。第三方库、复制代码、数据和图形资源按其各自的原许可证使用；根 LICENSE 不为它们重新授权。Apache-2.0 第 6 条也不授予 FolderSpan 名称或标志的商标权。

[NOTICE](NOTICE) 说明产品范围；[THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) 汇集组件和上游原文；[third-party](third-party/README.md) 保留声明生成需要的组件索引、来源、适用平台和原文摘要。审计过程资料与发布代码分开保存。JNA 采用其 Apache-2.0 许可选项，Zstandard 采用 BSD 选项；这些选择不会更改相应上游的双许可文本。

随附 Java Runtime 的 GPLv2、Classpath Exception、Assembly Exception 和其他第三方条款独立适用，必须保留 `runtime/legal`。使用哪个发行商和构建的 JDK，应按该实际构建保存对应源码与获取方式。未来 iOS 若静态链接 LGPL 的 libsmb2，还需履行该库的源码、修改与重链接义务；放入许可全文不能代替这些材料。

普通版构建配置将产品许可和第三方声明复制进 Android 的 `assets/licenses/`、JVM 的 `licenses/` 资源以及 Web 的 `licenses/` 目录。桌面和 Headless 普通版原生分发还包含独立许可文件。Pro 构建不接入这份 no-pro 组件清单。已有公开 v1.0.0 资产未在本次被重打包，不能据此声称旧安装包已含新增声明。

更新依赖或静态资源后，按 [维护说明](third-party/README.md) 更新来源索引并重新生成声明，然后检查最终发行包。声明的生成、校验不联网：

```bash
python3 scripts/generate_third_party_notices.py
python3 scripts/generate_third_party_notices.py --check
```
