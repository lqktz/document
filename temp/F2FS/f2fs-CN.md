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
**Flash Awareness**
- 扩大了随机写的区域用以提高性能，但是要提供更高的空间位置；
- 尽最大的努力，在FTL中，将FS的数据结构和操作单元对齐；

**Wandering Tree Problem（滚学球问题）**
- 引用一个术语，node，它代表inodes以及各种指针块。
- 引入包含所有“节点”位置的节点地址表（NAT Node Address Table）块；这将切断更新传播。

**Cleaning Overhead(清洁开销)**
- 支持后台清理进程
- 在搬移目标选择上，支持贪心（greedy）算法和成本最优（cost-benefit）算法
- 在静态/动态冷热数据分离上，支持multi-head logs 
- 引入自适应日志以实现高效的块分配

## 挂载选项
**background_gc=%s**
打开/关闭清理现象，即GC，当IO子系统是idle在后台触发。如果background_gc=on，将会打开GC，如果background_gc=off，GC就关闭。如果background_gc=sync，它将同步在后台GC。默认值是on。所以GC默认是打开的。

**disable_roll_forward**
关闭前滚恢复。

**norecovery**
关闭前滚恢复,以只读方式挂载。

**discard/nodiscard**
在f2fs中启用/禁用实时丢弃，如果启用了discard，f2fs将在清除段时发出discard/TRIM命令。 

**no_heap**
禁用堆样式的段分配，它从主区域开始的位置查找data的空闲段，而从主区域结束查找node的空闲段。

**nouser_xattr**
关闭扩展用户属性。Note： 如果配置了CONFIG_F2FS_FS_XATTR，xattr是使能的。

**noacl**
关闭POSIX Access Control List。 Note：如果配置了CONFIG_F2FS_FS_POSIX_ACL， acl是默认打开的。

**active_logs=%u**
支持配置active logs的数量。目前的设计中只支持2,4,6.默认是6.

**disable_ext_identify**
禁用mkfs配置的扩展名列表，这样f2fs就不会发现诸如媒体文件之类的冷文件。

**inline_xattr**
使能inline xattrs功能。

**noinline_xattr**
关闭inline xattrs功能。

**inline_xattr_size=%u**
支持配置inline xattr大小。它依赖灵活的inline xattr功能。

**inline_data**
使能inline date功能： 新创建的一个小文件（<～3.4K）能被写入inode块。（可提升小文件读写行性能）

**inline_dentry**
使能inline dir功能： 新创建的dir中的data可以写入inode块。inode块用于存储inline dir空间限制在~3.4k。（可提升性能）

**noinline_dentry**
关闭inline data功能。

**flush_merge**
尽可能合并当前的cache_flush，来消除冗余命令问题。如果底层设备处理cache_flush命令的速度相对较慢，建议启用此选项。

**nobarrier**
如果底层存储保证其缓存的数据应写入novolatile区域，则可以使用此选项。如果设置了此选项，则不会发出缓存刷新命令，但f2fs仍然保证所有数据写入的写入顺序。（可能会牺牲稳定性，一定提升性能）

**fastboot**
当系统希望尽可能减少装载时间时，即使可以牺牲正常性能，也可以使用此选项

**extent_cache**
启用基于rb-tree的扩展缓存，它可以缓存每个inode在相邻逻辑地址和物理地址之间映射的尽可能多的扩展，从而提高缓存命中率。默认设置。（提升性能）

**noextent_cache**
Disable an extent cache based on rb-tree explicitly, see the above extent_cache mount option.

**noinline_data**
禁用inline data功能，默认情况下启用inline data功能。

**data_flush**
在检查点之前启用数据刷新，以便保留常规和符号链接的数据。

**fault_injection=%d**
使能fault injection，支持所有的类型中启用故障注入。

**fault_type=%d**
支持配置故障注射类型，需要配置fault_injection选项，错误类型值如下，支持单个或者组合类型。
                       Type_Name		Type_Value
                       FAULT_KMALLOC		0x000000001
                       FAULT_KVMALLOC		0x000000002
                       FAULT_PAGE_ALLOC		0x000000004
                       FAULT_PAGE_GET		0x000000008
                       FAULT_ALLOC_BIO		0x000000010
                       FAULT_ALLOC_NID		0x000000020
                       FAULT_ORPHAN		0x000000040
                       FAULT_BLOCK		0x000000080
                       FAULT_DIR_DEPTH		0x000000100
                       FAULT_EVICT_INODE	0x000000200
                       FAULT_TRUNCATE		0x000000400
                       FAULT_READ_IO		0x000000800
                       FAULT_CHECKPOINT		0x000001000
                       FAULT_DISCARD		0x000002000
                       FAULT_WRITE_IO		0x000004000

**mode=%s**
控制block分配模式，支持adaptive，lfs。如果在lfs模式，不应向主区域随机写入数据。（一般都是选择adaptive）

**io_bits**
设置每个IO请求的大小（bit）。需要在mode=lfs中使用。

**usrquota**
启用普通用户磁盘配额记帐。

**grpquota**
启用普通组磁盘配额记帐。

**prjquota**
启用普通项目配额记帐。 

**usrjquota=<file>**
**grpjquota=<file>**
**prjjquota=<file>**
在装载期间指定指定的文件和类型，以便在恢复流期间正确更新配额信息，<quota file>: 必须在root dir;

**jqfmt=<quota type>**
<quota type>: [vfsold,vfsv0,vfsv1].

**offusrjquota**
关闭用户日记配额。

**offgrpjquota**
关闭组日记配额。

**offprjjquota**
关闭项目日记配额。

**quota**
启用普通用户磁盘配额记帐。

**noquota**
禁用所有普通磁盘配额选项。

**whint_mode=%s**
Control which write hints are passed down to block
                       layer. This supports "off", "user-based", and
                       "fs-based".  In "off" mode (default), f2fs does not pass
                       down hints. In "user-based" mode, f2fs tries to pass
                       down hints given by users. And in "fs-based" mode, f2fs
                       passes down hints with its policy.（没有明白，主要是hint是什么意思？？？）

**alloc_mode=%s**
调整块分配策略，支持“reuse”和“default”。

**fsync_mode=%s**
控制fsync的策略。当前支持posix，strict，nobarrier。在posix模式（默认方式），fsync遵循POSIX语义，做一个轻量级的操作来提升文件系统性能。在strict模式，fsync will be heavy and behaves in line with xfs, ext4 and btrfs, where xfstest generic/342 will pass, 但是性能会下降.nobarrier模式，是基于posix，但不会对非原子文件发出flush命令，类似挂载参数nobarrier选项。

**test_dummy_encryption**
使能一个傀儡加密，提供一个虚假的文件系统加密上下文。它被xfstests使用。

**checkpoint=%s**
设置disable，可以关闭checkpoint。设置enable将重新打开checkpoint。默认是enable。当设置为disable时，任何卸载或意外关闭都将导致文件系统内容显示为使用该选项装载文件系统时的显示。

## DEBUG入口
`/sys/kernel/debug/f2fs/`

`/sys/kernel/debug/f2fs/status` 包括：
- 当前由f2fs管理的主要文件系统信息
- 平均的SIT 信息，包括了全部的segments的信息
- f2fs消耗的当前内存占用。 


## SYSFS入口

mount的f2fs文件系统的信息可以在`/sys/fs/f2fs`中找到。每个mount的文件系统将在`/sys/fs/f2fs`中有一个基于他的设备名的文件夹（如：/sys/fs/f2fs/sda）。文件夹里面的文件如列表所示：

在`/sys/fs/f2fs/<devname>`的文件：
（可参见： Documentation/ABI/testing/sysfs-fs-f2fs）
**gc_max_sleep_time**
此优化参数控制垃圾收集线程的最大睡眠时间。时间以毫秒为单位。

**gc_min_sleep_time**
此优化参数控制垃圾收集线程的最小睡眠时间。时间以毫秒为单位。

**gc_no_gc_sleep_time**
此优化参数控制垃圾收集线程的默认睡眠时间。时间以毫秒为单位。

**gc_idle**
该参数用于选择GC时的搜索策略选择。设置gc_idle = 0 （默认值）将关闭此选项。设置gc_idle = 1将选择cost benefit算法，设置gc_idle = 2设置贪心算法

**gc_urgent**
该参数控制触发后台GCs是否紧急，设置　gc_urgent＝０（默认值）会按照默认行为，一旦设置为１，后台线程去ＧＣ，给定的时间gc_urgent_sleep_time间隔。


**gc_urgent_sleep_time**
这个参数控制gc_urgent的睡眠时间。默认是500ｍｓ

**reclaim_segments**
此参数控制要回收的预释放段数。如果预释放段的数量大于占总卷大小百分比的段的数量，f2fs会尝试执行checkpoint以回收预释放段以释放段。默认值，超过总segments的5%.

**max_small_discards**
此参数控制由小于2MB的小块组成的丢弃命令的数量。 将缓存待丢弃的候选对象，直到触发检查点为止，然后在检查点期间将其发布。 默认情况下，它被禁用为0。

**trim_sections**
此参数控制在执行FITRIM操作时要在批处理模式中修剪的节数。默认设置32个部分。

**ipu_policy**
这个参数是控制在f2fs中原地更新数据（in-place updates）的策略.有一下5种策略：
                               0x01: F2FS_IPU_FORCE, 0x02: F2FS_IPU_SSR,
                               0x04: F2FS_IPU_UTIL,  0x08: F2FS_IPU_SSR_UTIL,
                               0x10: F2FS_IPU_FSYNC.

**min_ipu_util**
这个参数控制参数触发ipu的阈值。这个数值是指文件李彤利用的百分比。被F2FS_IPU_UTIL 和 F2FS_IPU_SSR_UTIL策略使用。

**min_fsync_blocks**
当F2FS_IPU_FSYNC被设置，该参数控制触发原地更新数据的一个阈值。数字表示fsync需要刷新其调用路径时的脏页数。如果数字小于此值，则会触发就地更新。

**max_victim_search**
此参数控制在执行SSR和清理操作时查找受害者片段的试验次数。默认值为4096，覆盖8GB块地址范围。

**dir_level**
此参数控制目录级别以支持大目录。如果目录包含多个文件，则可以通过增加此目录级别值来减少文件查找延迟。否则，它需要将该值减小到减少空间开销。默认值为0。

**ram_thresh**
此参数控制空闲NID和缓存NAT项使用的内存占用。默认设置为10，表示内存为10 MB/1 GB。

## 使用


## 设计

**在磁盘的布局**
F2FS将整个卷分为多个segment，每个的固定大小是2M。一个section由连续的segment组成。一个zone由多个

                                            align with the zone size <-|
                 |-> align with the segment size
     _________________________________________________________________________
    |            |            |   Segment   |    Node     |   Segment  |      |
    | Superblock | Checkpoint |    Info.    |   Address   |   Summary  | Main |
    |    (SB)    |   (CP)     | Table (SIT) | Table (NAT) | Area (SSA) |      |
    |____________|_____2______|______N______|______N______|______N_____|__N___|
                                                                       .      .
                                                             .                .
                                                 .                            .
                                    ._________________________________________.
                                    |_Segment_|_..._|_Segment_|_..._|_Segment_|
                                    .           .
                                    ._________._________
                                    |_section_|__...__|_
                                    .            .
		                    .________.
	                            |__zone__|



****

****

****


****