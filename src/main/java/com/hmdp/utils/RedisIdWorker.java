package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIME = 1640995200L; //开始时间戳
    private static final long COUNT_BITS = 32; //序列号的位数

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String key) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond  = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIME;

        //生成序列号
        //获取当天日期，用于key前缀
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+key+":"+data);

        //拼接返回
        return timestamp<<COUNT_BITS | count;
    }
}
