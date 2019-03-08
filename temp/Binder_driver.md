# Binder 驱动

**Android P + kernel 4.4**

binder驱动代码目录

```
kernel4.4/drivers/android/binder_alloc.c
kernel4.4/drivers/android/binder_alloc.h
kernel4.4/drivers/android/binder_alloc_selftest.c
kernel4.4/drivers/android/binder.c
kernel4.4/drivers/android/binder_trace.h
kernel4.4/drivers/android/Kconfig
kernel4.4/drivers/android/Makefile
```

binder驱动主要的方法: init,open,mmap,ioctl

## 1. Binder binder_init()

`kernel4.4/drivers/android/binder.c`

```
                                                                                 
static int __init binder_init(void)                                              
{                                                                                
    int ret;                                                                     
    char *device_name, *device_names, *device_tmp;                               
    struct binder_device *device;                                                
    struct hlist_node *tmp;                                                      
                                                                                 
    ret = binder_alloc_shrinker_init();                                          
    if (ret)                                                                     
        return ret;                                                              
                                                                                 
    atomic_set(&binder_transaction_log.cur, ~0U);                                
    atomic_set(&binder_transaction_log_failed.cur, ~0U);                         
    binder_deferred_workqueue = create_singlethread_workqueue("binder");         
    if (!binder_deferred_workqueue)                                              
        return -ENOMEM;                                                          
                                                                                 
    binder_debugfs_dir_entry_root = debugfs_create_dir("binder", NULL);          
    if (binder_debugfs_dir_entry_root)                                           
        binder_debugfs_dir_entry_proc = debugfs_create_dir("proc",               
                         binder_debugfs_dir_entry_root);                         
                                                                                 
    if (binder_debugfs_dir_entry_root) {
	// 
        debugfs_create_file("state",                                             
                    0444,                                                        
                    binder_debugfs_dir_entry_root,                               
                    NULL,                                                        
                    &binder_state_fops);                                         
        debugfs_create_file("stats",                                             
                    0444,                                                        
                    binder_debugfs_dir_entry_root,                               
                    NULL,                                                        
                    &binder_stats_fops);                                         
        debugfs_create_file("transactions",                                      
                    0444,                                                        
                    binder_debugfs_dir_entry_root,                               
                    NULL,                                                        
                    &binder_transactions_fops);                                  
        debugfs_create_file("transaction_log",                                   
                    0444,                                                        
                    binder_debugfs_dir_entry_root,                               
                    &binder_transaction_log,                                     
                    &binder_transaction_log_fops);                               
        debugfs_create_file("failed_transaction_log",                            
                    0444,                                                        
                    binder_debugfs_dir_entry_root,                               
                    &binder_transaction_log_failed,                              
                    &binder_transaction_log_fops);                               
    }
                                                                                 
    /*
     * Copy the module_parameter string, because we don't want to
     * tokenize it in-place.
     */
    device_names = kzalloc(strlen(binder_devices_param) + 1, GFP_KERNEL);        
    if (!device_names) {                                                         
        ret = -ENOMEM;                                                           
        goto err_alloc_device_names_failed;                                      
    }                                                                            
    strcpy(device_names, binder_devices_param);                                  
                                                                                 
    device_tmp = device_names;                                                   
    while ((device_name = strsep(&device_tmp, ","))) {                           
        ret = init_binder_device(device_name);                                   
        if (ret)                                                                 
            goto err_init_binder_device_failed;                                  
    }                                                                            
                                                                                 
    return ret;                                                                  
err_init_binder_device_failed:                                                   
    hlist_for_each_entry_safe(device, tmp, &binder_devices, hlist) {             
        misc_deregister(&device->miscdev);                                       
        hlist_del(&device->hlist);                                               
        kfree(device);                                                           
    }                                                                            
                                                                                 
    kfree(device_names);                                                         
                                                                                 
err_alloc_device_names_failed:                                                   
    debugfs_remove_recursive(binder_debugfs_dir_entry_root);                     
                                                                                 
    destroy_workqueue(binder_deferred_workqueue);                                
                                                                                 
    return ret;                                                                  
}                                                                                
```

`init_binder_device`实现如下:

```
static int __init init_binder_device(const char *name)
{
    int ret;
    struct binder_device *binder_device;

    binder_device->miscdev.fops = &binder_fops; // binder 驱动支持的文件操作
    binder_device->miscdev.minor = MISC_DYNAMIC_MINOR; // 动态分配次设备号,misc的主设备号统一为10
    binder_device->miscdev.name = name; // 驱动名称 CONFIG_ANDROID_BINDER_DEVICES="binder,hwbinder,vndbinder"

	......
    ret = misc_register(&binder_device->miscdev); //注册将binder注册为misc设备
	......
}
```

`binder_device`的`miscdev`是一个`miscdevice`结构体,在`kernel/include/linux/miscdevice.h`.

```
struct miscdevice  {
    int minor;
    const char *name;
    const struct file_operations *fops;
    struct list_head list;
    struct device *parent;
    struct device *this_device;
    const struct attribute_group **groups;
    const char *nodename;
    umode_t mode;
};
```

`file_operations *fops`指的就是 binder 驱动支持的文件操作, 这些操作就是 binder 驱动的核心.

```
static const struct file_operations binder_fops = {
    .owner = THIS_MODULE,
    .poll = binder_poll,
    .unlocked_ioctl = binder_ioctl,
    .compat_ioctl = binder_ioctl,
    .mmap = binder_mmap,
    .open = binder_open,
    .flush = binder_flush,
    .release = binder_release,
};
```

binder 驱动为上层提供的操作有7种, 底层实现其实是6种, 使用最多的就是`binder_ioctl`,`binder_mmap`,`binder_open`.
用户空间调用这些方法是通过`open`,`mmap`,`ioctl` 经过`syscall`,调用了对应的`binder_open`,`binder_mmap`,`binder_ioctl`.

binder的设备驱动入口函数是:

```
device_initcall(binder_init);
```

## 2. Binder open()

上层应用使用binder驱动的时候首先要打开binder驱动节点`/dev/binder`,该操作在`kernel4.4/drivers/android/binder.c`中.
使用`binder_open`实现.

```
static int binder_open(struct inode *nodp, struct file *filp)
{
    struct binder_proc *proc;
    struct binder_device *binder_dev;
    ......
    proc = kzalloc(sizeof(*proc), GFP_KERNEL); //为binder_proc结构体分配kernel内存
    if (proc == NULL)
        return -ENOMEM;
    spin_lock_init(&proc->inner_lock);
    spin_lock_init(&proc->outer_lock);
    get_task_struct(current->group_leader);
    proc->tsk = current->group_leader; //将当前线程的task保存到binder进程的tsk
    mutex_init(&proc->files_lock);
    INIT_LIST_HEAD(&proc->todo); //初始化todo列表
    if (binder_supported_policy(current->policy)) {
        proc->default_priority.sched_policy = current->policy;
        proc->default_priority.prio = current->normal_prio;
    } else {
        proc->default_priority.sched_policy = SCHED_NORMAL;
        proc->default_priority.prio = NICE_TO_PRIO(0);
    }

    binder_dev = container_of(filp->private_data, struct binder_device,
                  miscdev);
    proc->context = &binder_dev->context;
    binder_alloc_init(&proc->alloc);

    binder_stats_created(BINDER_STAT_PROC); // binder proc创建的数量+1
    proc->pid = current->group_leader->pid;
    INIT_LIST_HEAD(&proc->delivered_death); // 初始化delivered_death列表
    INIT_LIST_HEAD(&proc->waiting_threads); // 初始化wait队列
    filp->private_data = proc; // 将这个proc与filp关联起来,以后就可以通过filp找到proc

    mutex_lock(&binder_procs_lock); // 枷锁,因为binder_procs是一个全局的变量
    hlist_add_head(&proc->proc_node, &binder_procs); //将proc_node节点添加到binder_procs为表头的队列
    mutex_unlock(&binder_procs_lock); // 解除锁
	......
    return 0;
}
```

`hlist_add_head`的实现在`kernel/include/linux/list.h` 

```
static inline void hlist_add_head(struct hlist_node *n, struct hlist_head *h)
{
	struct hlist_node *first = h->first;
	n->next = first;
	if (first)
		first->pprev = &n->next;
	h->first = n;
	n->pprev = &h->first;
}
```

创建binder_proc对象，并把当前进程等信息保存到binder_proc对象，该对象管理IPC所需的各种信息并拥有其他结构体的根结构体；
再把binder_proc对象保存到文件指针filp，以及把binder_proc加入到全局链表binder_procs。Binder驱动中通过
`static HLIST_HEAD(binder_procs)`,创建了全局的哈希链表binder_procs，用于保存所有的binder_proc队列，每次新创建的
binder_proc对象都会加入binder_procs链表中。

这样,用户空间的进程,通过系统调用,调用到binder驱动的方法`binder_open`, 那么, Binder驱动,就会为用户创建一个属于该用户
空间进程的的binder_proc实体,之后用户
对Binder设备的操作就是基于该对象.

```
/**
 * struct binder_proc - binder process bookkeeping
 * @proc_node:            element for binder_procs list
 * @threads:              rbtree of binder_threads in this proc
 *                        (protected by @inner_lock)
 * @nodes:                rbtree of binder nodes associated with
 *                        this proc ordered by node->ptr
 *                        (protected by @inner_lock)
 * @refs_by_desc:         rbtree of refs ordered by ref->desc
 *                        (protected by @outer_lock)
 * @refs_by_node:         rbtree of refs ordered by ref->node
 *                        (protected by @outer_lock)
 * @waiting_threads:      threads currently waiting for proc work
 *                        (protected by @inner_lock)
 * @pid                   PID of group_leader of process
 *                        (invariant after initialized)
 * @tsk                   task_struct for group_leader of process
 *                        (invariant after initialized)
 * @files                 files_struct for process
 *                        (protected by @files_lock)
 * @files_lock            mutex to protect @files
 * @deferred_work_node:   element for binder_deferred_list
 *                        (protected by binder_deferred_lock)
 * @deferred_work:        bitmap of deferred work to perform
 *                        (protected by binder_deferred_lock)
 * @is_dead:              process is dead and awaiting free
 *                        when outstanding transactions are cleaned up
 *                        (protected by @inner_lock)
 * @todo:                 list of work for this process
 *                        (protected by @inner_lock)
 * @stats:                per-process binder statistics
 *                        (atomics, no lock needed)
 * @delivered_death:      list of delivered death notification
 *                        (protected by @inner_lock)
 * @max_threads:          cap on number of binder threads
 *                        (protected by @inner_lock)
 * @requested_threads:    number of binder threads requested but not
 *                        yet started. In current implementation, can
 *                        only be 0 or 1.
 *                        (protected by @inner_lock)
 * @requested_threads_started: number binder threads started
 * @tmp_ref:              temporary reference to indicate proc is in use
 *                        (protected by @inner_lock)
 * @default_priority:     default scheduler priority
 *                        (invariant after initialized)
 * @debugfs_entry:        debugfs node
 * @alloc:                binder allocator bookkeeping
 * @context:              binder_context for this proc
 *                        (invariant after initialized)
 * @inner_lock:           can nest under outer_lock and/or node lock
 * @outer_lock:           no nesting under innor or node lock
 *                        Lock order: 1) outer, 2) node, 3) inner
 *
 * Bookkeeping structure for binder processes
 */
struct binder_proc {
    struct hlist_node proc_node; // 会添加到 binder procs的表头节点
    struct rb_root threads;
    struct rb_root nodes;
    struct rb_root refs_by_desc;
    struct rb_root refs_by_node;
    struct list_head waiting_threads;
    int pid;
    struct task_struct *tsk;
    struct files_struct *files;
    struct mutex files_lock;
    struct hlist_node deferred_work_node;
    int deferred_work;
    bool is_dead;

    struct list_head todo;
    struct binder_stats stats;
    struct list_head delivered_death;
    int max_threads;
    int requested_threads;
    int requested_threads_started;
    int tmp_ref;
    struct binder_priority default_priority;
    struct dentry *debugfs_entry;
    struct binder_alloc alloc;
    struct binder_context *context;
    spinlock_t inner_lock;
    spinlock_t outer_lock;
};
```

## 3. Binder mmap()

```
// vm_area_struct 描述一段应用程序使用的虚拟内存
static int binder_mmap(struct file *filp, struct vm_area_struct *vma)
{
    int ret;
	// 取出这一个进程对应的binder_proc对象
    struct binder_proc *proc = filp->private_data;
    const char *failure_string;

    if (proc->tsk != current->group_leader)
        return -EINVAL;

    if ((vma->vm_end - vma->vm_start) > SZ_4M)
        vma->vm_end = vma->vm_start + SZ_4M; //保证映射内存大小不超过4M
	......
    if (vma->vm_flags & FORBIDDEN_MMAP_FLAGS) {
        ret = -EPERM;
        failure_string = "bad vm_flags";
        goto err_bad_arg;
    }
    vma->vm_flags |= VM_DONTCOPY | VM_MIXEDMAP;
    vma->vm_flags &= ~VM_MAYWRITE;

    vma->vm_ops = &binder_vm_ops;
    vma->vm_private_data = proc;

    ret = binder_alloc_mmap_handler(&proc->alloc, vma); // 映射虚拟地址空间
    if (ret)
        return ret;
    mutex_lock(&proc->files_lock);
    proc->files = get_files_struct(current);
    mutex_unlock(&proc->files_lock);
    return 0;
err_bad_arg:
    pr_err("%s: %d %lx-%lx %s failed %d\n", __func__,
           proc->pid, vma->vm_start, vma->vm_end, failure_string, ret);
    return ret;
}
```

`binder_alloc_mmap_handler` 在`binder_alloc.c`中:

```
/**
 * binder_alloc_mmap_handler() - map virtual address space for proc
 * @alloc:  alloc structure for this proc
 * @vma:    vma passed to mmap()
 *
 * Called by binder_mmap() to initialize the space specified in
 * vma for allocating binder buffers
 *
 * Return:
 *      0 = success
 *      -EBUSY = address space already mapped
 *      -ENOMEM = failed to map memory to given address space
 */
int binder_alloc_mmap_handler(struct binder_alloc *alloc,
                  struct vm_area_struct *vma)
{
    int ret;
	// vm_struct 
    struct vm_struct *area;
    const char *failure_string;
    struct binder_buffer *buffer;

    mutex_lock(&binder_alloc_mmap_lock); // 同步锁
	......
	//采用ALLOC方式，分配一个连续的内核虚拟空间，与进程虚拟空间大小一致
	//保证一次只有一个进程分配内存，保证多进程间的并发访问。
    area = get_vm_area(vma->vm_end - vma->vm_start, VM_ALLOC);
	......
    alloc->buffer = area->addr;
    alloc->user_buffer_offset =
        vma->vm_start - (uintptr_t)alloc->buffer;
    mutex_unlock(&binder_alloc_mmap_lock); // 解除同步锁
	......
	//分配物理页的指针数组，数组大小为vma的等效page个数
    alloc->pages = kzalloc(sizeof(alloc->pages[0]) *
                   ((vma->vm_end - vma->vm_start) / PAGE_SIZE),
                   GFP_KERNEL);
	......
	// 申请的空间大小
    alloc->buffer_size = vma->vm_end - vma->vm_start;
	//分配物理页的指针数组，数组大小为vma的等效page个数,GFP_KERNEL —— 正常分配内存
	// kzalloc()
    buffer = kzalloc(sizeof(*buffer), GFP_KERNEL);
	.....
    buffer->data = alloc->buffer;
	//将binder_buffer地址 加入到所属进程的buffers队列
    list_add(&buffer->entry, &alloc->buffers);
	// free 是1 说明buffer是空闲的
    buffer->free = 1;
	//将空闲buffer放入proc->free_buffers中
    binder_insert_free_buffer(alloc, buffer);
    alloc->free_async_space = alloc->buffer_size / 2;
    barrier();
    alloc->vma = vma;
    alloc->vma_vm_mm = vma->vm_mm;
    /* Same as mmgrab() in later kernel versions */
    atomic_inc(&alloc->vma_vm_mm->mm_count); // 自动增减mmap的次数
    return 0;
	......
    return ret;
}
```

```
static void binder_insert_free_buffer(struct binder_alloc *alloc,
                      struct binder_buffer *new_buffer)
{
    struct rb_node **p = &alloc->free_buffers.rb_node;
    struct rb_node *parent = NULL;
    struct binder_buffer *buffer;
    size_t buffer_size;
    size_t new_buffer_size;

    BUG_ON(!new_buffer->free);

    new_buffer_size = binder_alloc_buffer_size(alloc, new_buffer);

    binder_alloc_debug(BINDER_DEBUG_BUFFER_ALLOC,
             "%d: add free buffer, size %zd, at %pK\n",
              alloc->pid, new_buffer_size, new_buffer);

    while (*p) {
        parent = *p;
        buffer = rb_entry(parent, struct binder_buffer, rb_node);
        BUG_ON(!buffer->free);

        buffer_size = binder_alloc_buffer_size(alloc, buffer);

        if (new_buffer_size < buffer_size)
            p = &parent->rb_left;
        else
            p = &parent->rb_right;
    }
    rb_link_node(&new_buffer->rb_node, parent, p);
    rb_insert_color(&new_buffer->rb_node, &alloc->free_buffers);
}
```


## 4. Binder ioctl()











