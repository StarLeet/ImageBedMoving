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
3. 用记事本编辑`ImageMoving.properties`

```
# 笔记所在目录
NotesDir=E:\\git_exercise\\imageBedMoving\\example
# 笔记中存储图片的图床路径
ImagesBedPathReg=Z:\\\\MyNotes\\\\github图床\\\\cloud_img\\\\data\\\\
# 正则匹配图片名字(下式不支持中文命名！)
# 如匹配失败，才建议自行修改
ImageNameReg=\\w*-?\\w*\\.(jpeg|[a-zA-Z]{3})
```

4. 双击`run.bat`

验收成果即可。如果提示失败，可以检查一下配置文件

# 成果展示

笔记目录(**直接从原目录建立笔记本**)改动前：
![](vx_images/549861414249665.png)

笔记1改动前：
![](vx_images/366761314236031.png)

笔记2改动前：
![](vx_images/176551414231785.png)



## 运行结果
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