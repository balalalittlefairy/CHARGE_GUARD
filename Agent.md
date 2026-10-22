# GitHub 提交与发布约定

## 提交身份

- 本项目使用 GitHub 隐私邮箱：`38862718+balalalittlefairy@users.noreply.github.com`。
- 提交作者、提交者，以及附注标签的创建者均使用该邮箱，不写入个人收件邮箱。
- 开始提交或创建标签前，检查本项目 Git 配置以及可能覆盖身份的环境变量。
- 使用以下命令设置当前仓库；不要修改全局 Git 配置：

```powershell
git config --local user.name "balalalittlefairy"
git config --local user.email "38862718+balalalittlefairy@users.noreply.github.com"
```

- GitHub 网页的邮箱隐私设置不会自动修改本机 Git 配置，也不会修复已有提交。
- 提交后检查作者和提交者邮箱；创建附注标签后也检查标签邮箱，再推送。

## 上传范围

- 提交程序源码、构建必需文件、README、许可证、第三方声明及本文件。
- 不提交开发计划、调试资料、测试报告、界面原型、临时工具和备份；遵守 `.gitignore`。
- `artifacts/`、本机 SDK 配置、构建缓存、日志、截图和安装包留在本地。
- 密码、设备 Token、账号会话、Cookie、签名私钥和签名密码不得进入源码、文档或 Git 历史。
- 安装包和校验文件通过 GitHub Release 分发，不提交到源码仓库。
- 当前发布约定：使用 release 构建关闭调试，并沿用现有调试签名密钥。关闭调试与更换签名是两件事，未经用户授权不要更换签名密钥。
- 发布前确认 APK 的 `debuggable=false`，并比对现有 Release 的证书 SHA-256 指纹，确保覆盖升级兼容。

## 历史清理后的协作

- 本仓库已清理旧历史中的私人邮箱和开发资料，旧提交与标签编号已失效。
- 其他电脑或其他 Agent 如果仍使用清理前的副本，应重新克隆，不得把旧历史合并或推送回来。
- 不使用 `git push --mirror` 上传本地内部引用或备份引用。
- 推送前核对暂存文件和提交身份，避免将被忽略的本地资料强制加入 Git。
- 历史重写和强制更新分支、标签必须有用户明确授权；普通修改使用正常提交和推送。
