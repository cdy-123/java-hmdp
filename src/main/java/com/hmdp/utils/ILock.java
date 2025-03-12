package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String key_prefix = "lock:";
    private static final String id_prefix = UUID.randomUUID()+"-"; //UUID用来唯一标识当前线程，跨JVM出现重复的线程id
    private static final DefaultRedisScript<Long> script;
    static { //加载定义在资源文件中的lua脚本
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("unlock.lua"));
        script.setResultType(Long.class);
    }

    public ILock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    //获取锁
    public boolean tryLock(long timeout) {
        //获取当前线程id，加上UUID
        String id = id_prefix+Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key_prefix+name,id,timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success); //防止返回空值，如果是null返回false
    }

    //释放锁
    public void unlock() {
        //判断这个锁是不是当前线程的，是才释放
        String id = id_prefix+Thread.currentThread().getId();
        String id2 = stringRedisTemplate.opsForValue().get(key_prefix+name);
        if(id.equals(id2)) {
            stringRedisTemplate.delete(key_prefix + name);
        }

        //优化之后-用Lua脚本实现判断释放的过程
        stringRedisTemplate.execute(script, Collections.singletonList(key_prefix+name),id_prefix+Thread.currentThread().getId());
    }
}
