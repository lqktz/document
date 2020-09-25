# IO栈

## 重要结构体
bio   --> include/linux/blk_types.h

## 0 脏页回写
- 当空闲内存低于一个特定的阈值时，内核必须将脏页写回磁盘，以便释放内存。 
- 当脏页在内存中驻留时间超过一个特定的阈值时，内核必须将超时的脏页写回磁盘，
   以确保脏页不会无限期地驻留在内存中。

而是先写到了系统cache里，随后由pdflush内核线程将系统中的脏页写到磁盘上，在下面几种情况下，
系统会唤醒pdflush回写脏页：

1 、定时方式：
     定时机制定时唤醒pdflush内核线程，周期为/proc/sys/vm/dirty_writeback_centisecs ，单位
是(1/100)秒，每次周期性唤醒的pdflush线程并不是回写所有的脏页，而是只回写变脏时间超过
/proc/sys/vm/dirty_expire_centisecs（单位也是1/100秒）。
注意：变脏的时间是以文件的inode节点变脏的时间为基准的，也就是说如果某个inode节点是10秒前变脏的，
pdflush就认为这个inode对应的所有脏页的变脏时间都是10秒前，即使可能部分页面真正变脏的时间不到10秒，
细节可以查看内核函数wb_kupdate()。

2、 内存不足的时候：
    这时并不将所有的dirty页写到磁盘，而是每次写大概1024个页面，直到空闲页面满足需求为止。

3 、写操作时发现脏页超过一定比例：
    当脏页占系统内存的比例超过/proc/sys/vm/dirty_background_ratio 的时候，write系统调用会唤醒
pdflush回写dirty page,直到脏页比例低于/proc/sys/vm/dirty_background_ratio，但write系统调
用不会被阻塞，立即返回。当脏页占系统内存的比例超过/proc/sys/vm/dirty_ratio的时候， write系
统调用会被被阻塞，主动回写dirty page，直到脏页比例低于/proc/sys/vm/dirty_ratio，这一点在
2.4内核中是没有的。

4 、用户调用sync系统调用：
    这是系统会唤醒pdflush直到所有的脏页都已经写到磁盘为止。


pdflush回写期间极其消耗磁盘IO，严重影响读性能。
## 1 F2FS
```
const struct address_space_operations f2fs_dblock_aops = {
    .readpage   = f2fs_read_data_page,
    .readpages  = f2fs_read_data_pages,
    .writepage  = f2fs_write_data_page,
    .writepages = f2fs_write_data_pages,
    .write_begin    = f2fs_write_begin,
    .write_end  = f2fs_write_end,
    .set_page_dirty = f2fs_set_data_page_dirty,
    .invalidatepage = f2fs_invalidate_page,
    .releasepage    = f2fs_release_page,
    .direct_IO  = f2fs_direct_IO,
    .bmap       = f2fs_bmap,
#ifdef CONFIG_MIGRATION
    .migratepage    = f2fs_migrate_page,
#endif
};
```

```
/*
 * A control structure which tells the writeback code what to do.  These are
 * always on the stack, and hence need no locking.  They are always initialised
 * in a manner such that unspecified fields are set to zero.
 */
struct writeback_control {
    long nr_to_write;       /* Write this many pages, and decrement
                       this for each page written */
    long pages_skipped;     /* Pages which were not written */

    /*
     * For a_ops->writepages(): if start or end are non-zero then this is
     * a hint that the filesystem need only write out the pages inside that
     * byterange.  The byte at `end' is included in the writeout request.
     */
    loff_t range_start;
    loff_t range_end;

    enum writeback_sync_modes sync_mode;

    unsigned for_kupdate:1;     /* A kupdate writeback */
    unsigned for_background:1;  /* A background writeback */
    unsigned tagged_writepages:1;   /* tag-and-write to avoid livelock */
    unsigned for_reclaim:1;     /* Invoked from the page allocator */
    unsigned range_cyclic:1;    /* range_start is cyclic */
    unsigned for_sync:1;        /* sync(2) WB_SYNC_ALL writeback */
#ifdef CONFIG_CGROUP_WRITEBACK
    struct bdi_writeback *wb;   /* wb this writeback is issued under */
    struct inode *inode;        /* inode being written out */

    /* foreign inode detection, see wbc_detach_inode() */
    int wb_id;          /* current wb id */
    int wb_lcand_id;        /* last foreign candidate wb id */
    int wb_tcand_id;        /* this foreign candidate wb id */
    size_t wb_bytes;        /* bytes written by current wb */
    size_t wb_lcand_bytes;      /* bytes written by last candidate */
    size_t wb_tcand_bytes;      /* bytes written by this candidate */
#endif
};
```

```
    struct f2fs_io_info fio =
    {
        .sbi = sbi,
        .ino = inode->i_ino,
        .type = DATA,
        .op = REQ_OP_WRITE,
        .op_flags = wbc_to_write_flags(wbc),
        .old_blkaddr = NULL_ADDR,
        .page = page,
        .encrypted_page = NULL,
        .submitted = false,
        .need_lock = LOCK_RETRY,
        .io_type = io_type,
        .io_wbc = wbc,
    };
```
## 2 调度器层


## 
