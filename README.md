# okpay-plugins

okpay 支付平台的**通道插件**仓库（开源）。每个插件实现一条支付通道，只依赖闭源发布的 `okpay-plugin` SDK 即可独立开发、编译、运行。

> 平台核心（manager / common / payment / web）为闭源仓库，不在本仓库内。插件与核心通过 `okpay-plugin` SDK（PF4J 扩展点）解耦：核心在运行时按 jar 内 `Plugin-Id` 清单动态加载插件，对插件实现类零编译依赖。

## 仓库结构

```
okpay-plugins/
├── build.sh                一键编译脚本（全量 / 单插件）
├── pom.xml                 父 POM（聚合器 + 版本统一管理）
├── plugins/                通道插件
│   ├── alipay/             支付宝
│   ├── epay/               彩虹易支付
│   ├── helipay/            合利宝
│   ├── joinpay/            汇聚支付
│   ├── sumapay/            丰付支付
│   └── wxpay/              微信支付（APIv3）
└── maven-repo/             闭源 SDK 构件仓库（okpay-plugin jar + pom + javadoc）
```

## 一键编译

```bash
./build.sh           # 编译全部插件并安装到本地 .m2
./build.sh alipay    # 仅编译 alipay 插件
```

`okpay-plugin` SDK 由父 POM 的 `<repositories>` 从本仓库 GitHub Pages 静态 Maven 仓库自动拉取（`https://ppswws.github.io/okpay-plugins/maven-repo/`），无需手动安装依赖。

## 开发一个新插件

1. 在 `plugins/` 下新建目录，pom 继承父 POM（见 `plugins/wxpay/pom.xml` 示例）。
2. 唯一必选依赖是 `com.okpay:okpay-plugin`（SDK 已声明在 dependencyManagement，无需写版本号）。
3. 实现 SDK 暴露的扩展点接口，类名必须带 `Plugin` 后缀（PF4J 扩展发现约定）。
4. `./build.sh <插件名>` 编译，产物为可被核心加载的插件 jar（manifest 含 `Plugin-Id`/`Plugin-Version`）。

## 运行要求

- JDK 21
- Maven 3.6.3+

## License

[Apache-2.0](LICENSE)
