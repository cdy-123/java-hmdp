package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //普通set
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //逻辑过期set
    public void setLogical(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //预防缓存穿透的查询
    public <R,ID> R querywithPassThrough(String keyPrefix, ID id, Class<R> clazz, Function<ID,R> function,Long time, TimeUnit unit) {
        String key = keyPrefix+id.toString();
        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //存在，就直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, clazz);
        }
        if(json != null){
            return null;
        }

        //不存在，就访问数据库查询
        R re = function.apply(id);
        if(re==null){
            //将空值写到redis，预防缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //将数据添加到redis缓存，设置有效时间为30分钟
        this.set(key,re,time,unit);

        return re;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10); //创建线程池
    private boolean tryLock(String key){
        //判断能不能获取锁，设置锁的有效期为10秒
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag); //需要用工具类封箱和拆箱，避免空指针
    }
    private void unlock(String key){
        //释放锁
        stringRedisTemplate.delete(key);
    }

    //使用逻辑过期解决缓存击穿的查询
    public <R,ID> R querywithLogical(String keyPrefix, ID id, Class<R> clazz, Function<ID,R> function,Long time, TimeUnit unit){
        String key = keyPrefix+id.toString();
        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //不存在，就直接返回
        if(StrUtil.isBlank(json)){
            return null;
        }

        //存在，判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R re = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return re;
        }

        //已经过期，需要重建缓存
        if(tryLock(RedisConstants.LOCK_SHOP_KEY+id.toString())){
            //获取锁成功，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查数据库
                    R r = function.apply(id);
                    //重建缓存
                    this.setLogical(key,r,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(RedisConstants.LOCK_SHOP_KEY+id.toString());
                }
            });

        }
        return re;
    }

    //使用互斥锁解决缓存击穿的查询
    public <R,ID> R querywithMutex(String keyPrefix, ID id, Class<R> clazz, Function<ID,R> function,Long time, TimeUnit unit){
        String key = keyPrefix+id.toString();
        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //存在，就直接返回
        if(StrUtil.isNotBlank(json)){
            R re = JSONUtil.toBean(json, clazz);
            return re;
        }
        if(json != null){  //放行shopJson为null的情况
            return null;
        }
        R r = null;
        try {
            //实现缓存重建
            //获取互斥锁
            if(!tryLock(RedisConstants.LOCK_SHOP_KEY)){
                Thread.sleep(50); //没有获得锁就休眠50毫秒
                return querywithMutex(keyPrefix,id,clazz,function,time,unit); //递归继续查询
            }

            //获得锁，访问数据库查询
            r = function.apply(id);
//            Thread.sleep(200);
            if(r==null){
                //将空值写到redis，预防缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //将数据添加到redis缓存
            this.set(key,r,time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(RedisConstants.LOCK_SHOP_KEY);
        }
        return r;
    }

}
