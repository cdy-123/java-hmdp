package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10); //创建线程池

    @Override
    public Result queryById(Long id){

//        //解决缓存穿透
//        Shop shop = querywithPassThrough(id);
//        //优化之后-调用工具类
//        Shop shop = cacheClient.querywithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,id2 ->getById(id2),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

//        //优化之后-使用互斥锁解决缓存击穿
//        Shop shop = querywithMutex(id);
        //优化之后-调用工具类
        Shop shop = cacheClient.querywithMutex(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,id2 ->getById(id2),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

//        //优化之后-使用逻辑过期解决缓存击穿
//        Shop shop = querywithLogical(id);
//        //优化之后-调用工具类
//        Shop shop = cacheClient.querywithLogical(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,id2 ->getById(id2),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    public Shop querywithPassThrough(Long id){
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id.toString());

        //存在，就直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson != null){  //放行shopJson为null的情况
            return null;
        }

        //不存在，就访问数据库查询
        Shop shop = getById(id);
        if(shop==null){
            //将空值写到redis，预防缓存穿透
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id.toString(),"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //将数据添加到redis缓存，设置有效时间为30分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id.toString(),JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop querywithMutex(Long id){
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id.toString());

        //存在，就直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson != null){  //放行shopJson为null的情况
            return null;
        }
        Shop shop = null;
        try {
            //实现缓存重建
            //获取互斥锁
            if(!tryLock(RedisConstants.LOCK_SHOP_KEY)){
                Thread.sleep(50); //没有获得锁就休眠50毫秒
                return querywithMutex(id); //递归继续查询
            }

            //获得锁，访问数据库查询
            shop = getById(id);
//            Thread.sleep(200);
            if(shop==null){
                //将空值写到redis，预防缓存穿透
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id.toString(),"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //将数据添加到redis缓存，设置有效时间为30分钟
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id.toString(),JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(RedisConstants.LOCK_SHOP_KEY);
        }
        return shop;
    }

    public Shop querywithLogical(Long id){
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id.toString());

        //不存在，就直接返回
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        //存在，判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        //已经过期，需要重建缓存
        if(tryLock(RedisConstants.LOCK_SHOP_KEY+id.toString())){
            //获取锁成功，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(RedisConstants.LOCK_SHOP_KEY+id.toString());
                }
            });

        }
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop){
        if(shop.getId()==null){
            return Result.fail("店铺id不存在");
        }

        //更新数据库
        updateById(shop);

        //删除对应的redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId().toString());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId,Integer current,Double x,Double y){ //要实现滚动刷新效果前端页面得调成350*666
        //判断是否需要根据坐标查询
        if(x == null || y == null){
            //不需要查询坐标，按数据库查
            Page<Shop> page = query()
                    .eq("type_id",typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        //计算分页参数
        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;

        //查询redis
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(RedisConstants.SHOP_GEO_KEY + typeId,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) { //没有下一页了结束
            return Result.ok(Collections.emptyList());
        }

        //截取从from到end
        List<Long> ids = new ArrayList<>();
        Map<String,Distance> distanceMap = new HashMap<>();
        list.stream().skip(from).forEach(result -> {
            String shopid = result.getContent().getName();
            ids.add(Long.valueOf(shopid));
            Distance distance = result.getDistance();
            distanceMap.put(shopid,distance);
        });

        //查询数据库
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }

    private void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //从数据库查询数据
        Shop shop = getById(id);
        Thread.sleep(200);

        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds)); //现在的时间加上expireSeconds秒

        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id.toString(),JSONUtil.toJsonStr(redisData));
    }

    private boolean tryLock(String key){
        //判断能不能获取锁，设置锁的有效期为10秒
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag); //需要用工具类封箱和拆箱，避免空指针
    }

    private void unlock(String key){
        //释放锁
        stringRedisTemplate.delete(key);
    }

}
