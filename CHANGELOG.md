# Changelog

## v0.15.0 - 2026-10-14

## [0.15.0](https://github.com/wenisch-tech/Petri/compare/v0.14.0...v0.15.0) (2026-10-14)


### Features

* initial suppport for git related skills ([6bc657d](https://github.com/wenisch-tech/Petri/commit/6bc657dd0a39a7855712d7f17eac0fddf4f49413))
* introduced vulnerability  discovery ([d975ec1](https://github.com/wenisch-tech/Petri/commit/d975ec179eb8ea665c0bb2e6d9ef3e1a75915d3d))
* user is redirected to target page after signin when no session has been found ([5c43ceb](https://github.com/wenisch-tech/Petri/commit/5c43cebd2a8d92bad3d17075afe1c9123f20b271))


### Bug Fixes

* fixed streaming problem when no choices received ([ccc23ea](https://github.com/wenisch-tech/Petri/commit/ccc23eaddd9c652102350cd4e52f9d2e0800eef6))



Docker image: ghcr.io/wenisch-tech/petri:0.15.0


## v0.14.0 - 2026-10-14

## [0.14.0](https://github.com/wenisch-tech/Petri/compare/v0.13.0...v0.14.0) (2026-10-14)


### Features

* wired code review skill into pull requests ([ecaef40](https://github.com/wenisch-tech/Petri/commit/ecaef409db247e091571811b8c93d99ff06b9f05))



Docker image: ghcr.io/wenisch-tech/petri:0.14.0


## v0.13.0 - 2026-10-13

## [0.13.0](https://github.com/wenisch-tech/Petri/compare/v0.12.0...v0.13.0) (2026-10-13)


### Features

* initial introduction mcp client connections ([27e3030](https://github.com/wenisch-tech/Petri/commit/27e3030d6063b67a58f097d12227ed1a2732b44b))



Docker image: ghcr.io/wenisch-tech/petri:0.13.0


## v0.12.0 - 2026-10-13

## [0.12.0](https://github.com/wenisch-tech/Petri/compare/v0.11.0...v0.12.0) (2026-10-13)


### Features

* added gui for local user management ([812bcd2](https://github.com/wenisch-tech/Petri/commit/812bcd2a981a6c30475fbb2d0802487a650d9c21))
* allow project specifc project environment ([bd919c1](https://github.com/wenisch-tech/Petri/commit/bd919c1f43e66a0d1918ef2a2a5f26fb783940cc))


### Bug Fixes

* updated version info ([6221db3](https://github.com/wenisch-tech/Petri/commit/6221db326aef574e463db4e6f160bbd2fbeed3e3))



Docker image: ghcr.io/wenisch-tech/petri:0.12.0


## v0.11.0 - 2026-10-13

## [0.11.0](https://github.com/wenisch-tech/Petri/compare/v0.10.0...v0.11.0) (2026-10-13)


### Features

* added automatic fetching from models ([5b13ecf](https://github.com/wenisch-tech/Petri/commit/5b13ecfa949dbcd0c36bee88a2a6b5e13c1298b7))
* improved dashboard and introduced automations overview ([282a483](https://github.com/wenisch-tech/Petri/commit/282a48343f5671cb37bb3536ccc88c18f016c36d))
* improved projects vizsualization ([35066e5](https://github.com/wenisch-tech/Petri/commit/35066e5a53f47f276bdba3ba2e10bdd2c9269db1))


### Bug Fixes

* further minor improvement to the UI ([fe3f708](https://github.com/wenisch-tech/Petri/commit/fe3f7089f9ff25de6f39c3dc486aaf4ec1ec4626))
* minor changes to iconset ([7f3da82](https://github.com/wenisch-tech/Petri/commit/7f3da8283271778fa9f303f71911614dfddf1dd1))
* minor improvements to Workspace settings UI ([328fbab](https://github.com/wenisch-tech/Petri/commit/328fbab7cd19964c22999315fd7a47a9ec3794b4))



Docker image: ghcr.io/wenisch-tech/petri:0.11.0


## v0.10.0 - 2026-10-12

## [0.10.0](https://github.com/wenisch-tech/Petri/compare/v0.9.0...v0.10.0) (2026-10-12)


### Features

* Introduced pull request action ([6aeff43](https://github.com/wenisch-tech/Petri/commit/6aeff43f064a2b7c1951d5cf76461bcdc11438a4))


### Bug Fixes

* convenience features for restarting failed automation runs + minor improvements for loopguard handling. Also fixed problem leading to unsuccessfl tool calls on submit review ([34e488d](https://github.com/wenisch-tech/Petri/commit/34e488d5a83a126f5bf1f8d2dc3a25b54bc99874))
* ensure only pull request of the current project are reviewed ([f98d318](https://github.com/wenisch-tech/Petri/commit/f98d3189ab2d2b784721e0a6e10025275a8e86e8))



Docker image: ghcr.io/wenisch-tech/petri:0.10.0


## v0.9.0 - 2026-10-12

## [0.9.0](https://github.com/wenisch-tech/Petri/compare/v0.8.1...v0.9.0) (2026-10-12)


### Features

* automatic commt message generation ([b70dca6](https://github.com/wenisch-tech/Petri/commit/b70dca61d39bbb46813c9d957eb6e1886bc9ea75))
* introduced loop guard espcially fix problem with smaller local models ([8b5c271](https://github.com/wenisch-tech/Petri/commit/8b5c2712468ff7f18d5da34df47eedda4b272db8))
* refactoring menu in workspace ([fecd103](https://github.com/wenisch-tech/Petri/commit/fecd10370c4715b3dcc8fa09c3e248cc74919c6c))


### Bug Fixes

* Allow  manual approval / completion of workflow although not all gates are completed ([a314749](https://github.com/wenisch-tech/Petri/commit/a31474907c9c3094424b43882e168105af1ff5aa))
* fixed changes indication for branch ([a8fffb7](https://github.com/wenisch-tech/Petri/commit/a8fffb72056aa16301c984d14f94de1127c84099))
* only latest run decides and superseeded failures are named ([93c6400](https://github.com/wenisch-tech/Petri/commit/93c64009453b7a0f3cd92f63801c249a17bbc227))



Docker image: ghcr.io/wenisch-tech/petri:0.9.0


## v0.8.1 - 2026-10-12

### [0.8.1](https://github.com/wenisch-tech/Petri/compare/v0.8.0...v0.8.1) (2026-10-12)


### Bug Fixes

* ensured duplicate port binds are automatically resolved ([d2b0cb5](https://github.com/wenisch-tech/Petri/commit/d2b0cb592713b6021974c377135f38cd57742b75))
* unclear test results go back to the user for confirmation ([44f89c2](https://github.com/wenisch-tech/Petri/commit/44f89c297aac8e114db992250502e3dd20d03358))



Docker image: ghcr.io/wenisch-tech/petri:0.8.1


## v0.8.0 - 2026-10-12

## [0.8.0](https://github.com/wenisch-tech/Petri/compare/v0.7.0...v0.8.0) (2026-10-12)


### Features

* Introduction of automations and code-review skill ([f299875](https://github.com/wenisch-tech/Petri/commit/f299875e4d322612049d4ea0849c4c773f6de07f))


### Bug Fixes

* ensure symlinks for package installs are properly installed ([f6eebbb](https://github.com/wenisch-tech/Petri/commit/f6eebbbaf797e6428aadc6516ef3698f91f30135))



Docker image: ghcr.io/wenisch-tech/petri:0.8.0


## v0.7.0 - 2026-10-12

## [0.7.0](https://github.com/wenisch-tech/Petri/compare/v0.6.0...v0.7.0) (2026-10-12)


### Features

* introduced support for custom env variables ([4fa707a](https://github.com/wenisch-tech/Petri/commit/4fa707ab3315cdc1be5ed2b74ad8bbc2460f5c78))



Docker image: ghcr.io/wenisch-tech/petri:0.7.0


## v0.6.0 - 2026-10-12

## [0.6.0](https://github.com/wenisch-tech/Petri/compare/v0.5.0...v0.6.0) (2026-10-12)


### Features

* Introducing support for multiple OIDC provider ([77c939b](https://github.com/wenisch-tech/Petri/commit/77c939b1b49dbabdfbf8a0831638b5d816f87c9c))



Docker image: ghcr.io/wenisch-tech/petri:0.6.0


## v0.5.0 - 2026-10-12

## [0.5.0](https://github.com/wenisch-tech/Petri/compare/v0.4.0...v0.5.0) (2026-10-12)


### Features

* update readme with gif ([976126e](https://github.com/wenisch-tech/Petri/commit/976126e4399740d3393e83b71e9ac738e2b62bb1))



Docker image: ghcr.io/wenisch-tech/petri:0.5.0


## v0.3.0 - 2026-10-12

## [0.3.0](https://github.com/wenisch-tech/Petri/compare/v0.2.0...v0.3.0) (2026-10-12)


### Features

* approval section in workspace ([ef359d4](https://github.com/wenisch-tech/Petri/commit/ef359d4d7d6e5deb18fe3f2079df84065d904614))



Docker image: ghcr.io/wenisch-tech/petri:0.3.0


## v0.2.0 - 2026-10-11

## [0.2.0](https://github.com/wenisch-tech/Petri/compare/v0.1.0...v0.2.0) (2026-10-11)


### Features

* improved terminal run commands and anyuid with custom landlock ([83edbb7](https://github.com/wenisch-tech/Petri/commit/83edbb70d15ffd8d742dc739df53637605005343))
* switched to alpine based image and ensured agent activities wait for approval ([1972857](https://github.com/wenisch-tech/Petri/commit/1972857c9090d367278246987aa76e4d97f27022))



Docker image: ghcr.io/wenisch-tech/petri:0.2.0


## v0.1.0 - 2026-10-11

## 0.1.0 (2026-10-11)


### Features

* add ci ([b8e1ac6](https://github.com/wenisch-tech/Petri/commit/b8e1ac60754d28770ffa4af59d0cb7aebaba667c))
* added oidc login ([64a5d6b](https://github.com/wenisch-tech/Petri/commit/64a5d6b82905f9b3899a682664af5aaf98d146c9))
* Added workflow mode ([b44715d](https://github.com/wenisch-tech/Petri/commit/b44715d6e9320d1e02411cad1e3f6f11aba57efe))
* authentication & api keys ([0c2bb06](https://github.com/wenisch-tech/Petri/commit/0c2bb06d81051cf56dff3e45ef7bc4438c5be59c))
* cleanup UI ([badc904](https://github.com/wenisch-tech/Petri/commit/badc904209f9cb84289b3f4d702304aa43e9f6e0))
* create_file agent tool for worktree file proposals ([0b44343](https://github.com/wenisch-tech/Petri/commit/0b4434367c192b8aea0c6c9d60e0d9900609ff6f))
* docs and chat completions ([f0b2cbc](https://github.com/wenisch-tech/Petri/commit/f0b2cbc70a76f1fa2390a902cb205b53aa4d28b9))
* first tools implementation ([ceb3add](https://github.com/wenisch-tech/Petri/commit/ceb3add0616a4be3893ad1687e94c789c7df5116))
* fixed secret key creation ([7e692b1](https://github.com/wenisch-tech/Petri/commit/7e692b199a1cde2813489a2d07ba4cc6c0599531))
* improved git behavior ([2644680](https://github.com/wenisch-tech/Petri/commit/26446808ef2798e9f98114cd39b69443fede7712))
* initial commit workspace ([fc53c2c](https://github.com/wenisch-tech/Petri/commit/fc53c2cc222d1ea4f20c76d79bb693cf3fee6c70))
* Introduce git management ([f50a6d0](https://github.com/wenisch-tech/Petri/commit/f50a6d036f5ff5ecf2e21459aa4bca9dffc713ed))
* ModelCLient ([8a1123f](https://github.com/wenisch-tech/Petri/commit/8a1123f74eb37f44ce6564e160a783edaa312518))
* monace editor ([00cd520](https://github.com/wenisch-tech/Petri/commit/00cd520a3f9115779c4456ababb89d3a6c437dae))
* persistent model loop ([41ce5d5](https://github.com/wenisch-tech/Petri/commit/41ce5d535e8f55d5b298016b8a453070954f0c45))
* personal git handling ([079110c](https://github.com/wenisch-tech/Petri/commit/079110c0154e5c2afd4677b896b63607b180ca15))
* personal tokens ([b74a233](https://github.com/wenisch-tech/Petri/commit/b74a23316dd5b317a76cc620c8e27f2acaacc9e2))
* Phase 1 ([af350e5](https://github.com/wenisch-tech/Petri/commit/af350e519941fbe2efe8c17fadc8b2d85ea22c0c))
* project management ([a2af7ce](https://github.com/wenisch-tech/Petri/commit/a2af7ce10130fb11f3a383b79e92e23718dca070))
* runs are now part of chart ([163f326](https://github.com/wenisch-tech/Petri/commit/163f3267862315e0be303874741d66b76450c663))
* sse livestreams for tool calling ([46d8c95](https://github.com/wenisch-tech/Petri/commit/46d8c952087978e454745fe8539e03234fe945a6))
* updated display in chat and introduced reverting of changes ([074a1e1](https://github.com/wenisch-tech/Petri/commit/074a1e10b5b85a499fb843b0024955981bb191e6))
* updated docs and git behaviour ([2c00331](https://github.com/wenisch-tech/Petri/commit/2c00331e8b63cdbdf72b96fde7ebeec23616ad7a))
* updated work editor ([885dc9f](https://github.com/wenisch-tech/Petri/commit/885dc9f1d44c0fb492509d6882fa0dd6fcaf3232))
* ux remake ([315510f](https://github.com/wenisch-tech/Petri/commit/315510fd67cd33cf7863cdfbe3317b6ec8eb12e0))


### Bug Fixes

* fixed startup problems due to scheduler ([edd0029](https://github.com/wenisch-tech/Petri/commit/edd0029cc5ca19a393b3a8d1a465e10f21b5734f))
* improved workflow displays ([7a41563](https://github.com/wenisch-tech/Petri/commit/7a41563d5847eeebccd7595e2b9bc71000ed6fad))



Docker image: ghcr.io/wenisch-tech/petri:0.1.0

