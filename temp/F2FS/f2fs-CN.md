# 什么是F2FS？

基于NAND的存储设备，例如 SSD, eMMC, and SD cards，已经广泛的使用在从手机到服务器等设备上。由于已知它们具有不同于传统旋转磁盘的特性，文件系统作为存储设备的上层，应该在设计层面上适应适应这种变化。

F2FS是一种利用基于NAND闪存的存储设备的文件系统，它基于日志结构文件系统（LFS - Log-structured File System）。设计的重点是解决LFS中的基本问题，即游荡的雪球效应树（wandering tree）和高空清洁（high cleaning overhead）。

因为基于NAND闪存的存储设备显示出不同的特性根据其内部几何结构或闪存管理方案，即FTL，F2FS及其工具不仅支持在磁盘上配置的各种参数布局，也可用于选择分配和清理算法。

下面的git树提供了文件系统格式化工具（mkfs.f2fs），一个一致性检查工具（fsck.f2fs）和一个调试工具（dump.f2fs）。

[工具下载链接](git://git.kernel.org/pub/scm/linux/kernel/git/jaegeuk/f2fs-tools.git)

## 背景和涉及问题

**LFS(log struction文件系统)**

“日志结构的文件系统将所有修改按顺序写入磁盘一种类似日志的结构，从而加快文件写入和崩溃恢复。log是磁盘上唯一的结构；它包含索引信息，以便可以有效地从日志中读取文件。为了在磁盘上保持较大的可用区域以进行快速写入，我们将日志划分为多个段，并使用一个段清除器从碎片严重的段中压缩实时信息。”来自 Rosenblum, M. and Ousterhout, J. K., 1992，“日志结构文件系统的设计与实现”ACM Trans. Computer Systems 10, 1, 26–52.

 [LFS补充知识](https://blog.csdn.net/wdy_yx/article/details/42848773)

**Wandering Tree 问题（滚雪球问题）**
在LFS，当一个数据文件更新，并写入到log的末尾，由于改变了位置，所有它的直接指向block改变。由于直接指向块更新，所以其间接指向块也要更新。以这种方式，向上的索引结构都要递归更新，例如inode,inode map,checkpoint block.这个问题被叫做Wandering Tree问题，为了提升性能，应该尽可能的消除和减缓更新的传播。

[1] Bityutskiy, A. 2005. JFFS3 design issues. http://www.linux-mtd.infradead.org/

**Cleaning Overhead（高清理开销）**
LFS是基于异地写， 它会产生很多荒废的分散的block遍布在整个存储空间。为了提供新的空日志空间，它需要无缝地向用户回收这些过时的块。这个工作叫做作为一个清洁过程。

这个过程包括以下三个过程：
1. A victim segment is selected through referencing segment usage table.
2. It loads parent index structures of all the data in the victim identified by
   segment summary blocks.
3. It checks the cross-reference between the data and its parent index structure.
4. It moves valid data selectively.

此清理作业可能会导致意外的长延迟，因此最重要的目标是向用户隐藏延迟。当然，它也应该减少要移动的有效数据的数量，并快速移动它们。

## 关键特性

