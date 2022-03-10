# ImageBedMoving

# ImageBedMoving做了什么？

1. ImageBedMoving会先读取`ImageMoving.properties`内的配置信息
2. 到指定的目录(NotesDir)下遍历各个文件中的内容，将符合配置要求的图片移动到指定目录下的`vx_images`
3. 将原文件进行备份，存储到`notes_bak`目录下，如遇特殊情况，随时可以还原回来。
4. 对原文件进行路径更新(因为图片的路径发生改变，文件内的引用也应该进行同步)。

**请不要急着关掉cmd窗口，上面写着非常详细的操作信息，你可以随时掌控ImageBedMoving的所有操作。**

# 使用教程

1. 将本仓库clone到本地
2. 解压`jre.zip`(Java运行环境)

### ~~请注意：务必确保你的markdown文件是UTF-8编码的！~~
### 现在完美支持GBK或者UTF8(no ROM),其他编码的markdown文件不支持！有乱码的可能

### 使用typora新建的文件默认为UTF-8,可以放心使用

## ImageMoving

1. 用记事本编辑`ImageMoving.properties`

```
# 笔记所在目录(需要用户自定义)
NotesDir=E:\\git_exercise\\imageBedMoving\\example

# 笔记文件名后缀 md
NotesType=md

# 笔记中存储图片的图床绝对路径(需要用户自定义)
ImagesBedPathReg=Z:\\\\MyNotes\\\\github图床\\\\cloud_img\\\\data\\\\

# 匹配图片名的正则式(下式可匹配 英文+数字 命名的图片)
# 如果有个人需求,可以自行编写; 填写前,请使用RegStringTest测试是否有效
ImageNameReg=\\w*-?\\w*\\.((?!)jpeg|[a-zA-Z]{3})

# 是否区分大小写(路径+图片名) no|yes
IgnoreCase=no

# 是否保留原来的图片  no|yes
# 选no相当于图床迁移,选yes则为图床复制
KeepOriginImages=no
```

2. 双击`ImageMoving_Run.bat`

验收成果即可。如果提示失败，可以检查一下配置文件

## RegStringTest(如果你希望检测你的正则表达式)

> 这是一个正则表达式测试工具，用来辅助用户编辑`ImageMoving.properties`。你可以先使用RegStringTest测试你自己的正则表达式，看一下是否符合预期的效果。确认无误后再将其写入`ImageMoving.properties`

1. 用记事本编辑`RegStringTest.properties`
2. 双击`RegStringTest_Run.bat`
3. 光标会先停顿,你可以将记事本中准备好的案例复制到终端
4. 回车后，输入exit再次回车
5. 程序会自动进行匹配，通过打印结果，可以检测你的正则表达式是否有效

# 成果展示

笔记目录(**直接从原目录建立笔记本**)改动前：
![](vx_images/549861414249665.png)

笔记1改动前：
![](vx_images/366761314236031.png)

笔记2改动前：
![](vx_images/176551414231785.png)



## 运行结果

### 新版不再显示成功信息(大多数情况是成功的,会导致控制台信息杂乱),着重显示失败信息
![](vx_images/123.gif)

## 最后成效

笔记1更改成功
![](vx_images/34063914237134.png)

笔记2更改成功
![](vx_images/363383914230268.png)

笔记目录下多出了notes_bak目录，存放的是改动前的笔记，且vx_images目录已被自动创建
![](vx_images/34384014220798.png)

进入vx_images，可以看到所有被迁移的图片
![](vx_images/265284014223302.png)