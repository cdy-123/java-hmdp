package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public List<ShopType> queryAll(){
        //从redis查询商铺分类缓存
        String shoptypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOPTYPE_KEY);

        //存在，就直接返回
        if(shoptypeJson != null){
            List<ShopType> shoptype = JSONUtil.toList(shoptypeJson, ShopType.class);
            return shoptype;
        }

        //不存在，就访问数据库查询
        List<ShopType> shoptype = query().orderByAsc("sort").list();

        //将数据添加到redis缓存
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOPTYPE_KEY,JSONUtil.toJsonStr(shoptype),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shoptype;
    }
}
