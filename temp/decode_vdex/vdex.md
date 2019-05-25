# Android P 反编译vdex

本文记一次解析YouTube的vdex的过程.由于在Android P中有一种新的dex文件格式cdex,

## 0 准备文件
从机器中拷贝出文件:

```
adb pull system/app/YouTube ./
```

目录结构如下:

```
YouTube |
        |--YouTube.apk
        |--oat/arm
                  |--YouTube.odex
                  |--YouTube.vdex       
```
本例子中,要解析的文件就是`YouTube.vdex`.

**在以下解析过程中,如果提示权限问题,请自行使用`chmod`添加可执行权限，下文中不在赘述！！！**

## 1 解析vdex到cdex文件

需要用到一个开源工具[vdexExtractor](https://github.com/anestisb/vdexExtractor),

```
git clone https://github.com/anestisb/vdexExtractor.git
```
执行`vdexExtractor/make.sh`, 就会在产生`vdexExtractor/bin/vdexExtractor`.
使用bin文件`vdexExtractor`解析vdex文件:

```
./vdexExtractor -i ./YouTube.vdex -o ./ --deps -f
```
> 此处将vdex文件和vdexExtractor放到同一目录下

解析完成后会得到4个cdex文件:`YouTube_classes.cdex`,`YouTube_classes2.cdex`,`YouTube_classes3.cdex`,`YouTube_classes4.cdex`.

> vdexExtractor 的详细使用,可以使用`./vdexExtractor -h` 查看.

## 2 解析cdex到dex文件
解析cdex到需要用到[compact_dex_converter](https://github.com/anestisb/vdexExtractor/issues/23),在网页中下载`compact_dex_converter_linux.zip`
可以在ubuntu下使用.

使用`compact_dex_converter`,解析
```
./compact_dex_converter YouTube_classes.cdex
```
> 四个cdex文件我们以YouTube_classes.cdex为例

运行完会产生一个`YouTube_classes.cdex.new`文件，该文件就是我们的dex文件。我们可以用vim打开改文件，文件的开头就是dex文件的格式。

```

```
## 3 解析dex到jar
解析dex文件到jar文件需要用到dex2jar，
dex2jar YouTube_classes.cdex.new -> ./YouTube_classes.cdex-dex2jar.jar
com.googlecode.d2j.DexException: not support version.
	at com.googlecode.d2j.reader.DexFileReader.<init>(DexFileReader.java:151)
	at com.googlecode.d2j.reader.DexFileReader.<init>(DexFileReader.java:211)
	at com.googlecode.dex2jar.tools.Dex2jarCmd.doCommandLine(Dex2jarCmd.java:104)
	at com.googlecode.dex2jar.tools.BaseCmd.doMain(BaseCmd.java:288)
	at com.googlecode.dex2jar.tools.Dex2jarCmd.main(Dex2jarCmd.java:32)

https://github.com/pxb1988/dex2jar/releases


## 4 解析jar文件


## 参看文献





