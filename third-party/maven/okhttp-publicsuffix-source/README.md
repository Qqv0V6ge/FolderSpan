# OkHttp 公共后缀数据源码

本目录保留实际使用的OkHttp 4.12.0中受MPL-2.0覆盖的数据、原始NOTICE、读取格式的上游源码，以及由该资源原样解码的可编辑UTF-8规则与例外。规则顺序/字节未改；包内与官方精确版本sources.jar中的gzip资源完全一致。读取器PublicSuffixDatabase.kt自身为Apache-2.0，数据按MPL-2.0。

来源与摘要见[source.json](source.json)。对应[官方源码归档](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0-sources.jar)公开提供；不把今天不断更新的完整PSL网页当成这个历史资源的逐字源文件。

重新组合：按UTF-8原字节给rules.txt与exceptions.txt各加四字节大端长度前缀并依次连接，再gzip压缩。压缩时间戳可能不同，解压后数据应逐字节相等。
