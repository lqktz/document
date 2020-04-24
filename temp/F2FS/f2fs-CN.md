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
此处略去，主要适用于Linux，非android配置。

## 设计

**在磁盘的布局**
F2FS将整个卷分为多个segment，每个的固定大小是2M。一个section由连续的segment组成。一个zone由多个由一组多个section组成。默认情况下，部分和区域大小设置为1段大小相同，但是用户可以通过mkfs轻松修改大小。

F2FS将整个区域分割为6大区域，并且，除superblock意外的其他区域都包含多个segments，如下描述：
```
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
```

<以下翻译不是完全翻译，包括其他的先关的理解>
- 超级块（SB） 包含基本分区信息和F2FS在格式化分区时确定不可更改的参数。它位于分区的开始的位置，为了避免文件系统crash，有两份copy。把包括基本的分区参数和一些f2fs默认的参数。

- 检查点（CP） 保存文件系统状态，有效NAT/SIT（见下文说明）集合的位图，孤儿inode列表（文件被删除时尚有引用无法立即释放时需被计入此列表，以便再次挂载时释放）和当前活跃段的所有者信息。和其他日志结构文件系统一样，F2FS检查点时某一给定时点一致的文件系统状态集合——可用于系统崩溃或掉电后的数据恢复。F2FS的两个检查点各占一个Segment，和前述不同的是，F2FS通过检查点头尾两个数据块中的version信息判断检查点是否有效。

- 段信息表Segment Information Table（SIT） 包含主区域（Main Area，见下文说明）中每个段的有效块数和标记块是否有效的位图。SIT主要用于回收过程中选择需要搬移的段和识别段中有效数据。

- 索引节点地址表Node Address Table（NAT） 用于定位所有主区域的索引节点块（包括：inode节点、直接索引节点、间接索引节点）地址。即NAT中存放的是inode或各类索引node的实际存放地址。

- 段摘要区Segment Summary Area (SSA) 主区域所有数据块的所有者信息（即反向索引），包括：父inode号和内部偏移。SSA表项可用于搬移有效块前查找其父亲索引节点编号,

- 主区域 Main Area 由4KB大小的数据块组成，每个块被分配用于存储数据（文件或目录内容）和索引（inode或数据块索引）。一定数量的连续块组成Segment，进而组成Section和Zone（如前所述）。一个Segment要么存储数据，要么存储索引，据此可将Segment划分为数据段和索引段。

**File System Metadata Structure（文件系统元数据结构）**
F2FS采用检查点方案来维护文件系统的一致性。在挂载时，F2FS首先尝试通过扫描CP区域来查找最后一个有效的检查点数据。为了减少扫描时间，F2FS仅使用CP的两个副本。“一个”始终表示最后一个有效数据，称为“影子复制”机制。除了CP之外，NAT和SIT还采用影子复制机制。

为了文件系统的一致性，每个ＣＰ指向有效的NAT和SIT副本，如下所示。
```

  +--------+----------+---------+
  |   CP   |    SIT   |   NAT   |
  +--------+----------+---------+
  .         .          .          .
  .            .              .              .
  .               .                 .                 .
  +-------+-------+--------+--------+--------+--------+
  | CP #0 | CP #1 | SIT #0 | SIT #1 | NAT #0 | NAT #1 |
  +-------+-------+--------+--------+--------+--------+
     |             ^                          ^
     |             |                          |
     `----------------------------------------'
```

**Index Structure（索引结构）**
管理数据位置的关键数据结构是“node”。与传统文件结构类似，F2FS具有三种类型的节点：inode，direct node,indirect node.
间接节点。F2FS给一个inode块分配了4k，一个inode包括了923个data块索引，两个direct node指针，两个indirect node指针，一个double indirect node。一个直接索引块可以包括1018个data块，一个one indirect node块也包括1018个node块。因此，一个inode块包含：
```
  4KB * (923 + 2 * 1018 + 2 * 1018 * 1018 + 1018 * 1018 * 1018) := 3.94TB.   （4KB是指一个block大小）

   Inode block (4KB)
     |- data (923)
     |- direct node (2)
     |          `- data (1018)
     |- indirect node (2)
     |            `- direct node (1018)
     |                       `- data (1018)
     `- double indirect node (1)
                         `- indirect node (1018)
			              `- direct node (1018)
	                                         `- data (1018)
```

请注意，所有节点块均由NAT映射，这意味着每个节点的位置均由NAT表转换。考虑到游荡树问题，F2FS能够阻止由叶数据写入引起的节点更新的传播。

**Directory Structure**
一个dir entry占据11比特，包括以下属性：
- hash 文件名的hash值
- ino inode 数量
- len 文件名长度
- type 文件类型，例如： 文件夹，符号链接等

一个文件夹entry块包括214个插槽和文件名。其中，位图用于表示每个dentry是否有效。一个占据4k的dentry块有以下组成部分：
```
  Dentry Block(4 K) = bitmap (27 bytes) + reserved (3 bytes) +
	              dentries(11 * 214 bytes) + file name (8 * 214 bytes)

                         [Bucket]
             +--------------------------------+
             |dentry block 1 | dentry block 2 |
             +--------------------------------+
             .               .
       .                             .
  .       [Dentry Block Structure: 4KB]       .
  +--------+----------+----------+------------+
  | bitmap | reserved | dentries | file names |
  +--------+----------+----------+------------+
  [Dentry Block: 4KB] .   .
		 .               .
            .                          .
            +------+------+-----+------+
            | hash | ino  | len | type |
            +------+------+-----+------+
            [Dentry Structure: 11 bytes]
```

F2FS为目录结构实现了多级别hash表。
```
----------------------
A : bucket
B : block
N : MAX_DIR_HASH_DEPTH
----------------------

level #0   | A(2B)
           |
level #1   | A(2B) - A(2B)
           |
level #2   | A(2B) - A(2B) - A(2B) - A(2B)
     .     |   .       .       .       .
level #N/2 | A(2B) - A(2B) - A(2B) - A(2B) - A(2B) - ... - A(2B)
     .     |   .       .       .       .
level #N   | A(4B) - A(4B) - A(4B) - A(4B) - A(4B) - ... - A(4B)

block和bucket的数量取决于,

                            ,- 2, if n < MAX_DIR_HASH_DEPTH / 2,
  # of blocks in level #n = |
                            `- 4, Otherwise

                             ,- 2^(n + dir_level),
			     |        if n + dir_level < MAX_DIR_HASH_DEPTH / 2,
  # of buckets in level #n = |
                             `- 2^((MAX_DIR_HASH_DEPTH / 2) - 1),
			              Otherwise
```
当F2FS在目录中找到文件名时，首先会计算该文件名的哈希值。 然后，F2FS扫描级别0的哈希表，以查找由文件名及其索引节点号组成的dentry。 如果未找到，F2FS将扫描级别1的下一个哈希表。 通过这种方式，F2FS从1到N递增地扫描每个级别中的哈希表。在每个级别中，F2FS仅需要扫描由以下等式确定的一个存储桶，这显示了O（log（file＃））复杂性。

要在级别扫描的存储桶编号#n = (hash value) % (# of buckets in level #n)

在创建文件的情况下，F2FS查找覆盖文件名的连续的空插槽。 F2FS以与查找操作相同的方式搜索从1到N的整个级别的哈希表中的空插槽。

下图显示了两个带孩子的案件的例子：
```
       --------------> Dir <--------------
       |                                 |
    child                             child

    child - child                     [hole] - child

    child - child - child             [hole] - [hole] - child

   Case 1:                           Case 2:
   Number of children = 6,           Number of children = 3,
   File size = 7                     File size = 7
```

**Default Block Allocation**
在运行时，在Main区域，F2FS管理者6个激活的logs： Hot/Warm/Cold node 和 Hot/Warm/Cold data.

- Hot node	contains direct node blocks of directories.
- Warm node	contains direct node blocks except hot node blocks.
- Cold node	contains indirect node blocks
- Hot data	contains dentry blocks
- Warm data	contains data blocks except hot and cold data blocks
- Cold data	contains multimedia data or migrated data blocks

LFS有两个策略针对空闲空间的管理：threaded log 和 copy-and-compac-tion.复制和压缩方案（称为清理）非常适合于显示出非常好的顺序写入性能的设备，因为空闲时间段一直用于写入新数据。 然而，它在高利用率下遭受清洁开销的困扰。 相反，线程日志方案遭受随机写操作，但是不需要清理过程。 F2FS采用一种混合方案，默认情况下采用复制和压缩方案，但是该策略会根据文件系统状态动态更改为线程日志方案。

为了使F2FS与基础的基于闪存的存储保持一致，F2FS以段为单位分配一个段。 F2FS期望节的大小将与FTL中垃圾回收的单位大小相同。 此外，关于FTL中的映射粒度，由于FTL可以根据其映射粒度将活动日志中的数据写入一个分配单元，因此F2FS尽可能多地分配来自不同区域的活动日志的每个部分。

**Cleaning process**
F2FS确实可以按需和在后台进行清理。 当没有足够的空闲段来服务VFS呼叫时，将触发按需清洁。 后台清理程序由内核线程操作，并在系统空闲时触发清理作业。

F2FS支持两种受害者选择策略：贪婪算法和成本收益算法。在贪婪算法中，F2FS选择有效块数量最少的受害者段。 在成本效益算法中，F2FS根据分段寿命和有效块数选择一个受害分段，以解决贪婪算法中的日志块跳动问题。 F2FS对按需清洁器采用贪婪算法，而背景清洁器采用成本效益算法。

为了识别受害段中的数据是否有效，F2FS管理一个位图。 每个位代表一个块的有效性，位图由覆盖主区域中整个块的位流组成。