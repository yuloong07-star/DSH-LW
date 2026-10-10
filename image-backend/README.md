# image-backend

一棵树里的图片后端, 也就是 host 树里那个 `sharp`。

## 现在是什么

**官方 sharp 0.35.5 的 WebAssembly 构建**, 由 `tools/pack-host.mjs` 作为 host 树的普通依赖装进去
(`sharp` + `@img/sharp-wasm32`), 没有覆盖任何东西。

要 wasm 而不是原生那套, 是因为这台设备上原生那条路是死的: sharp 的平台包裹的是 libvips, 而它是
按 glibc 编的, 安卓的 bionic 加载不了; 何况打包时本来就带 `--omit=optional`, 连平台包都不会装。
`@img/sharp-wasm32` 是一份 WebAssembly 模块, 不需要任何原生 binding, 而 sharp 在发现
`@img/sharp-linux-arm64` 不在时会自己挑它。

在没有原生 binding 的机器上实测 (scratch 目录 + `--omit=optional`): 它自己编出来的 JPEG 读回来是
`format jpeg, 64x48, 3 channels` 并解出了像素, PNG 没有回归。

## 以前是什么, 为什么换掉

`sharp/` 那个目录里以前是一个纯 JS 替身 (package.json 的版本写着 `0.0.0-luwi`), 它只回答
附件库入库时问的两个问题: 从 PNG 头读格式与宽高, 再用 `zlib.inflateSync` 加反过滤真解一遍像素当
"字节完整"的证明。**终端编码一律明确报错**, 不假装能编码。

它的代价写在用户看得见的地方: 只有 PNG 能进模型, **JPEG / WebP / GIF / 调色板 PNG 一律
`INVALID_IMAGE`**, 所以相册里的照片、别的应用分享过来的图都到不了模型; 超过 route 预算的图也没救
(host 那边要重编码, 而替身按设计拒绝)。

替身被删掉了, 它的历史留在 `docs/step5-record.md` (当时为什么这么做, 以及踩到的 `TRANSPORT` 那条
坑), 那份记录不再改动。

## 换过来之后变了什么

- 相册里的 JPEG 能进模型了, WebP / GIF 同理
- 超过 route 预算的图不再必然失败: host 侧现在真的能重编码, 那条质量阶梯有后端了
- 包体多约 9 MB (`@img/sharp-wasm32` 解包后的大小), 随 host 树打进 APK
- 代价是速度: wasm 比原生慢, 手机上 4608x3456 的 JPG 约 2 秒一张
